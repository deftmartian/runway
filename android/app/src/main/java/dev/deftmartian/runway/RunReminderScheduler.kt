package dev.deftmartian.runway

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import dev.deftmartian.runway.data.LocalRunReminderCandidate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object RunReminderScheduler {
    internal const val WORK_NAME = "runway-next-run-reminder"

    suspend fun reconcile(context: Context): Boolean = try {
        schedule(context.applicationContext, ExistingWorkPolicy.REPLACE, cancelWhenEmpty = true)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    suspend fun continueAfterWorker(context: Context) {
        schedule(
            context.applicationContext,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            cancelWhenEmpty = false,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun cancelAndWait(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(WORK_NAME)
            .result
            .get(WORK_CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private suspend fun schedule(
        context: Context,
        policy: ExistingWorkPolicy,
        cancelWhenEmpty: Boolean,
    ) {
        val services = context.runwayServices
        val preferences = services.notifications.preferences()
        if (
            !preferences.runReminderEnabled ||
            !RunwayNotificationManager.notificationsAllowed(
                context,
                RunwayNotificationManager.RUN_REMINDER_CHANNEL_ID,
            )
        ) {
            if (cancelWhenEmpty) cancel(context)
            return
        }
        val profile = services.trainingContext.profile()
        val zone = profile?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        if (zone == null) {
            if (cancelWhenEmpty) cancel(context)
            return
        }
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate().toEpochDay()
        RunwayNotificationManager.deliverPendingRunReminders(context, today)
        val candidate = nextFutureCandidate(
            services.notifications.nextPlannedRun(today),
            preferences.runReminderMinuteOfDay,
            now,
            zone,
        ) ?: services.notifications.nextPlannedRun(today + 1)
        if (candidate == null) {
            if (cancelWhenEmpty) cancel(context)
            return
        }
        val trigger = runReminderTrigger(
            candidate.epochDay,
            preferences.runReminderMinuteOfDay,
            zone,
        )
        val delayMillis = (trigger.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0)
        val request = OneTimeWorkRequest.Builder(RunReminderWorker::class.java)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putLong(RunReminderWorker.INPUT_TARGET_EPOCH_DAY, candidate.epochDay)
                    .putLong(RunReminderWorker.INPUT_TRIGGER_EPOCH_MILLIS, trigger.toEpochMilli())
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}

internal fun runReminderTrigger(epochDay: Long, minuteOfDay: Int, zone: ZoneId): Instant {
    require(minuteOfDay in 0 until 24 * 60)
    return LocalDate.ofEpochDay(epochDay)
        .atTime(minuteOfDay / 60, minuteOfDay % 60)
        .atZone(zone)
        .toInstant()
}

internal fun nextFutureCandidate(
    candidate: LocalRunReminderCandidate?,
    minuteOfDay: Int,
    now: Instant,
    zone: ZoneId,
): LocalRunReminderCandidate? = candidate?.takeIf {
    runReminderTrigger(it.epochDay, minuteOfDay, zone).isAfter(now)
}

private const val WORK_CANCELLATION_TIMEOUT_SECONDS = 30L
