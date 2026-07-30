package dev.deftmartian.runway

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal data class ReconciliationScheduleSnapshot(
    val folderCheckScheduled: Boolean,
    val folderPeriodicScheduled: Boolean,
    val healthConnectPeriodicScheduled: Boolean,
)

object ReconciliationScheduler {
    internal const val ONE_TIME_WORK_NAME = "runway-folder-check"
    internal const val PERIODIC_WORK_NAME = "runway-folder-reconciliation"
    internal const val HEALTH_CONNECT_WORK_NAME = "runway-health-connect-sync"

    /** Called on app focus as well as from the import settings screen. */
    fun runOnce(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            oneTimeRequest(MAX_DRAIN_WORKERS),
        )
    }

    fun enablePeriodic(context: Context) {
        val request = PeriodicWorkRequest.Builder(
            ReconciliationWorker::class.java,
            15,
            TimeUnit.MINUTES,
        )
            .setConstraints(localStorageConstraints())
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

    fun cancelFolderWork(context: Context) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(ONE_TIME_WORK_NAME)
            cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }

    fun cancelAll(context: Context) {
        cancelFolderWork(context)
        WorkManager.getInstance(context).cancelUniqueWork(HEALTH_CONNECT_WORK_NAME)
    }

    /**
     * Stops every database-owning worker before a destructive restore closes and replaces Room.
     *
     * This is called from an IO dispatcher. Waiting for WorkManager's cancellation operations
     * prevents an import worker from racing the database replacement.
     */
    fun cancelAllAndWait(context: Context) {
        val manager = WorkManager.getInstance(context)
        listOf(
            manager.cancelUniqueWork(ONE_TIME_WORK_NAME),
            manager.cancelUniqueWork(PERIODIC_WORK_NAME),
            manager.cancelUniqueWork(HEALTH_CONNECT_WORK_NAME),
        ).forEach { operation ->
            operation.result.get(WORK_CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    /**
     * Captures user-enabled work before a short privacy mutation pauses database owners.
     *
     * WorkManager state is read before cancellation so the caller can restore the same work after
     * the acquisition gate reopens. Source-disconnect and restore flows deliberately do not call
     * this method because their schedules must remain disabled.
     */
    internal fun captureSchedule(context: Context): ReconciliationScheduleSnapshot {
        val manager = WorkManager.getInstance(context)
        return ReconciliationScheduleSnapshot(
            folderCheckScheduled = manager.hasScheduledWork(ONE_TIME_WORK_NAME),
            folderPeriodicScheduled = manager.hasScheduledWork(PERIODIC_WORK_NAME),
            healthConnectPeriodicScheduled = manager.hasScheduledWork(HEALTH_CONNECT_WORK_NAME),
        )
    }

    internal fun restoreSchedule(
        context: Context,
        snapshot: ReconciliationScheduleSnapshot,
    ) {
        val failures = buildList {
            attemptIf(snapshot.folderPeriodicScheduled, "GPX folder schedule") {
                enablePeriodic(context)
            }
            attemptIf(snapshot.healthConnectPeriodicScheduled, "Health Connect schedule") {
                enableHealthConnectPeriodic(context)
            }
            attemptIf(snapshot.folderCheckScheduled, "pending GPX folder check") {
                runOnce(context)
            }
        }
        check(failures.isEmpty()) {
            "Could not resume ${failures.joinToString()} after the local privacy change."
        }
    }

    fun enableHealthConnectPeriodic(context: Context) {
        val request = PeriodicWorkRequest.Builder(HealthConnectWorker::class.java, 6, TimeUnit.HOURS)
            .setConstraints(localStorageConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEALTH_CONNECT_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun disableHealthConnectPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(HEALTH_CONNECT_WORK_NAME)
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
            .setConstraints(localStorageConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workerInput(remainingWorkers))
            .build()

    private fun workerInput(remainingWorkers: Int): Data = Data.Builder()
        .putInt(ReconciliationWorker.INPUT_DRAIN_WORKERS, remainingWorkers.coerceAtLeast(1))
        .build()

    private fun WorkManager.hasScheduledWork(name: String): Boolean =
        getWorkInfosForUniqueWork(name)
            .get(WORK_CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .any { work ->
                work.state == WorkInfo.State.ENQUEUED ||
                    work.state == WorkInfo.State.RUNNING ||
                    work.state == WorkInfo.State.BLOCKED
            }

    private inline fun MutableList<String>.attemptIf(
        required: Boolean,
        label: String,
        action: () -> Unit,
    ) {
        if (!required) return
        try {
            action()
        } catch (_: Exception) {
            add(label)
        }
    }

    private fun localStorageConstraints(): Constraints = Constraints.Builder()
        .setRequiresStorageNotLow(true)
        .build()

    private const val RETRY_BACKOFF_SECONDS = 15L
    private const val WORK_CANCELLATION_TIMEOUT_SECONDS = 30L
    private const val MAX_DRAIN_WORKERS = 8
}
