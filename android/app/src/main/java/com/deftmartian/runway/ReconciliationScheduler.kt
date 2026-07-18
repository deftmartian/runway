package com.deftmartian.runway

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ReconciliationScheduler {
    private const val ONE_TIME_WORK_NAME = "runway-folder-check"
    private const val PERIODIC_WORK_NAME = "runway-folder-reconciliation"

    fun runOnce(context: Context) {
        val request = OneTimeWorkRequest.Builder(ReconciliationWorker::class.java).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun enablePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequest.Builder(
            ReconciliationWorker::class.java,
            15,
            TimeUnit.MINUTES,
        ).setConstraints(constraints).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun disablePeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }
}
