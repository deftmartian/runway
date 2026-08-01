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
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun consequenceChangePersistsExactSnapshotsAndCanBeUndoneTwiceSafely() = runBlocking {
        seedDistanceSourceBackedPlanAndFeedback()
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

    @Test
    fun timedReductionPersistsMatchingStructureAndRoundTripsIdempotently() = runBlocking {
        seedTimedSourceBackedPlanAndFeedback()
        val decisions = LocalConsequenceDecisionRepository(database, nowEpochMillis = { now })
        val prepared = decisions.prepare(
            LocalDecisionSourceKind.WorkoutFeedback,
            "feedback-origin-timed",
            PlanDecision.REDUCE_NEXT,
        ) as LocalConsequenceDecisionPreparation.Prepared

        assertTrue(
            decisions.apply(
                preview = prepared.preview,
                input = prepared.input,
                adjustmentId = "adjustment-reduce-timed",
                decisionId = "decision-reduce-timed",
                appliedAtEpochMillis = now,
            ) is LocalConsequenceDecisionPersistenceResult.Applied,
        )
        val reduced = requireNotNull(database.goalPlanDao().workout("future-timed"))
        assertEquals(1_500, reduced.currentDurationSeconds)
        assertEquals(250, reduced.currentWarmupSeconds)
        assertEquals(250, reduced.currentCooldownSeconds)
        assertEquals(reduced.currentDurationSeconds, currentStructureTotal(reduced))

        assertEquals(
            LocalConsequenceDecisionPersistenceResult.AlreadyApplied,
            decisions.apply(
                preview = prepared.preview,
                input = prepared.input,
                adjustmentId = "another-adjustment-reduce-timed",
                decisionId = "another-decision-reduce-timed",
                appliedAtEpochMillis = now + 1,
            ),
        )
        assertEquals(1_500, currentStructureTotal(requireNotNull(database.goalPlanDao().workout("future-timed"))))

        val changes = database.localWorkoutChangeRepository()
        assertEquals(
            UndoLocalWorkoutChangeResult.Undone("adjustment-reduce-timed", listOf("future-timed")),
            changes.undo(
                adjustmentId = "adjustment-reduce-timed",
                reversalId = "reversal-reduce-timed",
                reversedAtEpochMillis = now + 2,
                today = today,
            ),
        )
        val restored = requireNotNull(database.goalPlanDao().workout("future-timed"))
        assertEquals(1_800, restored.currentDurationSeconds)
        assertEquals(300, restored.currentWarmupSeconds)
        assertEquals(300, restored.currentCooldownSeconds)
        assertEquals(restored.currentDurationSeconds, currentStructureTotal(restored))
        assertEquals(
            UndoLocalWorkoutChangeResult.AlreadyUndone("adjustment-reduce-timed"),
            changes.undo(
                adjustmentId = "adjustment-reduce-timed",
                reversalId = "another-reversal-reduce-timed",
                reversedAtEpochMillis = now + 3,
                today = today,
            ),
        )
    }

    @Test
    fun timedRepeatCopiesOriginStructureAndRoundTripsIdempotently() = runBlocking {
        seedTimedSourceBackedPlanAndFeedback()
        val planDao = database.goalPlanDao()
        val originBefore = requireNotNull(planDao.workout("origin-timed"))
        val futureBefore = requireNotNull(planDao.workout("future-timed"))
        val originBlocks = currentBlocks(originBefore.workoutId)
        val futureBlocks = currentBlocks(futureBefore.workoutId)
        val originReferences = currentReferences(originBefore.workoutId)
        val futureReferences = currentReferences(futureBefore.workoutId)
        val decisions = LocalConsequenceDecisionRepository(database, nowEpochMillis = { now })
        val prepared = decisions.prepare(
            LocalDecisionSourceKind.WorkoutFeedback,
            "feedback-origin-timed",
            PlanDecision.REPEAT_PRESCRIPTION,
        ) as LocalConsequenceDecisionPreparation.Prepared

        assertTrue(
            decisions.apply(
                preview = prepared.preview,
                input = prepared.input,
                adjustmentId = "adjustment-repeat-timed",
                decisionId = "decision-repeat-timed",
                appliedAtEpochMillis = now,
            ) is LocalConsequenceDecisionPersistenceResult.Applied,
        )
        val repeated = requireNotNull(planDao.workout("future-timed"))
        assertEquals(futureBefore.workoutId, repeated.workoutId)
        assertEquals(futureBefore.currentScheduledEpochDay, repeated.currentScheduledEpochDay)
        assertEquals(futureBefore.currentStatus, repeated.currentStatus)
        assertEquals(originBefore.currentWorkoutType, repeated.currentWorkoutType)
        assertEquals(originBefore.currentPrescriptionKind, repeated.currentPrescriptionKind)
        assertEquals(originBefore.currentDistanceMeters, repeated.currentDistanceMeters)
        assertEquals(originBefore.currentDurationSeconds, repeated.currentDurationSeconds)
        assertEquals(originBefore.currentIntensity, repeated.currentIntensity)
        assertEquals(originBefore.currentPurpose, repeated.currentPurpose)
        assertEquals(originBefore.currentWarmupSeconds, repeated.currentWarmupSeconds)
        assertEquals(originBefore.currentCooldownSeconds, repeated.currentCooldownSeconds)
        assertEquals(
            "The runner explicitly chose to repeat the earlier prescription.",
            repeated.currentReason,
        )
        assertEquals(originBlocks, currentBlocks(repeated.workoutId))
        assertEquals(originReferences, currentReferences(repeated.workoutId))
        assertEquals(repeated.currentDurationSeconds, currentStructureTotal(repeated))

        assertEquals(
            LocalConsequenceDecisionPersistenceResult.AlreadyApplied,
            decisions.apply(
                preview = prepared.preview,
                input = prepared.input,
                adjustmentId = "another-adjustment-repeat-timed",
                decisionId = "another-decision-repeat-timed",
                appliedAtEpochMillis = now + 1,
            ),
        )
        assertEquals(repeated, planDao.workout("future-timed"))
        assertEquals(originBlocks, currentBlocks(repeated.workoutId))
        assertEquals(originReferences, currentReferences(repeated.workoutId))

        val changes = database.localWorkoutChangeRepository()
        assertEquals(
            UndoLocalWorkoutChangeResult.Undone("adjustment-repeat-timed", listOf("future-timed")),
            changes.undo(
                adjustmentId = "adjustment-repeat-timed",
                reversalId = "reversal-repeat-timed",
                reversedAtEpochMillis = now + 2,
                today = today,
            ),
        )
        val restored = requireNotNull(planDao.workout("future-timed"))
        assertEquals(futureBefore.currentWorkoutType, restored.currentWorkoutType)
        assertEquals(futureBefore.currentPrescriptionKind, restored.currentPrescriptionKind)
        assertEquals(futureBefore.currentDistanceMeters, restored.currentDistanceMeters)
        assertEquals(futureBefore.currentDurationSeconds, restored.currentDurationSeconds)
        assertEquals(futureBefore.currentIntensity, restored.currentIntensity)
        assertEquals(futureBefore.currentPurpose, restored.currentPurpose)
        assertEquals(futureBefore.currentWarmupSeconds, restored.currentWarmupSeconds)
        assertEquals(futureBefore.currentCooldownSeconds, restored.currentCooldownSeconds)
        assertEquals(futureBlocks, currentBlocks(restored.workoutId))
        assertEquals(futureReferences, currentReferences(restored.workoutId))
        assertEquals(restored.currentDurationSeconds, currentStructureTotal(restored))
        assertEquals(
            UndoLocalWorkoutChangeResult.AlreadyUndone("adjustment-repeat-timed"),
            changes.undo(
                adjustmentId = "adjustment-repeat-timed",
                reversalId = "another-reversal-repeat-timed",
                reversedAtEpochMillis = now + 3,
                today = today,
            ),
        )
        assertEquals(restored, planDao.workout("future-timed"))
        assertEquals(futureBlocks, currentBlocks(restored.workoutId))
        assertEquals(futureReferences, currentReferences(restored.workoutId))
    }

    private suspend fun seedDistanceSourceBackedPlanAndFeedback() {
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

    private suspend fun seedTimedSourceBackedPlanAndFeedback() {
        val goal = GoalEntity(
            "goal-timed",
            "Foundation",
            today.plusWeeks(8).toEpochDay(),
            "active",
            now,
            now,
            "race",
            "foundation",
            5_000,
            "finish_healthy",
        )
        val plan = PlanEntity(
            "plan-timed",
            goal.goalId,
            "foundation",
            "active",
            today.minusWeeks(1).toEpochDay(),
            today.plusWeeks(8).toEpochDay(),
            now,
            now,
        )
        val week = PlanWeekEntity(
            "week-timed",
            plan.planId,
            1,
            today.minusDays(2).toEpochDay(),
            generatedLoadMeters = null,
            generatedLoadDurationSeconds = 3_000,
        )
        val origin = timedWorkout(
            "origin-timed",
            plan.planId,
            week.weekId,
            0,
            today.minusDays(1),
            "done",
            durationSeconds = 1_200,
            warmupSeconds = 120,
            cooldownSeconds = 180,
            workoutType = "recovery",
            purpose = "Origin recovery intervals",
        )
        val future = timedWorkout(
            "future-timed",
            plan.planId,
            week.weekId,
            1,
            today.plusDays(1),
            "planned",
            durationSeconds = 1_800,
            warmupSeconds = 300,
            cooldownSeconds = 300,
            workoutType = "easy",
            purpose = "Future easy intervals",
        )
        val planDao = database.goalPlanDao()
        planDao.saveGoal(goal)
        planDao.createPlanGraph(plan, listOf(week), listOf(origin, future))
        planDao.insertBlocks(
            listOf(
                WorkoutBlockEntity(
                    blockId = "origin-timed-current-block",
                    workoutId = origin.workoutId,
                    prescriptionVersion = "current",
                    ordinal = 0,
                    blockType = "timed",
                    repetitions = 2,
                ),
                WorkoutBlockEntity(
                    blockId = "future-timed-current-block",
                    workoutId = future.workoutId,
                    prescriptionVersion = "current",
                    ordinal = 0,
                    blockType = "timed",
                    repetitions = 2,
                ),
            ),
        )
        planDao.insertSegments(
            listOf(
                WorkoutSegmentEntity(
                    segmentId = "origin-timed-current-run",
                    blockId = "origin-timed-current-block",
                    ordinal = 0,
                    segmentType = "run",
                    targetDistanceMeters = null,
                    targetDurationSeconds = 300,
                ),
                WorkoutSegmentEntity(
                    segmentId = "origin-timed-current-walk",
                    blockId = "origin-timed-current-block",
                    ordinal = 1,
                    segmentType = "walk",
                    targetDistanceMeters = null,
                    targetDurationSeconds = 150,
                ),
                WorkoutSegmentEntity(
                    segmentId = "future-timed-current-run",
                    blockId = "future-timed-current-block",
                    ordinal = 0,
                    segmentType = "run",
                    targetDistanceMeters = null,
                    targetDurationSeconds = 300,
                ),
                WorkoutSegmentEntity(
                    segmentId = "future-timed-current-walk",
                    blockId = "future-timed-current-block",
                    ordinal = 1,
                    segmentType = "walk",
                    targetDistanceMeters = null,
                    targetDurationSeconds = 300,
                ),
            ),
        )
        planDao.insertWorkoutSourceReferences(
            listOf(
                WorkoutSourceReferenceEntity(
                    referenceId = "origin-timed-current-source",
                    workoutId = origin.workoutId,
                    prescriptionVersion = "current",
                    ordinal = 0,
                    sourceName = "Origin source",
                    sourceLocator = "origin-source-locator",
                ),
                WorkoutSourceReferenceEntity(
                    referenceId = "future-timed-current-source",
                    workoutId = future.workoutId,
                    prescriptionVersion = "current",
                    ordinal = 0,
                    sourceName = "Future source",
                    sourceLocator = "future-source-locator",
                ),
            ),
        )

        val activityDao = database.activityLedgerDao()
        activityDao.saveWorkoutFeedback(
            WorkoutFeedbackEntity(
                feedbackId = "feedback-origin-timed",
                workoutId = origin.workoutId,
                completionState = "done",
                feltHard = true,
                pain = false,
                notes = null,
                recordedAtEpochMillis = now,
                completedDurationSeconds = 1_200,
            ),
        )
        activityDao.saveWorkoutFeedbackConsequence(
            WorkoutFeedbackConsequenceEntity(
                feedbackId = "feedback-origin-timed",
                classification = "hard_effort",
                distanceDifferenceMeters = null,
                durationDifferenceSeconds = 0,
                currentWeekLoadMeters = null,
                projectedWeekLoadMeters = null,
                assessment = "moderate",
                recoveryConflictCount = 0,
                recommendedDecision = "reduce_next",
                nextWorkoutAction = "reduce_next",
                requiresExplicitConfirmation = false,
                deviation = "near_plan",
                loadMetric = "duration",
                risk = "moderate",
                currentWeekLoadDurationSeconds = 3_000,
                projectedWeekLoadDurationSeconds = 2_700,
            ),
        )
        listOf("keep_plan", "reduce_next", "next_rest", "repeat_prescription", "rebalance_week")
            .forEach {
                activityDao.saveWorkoutFeedbackConsequenceOption(
                    WorkoutFeedbackConsequenceOptionEntity("feedback-origin-timed", it),
                )
            }
    }

    private suspend fun currentStructureTotal(workout: WorkoutEntity): Int {
        return (workout.currentWarmupSeconds ?: 0) +
            (workout.currentCooldownSeconds ?: 0) +
            currentBlocks(workout.workoutId).sumOf { block ->
                block.repetitions * block.segments.sumOf { it.targetDurationSeconds ?: 0 }
            }
    }

    private suspend fun currentBlocks(workoutId: String): List<StoredWorkoutBlock> {
        val planDao = database.goalPlanDao()
        return planDao.blocksForWorkout(workoutId, "current", 10).map { block ->
            StoredWorkoutBlock(
                blockType = block.blockType,
                repetitions = block.repetitions,
                segments = planDao.segmentsForBlock(block.blockId, 10).map { segment ->
                    StoredWorkoutSegment(
                        segmentType = segment.segmentType,
                        targetDistanceMeters = segment.targetDistanceMeters,
                        targetDurationSeconds = segment.targetDurationSeconds,
                    )
                },
            )
        }
    }

    private suspend fun currentReferences(workoutId: String): List<StoredWorkoutSourceReference> =
        database.goalPlanDao().workoutSourceReferences(workoutId, "current", 10)
            .map { StoredWorkoutSourceReference(it.sourceName, it.sourceLocator) }

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

    private fun timedWorkout(
        workoutId: String,
        planId: String,
        weekId: String,
        position: Int,
        date: LocalDate,
        state: String,
        durationSeconds: Int,
        warmupSeconds: Int,
        cooldownSeconds: Int,
        workoutType: String,
        purpose: String,
    ) = WorkoutEntity(
        workoutId = workoutId,
        planId = planId,
        weekId = weekId,
        position = position,
        generatedPurpose = purpose,
        generatedDistanceMeters = null,
        generatedDurationSeconds = durationSeconds,
        currentPurpose = purpose,
        currentDistanceMeters = null,
        currentDurationSeconds = durationSeconds,
        tombstonedAtEpochMillis = null,
        updatedAtEpochMillis = now,
        generatedScheduledEpochDay = date.toEpochDay(),
        currentScheduledEpochDay = date.toEpochDay(),
        generatedWorkoutType = workoutType,
        currentWorkoutType = workoutType,
        generatedPrescriptionKind = "timed",
        currentPrescriptionKind = "timed",
        generatedIntensity = "easy",
        currentIntensity = "easy",
        currentStatus = state,
        generatedWarmupSeconds = warmupSeconds,
        generatedCooldownSeconds = cooldownSeconds,
        currentWarmupSeconds = warmupSeconds,
        currentCooldownSeconds = cooldownSeconds,
    )
}
