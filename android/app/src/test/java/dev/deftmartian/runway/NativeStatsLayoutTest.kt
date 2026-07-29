package dev.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStatsLayoutTest {
    @Test
    fun `phone-width trace rows stack at default text size`() {
        assertTrue(usesStackedNativeTraceRow(356f, 1f))
    }

    @Test
    fun `enlarged text trace rows stack even on a wide surface`() {
        assertTrue(usesStackedNativeTraceRow(600f, 1.3f))
    }

    @Test
    fun `wide default-text trace rows retain the compact two-column layout`() {
        assertFalse(usesStackedNativeTraceRow(600f, 1f))
    }
}
