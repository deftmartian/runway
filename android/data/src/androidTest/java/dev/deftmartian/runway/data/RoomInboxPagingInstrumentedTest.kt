package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomInboxPagingInstrumentedTest {
    private lateinit var database: RunwayLedgerDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RunwayLedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun inboxPagesEveryCalendarCountedDecisionOnceAndRerunsDeterministically() = runBlocking {
        val goal = GoalEntity("goal", "Goal", null, "active", 1, 1, "distance", "established", null, "finish_healthy")
        val plan = PlanEntity("plan", goal.goalId, "distance", "active", 0, 30, 1, 1)
        val week = PlanWeekEntity("week", plan.planId, 1, 0, 5_000)
        val candidate = workout("candidate", plan.planId, week.weekId, 10)
        val linkedWorkout = workout("linked-workout", plan.planId, week.weekId, 11)
        val directWorkout = workout("direct-workout", plan.planId, week.weekId, 12)
        database.goalPlanDao().saveGoal(goal)
        database.goalPlanDao().createPlanGraph(plan, listOf(week), listOf(candidate, linkedWorkout, directWorkout))

        val activities = database.activityLedgerDao()
        activities.saveActivity(activity("review", "review", 10))
        activities.saveActivity(activity("duplicate-review", "review", 9))
        activities.saveActivity(activity("duplicate-target", "accepted", 9))
        activities.saveActivity(activity("extra", "accepted", 8, extraConfirmed = true))
        activities.saveActivity(activity("linked", "accepted", 11, linkedWorkout.workoutId))
        activities.saveActivity(activity("unrelated", "accepted", 7))
        activities.saveActivityConsequence(activityConsequence("extra"))
        val linkedFeedback = feedback("linked-feedback", linkedWorkout.workoutId, "linked", 11)
        val directFeedback = feedback("direct-feedback", directWorkout.workoutId, null, 12)
        activities.saveWorkoutFeedback(linkedFeedback)
        activities.saveWorkoutFeedback(directFeedback)
        activities.saveWorkoutFeedbackConsequence(workoutConsequence(linkedFeedback.feedbackId))
        activities.saveWorkoutFeedbackConsequence(workoutConsequence(directFeedback.feedbackId))

        val imports = database.importLedgerDao()
        imports.saveHealthConnectMapping(mapping("duplicate", "duplicate-review", 13, duplicateCandidateId = "duplicate-target"))
        imports.saveHealthConnectMapping(mapping("correction", null, 14, correction = true))
        imports.saveHealthConnectMapping(mapping("delete", null, 15, delete = true))

        val reader = RoomLocalSurfaceLedgerReader(database)
        val limits = LocalSurfaceReadLimits(inboxActivities = 1)
        val firstTraversal = collect(reader, limits)
        val secondTraversal = collect(reader, limits)

        assertEquals(firstTraversal, secondTraversal)
        assertEquals(7, firstTraversal.size)
        assertEquals(firstTraversal.size, firstTraversal.toSet().size)
        assertEquals(7, reader.calendar(0, 30, limits).pendingDecisionCount)
        assertTrue("review:review" in firstTraversal)
        assertFalse("review:duplicate-review" in firstTraversal)
        assertTrue(firstTraversal.containsAll(setOf(
            "extra:extra", "linked:linked", "direct:direct-feedback",
            "health:duplicate", "health:correction", "health:delete",
        )))
    }

    private suspend fun collect(reader: RoomLocalSurfaceLedgerReader, limits: LocalSurfaceReadLimits): List<String> {
        var cursor = LocalInboxPagingCursor()
        val identities = mutableListOf<String>()
        do {
            val page = reader.inboxPage(limits, cursor)
            page.activities.forEach { row ->
                when {
                    row.activity.reviewState == "review" -> {
                        identities += "review:${row.activity.activityId}"
                        assertTrue(page.linkCandidates.any { it.workoutId == "candidate" })
                    }
                    row.linkedWorkoutConsequence != null -> {
                        identities += "linked:${row.activity.activityId}"
                        assertNotNull(row.linkedWorkoutFeedback)
                    }
                    else -> {
                        identities += "extra:${row.activity.activityId}"
                        assertNotNull(row.consequence)
                    }
                }
            }
            page.pendingWorkoutFeedback.forEach { row ->
                identities += "direct:${row.feedback.feedbackId}"
                assertNotNull(row.consequence)
                assertNotNull(row.workout)
            }
            page.pendingHealthConnect.forEach { row ->
                identities += "health:${row.mappingId}"
                assertTrue(row.state in setOf("possible_duplicate", "pending_correction", "pending_delete"))
            }
            cursor = page.nextPage ?: break
        } while (true)
        return identities
    }

    private fun activity(
        id: String, state: String, day: Long, linkedWorkoutId: String? = null, extraConfirmed: Boolean = false,
    ) = ActivityEntity(
        id,
        "test",
        id,
        state,
        day * MILLIS_PER_DAY,
        900,
        2_000,
        null,
        null,
        linkedWorkoutId,
        (day * MILLIS_PER_DAY).takeIf { state == "accepted" },
        day * MILLIS_PER_DAY,
        day * MILLIS_PER_DAY,
        extraPlanImpactConfirmed = extraConfirmed,
    )

    private fun activityConsequence(activityId: String) = ActivityConsequenceEntity(
        activityId = activityId,
        classification = "extra",
        distanceDifferenceMeters = 0,
        durationDifferenceSeconds = null,
        actualLoadMeters = 2_000,
        assessment = "conservative",
        recommendedDecision = "keep_plan",
        resolvedAtEpochMillis = null,
        planChangeAvailable = true,
    )

    private fun feedback(id: String, workoutId: String, activityId: String?, recordedAt: Long) = WorkoutFeedbackEntity(
        feedbackId = id,
        workoutId = workoutId,
        completionState = "shortened",
        feltHard = true,
        pain = false,
        notes = null,
        recordedAtEpochMillis = recordedAt,
        completedDistanceMeters = 1_000,
        completedDurationSeconds = 600,
        sourceActivityId = activityId,
    )

    private fun workoutConsequence(feedbackId: String) = WorkoutFeedbackConsequenceEntity(
        feedbackId = feedbackId,
        classification = "short",
        distanceDifferenceMeters = -1_000,
        durationDifferenceSeconds = null,
        currentWeekLoadMeters = 2_000,
        projectedWeekLoadMeters = 2_000,
        assessment = "conservative",
        recoveryConflictCount = 0,
        recommendedDecision = "keep_plan",
        nextWorkoutAction = "keep_plan",
        requiresExplicitConfirmation = false,
        appliedDecision = null,
        planChangeAvailable = true,
    )

    private fun mapping(
        id: String, activityId: String?, observedAt: Long, correction: Boolean = false,
        delete: Boolean = false, duplicateCandidateId: String? = null,
    ) = HealthConnectMappingEntity(
        id, "health_connect", "record-$id", activityId, observedAt, observedAt,
        correctionPending = correction, deletePending = delete, duplicateCandidateActivityId = duplicateCandidateId,
    )

    private fun workout(id: String, planId: String, weekId: String, day: Long) = WorkoutEntity(
        workoutId = id,
        planId = planId,
        weekId = weekId,
        position = 0,
        generatedPurpose = "Run",
        generatedDistanceMeters = 2_000,
        generatedDurationSeconds = null,
        currentPurpose = "Run",
        currentDistanceMeters = 2_000,
        currentDurationSeconds = null,
        tombstonedAtEpochMillis = null,
        updatedAtEpochMillis = day,
        generatedScheduledEpochDay = day,
        currentScheduledEpochDay = day,
        generatedWorkoutType = "easy",
        currentWorkoutType = "easy",
        generatedPrescriptionKind = "distance",
        currentPrescriptionKind = "distance",
        currentStatus = "planned",
    )

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
