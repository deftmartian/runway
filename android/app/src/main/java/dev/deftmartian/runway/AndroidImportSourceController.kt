package dev.deftmartian.runway

import android.content.Context
import kotlinx.coroutines.CancellationException

/**
 * Owns the Android-side half of removing import access.
 *
 * Every action is attempted so one provider failure cannot leave another source connected
 * unnecessarily. Callers decide whether a failed disconnect should block or follow a database
 * mutation.
 */
internal class AndroidImportSourceController(
    private val cancelWorkers: () -> Unit,
    private val disconnectFolder: () -> Unit,
    private val clearHealthConnectCursor: () -> Unit,
    private val revokeHealthConnectPermissions: () -> Boolean,
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

    fun disconnectAll() {
        val failures = buildList {
            attempt("background import work", cancelWorkers)
            attempt("GPX folder access", disconnectFolder)
            attempt("Health Connect cursor", clearHealthConnectCursor)
            attempt("Health Connect permission") {
                check(revokeHealthConnectPermissions()) {
                    "Health Connect did not confirm permission removal."
                }
            }
        }
        check(failures.isEmpty()) {
            "Could not fully disconnect ${failures.joinToString()}. No local data was erased; " +
                "other import sources may already be disconnected."
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
    suspend fun <T> disconnectBeforeErase(erase: suspend () -> T): T {
        return AndroidStateCoordinator.withImportDataBoundary {
            disconnectAll()
            try {
                erase()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Import sources were disconnected, but local data could not be erased. " +
                        "Existing data is still on this phone. Reconnect sources to resume imports.",
                    error,
                )
            }
        }
    }

    private fun MutableList<String>.attempt(label: String, action: () -> Unit) {
        if (runCatching(action).isFailure) add(label)
    }
}
