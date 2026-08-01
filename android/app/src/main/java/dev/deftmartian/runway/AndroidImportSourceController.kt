package dev.deftmartian.runway

import android.content.Context
import dev.deftmartian.runway.data.LocalRestoreResult
import kotlinx.coroutines.CancellationException

internal class ImportSourceBoundaryException(
    val safeMessage: String,
    cause: Throwable? = null,
) : IllegalStateException(safeMessage, cause)

/**
 * Owns the Android-side half of removing import access.
 *
 * Every action is attempted so one provider failure cannot leave another source connected
 * unnecessarily. Non-cancellation failures are collected; cancellation propagates immediately so
 * callers do not treat an interrupted disconnect as a completed boundary.
 */
internal class AndroidImportSourceController(
    private val cancelWorkers: () -> Unit,
    private val disconnectFolder: () -> Unit,
    private val clearHealthConnectCursor: () -> Unit,
    private val revokeHealthConnectPermissions: suspend () -> Boolean,
) {
    constructor(context: Context) : this(
        cancelWorkers = { ReconciliationScheduler.cancelAllAndWait(context.applicationContext) },
        disconnectFolder = {
            check(
                TreeAccessStore(context.applicationContext).disconnect() ==
                    TreeAccessMutation.Changed,
            ) {
                "Android did not confirm removal of the folder grant."
            }
        },
        clearHealthConnectCursor = { HealthConnectCursorStore(context.applicationContext).clearAll() },
        revokeHealthConnectPermissions = {
            AndroidHealthConnectGateway(context.applicationContext).revokeAllPermissions()
        },
    )

    suspend fun disconnectAll() {
        val failures = buildList {
            attempt("background import work") { cancelWorkers() }
            attempt("GPX folder access") { disconnectFolder() }
            attempt("Health Connect cursor") { clearHealthConnectCursor() }
            attempt("Health Connect permission") {
                check(revokeHealthConnectPermissions()) {
                    "Health Connect did not confirm permission removal."
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw ImportSourceBoundaryException(
                "Could not fully disconnect ${failures.joinToString()}. Local data was not changed; " +
                    "other import sources may already be disconnected.",
            )
        }
    }

    /**
     * Makes destructive import-data operations race-safe. Acquisition is stopped and every
     * provider grant is removed before Room changes, so an in-flight worker cannot recreate data
     * after the transaction commits.
     *
     * Android grants and a Room transaction cannot be rolled back together. If the database
     * operation fails, report that access was already removed instead of pretending nothing
     * changed.
     */
    suspend fun <T> disconnectBeforeErase(erase: suspend () -> T): T =
        disconnectBeforeDestructiveMutation("erased", erase)

    /**
     * Restore must not inherit a folder grant or Health Connect cursor from the ledger it replaces.
     * The caller supplies only the Room replacement; this boundary owns acquisition shutdown.
     */
    suspend fun disconnectBeforeRestore(
        restore: suspend () -> LocalRestoreResult,
    ): LocalRestoreResult = AndroidStateCoordinator.withDestructiveImportBoundary(
        closeAcquisition = ::disconnectAll,
        keepAcquisitionClosedAfter = LocalRestoreResult::leavesRoomUnavailable,
    ) {
        try {
            restore()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw disconnectedMutationFailure("restored", error)
        }
    }

    private suspend fun <T> disconnectBeforeDestructiveMutation(
        operation: String,
        mutation: suspend () -> T,
    ): T = AndroidStateCoordinator.withDestructiveImportBoundary(
        closeAcquisition = ::disconnectAll,
    ) {
        try {
            mutation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw disconnectedMutationFailure(operation, error)
        }
    }

    private fun disconnectedMutationFailure(
        operation: String,
        error: Exception,
    ): ImportSourceBoundaryException = ImportSourceBoundaryException(
        "Import sources were disconnected, but local data could not be $operation. " +
            "Existing data is still on this phone. Reconnect sources to resume imports.",
        error,
    )

    private suspend fun MutableList<String>.attempt(label: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            add(label)
        }
    }
}

internal fun LocalRestoreResult.leavesRoomUnavailable(): Boolean = when (this) {
    is LocalRestoreResult.Restored -> true
    is LocalRestoreResult.Rejected -> restartRequired
    is LocalRestoreResult.RecoveryRequired -> true
}
