package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeSettingsSummaryTest {
    @Test
    fun `current pain remains visible in the settings summary`() {
        assertEquals(
            "Current pain reported",
            healthContextSummary(
                NativeInjuryFlags(
                    recentInjury = false,
                    currentPain = true,
                    recurringPain = false,
                    medicalRestriction = false,
                    notes = "private detail",
                ),
            ),
        )
    }

    @Test
    fun `configured import sources are not all described as connected`() {
        assertEquals(
            "1 need attention · 1 active · 1 paused",
            nextcloudSourceSummary(
                listOf(
                    NativeImportSource("error", "Error", true, "Could not connect", null),
                    NativeImportSource("active", "Active", true, null, null),
                    NativeImportSource("paused", "Paused", false, null, null),
                ),
            ),
        )
    }

    @Test
    fun `health connect attention retains its actionable server detail`() {
        assertEquals(
            "Waiting for this phone to send records.",
            phoneImportSummary(
                NativeHealthConnectStatus(
                    state = "needs_attention",
                    message = "Waiting for this phone to send records.",
                    lastSyncAt = null,
                    permissions = emptyList(),
                ),
            ),
        )
    }
}
