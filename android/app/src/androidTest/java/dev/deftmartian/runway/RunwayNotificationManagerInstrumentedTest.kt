package dev.deftmartian.runway

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.deftmartian.runway.data.LocalRunReminderCandidate
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunwayNotificationManagerInstrumentedTest {
    @Test
    fun runReminderPostsOnceWithPrivateGenericCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        RunwayNotificationManager.createChannels(context)
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        manager.cancelAll()
        val services = context.runwayServices
        services.dataManagement.eraseAllTrainingData()
        services.notifications.updateRunReminder(enabled = true, minuteOfDay = 8 * 60)
        val epochDay = 24_000L
        assertTrue(
            services.notifications.enqueueRunReminder(
                LocalRunReminderCandidate(epochDay, listOf(UUID.randomUUID().toString())),
            ),
        )

        try {
            assertTrue(RunwayNotificationManager.deliverPendingRunReminders(context, epochDay))
            assertFalse(RunwayNotificationManager.deliverPendingRunReminders(context, epochDay))
            val active = manager.activeNotifications.single {
                it.notification.channelId == RunwayNotificationManager.RUN_REMINDER_CHANNEL_ID
            }.notification
            assertEquals(Notification.VISIBILITY_PRIVATE, active.visibility)
            assertEquals("Run planned today", active.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(
                "Open Runway to review the plan when you’re ready.",
                active.extras.getCharSequence(Notification.EXTRA_TEXT),
            )
            val visibleCopy = active.extras.toString().lowercase()
            listOf("route", "heart", "filename", "distance", "pace", "pain").forEach {
                forbidden -> assertFalse(visibleCopy.contains(forbidden))
            }
        } finally {
            manager.cancelAll()
            services.dataManagement.eraseAllTrainingData()
        }
    }

    private fun grantNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            ).use { output ->
                FileInputStream(output.fileDescriptor).use { it.readBytes() }
            }
    }
}
