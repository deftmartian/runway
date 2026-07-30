package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.CalibrationIntake
import dev.deftmartian.runway.domain.EstablishedTrainingIntake
import dev.deftmartian.runway.domain.Experience
import dev.deftmartian.runway.domain.FoundationIntake
import dev.deftmartian.runway.domain.GeneratedCalibrationPlan
import dev.deftmartian.runway.domain.GeneratedFoundationPlan
import dev.deftmartian.runway.domain.GoalKind
import dev.deftmartian.runway.domain.GoalPriority
import dev.deftmartian.runway.domain.InjuryFlags
import dev.deftmartian.runway.domain.RaceDistance
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.domain.TrainingPlanner
import dev.deftmartian.runway.domain.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GeneratedPlanPersistenceMapperTest {
    @Test fun `established distance plan maps stable goal plan weeks and rest race prescriptions`() {
        val generated = TrainingPlanner.generatePlan(
            EstablishedTrainingIntake(
                priority = GoalPriority.FINISH_HEALTHY,
                experience = Experience.RETURNING,
                availability = listOf(1, 3, 6),
                injuryFlags = InjuryFlags(),
                raceDistance = RaceDistance.FIVE_K,
                targetDate = "2026-08-30",
                currentWeeklyDistanceMeters = 8_000,
                currentRunsPerWeek = 3,
                longestRecentRunMeters = 4_000,
                preferredLongRunDay = 6,
                startDate = "2026-06-01",
            ),
        )
        val metadata = raceMetadata()
        val graph = GeneratedPlanPersistenceMapper.map(generated, metadata)
        val repeated = GeneratedPlanPersistenceMapper.map(generated, metadata)

        assertEquals("goal-5k", graph.goal.goalId)
        assertEquals("race", graph.goal.kind)
        assertEquals("established", graph.goal.startMode)
        assertEquals(LocalDate.parse("2026-08-30").toEpochDay(), graph.goal.targetDateEpochDay)
        assertEquals("distance", graph.plan.phaseType)
        assertEquals(graph.weeks.size, generated.weeks.size)
        assertEquals(graph.workouts.size, generated.weeks.sumOf { it.workouts.size })
        assertEquals(graph.workouts.map { it.workoutId }, repeated.workouts.map { it.workoutId })
        assertTrue(graph.workouts.any { it.generatedWorkoutType == "race" && it.generatedPrescriptionKind == "distance" })
        assertTrue(graph.workouts.any { it.generatedWorkoutType == "rest" && it.generatedPrescriptionKind == "rest" })
        graph.workouts.forEach { workout ->
            assertEquals(workout.generatedPrescriptionKind, workout.currentPrescriptionKind)
            assertEquals(workout.generatedScheduledEpochDay, workout.currentScheduledEpochDay)
            assertEquals(workout.generatedWorkoutType, workout.currentWorkoutType)
            assertEquals(workout.generatedDistanceMeters, workout.currentDistanceMeters)
            assertEquals(workout.generatedDurationSeconds, workout.currentDurationSeconds)
        }
        assertEquals(generated.sourceRefs, graph.planSourceReferences.sortedBy { it.ordinal }.map { it.sourceLocator })
        assertEquals(graph.workouts.sumOf { workout ->
            generated.weeks.flatMap { it.workouts }
                .first { candidate -> candidate.scheduledDate.toEpochDay() == workout.generatedScheduledEpochDay && candidate.type.name.lowercase() == workout.generatedWorkoutType }
                .sourceRefs.size * 2
        }, graph.workoutSourceReferences.size)
        assertTrue(graph.blocks.isEmpty())
        assertTrue(graph.segments.isEmpty())
    }

    @Test fun `foundation timed plan preserves every run walk block and source for both prescription versions`() {
        val generated = TrainingPlanner.generatePlan(
            FoundationIntake(
                startMode = StartMode.FOUNDATION_ONLY,
                goalKind = GoalKind.FOUNDATION,
                raceDistance = null,
                availability = listOf(1, 3, 6),
                injuryFlags = InjuryFlags(),
                startDate = "2026-06-01",
            ),
        ) as GeneratedFoundationPlan
        val graph = GeneratedPlanPersistenceMapper.map(
            generated,
            GeneratedPlanGoalMetadata(
                goalId = "goal-foundation",
                planId = "plan-foundation",
                title = "Foundation",
                goalKind = GoalKind.FOUNDATION,
                startMode = StartMode.FOUNDATION_ONLY,
                createdAtEpochMillis = 10,
            ),
        )
        val timedWorkouts = graph.workouts.filter { it.generatedPrescriptionKind == "timed" }
        val expectedBlocks = generated.weeks.flatMap { it.workouts }
            .sumOf { workout -> (workout.prescription as? dev.deftmartian.runway.domain.WorkoutPrescription.Timed)?.blocks?.size ?: 0 }
        val expectedSegments = generated.weeks.flatMap { it.workouts }
            .sumOf { workout ->
                (workout.prescription as? dev.deftmartian.runway.domain.WorkoutPrescription.Timed)
                    ?.blocks?.sumOf { it.segments.size } ?: 0
            }

        assertEquals("foundation", graph.plan.phaseType)
        assertNull(graph.goal.targetDateEpochDay)
        assertEquals(9, graph.weeks.size)
        assertEquals(expectedBlocks * 2, graph.blocks.size)
        assertEquals(expectedSegments * 2, graph.segments.size)
        assertTrue(timedWorkouts.all { it.generatedDurationSeconds != null && it.generatedWarmupSeconds == 300 && it.currentWarmupSeconds == 300 })
        assertTrue(graph.segments.all { it.segmentType in setOf("run", "walk") })
        assertTrue(graph.workoutSourceReferences.any { it.sourceLocator == "nhs-couch-to-5k" })
        assertTrue(graph.workoutSourceReferences.groupBy { it.workoutId to it.ordinal }.values.all { refs -> refs.map { it.prescriptionVersion }.toSet() == setOf("generated", "current") })
    }

    @Test fun `calibration timed plan preserves duration blocks and a distinct stable graph identity`() {
        val generated = TrainingPlanner.generatePlan(
            CalibrationIntake(
                goalKind = GoalKind.RACE,
                raceDistance = RaceDistance.TEN_K,
                availability = listOf(1, 4),
                injuryFlags = InjuryFlags(),
                calibrationDurationSeconds = 1_200,
                startDate = "2026-06-01",
            ),
        ) as GeneratedCalibrationPlan
        val metadata = GeneratedPlanGoalMetadata(
            goalId = "goal-calibration",
            planId = "plan-calibration",
            title = "10K calibration",
            goalKind = GoalKind.RACE,
            startMode = StartMode.CALIBRATION,
            goalTargetDate = "2026-09-30",
            targetDistanceMeters = 10_000,
            createdAtEpochMillis = 20,
        )
        val graph = GeneratedPlanPersistenceMapper.map(generated, metadata)

        assertEquals("calibration", graph.plan.phaseType)
        assertEquals(2, graph.weeks.size)
        assertTrue(graph.workouts.filter { it.generatedPrescriptionKind == "timed" }.all {
            it.generatedDurationSeconds == 1_200 &&
                it.currentDurationSeconds == 1_200 &&
                it.generatedWarmupSeconds == 120 &&
                it.generatedCooldownSeconds == 120
        })
        assertTrue(graph.blocks.any { it.prescriptionVersion == "generated" })
        assertTrue(graph.blocks.any { it.prescriptionVersion == "current" })
        assertEquals(graph.blocks.size, graph.segments.map { it.blockId }.distinct().size)
        assertFalse(graph.workouts.any { it.generatedWorkoutType == WorkoutType.RACE.name.lowercase() })
        assertNotEquals(
            GeneratedPlanPersistenceMapper.map(generated, metadata.copy(planId = "another-plan")).workouts.first().workoutId,
            graph.workouts.first().workoutId,
        )
    }

    private fun raceMetadata() = GeneratedPlanGoalMetadata(
        goalId = "goal-5k",
        planId = "plan-5k",
        title = "5K",
        goalKind = GoalKind.RACE,
        startMode = StartMode.ESTABLISHED,
        goalTargetDate = "2026-08-30",
        targetDistanceMeters = 5_000,
        priority = GoalPriority.FINISH_HEALTHY,
        createdAtEpochMillis = 1,
    )

    private fun String.toEpochDay() = LocalDate.parse(this).toEpochDay()
}
