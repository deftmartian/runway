package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deftmartian.runway.domain.PlanDecision
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalConsequenceDecisionRepositoryInstrumentedTest {
    private lateinit var database: RunwayLedgerDatabase
    private val today = LocalDate.of(2026, 7, 30)
    private val now = today.atStartOfDay(ZoneId.of("America/Halifax")).toInstant().toEpochMilli()

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RunwayLedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.profileSettingsDao().save(
            ProfileSettingsEntity(
                timeZone = "America/Halifax",
                routeDataMode = "discard",
                heartRateSettingsSource = "none",
                maxHeartRateBpm = null,
                zone2FloorBpm = null,
                zone3FloorBpm = null,
                zone4FloorBpm = null,
                zone5FloorBpm = null,
                recentInjury = false,
                currentPain = false,
                recurringPain = false,
                medicalRestriction = false,
                privateNotes = null,
                updatedAtEpochMillis = now,
            ),
        )
        seedSourceBackedPlanAndFeedback()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun consequenceChangePersistsExactSnapshotsAndCanBeUndoneTwiceSafely() = runBlocking {
        val decisions = LocalConsequenceDecisionRepository(database, nowEpochMillis = { now })
        val prepared = decisions.prepare(
            LocalDecisionSourceKind.WorkoutFeedback,
            "feedback-origin",
            PlanDecision.REDUCE_NEXT,
        ) as LocalConsequenceDecisionPreparation.Prepared

        assertTrue(
            decisions.apply(
                preview = prepared.preview,
                input = prepared.input,
                adjustmentId = "adjustment-reduce",
                decisionId = "decision-reduce",
                appliedAtEpochMillis = now,
            ) is LocalConsequenceDecisionPersistenceResult.Applied,
        )
        assertEquals(5_000, database.goalPlanDao().workout("future")?.currentDistanceMeters)
        assertEquals(
            listOf("source-locator"),
            database.goalPlanDao().workoutSourceReferences("future", "current", 10)
                .map(WorkoutSourceReferenceEntity::sourceLocator),
        )

        val changes = database.localWorkoutChangeRepository()
        assertEquals(
            UndoLocalWorkoutChangeResult.Undone("adjustment-reduce", listOf("future")),
            changes.undo(
                adjustmentId = "adjustment-reduce",
                reversalId = "reversal-reduce",
                reversedAtEpochMillis = now + 1,
                today = today,
            ),
        )
        assertEquals(6_000, database.goalPlanDao().workout("future")?.currentDistanceMeters)
        assertEquals(
            listOf("source-locator"),
            database.goalPlanDao().workoutSourceReferences("future", "current", 10)
                .map(WorkoutSourceReferenceEntity::sourceLocator),
        )
        assertEquals(
            UndoLocalWorkoutChangeResult.AlreadyUndone("adjustment-reduce"),
            changes.undo(
                adjustmentId = "adjustment-reduce",
                reversalId = "another-reversal",
                reversedAtEpochMillis = now + 2,
                today = today,
            ),
        )
    }

    private suspend fun seedSourceBackedPlanAndFeedback() {
        val goal = GoalEntity(
            "goal",
            "5K",
            today.plusWeeks(8).toEpochDay(),
            "active",
            now,
            now,
            "race",
            "established",
            5_000,
            "finish_healthy",
        )
        val plan = PlanEntity(
            "plan",
            goal.goalId,
            "distance",
            "active",
            today.minusWeeks(1).toEpochDay(),
            today.plusWeeks(8).toEpochDay(),
            now,
            now,
        )
        val week = PlanWeekEntity("week", plan.planId, 1, today.minusDays(2).toEpochDay(), 11_000)
        val origin = workout("origin", plan.planId, week.weekId, 0, today.minusDays(1), 5_000, "done")
        val future = workout("future", plan.planId, week.weekId, 1, today.plusDays(1), 6_000, "planned")
        val planDao = database.goalPlanDao()
        planDao.saveGoal(goal)
        planDao.createPlanGraph(plan, listOf(week), listOf(origin, future))
        planDao.insertWorkoutSourceReferences(
            listOf(
                WorkoutSourceReferenceEntity(
                    referenceId = "future-current-source",
                    workoutId = future.workoutId,
                    prescriptionVersion = "current",
                    ordinal = 0,
                    sourceName = "Training source",
                    sourceUrl = null,
                    sourceLocator = "source-locator",
                ),
            ),
        )

        val activityDao = database.activityLedgerDao()
        activityDao.saveWorkoutFeedback(
            WorkoutFeedbackEntity(
                feedbackId = "feedback-origin",
                workoutId = origin.workoutId,
                completionState = "done",
                feltHard = true,
                pain = false,
                notes = null,
                recordedAtEpochMillis = now,
                completedDistanceMeters = 5_000,
            ),
        )
        activityDao.saveWorkoutFeedbackConsequence(
            WorkoutFeedbackConsequenceEntity(
                feedbackId = "feedback-origin",
                classification = "hard_effort",
                distanceDifferenceMeters = 0,
                durationDifferenceSeconds = null,
                currentWeekLoadMeters = 11_000,
                projectedWeekLoadMeters = 10_000,
                assessment = "moderate",
                recoveryConflictCount = 0,
                recommendedDecision = "reduce_next",
                nextWorkoutAction = "reduce_next",
                requiresExplicitConfirmation = false,
                deviation = "near_plan",
                loadMetric = "distance",
                risk = "moderate",
            ),
        )
        listOf("keep_plan", "reduce_next", "next_rest", "repeat_prescription", "rebalance_week")
            .forEach {
                activityDao.saveWorkoutFeedbackConsequenceOption(
                    WorkoutFeedbackConsequenceOptionEntity("feedback-origin", it),
                )
            }
    }

    private fun workout(
        workoutId: String,
        planId: String,
        weekId: String,
        position: Int,
        date: LocalDate,
        distanceMeters: Int,
        state: String,
    ) = WorkoutEntity(
        workoutId = workoutId,
        planId = planId,
        weekId = weekId,
        position = position,
        generatedPurpose = "Easy run",
        generatedDistanceMeters = distanceMeters,
        generatedDurationSeconds = null,
        currentPurpose = "Easy run",
        currentDistanceMeters = distanceMeters,
        currentDurationSeconds = null,
        tombstonedAtEpochMillis = null,
        updatedAtEpochMillis = now,
        generatedScheduledEpochDay = date.toEpochDay(),
        currentScheduledEpochDay = date.toEpochDay(),
        generatedWorkoutType = "easy",
        currentWorkoutType = "easy",
        generatedPrescriptionKind = "distance",
        currentPrescriptionKind = "distance",
        currentStatus = state,
    )
}
