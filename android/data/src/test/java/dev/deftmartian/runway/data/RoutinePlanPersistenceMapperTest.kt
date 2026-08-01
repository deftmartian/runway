package dev.deftmartian.runway.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RoutinePlanPersistenceMapperTest {
    @Test
    fun `routine starts today and never writes an earlier selected day as missed work`() {
        // Wednesday, with Monday, Wednesday, and Sunday selected.
        val graph = RoutinePlanPersistenceMapper.map(
            goalId = "routine-goal",
            planId = "routine-plan",
            title = "Three runs each week",
            priority = "consistency",
            startEpochDay = LocalDate.parse("2026-08-05").toEpochDay(),
            selectedDays = listOf(1, 3, 0),
            createdAtEpochMillis = 10,
        )

        assertEquals("routine", graph.goal.kind)
        assertEquals("routine", graph.plan.phaseType)
        assertEquals(LocalDate.parse("2026-08-05").toEpochDay(), graph.plan.startEpochDay)
        assertNull(graph.goal.targetDateEpochDay)
        assertNull(graph.plan.endEpochDay)
        assertEquals(3, graph.plan.summarySessionsPerWeek)
        assertEquals(8, graph.weeks.size)
        assertEquals(LocalDate.parse("2026-08-03").toEpochDay(), graph.weeks.first().startEpochDay)
        assertEquals(listOf(0, 1, 3), graph.days.map(RoutineScheduleDayEntity::dayOfWeek).sorted())
        assertFalse(graph.workouts.any { it.currentScheduledEpochDay < graph.plan.startEpochDay })
        assertEquals(
            listOf("2026-08-05", "2026-08-09"),
            graph.workouts.take(2).map { LocalDate.ofEpochDay(it.currentScheduledEpochDay).toString() },
        )
        assertTrue(graph.workouts.all {
            it.generatedPrescriptionKind == "open" && it.currentPrescriptionKind == "open" &&
                it.generatedDistanceMeters == null && it.currentDistanceMeters == null &&
                it.generatedDurationSeconds == null && it.currentDurationSeconds == null
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
    fun `setup preparation rejects a routine graph with an invented target`() {
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

    private fun graph() = RoutinePlanPersistenceMapper.map(
        goalId = "routine-goal",
        planId = "routine-plan",
        title = "Three runs each week",
        priority = "consistency",
        startEpochDay = LocalDate.parse("2026-08-05").toEpochDay(),
        selectedDays = listOf(1, 3, 0),
        createdAtEpochMillis = 10,
    )
}
