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
    fun extendingTheRoutineTwiceCreatesOneStableSetOfOpenRuns() = runBlocking {
        val graph = RoutinePlanPersistenceMapper.map(
            goalId = "routine-goal",
            planId = "routine-plan",
            title = "Weekly running routine",
            priority = "consistency",
            startEpochDay = LocalDate.parse("2026-08-05").toEpochDay(),
            selectedDays = listOf(1, 3, 6),
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
        assertTrue(afterFirstWorkouts.all {
            it.currentPrescriptionKind == "open" &&
                it.currentDistanceMeters == null &&
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
}
