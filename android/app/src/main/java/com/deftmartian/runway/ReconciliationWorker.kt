package com.deftmartian.runway

import android.content.Context
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.security.MessageDigest

class ReconciliationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    private val statusStore = ReconciliationStatusStore(appContext)

    override fun doWork(): Result {
        val serverStore = ServerConnectionStore(applicationContext)
        val serverConnection = serverStore.currentConnection() ?: run {
            ReconciliationScheduler.cancelAll(applicationContext)
            return success(STATE_SERVER_REQUIRED)
        }
        val origin = serverConnection.origin
        val credentialStore = AndroidCredentialStore(applicationContext, origin)
        val credential = credentialStore.load() ?: run {
            ReconciliationScheduler.cancelAll(applicationContext)
            return success(STATE_PAIRING_REQUIRED)
        }
        val treeStore = TreeAccessStore(applicationContext)
        val treeState = treeStore.currentState()
        if (treeState !is TreeAccessState.Connected) {
            ReconciliationScheduler.cancelAll(applicationContext)
            return success(STATE_PERMISSION_REQUIRED)
        }
        val connection = ImportConnectionGeneration.capture(serverConnection, credential, treeState)
        val scan = SafTreeScanner(applicationContext.contentResolver).scan(treeState)
        if (scan !is TreeScanResult.Success) {
            return when (scan) {
                TreeScanResult.PermissionRequired -> {
                    ReconciliationScheduler.cancelAll(applicationContext)
                    success(STATE_PERMISSION_REQUIRED)
                }
                TreeScanResult.ProviderError -> success(STATE_PROVIDER_ERROR)
                is TreeScanResult.Success -> error("unreachable")
            }
        }

        val handledStore = HandledImportStore(applicationContext)
        val candidates = handledStore
            .filterPotentiallyUnhandled(credential.deviceId, scan.summary.candidates)
            .sortedByDescending { it.lastModifiedEpochMs ?: Long.MIN_VALUE }
        val candidate = candidates.firstOrNull()
        if (candidate == null) {
            return success(
                state = if (scan.summary.truncated) STATE_SCAN_LIMIT else STATE_NO_CANDIDATES,
                candidateCount = scan.summary.gpxCandidates,
                truncated = scan.summary.truncated,
            )
        }

        if (isStopped) return retry(STATE_RETRYING, candidates.size, scan.summary.truncated)

        var oversized = false
        var bytes = ByteArray(0)
        val contentSha256 = try {
            bytes = applicationContext.contentResolver.openInputStream(candidate.uri)?.use {
                BoundedStreamInspector.readBytes(it, GpxCandidatePolicy.MAX_FILE_BYTES)
            } ?: return success(STATE_PROVIDER_ERROR)
            sha256(bytes)
        } catch (error: PayloadTooLargeException) {
            oversized = true
            error.observedPrefixSha256 ?: return retry(
                STATE_RETRYING,
                candidates.size,
                scan.summary.truncated,
            )
        } catch (_: SecurityException) {
            ReconciliationScheduler.cancelAll(applicationContext)
            return success(STATE_PERMISSION_REQUIRED)
        } catch (_: IOException) {
            return retry(STATE_RETRYING, candidates.size, scan.summary.truncated)
        } catch (_: RuntimeException) {
            return success(STATE_PROVIDER_ERROR)
        }

        if (
            !connection.isCurrent(
                serverStore.currentConnection(),
                credentialStore.load(),
                treeStore.currentState(),
            )
        ) {
            return success(STATE_STALE_CONNECTION)
        }
        when (
            handledStore.observeContent(
                deviceId = credential.deviceId,
                uri = candidate.uri,
                contentSha256 = contentSha256,
                nowEpochMs = System.currentTimeMillis(),
                settleDurationMs = FILE_SETTLE_MS,
            )
        ) {
            CandidateStability.Waiting -> return retry(
                STATE_SETTLING,
                candidates.size,
                scan.summary.truncated,
            )
            CandidateStability.StableHandled -> {
                handledStore.markHandled(credential.deviceId, candidate, contentSha256)
                return terminal(
                    state = STATE_DUPLICATE,
                    remainingCandidates = candidates.size - 1,
                    summary = scan.summary,
                )
            }
            CandidateStability.StableUnhandled -> Unit
        }

        if (oversized) {
            handledStore.markHandled(credential.deviceId, candidate, contentSha256)
            return terminal(
                state = STATE_QUARANTINED,
                remainingCandidates = candidates.size - 1,
                summary = scan.summary,
            )
        }

        if (isStopped) return retry(STATE_RETRYING, candidates.size, scan.summary.truncated)
        val requestId = handledStore.requestIdFor(credential.deviceId, contentSha256)
        if (
            !connection.isCurrent(
                serverStore.currentConnection(),
                credentialStore.load(),
                treeStore.currentState(),
            ) || isStopped
        ) {
            return success(STATE_STALE_CONNECTION)
        }
        val imported = RunwayApiClient(origin).importGpx(credential, bytes, requestId)
        if (
            !connection.isCurrent(
                serverStore.currentConnection(),
                credentialStore.load(),
                treeStore.currentState(),
            )
        ) {
            return success(STATE_STALE_CONNECTION)
        }
        return when (imported) {
            is ImportApiResult.Handled -> {
                handledStore.markHandled(credential.deviceId, candidate, contentSha256)
                terminal(
                    state = when (imported.result) {
                        "imported" -> STATE_IMPORTED
                        "duplicate" -> STATE_DUPLICATE
                        else -> STATE_QUARANTINED
                    },
                    remainingCandidates = candidates.size - 1,
                    summary = scan.summary,
                )
            }
            ImportApiResult.Unauthorized -> {
                handledStore.clearForDevice(credential.deviceId)
                credentialStore.clear()
                ReconciliationScheduler.cancelAll(applicationContext)
                success(STATE_PAIRING_REQUIRED)
            }
            ImportApiResult.RequestConflict -> {
                handledStore.clearPendingRequest(credential.deviceId, contentSha256)
                retry(STATE_RETRYING, candidates.size, scan.summary.truncated)
            }
            ImportApiResult.Retryable -> retry(
                STATE_RETRYING,
                candidates.size,
                scan.summary.truncated,
            )
        }
    }

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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        const val INPUT_DRAIN_WORKERS = "drain_workers"
        const val OUTPUT_STATE = "state"
        const val OUTPUT_CANDIDATE_COUNT = "candidate_count"
        const val OUTPUT_SCAN_TRUNCATED = "scan_truncated"
        const val OUTPUT_BACKLOG = "backlog"
        const val STATE_IMPORTED = "imported"
        const val STATE_DUPLICATE = "duplicate"
        const val STATE_QUARANTINED = "quarantined"
        const val STATE_NO_CANDIDATES = "no_candidates"
        const val STATE_PAIRING_REQUIRED = "pairing_required"
        const val STATE_PERMISSION_REQUIRED = "permission_required"
        const val STATE_PROVIDER_ERROR = "provider_error"
        const val STATE_SCAN_LIMIT = "scan_limit"
        const val STATE_SETTLING = "settling"
        const val STATE_RETRYING = "retrying"
        const val STATE_STALE_CONNECTION = "stale_connection"
        const val STATE_SERVER_REQUIRED = "server_required"
        const val FILE_SETTLE_MS = 30_000L
    }
}
