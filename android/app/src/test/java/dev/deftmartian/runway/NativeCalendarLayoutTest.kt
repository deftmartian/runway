package dev.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCalendarLayoutTest {
    @Test
    fun `compact and medium screens use the readable day ledger`() {
        assertTrue(usesCompactCalendarLedger(360f))
        assertTrue(usesCompactCalendarLedger(600f))
        assertTrue(usesCompactCalendarLedger(979f))
    }

    @Test
    fun `expanded screens retain the month overview`() {
        assertFalse(usesCompactCalendarLedger(980f))
        assertFalse(usesCompactCalendarLedger(1_000f))
    }

    @Test
    fun `enlarged text keeps the day ledger until the grid has readable cells`() {
        assertTrue(usesCompactCalendarLedger(1_273f, fontScale = 1.3f))
        assertFalse(usesCompactCalendarLedger(1_274f, fontScale = 1.3f))
    }

    @Test
    fun `record run action hides down and returns up or at the top`() {
        val top = CalendarScrollPosition(0, 0)
        val lower = CalendarScrollPosition(2, 20)
        val farther = CalendarScrollPosition(3, 0)

        assertFalse(calendarRecordRunFabVisibleAfterScroll(top, lower, true))
        assertFalse(calendarRecordRunFabVisibleAfterScroll(lower, farther, false))
        assertTrue(calendarRecordRunFabVisibleAfterScroll(farther, lower, false))
        assertTrue(calendarRecordRunFabVisibleAfterScroll(lower, top, false))
        assertFalse(calendarRecordRunFabVisibleAfterScroll(lower, lower, false))
    }
}
