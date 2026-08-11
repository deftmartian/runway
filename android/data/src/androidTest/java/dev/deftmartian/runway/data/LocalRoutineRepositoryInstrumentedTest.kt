package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalRoutineRepositoryInstrumentedTest {
    private lateinit var database: RunwayLedgerDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RunwayLedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun extendingTheRoutineTwiceCreatesOneStableSetOfPrescribedRuns() = runBlocking {
        val graph = RoutinePlanPersistenceMapper.map(
            goalId = "routine-goal",
            planId = "routine-plan",
            title = "Weekly running routine",
            priority = "consistency",
            startEpochDay = LocalDate.parse("2026-08-03").toEpochDay(),
            selectedDays = listOf(1, 3, 6),
            weeklyTargetDistanceMeters = 15_000,
            createdAtEpochMillis = 10,
        )
        database.goalPlanDao().saveGoal(graph.goal)
        database.goalPlanDao().createRoutineGraph(graph)
        val initialLastMonday = graph.weeks.last().startEpochDay
        val through = initialLastMonday + 21
        val repository = LocalRoutineRepository(database)

        assertEquals(
            LocalRoutineHorizonResult.Extended(3),
            repository.ensureHorizon(graph.plan.planId, through, 20),
        )
        val afterFirstWeeks = database.goalPlanDao().weeksForPlan(graph.plan.planId, 100)
        val afterFirstWorkouts = database.goalPlanDao().visibleWorkoutsForPlan(
            graph.plan.planId,
            limit = 1_000,
        )
        assertEquals(11, afterFirstWeeks.size)
        assertTrue(afterFirstWeeks.all { it.generatedLoadMeters == 15_000 })
        assertTrue(afterFirstWorkouts.all {
            it.currentPrescriptionKind == "distance" &&
                it.currentDistanceMeters == 5_000 &&
                it.currentDurationSeconds == null
        })

        assertEquals(
            LocalRoutineHorizonResult.Unchanged,
            repository.ensureHorizon(graph.plan.planId, through, 30),
        )
        assertEquals(
            afterFirstWeeks,
            database.goalPlanDao().weeksForPlan(graph.plan.planId, 100),
        )
        assertEquals(
            afterFirstWorkouts,
            database.goalPlanDao().visibleWorkoutsForPlan(graph.plan.planId, limit = 1_000),
        )
        assertNull(database.goalPlanDao().plan(graph.plan.planId)?.endEpochDay)
    }

    @Test
    fun changingOneFutureRoutineWeekToTimeRecalculatesItsRunsAndIsAudited() = runBlocking {
        val graph = RoutinePlanPersistenceMapper.map(
            goalId = "routine-goal-change",
            planId = "routine-plan-change",
            title = "Weekly running routine",
            priority = "consistency",
            startEpochDay = LocalDate.parse("2026-08-03").toEpochDay(),
            selectedDays = listOf(1, 3, 6),
            weeklyTargetDistanceMeters = 15_000,
            createdAtEpochMillis = 10,
        )
        database.goalPlanDao().saveGoal(graph.goal)
        database.goalPlanDao().createRoutineGraph(graph)
        val targetWeek = graph.weeks[1]
        val request = LocalRoutineWeekGoalRequest(
            planId = graph.plan.planId,
            weekId = targetWeek.weekId,
            targetDistanceMeters = null,
            targetDurationSeconds = 7_200,
            todayEpochDay = graph.plan.startEpochDay,
            operationId = "change-week-two",
            occurredAtEpochMillis = 20,
        )

        assertEquals(
            LocalRoutineWeekGoalResult.Updated(
                RoutineWeeklyTarget(15_000, null),
                RoutineWeeklyTarget(null, 7_200),
            ),
            LocalRoutineRepository(database).updateWeekGoal(request),
        )
        assertNull(database.goalPlanDao().week(targetWeek.weekId)?.generatedLoadMeters)
        assertEquals(7_200, database.goalPlanDao().week(targetWeek.weekId)?.generatedLoadDurationSeconds)
        assertEquals(15_000, database.goalPlanDao().week(graph.weeks[2].weekId)?.generatedLoadMeters)
        assertTrue(
            database.goalPlanDao().workoutsForWeek(targetWeek.weekId, limit = 10).all {
                it.currentPrescriptionKind == "timed" &&
                    it.currentDistanceMeters == null &&
                    it.currentDurationSeconds == 2_400
            },
        )
        val adjustmentId = "routine-week-goal-change-week-two"
        assertEquals("routine_week_goal", database.adjustmentDao().adjustment(adjustmentId)?.adjustmentType)
        assertEquals(
            7_200,
            database.adjustmentDao().effectGroups(adjustmentId, 10)
                .single().destinationWeekLoadDurationSeconds,
        )
        assertEquals(
            LocalRoutineWeekGoalResult.AlreadyApplied,
            LocalRoutineRepository(database).updateWeekGoal(request),
        )
        assertEquals(
            LocalRoutineWeekGoalResult.Rejected,
            LocalRoutineRepository(database).updateWeekGoal(
                request.copy(
                    targetDurationSeconds = 1_200,
                    operationId = "too-short-for-three-runs",
                ),
            ),
        )
        assertEquals(7_200, database.goalPlanDao().week(targetWeek.weekId)?.generatedLoadDurationSeconds)
    }
}
