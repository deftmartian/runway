package com.deftmartian.runway

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ReconciliationScheduler {
    internal const val ONE_TIME_WORK_NAME = "runway-folder-check"
    internal const val PERIODIC_WORK_NAME = "runway-folder-reconciliation"

    fun runOnce(context: Context) {
        val request = oneTimeRequest(MAX_DRAIN_WORKERS)
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun enablePeriodic(context: Context) {
        val request = PeriodicWorkRequest.Builder(
            ReconciliationWorker::class.java,
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(reconciliationConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workerInput(MAX_DRAIN_WORKERS))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun disablePeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(ONE_TIME_WORK_NAME)
            cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    internal fun continueBacklog(context: Context, remainingWorkers: Int) {
        if (remainingWorkers <= 0) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            oneTimeRequest(remainingWorkers),
        )
    }

    private fun oneTimeRequest(remainingWorkers: Int): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(ReconciliationWorker::class.java)
            .setConstraints(reconciliationConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workerInput(remainingWorkers))
            .build()

    private fun workerInput(remainingWorkers: Int): Data = Data.Builder()
        .putInt(ReconciliationWorker.INPUT_DRAIN_WORKERS, remainingWorkers.coerceAtLeast(1))
        .build()

    private fun reconciliationConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresStorageNotLow(true)
        .build()

    private const val RETRY_BACKOFF_SECONDS = 15L
    private const val MAX_DRAIN_WORKERS = 8
}
