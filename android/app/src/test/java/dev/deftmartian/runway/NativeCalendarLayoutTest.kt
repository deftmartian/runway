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

    @Test
    fun `calendar grid keeps labels readable when text is enlarged`() {
        assertTrue(calendarGridNeedsHorizontalScroll(344f, fontScale = 1.3f))
        assertFalse(calendarGridNeedsHorizontalScroll(448f, fontScale = 1.3f))
    }

    @Test
    fun `secondary plan actions stack for narrow or enlarged layouts`() {
        assertFalse(usesStackedCalendarPlanActions(360f, fontScale = 1f))
        assertTrue(usesStackedCalendarPlanActions(359f, fontScale = 1f))
        assertTrue(usesStackedCalendarPlanActions(400f, fontScale = 1.1f))
    }
}
