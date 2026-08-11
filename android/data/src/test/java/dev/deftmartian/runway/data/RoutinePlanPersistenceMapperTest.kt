package dev.deftmartian.runway.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RoutinePlanPersistenceMapperTest {
    @Test
    fun `routine divides its weekly distance exactly across the selected days`() {
        val graph = RoutinePlanPersistenceMapper.map(
            goalId = "routine-goal",
            planId = "routine-plan",
            title = "Three runs each week",
            priority = "consistency",
            startEpochDay = LocalDate.parse("2026-08-03").toEpochDay(),
            selectedDays = listOf(1, 3, 0),
            weeklyTargetDistanceMeters = 15_000,
            createdAtEpochMillis = 10,
        )

        assertEquals("routine", graph.goal.kind)
        assertEquals("routine", graph.plan.phaseType)
        assertEquals(LocalDate.parse("2026-08-03").toEpochDay(), graph.plan.startEpochDay)
        assertNull(graph.goal.targetDateEpochDay)
        assertNull(graph.plan.endEpochDay)
        assertEquals(3, graph.plan.summarySessionsPerWeek)
        assertEquals(15_000, graph.plan.summaryBaselineMeters)
        assertTrue(graph.weeks.all { it.generatedLoadMeters == 15_000 })
        assertEquals(8, graph.weeks.size)
        assertEquals(LocalDate.parse("2026-08-03").toEpochDay(), graph.weeks.first().startEpochDay)
        assertEquals(listOf(0, 1, 3), graph.days.map(RoutineScheduleDayEntity::dayOfWeek).sorted())
        assertEquals(
            listOf("2026-08-03", "2026-08-05", "2026-08-09"),
            graph.workouts.take(3).map { LocalDate.ofEpochDay(it.currentScheduledEpochDay).toString() },
        )
        assertTrue(graph.workouts.all {
            it.generatedPrescriptionKind == "distance" && it.currentPrescriptionKind == "distance" &&
                it.generatedDistanceMeters == 5_000 && it.currentDistanceMeters == 5_000 &&
                it.generatedDurationSeconds == null && it.currentDurationSeconds == null
        })
        assertTrue(graph.workouts.groupBy(WorkoutEntity::weekId).values.all { week ->
            week.sumOf { it.currentDistanceMeters ?: 0 } == 15_000
        })
    }

    @Test
    fun `same setup identity maps the same eight week rolling horizon`() {
        val first = graph()
        val second = graph()

        assertEquals(first.weeks.map(PlanWeekEntity::weekId), second.weeks.map(PlanWeekEntity::weekId))
        assertEquals(first.workouts.map(WorkoutEntity::workoutId), second.workouts.map(WorkoutEntity::workoutId))
        assertEquals(first.workouts.map(WorkoutEntity::currentScheduledEpochDay), second.workouts.map(WorkoutEntity::currentScheduledEpochDay))
    }

    @Test
    fun `setup preparation rejects a routine graph whose current prescription differs`() {
        val graph = graph()
        val invalid = graph.copy(workouts = graph.workouts.mapIndexed { index, workout ->
            if (index == 0) workout.copy(currentDistanceMeters = 1_000) else workout
        })

        val prepared = LocalPlanSetupPreparation.prepare(
            LocalPlanSetupRequest(
                operationId = "routine-operation",
                operationFingerprint = "a".repeat(64),
                profile = ProfileSettingsEntity(
                    timeZone = "America/Halifax", routeDataMode = "discard", heartRateSettingsSource = "none",
                    maxHeartRateBpm = null, zone2FloorBpm = null, zone3FloorBpm = null,
                    zone4FloorBpm = null, zone5FloorBpm = null, recentInjury = false, currentPain = false,
                    recurringPain = false, medicalRestriction = false, privateNotes = null, updatedAtEpochMillis = 10,
                ),
                availabilityDays = listOf(1, 3, 0),
                candidate = LocalPlanCandidate.Routine(invalid),
                confirmReplaceCurrent = false,
                archiveAtEpochMillis = 10,
            ),
        )

        assertEquals(
            LocalPlanSetupPreparation.Invalid(LocalPlanSetupError.INVALID_ROUTINE_GRAPH),
            prepared,
        )
    }

    @Test
    fun `timed routine divides the weekly duration exactly across selected days`() {
        val graph = RoutinePlanPersistenceMapper.map(
            goalId = "timed-routine-goal",
            planId = "timed-routine-plan",
            title = "Three timed runs each week",
            priority = "consistency",
            startEpochDay = LocalDate.parse("2026-08-03").toEpochDay(),
            selectedDays = listOf(1, 3, 0),
            weeklyTargetDurationSeconds = 7_200,
            createdAtEpochMillis = 10,
        )

        assertNull(graph.plan.summaryBaselineMeters)
        assertEquals(7_200, graph.plan.summarySessionDurationSeconds)
        assertTrue(graph.weeks.all { it.generatedLoadDurationSeconds == 7_200 })
        assertTrue(graph.workouts.all {
            it.currentPrescriptionKind == "timed" &&
                it.currentDistanceMeters == null &&
                it.currentDurationSeconds == 2_400
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `routine rejects a weekly target that cannot make valid individual runs`() {
        RoutinePlanPersistenceMapper.map(
            goalId = "short-routine-goal",
            planId = "short-routine-plan",
            title = "Three timed runs each week",
            priority = "consistency",
            startEpochDay = LocalDate.parse("2026-08-03").toEpochDay(),
            selectedDays = listOf(1, 3, 0),
            weeklyTargetDurationSeconds = 1_200,
            createdAtEpochMillis = 10,
        )
    }

    private fun graph() = RoutinePlanPersistenceMapper.map(
        goalId = "routine-goal",
        planId = "routine-plan",
        title = "Three runs each week",
        priority = "consistency",
        startEpochDay = LocalDate.parse("2026-08-03").toEpochDay(),
        selectedDays = listOf(1, 3, 0),
        weeklyTargetDistanceMeters = 15_000,
        createdAtEpochMillis = 10,
    )
}
