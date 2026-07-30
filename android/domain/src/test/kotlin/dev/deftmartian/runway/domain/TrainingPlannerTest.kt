package dev.deftmartian.runway.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrainingPlannerTest {
    private val flags = InjuryFlags(recentInjury = true)
    private val established = EstablishedTrainingIntake(
        priority = GoalPriority.FINISH_HEALTHY, experience = Experience.RETURNING,
        availability = listOf(1, 3, 6), injuryFlags = flags, raceDistance = RaceDistance.HALF,
        targetDate = "2026-09-01", currentWeeklyDistanceMeters = 12000, currentRunsPerWeek = 3,
        longestRecentRunMeters = 8000, preferredLongRunDay = 6, startDate = "2026-05-11"
    )

    @Test fun `established plan preserves race endpoint, recovery and source references`() {
        val plan = TrainingPlanner.generateEstablished(established)
        assertEquals("2026-05-11", plan.startDate)
        assertEquals(17, plan.weeks.size)
        assertEquals(21100, plan.weeks.last().eventDistanceMeters)
        assertEquals("2026-09-01", plan.weeks.last().workouts.last().scheduledDate)
        assertTrue(plan.sourceRefs.contains(TrainingSourceRefs.REI_HALF))
        assertTrue(plan.weeks.filter { it.longRunMeters > 0 }.all { week ->
            val long = week.workouts.first { it.type == WorkoutType.LONG }
            week.workouts.firstOrNull { it.scheduledDate == DateUtils.addDays(long.scheduledDate, 1) }?.type == WorkoutType.REST
        })
        assertEquals(5.5, plan.summary.defaultWeeklyIncreasePercent, 0.0)
    }

    @Test fun `foundation and calibration remain timed rather than fabricated distance`() {
        val foundation = TrainingPlanner.generateFoundation(FoundationIntake(StartMode.FOUNDATION_ONLY, GoalKind.FOUNDATION, null, listOf(1, 3, 6), InjuryFlags(), "2026-05-11"))
        assertEquals("2026-07-12", foundation.targetDate)
        assertEquals(9, foundation.weeks.size)
        assertEquals(27, foundation.weeks.sumOf { it.workouts.count { workout -> workout.type == WorkoutType.EASY } })
        assertTrue(foundation.weeks.flatMap { it.workouts }.all { it.targetDistanceMeters == 0 })
        val calibration = TrainingPlanner.generateCalibration(CalibrationIntake(GoalKind.RACE, RaceDistance.FIVE_K, listOf(2, 6), InjuryFlags(), 1200, "2026-05-11"))
        assertEquals("2026-05-24", calibration.targetDate)
        assertEquals(4, calibration.weeks.sumOf { it.workouts.count { workout -> workout.type == WorkoutType.EASY } })
        assertTrue(calibration.weeks.flatMap { it.workouts }.filter { it.type == WorkoutType.EASY }.all { it.targetDurationSeconds == 1200 && it.targetDistanceMeters == 0 })
    }

    @Test(expected = IllegalArgumentException::class) fun `phase sessions require rest-day spacing`() {
        TrainingPlanner.generateFoundation(FoundationIntake(StartMode.FOUNDATION_ONLY, GoalKind.FOUNDATION, null, listOf(1, 2, 3), InjuryFlags(), "2026-05-11"))
    }

    @Test fun `fixed today starts on next full week`() {
        val plan = TrainingPlanner.generateEstablished(established.copy(startDate = null), LocalDate.parse("2026-05-15"))
        assertEquals("2026-05-18", plan.startDate)
    }
}
