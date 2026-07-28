package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeDestinationTest {
    @Test
    fun `primary mobile navigation stays at five destinations`() {
        assertEquals(
            listOf("Today", "Calendar", "Imports", "Progress", "More"),
            NativeDestination.entries
                .filter(NativeDestination::primaryNavigation)
                .map(NativeDestination::label),
        )
    }

    @Test
    fun `history remains reachable without becoming a sixth bottom item`() {
        assertFalse(NativeDestination.History.primaryNavigation)
        assertEquals(NativeDestination.Progress, NativeDestination.History.navigationParent)
        assertEquals("history", NativeDestination.History.view)
        assertEquals(
            NativeDestination.Progress,
            NativeDestination.HistoryDetail.primaryNavigationDestination(),
        )
    }
}
