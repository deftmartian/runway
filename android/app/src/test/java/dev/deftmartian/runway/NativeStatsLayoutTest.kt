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

    @Test
    fun `long setting values stack instead of squeezing into half a phone`() {
        assertTrue(
            usesStackedSettingRow(
                label = "Goal",
                value = "30 minutes of continuous easy running",
                monospace = false,
                availableWidthDp = 328f,
                fontScale = 1f,
            ),
        )
    }

    @Test
    fun `short setting values retain the compact two-column layout`() {
        assertFalse(
            usesStackedSettingRow(
                label = "Route privacy",
                value = "Private",
                monospace = false,
                availableWidthDp = 328f,
                fontScale = 1f,
            ),
        )
    }

    @Test
    fun `technical identifiers and enlarged copy stack before they become cramped`() {
        assertTrue(
            usesStackedSettingRow(
                label = "Server",
                value = "https://runway.example.test",
                monospace = true,
                availableWidthDp = 328f,
                fontScale = 1f,
            ),
        )
        assertTrue(
            usesStackedSettingRow(
                label = "Two-factor authentication",
                value = "Not enabled",
                monospace = false,
                availableWidthDp = 328f,
                fontScale = 1.3f,
            ),
        )
    }
}
