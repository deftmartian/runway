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
    fun `secondary plan actions stack for narrow or enlarged layouts`() {
        assertFalse(usesStackedCalendarPlanActions(360f, fontScale = 1f))
        assertTrue(usesStackedCalendarPlanActions(359f, fontScale = 1f))
        assertTrue(usesStackedCalendarPlanActions(400f, fontScale = 1.1f))
    }
}
