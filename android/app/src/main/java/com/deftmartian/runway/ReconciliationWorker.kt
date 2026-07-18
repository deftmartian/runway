package com.deftmartian.runway

import android.content.Context
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException

class ReconciliationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val credentialStore = AndroidCredentialStore(applicationContext)
        val credential = credentialStore.load()
            ?: return success(STATE_PAIRING_REQUIRED)
        val treeStore = TreeAccessStore(applicationContext)
        val scan = SafTreeScanner(applicationContext.contentResolver).scan(treeStore.currentState())
        if (scan !is TreeScanResult.Success) {
            return when (scan) {
                TreeScanResult.PermissionRequired -> success(STATE_PERMISSION_REQUIRED)
                TreeScanResult.ProviderError -> success(STATE_PROVIDER_ERROR)
                is TreeScanResult.Success -> error("unreachable")
            }
        }

        val handledStore = HandledImportStore(applicationContext)
        val unhandled = scan.summary.candidates
            .filter { !handledStore.isHandled(credential.deviceId, it.uri) }
            .sortedByDescending { it.lastModifiedEpochMs ?: Long.MIN_VALUE }
        val settleBefore = System.currentTimeMillis() - FILE_SETTLE_MS
        val candidate = unhandled.firstOrNull {
            it.lastModifiedEpochMs == null || it.lastModifiedEpochMs <= settleBefore
        }
        if (candidate == null && unhandled.isNotEmpty()) return Result.retry()
        candidate ?: return success(
            state = STATE_NO_CANDIDATES,
            candidateCount = scan.summary.gpxCandidates,
            truncated = scan.summary.truncated,
        )

        val bytes = try {
            applicationContext.contentResolver.openInputStream(candidate.uri)?.use {
                BoundedStreamInspector.readBytes(it, GpxCandidatePolicy.MAX_FILE_BYTES)
            } ?: return success(STATE_PROVIDER_ERROR)
        } catch (_: PayloadTooLargeException) {
            handledStore.markHandled(credential.deviceId, candidate.uri)
            return success(STATE_QUARANTINED)
        } catch (_: SecurityException) {
            return success(STATE_PERMISSION_REQUIRED)
        } catch (_: IOException) {
            return Result.retry()
        } catch (_: RuntimeException) {
            return success(STATE_PROVIDER_ERROR)
        }
        if (bytes.isEmpty()) {
            handledStore.markHandled(credential.deviceId, candidate.uri)
            return success(STATE_QUARANTINED)
        }

        val requestId = handledStore.requestIdFor(credential.deviceId, candidate.uri)
        return when (val imported = RunwayApiClient().importGpx(credential, bytes, requestId)) {
            is ImportApiResult.Handled -> {
                handledStore.markHandled(credential.deviceId, candidate.uri)
                success(
                    state = when (imported.result) {
                        "imported" -> STATE_IMPORTED
                        "duplicate" -> STATE_DUPLICATE
                        else -> STATE_QUARANTINED
                    },
                    candidateCount = scan.summary.gpxCandidates,
                    truncated = scan.summary.truncated,
                )
            }
            ImportApiResult.Unauthorized -> {
                handledStore.clearForDevice(credential.deviceId)
                credentialStore.clear()
                success(STATE_PAIRING_REQUIRED)
            }
            ImportApiResult.RequestConflict -> {
                handledStore.clearPendingRequest(credential.deviceId, candidate.uri)
                Result.retry()
            }
            ImportApiResult.Retryable -> Result.retry()
        }
    }

    private fun success(
        state: String,
        candidateCount: Int = 0,
        truncated: Boolean = false,
    ): Result = Result.success(
        Data.Builder()
            .putString(OUTPUT_STATE, state)
            .putInt(OUTPUT_CANDIDATE_COUNT, candidateCount)
            .putBoolean(OUTPUT_SCAN_TRUNCATED, truncated)
            .build(),
    )

    companion object {
        const val OUTPUT_STATE = "state"
        const val OUTPUT_CANDIDATE_COUNT = "candidate_count"
        const val OUTPUT_SCAN_TRUNCATED = "scan_truncated"
        const val STATE_IMPORTED = "imported"
        const val STATE_DUPLICATE = "duplicate"
        const val STATE_QUARANTINED = "quarantined"
        const val STATE_NO_CANDIDATES = "no_candidates"
        const val STATE_PAIRING_REQUIRED = "pairing_required"
        const val STATE_PERMISSION_REQUIRED = "permission_required"
        const val STATE_PROVIDER_ERROR = "provider_error"
        const val FILE_SETTLE_MS = 30_000L
    }
}
