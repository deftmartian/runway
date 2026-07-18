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
        val treeState = treeStore.currentState()
        if (treeState !is TreeAccessState.Connected) return success(STATE_PERMISSION_REQUIRED)
        val scanProgressStore = ScanProgressStore(applicationContext)
        val scanOffset = scanProgressStore.offsetFor(credential.deviceId, treeState.uri)
        val scan = SafTreeScanner(applicationContext.contentResolver).scan(treeState, scanOffset)
        if (scan !is TreeScanResult.Success) {
            return when (scan) {
                TreeScanResult.PermissionRequired -> success(STATE_PERMISSION_REQUIRED)
                TreeScanResult.ProviderError -> success(STATE_PROVIDER_ERROR)
                is TreeScanResult.Success -> error("unreachable")
            }
        }

        val handledStore = HandledImportStore(applicationContext)
        val unhandled = handledStore
            .filterUnhandled(credential.deviceId, scan.summary.candidates)
            .sortedByDescending { it.lastModifiedEpochMs ?: Long.MIN_VALUE }
        val settleBefore = System.currentTimeMillis() - FILE_SETTLE_MS
        val candidate = unhandled.firstOrNull {
            it.lastModifiedEpochMs == null || it.lastModifiedEpochMs <= settleBefore
        }
        if (candidate == null && unhandled.isNotEmpty()) return Result.retry()
        if (candidate == null) {
            val nextOffset = scan.summary.nextOffset
            if (nextOffset != null) {
                scanProgressStore.advance(credential.deviceId, treeState.uri, nextOffset)
                return Result.retry()
            }
            scanProgressStore.reset(credential.deviceId)
            return success(
                state = STATE_NO_CANDIDATES,
                candidateCount = scan.summary.gpxCandidates,
                truncated = false,
            )
        }

        if (isStopped) return Result.retry()

        val bytes = try {
            applicationContext.contentResolver.openInputStream(candidate.uri)?.use {
                BoundedStreamInspector.readBytes(it, GpxCandidatePolicy.MAX_FILE_BYTES)
            } ?: return success(STATE_PROVIDER_ERROR)
        } catch (_: PayloadTooLargeException) {
            handledStore.markHandled(credential.deviceId, candidate.uri)
            advanceAfterLastCandidate(
                remainingCandidates = unhandled.size - 1,
                summary = scan.summary,
                deviceId = credential.deviceId,
                treeUri = treeState.uri,
                progressStore = scanProgressStore,
            )
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
            advanceAfterLastCandidate(
                remainingCandidates = unhandled.size - 1,
                summary = scan.summary,
                deviceId = credential.deviceId,
                treeUri = treeState.uri,
                progressStore = scanProgressStore,
            )
            return success(STATE_QUARANTINED)
        }

        if (isStopped) return Result.retry()
        val requestId = handledStore.requestIdFor(credential.deviceId, candidate.uri)
        return when (val imported = RunwayApiClient().importGpx(credential, bytes, requestId)) {
            is ImportApiResult.Handled -> {
                handledStore.markHandled(credential.deviceId, candidate.uri)
                advanceAfterLastCandidate(
                    remainingCandidates = unhandled.size - 1,
                    summary = scan.summary,
                    deviceId = credential.deviceId,
                    treeUri = treeState.uri,
                    progressStore = scanProgressStore,
                )
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
                scanProgressStore.reset(credential.deviceId)
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

    private fun advanceAfterLastCandidate(
        remainingCandidates: Int,
        summary: TreeScanSummary,
        deviceId: String,
        treeUri: android.net.Uri,
        progressStore: ScanProgressStore,
    ) {
        if (remainingCandidates != 0) return
        val nextOffset = summary.nextOffset
        if (nextOffset == null) {
            progressStore.reset(deviceId)
        } else {
            progressStore.advance(deviceId, treeUri, nextOffset)
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
