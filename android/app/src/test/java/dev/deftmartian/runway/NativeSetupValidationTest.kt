package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class NativeSetupValidationTest {
    @Test
    fun `pending health state bypasses established baseline fields`() {
        assertNull(
            startingPointValidation(
                mode = "established", weeklyKm = "", runsPerWeek = "", longestKm = "",
                calibrationMinutes = "20", healthBlocked = true,
            ),
        )
        assertEquals(
            "Enter a repeatable week from 3 to 250 km.",
            startingPointValidation("established", "2", "3", "8", "20", false),
        )
    }

    @Test
    fun `starting point mirrors established and calibration bounds`() {
        assertEquals(
            "Enter a whole number from 2 to 5 current runs.",
            startingPointValidation("established", "12", "2.5", "8", "20", false),
        )
        assertEquals(
            "Enter a positive recent longest run up to 80 km.",
            startingPointValidation("established", "12", "3", "0", "20", false),
        )
        assertEquals(
            "Choose a whole timed check-in from 10 to 30 minutes.",
            startingPointValidation("calibration", "", "", "", "10.5", false),
        )
    }

    @Test
    fun `goal and schedule validation enforce planner bounds`() {
        assertEquals(
            "Choose a date from 2026-09-01 to 2027-07-27.",
            goalValidation(true, "2026-08-31", "2026-09-01", "2027-07-27"),
        )
        assertEquals(
            "Choose at least 3 available days.",
            scheduleValidation("foundation_only", listOf(1, 3), "", "", "America/Halifax", false),
        )
        assertEquals(
            "Choose at least as many available days as current weekly runs.",
            scheduleValidation("established", listOf(1, 3), "3", "1", "America/Halifax", false),
        )
        assertEquals(
            "Choose an available long-run day.",
            scheduleValidation("established", listOf(1, 3, 6), "3", "2", "America/Halifax", false),
        )
    }

    @Test
    fun `pending health goals do not require concentrated schedule acceptance`() {
        assertEquals(
            false,
            requiresConcentratedScheduleAcceptance("established", "2", "half", true),
        )
        assertEquals(
            true,
            requiresConcentratedScheduleAcceptance("established", "2", "marathon", false),
        )
    }

    @Test
    fun `collapsed health context states what is actually saved`() {
        assertEquals(
            "Nothing noted",
            healthContextSummary(false, false, false, false),
        )
        assertEquals(
            "Recent injury, Pain now, Training limited",
            healthContextSummary(true, true, false, true),
        )
    }

    @Test
    fun `setup exposes mode specific bounds in the entered training time zone`() {
        val now = Instant.parse("2026-07-30T12:00:00Z")

        assertEquals(
            "2026-09-24",
            setupTargetDateBounds("America/Halifax", "established", now)?.minimum,
        )
        assertEquals(
            "2026-10-08",
            setupTargetDateBounds("America/Halifax", "calibration", now)?.minimum,
        )
        assertEquals(
            "2026-11-26",
            setupTargetDateBounds("America/Halifax", "foundation_to_goal", now)?.minimum,
        )
        assertEquals(
            "2027-07-28",
            setupTargetDateBounds("America/Halifax", "established", now)?.maximum,
        )
        assertNull(setupTargetDateBounds("not/a-zone", "established", now))
    }
}
