package dev.deftmartian.runway

import dev.deftmartian.runway.domain.RiskRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class NativeSetupValidationTest {
    @Test
    fun `setup blocks unsupported plans and requires confirmation for high increase`() {
        assertEquals(
            "Choose a later race date, a shorter goal, or a different starting point.",
            planAssessmentIssue(RiskRating.UNSAFE, confirmedHighIncrease = true),
        )
        assertEquals(
            "Confirm the schedule after reviewing its warnings, or change the plan inputs.",
            planAssessmentIssue(RiskRating.AGGRESSIVE, confirmedHighIncrease = false),
        )
        assertNull(planAssessmentIssue(RiskRating.AGGRESSIVE, confirmedHighIncrease = true))
        assertNull(planAssessmentIssue(RiskRating.MODERATE, confirmedHighIncrease = false))
        assertEquals("Within default", planAssessmentLabel(RiskRating.CONSERVATIVE))
        assertEquals("Above default", planAssessmentLabel(RiskRating.MODERATE))
        assertEquals("Needs confirmation", planAssessmentLabel(RiskRating.AGGRESSIVE))
        assertEquals("Not supported", planAssessmentLabel(RiskRating.UNSAFE))
    }

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
            "Enter a positive longest run in that week up to 80 km.",
            startingPointValidation("established", "12", "3", "0", "20", false),
        )
        assertEquals(
            "Make the weekly distance fit the longest run and number of runs in that week.",
            startingPointValidation("established", "5.5", "3", "5", "20", false),
        )
        assertEquals(
            "Make the weekly distance fit the longest run and number of runs in that week.",
            startingPointValidation("established", "15.1", "3", "5", "20", false),
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
        assertEquals(
            "Choose another available day so the plan can leave the day after the long run free.",
            scheduleValidation(
                "established",
                listOf(0, 1, 6),
                "3",
                "6",
                "America/Halifax",
                false,
                "half",
            ),
        )
        assertNull(
            scheduleValidation("routine", listOf(6), "", "", "America/Halifax", false),
        )
        assertEquals(
            "Choose at least 1 available day.",
            scheduleValidation("routine", emptyList(), "", "", "America/Halifax", false),
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

    @Test
    fun `setup refreshes a candidate after the training day changes`() {
        val beforeMidnight = Instant.parse("2026-08-03T02:59:59Z")
        val afterMidnight = Instant.parse("2026-08-03T03:00:01Z")

        assertEquals(
            true,
            setupDateChanged(beforeMidnight, afterMidnight, "America/Halifax"),
        )
        assertEquals(
            false,
            setupDateChanged(beforeMidnight, afterMidnight, "UTC"),
        )
    }
}
