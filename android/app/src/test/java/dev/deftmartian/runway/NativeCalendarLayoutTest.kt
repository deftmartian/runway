package dev.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCalendarLayoutTest {
    @Test
    fun `calendar grid fits a standard phone viewport without horizontal scrolling`() {
        assertFalse(calendarGridNeedsHorizontalScroll(344f))
    }

    @Test
    fun `calendar grid exposes its horizontal fallback below its 48dp cell width`() {
        assertTrue(calendarGridNeedsHorizontalScroll(328f))
        assertTrue(calendarGridNeedsHorizontalScroll(304f))
    }
}
