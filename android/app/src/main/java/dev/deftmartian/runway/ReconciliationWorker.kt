package dev.deftmartian.runway

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dev.deftmartian.runway.data.importing.LocalGpxImportException
import dev.deftmartian.runway.data.importing.LocalGpxImportFailure
import dev.deftmartian.runway.data.importing.LocalGpxImportOrigin
import dev.deftmartian.runway.data.importing.LocalGpxImportOutcome
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Scans one persisted SAF folder and commits at most one stable GPX per invocation.
 *
 * The local Room ledger owns duplicate and tombstone truth. [FolderImportIndex] only avoids
 * repeatedly opening unchanged documents and can be discarded without changing product state.
 */
class ReconciliationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    private val statusStore = ReconciliationStatusStore(appContext)

    override suspend fun doWork(): Result = AndroidStateCoordinator.withImportDataBoundary {
        importNextCandidate()
    }

    private suspend fun importNextCandidate(): Result {
        RunwayNotificationManager.deliverPendingFolderImportAlerts(applicationContext)
        val treeStore = TreeAccessStore(applicationContext)
        val treeState = treeStore.currentState()
        if (treeState !is TreeAccessState.Connected) {
            ReconciliationScheduler.disablePeriodic(applicationContext)
            return success(STATE_PERMISSION_REQUIRED)
        }

        val scan = SafTreeScanner(applicationContext.contentResolver).scan(treeState)
        if (scan !is TreeScanResult.Success) {
            return when (scan) {
                TreeScanResult.PermissionRequired -> {
                    ReconciliationScheduler.disablePeriodic(applicationContext)
                    success(STATE_PERMISSION_REQUIRED)
                }
                TreeScanResult.ProviderError -> success(STATE_PROVIDER_ERROR)
                is TreeScanResult.Success -> error("unreachable")
            }
        }

        if (treeStore.currentState() != treeState) return success(STATE_STALE_FOLDER)
        val index = FolderImportIndex(applicationContext)
        val now = System.currentTimeMillis()
        val candidates = scan.summary.candidates
            .sortedByDescending { it.lastModifiedEpochMs ?: Long.MIN_VALUE }
            .map { it to index.readiness(it, now, FILE_SETTLE_MS) }
        val ready = candidates.firstOrNull { (_, state) -> state == FolderCandidateReadiness.Ready }
        if (ready == null) {
            val settling = candidates.any { (_, state) ->
                state == FolderCandidateReadiness.WaitingForStableFile
            }
            return if (settling) {
                retry(STATE_SETTLING, candidates.size, scan.summary.truncated)
            } else {
                success(
                    state = if (scan.summary.truncated) STATE_SCAN_LIMIT else STATE_NO_CANDIDATES,
                    candidateCount = scan.summary.gpxCandidates,
                    truncated = scan.summary.truncated,
                )
            }
        }

        val candidate = ready.first
        val outcome = try {
            applicationContext.contentResolver.openInputStream(candidate.uri)?.use { input ->
                applicationContext.runwayServices.gpxImports.import(input, LocalGpxImportOrigin.Folder)
            } ?: return success(STATE_PROVIDER_ERROR)
        } catch (_: SecurityException) {
            ReconciliationScheduler.disablePeriodic(applicationContext)
            return success(STATE_PERMISSION_REQUIRED)
        } catch (_: IOException) {
            return retry(STATE_RETRYING, candidates.size, scan.summary.truncated)
        } catch (error: LocalGpxImportException) {
            index.markHandled(candidate)
            return terminal(
                state = if (error.reason == LocalGpxImportFailure.TOO_LARGE) {
                    STATE_TOO_LARGE
                } else {
                    STATE_INVALID
                },
                remainingCandidates = candidates.count { (_, state) ->
                    state != FolderCandidateReadiness.AlreadyHandled
                } - 1,
                summary = scan.summary,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            return retry(STATE_RETRYING, candidates.size, scan.summary.truncated)
        }

        // The import itself remains useful if the folder changed while the stream was open, but an
        // old worker must not update the new folder's accelerator or visible status.
        if (treeStore.currentState() != treeState) return success(STATE_STALE_FOLDER)
        return when (outcome) {
            is LocalGpxImportOutcome.Imported -> {
                index.markHandled(candidate)
                RunwayNotificationManager.deliverPendingFolderImportAlerts(applicationContext)
                terminal(STATE_IMPORTED, remaining(candidates), scan.summary)
            }
            is LocalGpxImportOutcome.Duplicate -> {
                index.markHandled(candidate)
                terminal(STATE_DUPLICATE, remaining(candidates), scan.summary)
            }
            LocalGpxImportOutcome.Tombstoned -> {
                index.markHandled(candidate)
                terminal(STATE_DELETED_PREVIOUSLY, remaining(candidates), scan.summary)
            }
            LocalGpxImportOutcome.FutureActivity -> {
                index.markHandled(candidate)
                terminal(STATE_FUTURE_ACTIVITY, remaining(candidates), scan.summary)
            }
            LocalGpxImportOutcome.ConfigurationRequired ->
                success(STATE_SETUP_REQUIRED, scan.summary.gpxCandidates, scan.summary.truncated)
        }
    }

    private fun remaining(
        candidates: List<Pair<GpxTreeCandidate, FolderCandidateReadiness>>,
    ): Int = (candidates.count { (_, state) ->
        state != FolderCandidateReadiness.AlreadyHandled
    } - 1).coerceAtLeast(0)

    private fun terminal(
        state: String,
        remainingCandidates: Int,
        summary: TreeScanSummary,
    ): Result {
        nextBacklogDrainBudget(
            currentBudget = inputData.getInt(INPUT_DRAIN_WORKERS, 1),
            remainingCandidates = remainingCandidates,
        )?.let { remainingWorkers ->
            ReconciliationScheduler.continueBacklog(applicationContext, remainingWorkers)
        }
        return success(
            state = state,
            candidateCount = summary.gpxCandidates,
            truncated = summary.truncated,
            backlog = remainingCandidates,
        )
    }

    private fun retry(state: String, backlog: Int, truncated: Boolean): Result {
        statusStore.record(state, backlog, truncated)
        return Result.retry()
    }

    private fun success(
        state: String,
        candidateCount: Int = 0,
        truncated: Boolean = false,
        backlog: Int = 0,
    ): Result {
        statusStore.record(state, backlog, truncated)
        return Result.success(
            Data.Builder()
                .putString(OUTPUT_STATE, state)
                .putInt(OUTPUT_CANDIDATE_COUNT, candidateCount)
                .putBoolean(OUTPUT_SCAN_TRUNCATED, truncated)
                .putInt(OUTPUT_BACKLOG, backlog)
                .build(),
        )
    }

    companion object {
        const val INPUT_DRAIN_WORKERS = "drain_workers"
        const val OUTPUT_STATE = "state"
        const val OUTPUT_CANDIDATE_COUNT = "candidate_count"
        const val OUTPUT_SCAN_TRUNCATED = "scan_truncated"
        const val OUTPUT_BACKLOG = "backlog"
        const val STATE_IMPORTED = "imported"
        const val STATE_DUPLICATE = "duplicate"
        const val STATE_DELETED_PREVIOUSLY = "deleted_previously"
        const val STATE_INVALID = "invalid"
        const val STATE_TOO_LARGE = "too_large"
        const val STATE_FUTURE_ACTIVITY = "future_activity"
        const val STATE_NO_CANDIDATES = "no_candidates"
        const val STATE_SETUP_REQUIRED = "setup_required"
        const val STATE_PERMISSION_REQUIRED = "permission_required"
        const val STATE_PROVIDER_ERROR = "provider_error"
        const val STATE_SCAN_LIMIT = "scan_limit"
        const val STATE_SETTLING = "settling"
        const val STATE_RETRYING = "retrying"
        const val STATE_STALE_FOLDER = "stale_folder"
        const val FILE_SETTLE_MS = 30_000L
    }
}
