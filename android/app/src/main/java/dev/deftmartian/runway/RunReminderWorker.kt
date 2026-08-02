package dev.deftmartian.runway

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

class RunReminderWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = try {
        val services = applicationContext.runwayServices
        val preferences = services.notifications.preferences()
        if (!preferences.runReminderEnabled) return Result.success()
        val zone = services.trainingContext.profile()?.timeZone
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: return Result.success()
        val targetEpochDay = inputData.getLong(INPUT_TARGET_EPOCH_DAY, Long.MIN_VALUE)
        val targetTrigger = inputData.getLong(INPUT_TRIGGER_EPOCH_MILLIS, Long.MAX_VALUE)
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate().toEpochDay()
        if (targetEpochDay == today && now.toEpochMilli() >= targetTrigger) {
            services.notifications.plannedRunsOn(today)?.let { candidate ->
                services.notifications.enqueueRunReminder(candidate)
                RunwayNotificationManager.deliverPendingRunReminders(applicationContext, today)
            }
        }
        RunReminderScheduler.continueAfterWorker(applicationContext)
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        const val INPUT_TARGET_EPOCH_DAY = "target_epoch_day"
        const val INPUT_TRIGGER_EPOCH_MILLIS = "trigger_epoch_millis"
    }
}
