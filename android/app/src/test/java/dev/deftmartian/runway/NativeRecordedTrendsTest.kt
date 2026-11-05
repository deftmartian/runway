package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeRecordedTrendsTest {
    @Test
    fun `last four weeks average runs and distance and weight pace by distance`() {
        val weeks = listOf(
            week("2026-10-05", runs = 5, km = 40.0, pace = 300.0, longest = 20_000.0),
            week("2026-10-12", runs = 0, km = 0.0),
            week("2026-10-19", runs = 2, km = 10.0, pace = 360.0, heartRate = 150, seconds = 3_600.0),
            week("2026-10-26", runs = 3, km = 20.0, pace = 330.0, heartRate = 140, seconds = 6_600.0),
            week("2026-11-02", runs = 1, km = 6.0, pace = 300.0),
        )

        val summary = recentWeeksSummary(weeks)

        assertEquals("1.5", summary.runsPerWeek) // (0 + 2 + 3 + 1) / 4
        assertEquals("9 km", summary.distancePerWeek) // 36 km / 4
        assertEquals("5:33 /km", summary.pace) // (3600 + 6600 + 1800) s / 36 km = 333 s/km
        assertEquals("144 bpm", summary.heartRate) // (150*3600 + 140*6600) / 10200 = 143.5 rounds to 144
        assertEquals("20 km", summary.longestRun) // the longest run spans every week shown
    }

    @Test
    fun `a week line states only what was recorded`() {
        assertEquals("No runs", recordedWeekDetail(week("2026-11-02", runs = 0, km = 0.0)))
        assertEquals(
            "1 run · 6 km · 30 min · 5:00 /km",
            recordedWeekDetail(week("2026-11-02", runs = 1, km = 6.0, pace = 300.0, seconds = 1_800.0)),
        )
        assertEquals(
            "3 runs · 20 km · longest 10 km · 142 bpm",
            recordedWeekDetail(week("2026-11-02", runs = 3, km = 20.0, longest = 10_000.0, heartRate = 142, seconds = 0.0)),
        )
    }

    @Test
    fun `trends start at the first week with a run and keep at least four weeks`() {
        val empty = (1..8).map { week("2026-08-${10 + it}", runs = 0, km = 0.0) }
        val withRuns = listOf(week("2026-10-26", runs = 1, km = 5.0), week("2026-11-02", runs = 2, km = 9.0))
        assertEquals(4, trimLeadingEmptyWeeks(empty + withRuns).size)
        assertEquals(
            listOf("2026-10-26", "2026-11-02", "2026-11-09"),
            trimLeadingEmptyWeeks(
                listOf(week("2026-10-19", runs = 0, km = 0.0)) + withRuns + week("2026-11-09", runs = 0, km = 0.0),
            ).map { it.startDate }.takeLast(3),
        )
        assertEquals(emptyList<NativeRecordedWeek>(), trimLeadingEmptyWeeks(empty))
    }

    @Test
    fun `weeks without pace or heart rate leave those rows out`() {
        val summary = recentWeeksSummary(listOf(week("2026-11-02", runs = 1, km = 5.0)))
        assertNull(summary.pace)
        assertNull(summary.heartRate)
        assertEquals("1 run", runCount(1))
        assertEquals("2 runs", runCount(2))
    }

    private fun week(
        start: String,
        runs: Int,
        km: Double,
        pace: Double? = null,
        heartRate: Int? = null,
        seconds: Double = (pace ?: 0.0) * km,
        longest: Double? = if (runs > 0) km * 1_000 else null,
    ) = NativeRecordedWeek(
        startDate = start,
        runs = runs,
        distanceMeters = km * 1_000,
        durationSeconds = seconds,
        longestRunMeters = longest,
        paceSecondsPerKm = pace,
        averageHeartRate = heartRate,
    )
}
