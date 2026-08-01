package dev.deftmartian.runway

import org.junit.Assert.assertEquals
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
    fun `trace accessibility description is concise and points to exact values`() {
        assertTrue(
            nativeTraceChartDescription("Weekly distance").contains(
                "Open Weekly values for each week.",
            ),
        )
    }

    @Test
    fun `routine progress selects the week containing local today`() {
        val previous = routineWeek(1, "2026-07-20")
        val current = routineWeek(2, "2026-07-27")
        val history = NativeTrainingHistory(
            weeklySummaries = listOf(previous, current),
            todayIso = "2026-08-01",
            currentSignal = null,
            hasAcceptedActivities = true,
            recordedSummary = null,
            heartRateSample = null,
        )

        assertEquals(current, routineCurrentWeek(history))
    }

    @Test
    fun `routine cadence stays factual for unrecorded skipped and extra runs`() {
        val summary = routineWeek(2, "2026-07-27").copy(
            completedRuns = 3,
            plannedRunsRecorded = 1,
            extraRuns = 2,
            missedRuns = 1,
            skippedRuns = 1,
        )

        assertEquals(
            "3 recorded · 1 on scheduled days · 2 on other days · 3 scheduled · 1 not recorded · 1 skipped",
            routineRunCountSummary(summary),
        )
        assertEquals("This week", routineWeekLabel(summary, "2026-08-01"))
    }

    @Test
    fun `routine waiting message names a future start without pretending stats are missing`() {
        assertEquals(
            "Your first routine week starts Monday, August 3. Calendar shows the open runs.",
            routineWaitingMessage("2026-08-03", "2026-08-01"),
        )
        assertEquals(
            "Calendar shows the next open run in your routine.",
            routineWaitingMessage("2026-07-27", "2026-08-01"),
        )
    }

    @Test
    fun `empty partial first routine week stays in the useful waiting state`() {
        assertTrue(
            routineWeekIsWaiting(
                routineWeek(1, "2026-07-27").copy(plannedRuns = 0),
            ),
        )
        assertFalse(
            routineWeekIsWaiting(
                routineWeek(1, "2026-07-27").copy(
                    plannedRuns = 0,
                    completedRuns = 1,
                    extraRuns = 1,
                ),
            ),
        )
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
                label = "Commit",
                value = "36899686c3bb4d8016b09bfa1def9c9584f8053c",
                monospace = true,
                availableWidthDp = 328f,
                fontScale = 1f,
            ),
        )
        assertTrue(
            usesStackedSettingRow(
                label = "Health Connect",
                value = "Permission needed",
                monospace = false,
                availableWidthDp = 328f,
                fontScale = 1.3f,
            ),
        )
    }

    private fun routineWeek(number: Int, start: String) = NativeWeekSummary(
        weekNumber = number,
        startDate = start,
        targetDistanceMeters = null,
        completedDistanceMeters = null,
        completedDurationSeconds = null,
        plannedRuns = 3,
        completedRuns = 0,
        missedRuns = 0,
        skippedRuns = 0,
        painFlags = 0,
        hardFlags = 0,
        averagePaceSecondsPerKm = null,
        averageHeartRate = null,
    )
}
