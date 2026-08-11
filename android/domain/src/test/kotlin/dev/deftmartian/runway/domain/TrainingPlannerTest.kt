package dev.deftmartian.runway.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrainingPlannerTest {
    private val established = EstablishedTrainingIntake(
        priority = GoalPriority.FINISH_HEALTHY,
        availability = listOf(1, 3, 6), raceDistance = RaceDistance.HALF,
        targetDate = "2026-09-01", currentWeeklyDistanceMeters = 12000, currentRunsPerWeek = 3,
        longestRecentRunMeters = 8000, preferredLongRunDay = 6, startDate = "2026-05-11"
    )

    @Test fun `race distances expose canonical metres and read released stored values`() {
        assertEquals(5_000, RaceDistance.FIVE_K.meters)
        assertEquals(10_000, RaceDistance.TEN_K.meters)
        assertEquals(21_100, RaceDistance.HALF.meters)
        assertEquals(42_200, RaceDistance.MARATHON.meters)
        assertEquals(RaceDistance.HALF, RaceDistance.fromStoredMeters(21_097))
        assertEquals(RaceDistance.MARATHON, RaceDistance.fromStoredMeters(42_195))
        assertEquals(null, RaceDistance.fromStoredMeters(21_098))
        assertEquals(42_200, TrainingPlanner.getRaceMeters(RaceDistance.MARATHON))
    }

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
        assertEquals(7.5, plan.summary.defaultWeeklyIncreasePercent, 0.0)
    }

    @Test fun `race week counts the goal event toward the preserved run frequency`() {
        val plan = TrainingPlanner.generateEstablished(
            established.copy(
                availability = listOf(1, 3, 5),
                preferredLongRunDay = 5,
                startDate = "2026-05-11",
                targetDate = "2026-05-17",
            ),
        )
        val raceWeek = plan.weeks.single()
        val training = raceWeek.workouts.filter { it.type == WorkoutType.EASY || it.type == WorkoutType.LONG }
        val race = raceWeek.workouts.single { it.type == WorkoutType.RACE }

        assertEquals(listOf("2026-05-11", "2026-05-13"), training.map { it.scheduledDate })
        assertEquals(2, training.size)
        assertEquals(4_000, raceWeek.trainingTargetDistanceMeters)
        assertEquals(listOf(2_000, 2_000), training.map { it.targetDistanceMeters })
        assertEquals("2026-05-17", race.scheduledDate)
        assertTrue(raceWeek.workouts.none { it.scheduledDate == "2026-05-16" && it.type != WorkoutType.REST })
        assertEquals(raceWeek.trainingTargetDistanceMeters, training.sumOf { it.targetDistanceMeters })
        assertEquals(raceWeek.eventDistanceMeters, race.targetDistanceMeters)
        assertEquals(raceWeek.targetDistanceMeters, training.sumOf { it.targetDistanceMeters } + race.targetDistanceMeters)
    }

    @Test fun `ordinary full weeks retain the established run frequency`() {
        val plan = TrainingPlanner.generateEstablished(
            established.copy(
                availability = listOf(1, 3, 5),
                preferredLongRunDay = 5,
                startDate = "2026-05-11",
                targetDate = "2026-05-24",
            ),
        )

        assertEquals(3, plan.weeks.first().workouts.count { it.type != WorkoutType.REST })
        assertEquals(3, plan.weeks.first().workouts.count { it.type == WorkoutType.EASY || it.type == WorkoutType.LONG })
        assertTrue(
            plan.weeks.flatMap { it.workouts }
                .filter { it.type != WorkoutType.REST && it.type != WorkoutType.RACE }
                .all { it.targetDistanceMeters % 500 == 0 },
        )
    }

    @Test fun `non round baseline still produces half kilometre training prescriptions`() {
        val plan = TrainingPlanner.generateEstablished(
            established.copy(
                currentWeeklyDistanceMeters = 12_300,
                longestRecentRunMeters = 7_200,
            ),
        )

        assertEquals(12_500, plan.weeks.first().trainingTargetDistanceMeters)
        assertTrue(
            plan.weeks.flatMap { it.workouts }
                .filter { it.type != WorkoutType.REST && it.type != WorkoutType.RACE }
                .all { it.targetDistanceMeters % 500 == 0 },
        )
    }

    @Test fun `ten kilometre baseline builds toward a half without rewriting the starting week`() {
        val intake = EstablishedTrainingIntake(
            priority = GoalPriority.FINISH_HEALTHY,
            availability = listOf(1, 3, 6),
            raceDistance = RaceDistance.HALF,
            targetDate = "2026-11-22",
            currentWeeklyDistanceMeters = 25_000,
            currentRunsPerWeek = 3,
            longestRecentRunMeters = 10_000,
            preferredLongRunDay = 6,
            startDate = "2026-08-03",
        )

        val plan = TrainingPlanner.generateEstablished(intake)

        assertEquals(16, plan.weeks.size)
        assertEquals(25_000, plan.weeks.first().trainingTargetDistanceMeters)
        assertEquals(10_000, plan.weeks.first().longRunMeters)
        assertEquals(3, plan.weeks.first().workouts.count { it.type != WorkoutType.REST })
        assertEquals(21_100, plan.weeks.last().eventDistanceMeters)
        assertEquals(3, plan.weeks.last().workouts.count { it.type != WorkoutType.REST })
        assertTrue(
            plan.weeks.last().workouts
                .filter { it.type == WorkoutType.EASY }
                .maxOf { it.targetDistanceMeters } <=
                plan.weeks.dropLast(1).last().workouts
                    .filter { it.type == WorkoutType.EASY || it.type == WorkoutType.LONG }
                    .maxOf { it.targetDistanceMeters },
        )
        assertTrue(plan.summary.peakMeters <= 34_000)
        assertTrue(
            "long-run peak was ${plan.summary.longRunPeakMeters}",
            plan.summary.longRunPeakMeters in 10_000..16_500,
        )
        assertTrue(plan.summary.requiredWeeklyIncreasePercent > plan.summary.defaultWeeklyIncreasePercent)
        assertEquals(RiskRating.MODERATE, plan.risk)
        assertTrue(plan.summary.warnings.none { it.startsWith("The longest planned run is low") })

        val later = TrainingPlanner.generateEstablished(
            intake.copy(targetDate = "2026-12-06"),
        )
        assertTrue(later.summary.longRunPeakMeters >= 16_000)
        assertTrue(later.risk.ordinal < RiskRating.AGGRESSIVE.ordinal)
    }

    @Test fun `calibration uses open runs rather than fabricated distance or duration`() {
        val calibration = TrainingPlanner.generateCalibration(CalibrationIntake(GoalKind.RACE, RaceDistance.FIVE_K, listOf(2, 6), "2026-05-11"))
        assertEquals("2026-05-24", calibration.targetDate)
        assertEquals(4, calibration.weeks.sumOf { it.workouts.count { workout -> workout.type == WorkoutType.EASY } })
        assertTrue(calibration.weeks.flatMap { it.workouts }.filter { it.type == WorkoutType.EASY }.all {
            it.prescription == WorkoutPrescription.Open &&
                it.targetDurationSeconds == null &&
                it.targetDistanceMeters == 0
        })
    }

    @Test(expected = IllegalArgumentException::class) fun `phase sessions require rest-day spacing`() {
        TrainingPlanner.generateCalibration(CalibrationIntake(GoalKind.RACE, RaceDistance.FIVE_K, listOf(1, 2), "2026-05-11"))
    }

    @Test fun `fixed today starts on next full week`() {
        val plan = TrainingPlanner.generateEstablished(established.copy(startDate = null), LocalDate.parse("2026-05-15"))
        assertEquals("2026-05-18", plan.startDate)
    }
}
