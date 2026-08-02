package dev.deftmartian.runway.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNotificationRepositoryTest {
    @Test
    fun `invalid restored reminder minutes fall back to the default`() {
        assertEquals(0, normalizedReminderMinuteOfDay(0))
        assertEquals(1_439, normalizedReminderMinuteOfDay(1_439))
        assertEquals(
            NotificationPreferencesEntity.DEFAULT_REMINDER_MINUTE_OF_DAY,
            normalizedReminderMinuteOfDay(-1),
        )
        assertEquals(
            NotificationPreferencesEntity.DEFAULT_REMINDER_MINUTE_OF_DAY,
            normalizedReminderMinuteOfDay(1_440),
        )
    }
}
