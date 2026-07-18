package com.deftmartian.runway

import android.content.Context
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters

class ReconciliationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val treeStore = TreeAccessStore(applicationContext)
        return when (val scan = SafTreeScanner(applicationContext.contentResolver).scan(treeStore.currentState())) {
            is TreeScanResult.Success -> Result.success(
                Data.Builder()
                    .putString(OUTPUT_STATE, STATE_API_UNAVAILABLE)
                    .putInt(OUTPUT_CANDIDATE_COUNT, scan.summary.gpxCandidates)
                    .putBoolean(OUTPUT_SCAN_TRUNCATED, scan.summary.truncated)
                    .build(),
            )
            TreeScanResult.PermissionRequired -> Result.success(
                Data.Builder().putString(OUTPUT_STATE, STATE_PERMISSION_REQUIRED).build(),
            )
            TreeScanResult.ProviderError -> Result.success(
                Data.Builder().putString(OUTPUT_STATE, STATE_PROVIDER_ERROR).build(),
            )
        }
    }

    companion object {
        const val OUTPUT_STATE = "state"
        const val OUTPUT_CANDIDATE_COUNT = "candidate_count"
        const val OUTPUT_SCAN_TRUNCATED = "scan_truncated"
        const val STATE_API_UNAVAILABLE = "api_unavailable"
        const val STATE_PERMISSION_REQUIRED = "permission_required"
        const val STATE_PROVIDER_ERROR = "provider_error"
    }
}
