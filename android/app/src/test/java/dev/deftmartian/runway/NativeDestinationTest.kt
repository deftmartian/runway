package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDestinationTest {
    @Test
    fun `primary mobile navigation matches the web product surfaces`() {
        assertEquals(
            listOf("Calendar", "Inbox", "Stats", "History", "Settings"),
            NativeDestination.entries
                .filter(NativeDestination::primaryNavigation)
                .map(NativeDestination::label),
        )
        assertEquals(
            listOf("calendar", "review", "stats", "history", "settings"),
            NativeDestination.entries
                .filter(NativeDestination::primaryNavigation)
                .map(NativeDestination::view),
        )
    }

    @Test
    fun `nested destinations select their owning primary surface`() {
        assertTrue(NativeDestination.History.primaryNavigation)
        assertEquals(
            NativeDestination.History,
            NativeDestination.HistoryDetail.primaryNavigationDestination(),
        )
        assertEquals(
            NativeDestination.Settings,
            NativeDestination.AccountSecurity.primaryNavigationDestination(),
        )
    }
}
