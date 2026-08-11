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
    fun `starting point validates an entered week and leaves learning open`() {
        assertEquals(
            "Enter a whole number from 2 to 5 current runs.",
            startingPointValidation("established", "12", "2.5", "8"),
        )
        assertEquals(
            "Enter a positive longest run in that week up to 80 km.",
            startingPointValidation("established", "12", "3", "0"),
        )
        assertEquals(
            "Make the weekly distance fit the longest run and number of runs in that week.",
            startingPointValidation("established", "5.5", "3", "5"),
        )
        assertEquals(
            "Make the weekly distance fit the longest run and number of runs in that week.",
            startingPointValidation("established", "15.1", "3", "5"),
        )
        assertNull(startingPointValidation("learn", "", "", ""))
    }

    @Test
    fun `goal and schedule validation enforce planner bounds`() {
        assertEquals(
            "Choose a date from 2026-09-01 to 2027-07-27.",
            goalValidation(true, "2026-08-31", "2026-09-01", "2027-07-27"),
        )
        assertEquals(
            "Choose at least as many available days as current weekly runs.",
            scheduleValidation("established", listOf(1, 3), "3", "1", "America/Halifax"),
        )
        assertEquals(
            "Choose an available long-run day.",
            scheduleValidation("established", listOf(1, 3, 6), "3", "2", "America/Halifax"),
        )
        assertEquals(
            "Choose another available day so the plan can leave the day after the long run free.",
            scheduleValidation(
                "established",
                listOf(0, 1, 6),
                "3",
                "6",
                "America/Halifax",
                "half",
            ),
        )
        assertNull(
            scheduleValidation("routine", listOf(6), "", "", "America/Halifax"),
        )
        assertEquals(
            "Choose at least 1 available day.",
            scheduleValidation("routine", emptyList(), "", "", "America/Halifax"),
        )
    }

    @Test
    fun `routine weekly goal validates distance and time`() {
        assertEquals(
            "Enter a weekly distance goal to continue.",
            routineWeeklyGoalValidation("distance", "", ""),
        )
        assertEquals(
            "Enter a weekly distance goal from 0.5 to 100 km.",
            routineWeeklyGoalValidation("distance", "0", ""),
        )
        assertNull(routineWeeklyGoalValidation("distance", "12.5", "", scheduledRuns = 3))
        assertEquals(
            "Use 0.5 km steps for the weekly goal.",
            routineWeeklyGoalValidation("distance", "12.3", "", scheduledRuns = 3),
        )
        assertEquals(
            "Enter a weekly running time from 30 to 1,080 minutes.",
            routineWeeklyGoalValidation("time", "", "5", scheduledRuns = 3),
        )
        assertNull(routineWeeklyGoalValidation("time", "", "120", scheduledRuns = 3))
        assertEquals(
            "Enter a weekly running time from 30 to 1,080 minutes.",
            routineWeeklyGoalValidation("time", "", "20", scheduledRuns = 3),
        )
    }

    @Test
    fun `routine starting point validates the chosen ramp unit`() {
        assertEquals(
            "Use a positive 0.5 km step for the weekly change.",
            routineStartingPointValidation(
                "entered", "distance", "12", "", "3", 3, "15", "", "0.3", "",
            ),
        )
        assertNull(
            routineStartingPointValidation(
                "entered", "distance", "12.2", "", "3", 3, "15", "", "0.5", "",
            ),
        )
        assertEquals(
            "Use a positive 5-minute step for the weekly change.",
            routineStartingPointValidation(
                "entered", "time", "", "90", "3", 3, "", "120", "", "12",
            ),
        )
        assertNull(
            routineStartingPointValidation(
                "learn", "distance", "", "", "", 3, "15", "", "", "",
            ),
        )
    }

    @Test
    fun `long distance goals require concentrated schedule acceptance`() {
        assertEquals(
            true,
            requiresConcentratedScheduleAcceptance("established", "2", "marathon"),
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
