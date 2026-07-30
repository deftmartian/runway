package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdjustmentDaoInstrumentedTest {
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
    fun undoableWorkoutAdjustmentsIncludeConsequenceChangesAndOrderTheNewestCurrentChangeFirst() = runBlocking {
        val planDao = database.goalPlanDao()
        val goal = GoalEntity("goal", "Goal", null, "active", 1, 1, "race", "established", 5_000, "finish_healthy")
        val plan = PlanEntity("plan", goal.goalId, "distance", "active", 10, 30, 1, 1)
        val week = PlanWeekEntity("week", plan.planId, 1, 10, 10_000)
        val workout = WorkoutEntity(
            workoutId = "workout",
            planId = plan.planId,
            weekId = week.weekId,
            position = 0,
            generatedPurpose = "Easy run",
            generatedDistanceMeters = 5_000,
            generatedDurationSeconds = null,
            currentPurpose = "Easy run",
            currentDistanceMeters = 4_000,
            currentDurationSeconds = null,
            tombstonedAtEpochMillis = null,
            updatedAtEpochMillis = 2,
            generatedScheduledEpochDay = 20,
            currentScheduledEpochDay = 21,
            generatedWorkoutType = "easy",
            currentWorkoutType = "easy",
            generatedPrescriptionKind = "distance",
            currentPrescriptionKind = "distance",
        )
        planDao.saveGoal(goal)
        planDao.createPlanGraph(plan, listOf(week), listOf(workout))
        saveAppliedChange("older", workout, distanceMeters = 4_500, createdAt = 10)
        saveAppliedChange("latest", workout, distanceMeters = 4_000, createdAt = 20)
        saveAppliedChange(
            "consequence",
            workout,
            distanceMeters = 4_000,
            createdAt = 30,
            effectType = "consequence_decision",
        )

        assertEquals(
            listOf("consequence", "latest"),
            database.adjustmentDao().undoableWorkoutAdjustments(listOf(workout.workoutId), todayEpochDay = 20, limit = 8)
                .map(UndoableWorkoutAdjustmentRow::adjustmentId),
        )
        assertTrue(
            database.adjustmentDao().undoableWorkoutAdjustments(listOf(workout.workoutId), todayEpochDay = 22, limit = 8)
                .isEmpty(),
        )

        database.adjustmentDao().saveReversal(
            PlanReversalEntity("reversal-consequence", "decision-consequence", null, 40),
        )
        assertEquals(
            listOf("latest"),
            database.adjustmentDao().undoableWorkoutAdjustments(
                listOf(workout.workoutId),
                todayEpochDay = 20,
                limit = 8,
            ).map(UndoableWorkoutAdjustmentRow::adjustmentId),
        )
        database.adjustmentDao().saveReversal(
            PlanReversalEntity("reversal-latest", "decision-latest", null, 50),
        )
        assertTrue(
            database.adjustmentDao().undoableWorkoutAdjustments(listOf(workout.workoutId), todayEpochDay = 20, limit = 8)
                .isEmpty(),
        )
    }

    private suspend fun saveAppliedChange(
        adjustmentId: String,
        workout: WorkoutEntity,
        distanceMeters: Int,
        createdAt: Long,
        effectType: String = "workout_change",
    ) {
        val dao = database.adjustmentDao()
        val groupId = "group-$adjustmentId"
        dao.saveAdjustment(
            PlanAdjustmentEntity(
                adjustmentId = adjustmentId,
                planId = workout.planId,
                workoutId = workout.workoutId,
                sourceActivityId = null,
                adjustmentType = "edit",
                state = "applied",
                measuredLoadSharePercent = null,
                projectedRampPercent = null,
                affectedWorkoutCount = 1,
                createdAtEpochMillis = createdAt,
            ),
        )
        dao.saveEffectGroup(AdjustmentEffectGroupEntity(groupId, adjustmentId, 0, effectType, null, null))
        dao.saveWorkoutEffect(
            AdjustmentWorkoutEffectEntity(
                effectId = "effect-$adjustmentId",
                groupId = groupId,
                workoutId = workout.workoutId,
                ordinal = 0,
                previousScheduledEpochDay = workout.generatedScheduledEpochDay,
                newScheduledEpochDay = workout.currentScheduledEpochDay,
                previousWorkoutType = workout.generatedWorkoutType,
                newWorkoutType = workout.currentWorkoutType,
                previousStatus = "planned",
                newStatus = workout.currentStatus,
                previousDistanceMeters = workout.generatedDistanceMeters,
                newDistanceMeters = distanceMeters,
                previousDurationSeconds = workout.generatedDurationSeconds,
                newDurationSeconds = workout.currentDurationSeconds,
                previousIntensity = workout.generatedIntensity,
                newIntensity = workout.currentIntensity,
                previousPurpose = workout.generatedPurpose,
                newPurpose = workout.currentPurpose,
                previousReason = workout.generatedReason,
                newReason = workout.currentReason,
                previousTombstonedAtEpochMillis = null,
                newTombstonedAtEpochMillis = workout.tombstonedAtEpochMillis,
                previousWarmupSeconds = workout.generatedWarmupSeconds,
                newWarmupSeconds = workout.currentWarmupSeconds,
                previousCooldownSeconds = workout.generatedCooldownSeconds,
                newCooldownSeconds = workout.currentCooldownSeconds,
                previousPrescriptionKind = workout.generatedPrescriptionKind,
                newPrescriptionKind = workout.currentPrescriptionKind,
                previousWeekId = workout.weekId,
                newWeekId = workout.weekId,
            ),
        )
        dao.saveDecision(PlanDecisionEntity("decision-$adjustmentId", adjustmentId, "edit", 1, 21, createdAt))
    }
}
