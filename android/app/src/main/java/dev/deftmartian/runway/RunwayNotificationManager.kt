package dev.deftmartian.runway

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException

object RunwayNotificationManager {
    internal const val RUN_REMINDER_CHANNEL_ID = "runway_run_reminders"
    internal const val IMPORT_REVIEW_CHANNEL_ID = "runway_import_reviews"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    RUN_REMINDER_CHANNEL_ID,
                    "Run reminders",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Best-effort reminders on planned run days"
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
                NotificationChannel(
                    IMPORT_REVIEW_CHANNEL_ID,
                    "Import review alerts",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Alerts when a folder import is ready to review"
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
            ),
        )
    }

    fun notificationsAllowed(context: Context, channelId: String): Boolean {
        val runtimePermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!runtimePermissionGranted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(channelId) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    suspend fun deliverPendingFolderImportAlerts(context: Context): Boolean {
        return try {
            val services = context.runwayServices
            val preferences = services.notifications.preferences()
            if (
                !preferences.folderImportAlertsEnabled ||
                !notificationsAllowed(context, IMPORT_REVIEW_CHANNEL_ID)
            ) return false
            val pending = services.notifications.pendingFolderImportAlerts()
            if (pending.isEmpty()) return false
            val notification = NotificationCompat.Builder(context, IMPORT_REVIEW_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(
                    if (pending.size == 1) "Run ready to review" else "Runs ready to review",
                )
                .setContentText("Open Inbox to review the new folder import.")
                .setContentIntent(
                    destinationIntent(
                        context,
                        MainActivity.ACTION_OPEN_INBOX,
                        IMPORT_NOTIFICATION_ID,
                    ),
                )
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .build()
            postAndComplete(
                context = context,
                notificationId = IMPORT_NOTIFICATION_ID,
                notification = notification,
                onPosted = { services.notifications.markDelivered(pending) },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deliverPendingRunReminders(context: Context, epochDay: Long): Boolean {
        return try {
            val services = context.runwayServices
            val preferences = services.notifications.preferences()
            if (
                !preferences.runReminderEnabled ||
                !notificationsAllowed(context, RUN_REMINDER_CHANNEL_ID)
            ) return false
            services.notifications.discardStaleRunReminders(epochDay)
            val pending = services.notifications.pendingRunReminders()
                .filter { it.localEpochDay == epochDay }
            if (pending.isEmpty()) return false
            val notification = NotificationCompat.Builder(context, RUN_REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Run planned today")
                .setContentText("Open Runway to review the plan when you’re ready.")
                .setContentIntent(
                    destinationIntent(
                        context,
                        MainActivity.ACTION_OPEN_CALENDAR,
                        RUN_NOTIFICATION_ID,
                    ),
                )
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .build()
            postAndComplete(
                context = context,
                notificationId = RUN_NOTIFICATION_ID,
                notification = notification,
                onPosted = { services.notifications.markDelivered(pending) },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    fun cancelRunReminder(context: Context) {
        NotificationManagerCompat.from(context).cancel(RUN_NOTIFICATION_ID)
    }

    fun cancelImportReview(context: Context) {
        NotificationManagerCompat.from(context).cancel(IMPORT_NOTIFICATION_ID)
    }

    fun cancelAll(context: Context) {
        cancelRunReminder(context)
        cancelImportReview(context)
    }

    private suspend fun postAndComplete(
        context: Context,
        notificationId: Int,
        notification: android.app.Notification,
        onPosted: suspend () -> Unit,
    ): Boolean = try {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        onPosted()
        true
    } catch (_: SecurityException) {
        false
    }

    private fun destinationIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val RUN_NOTIFICATION_ID = 2_401
    private const val IMPORT_NOTIFICATION_ID = 2_402
}
