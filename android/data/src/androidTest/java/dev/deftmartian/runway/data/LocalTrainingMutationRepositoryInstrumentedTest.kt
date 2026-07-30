package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalTrainingMutationRepositoryInstrumentedTest {
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
                timeZone = "America/Halifax", routeDataMode = "private", heartRateSettingsSource = "none",
                maxHeartRateBpm = null, zone2FloorBpm = null, zone3FloorBpm = null,
                zone4FloorBpm = null, zone5FloorBpm = null, recentInjury = false, currentPain = false,
                recurringPain = false, medicalRestriction = false, privateNotes = null, updatedAtEpochMillis = now,
            ),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun deleteDirectFeedbackRestoresPlannedStatusCascadesConsequenceAndIsIdempotentlyAbsent() = runBlocking {
        insertPlanGraph("workout")
        insertDirectFeedback("workout", appliedDecision = null)
        val repository = LocalTrainingMutationRepository(database, nowEpochMillis = { now })

        assertEquals(
            LocalWorkoutFeedbackDeletionResult.Deleted("feedback-workout", "workout"),
            repository.deleteWorkoutFeedback("workout"),
        )
        assertEquals("planned", database.goalPlanDao().workout("workout")?.currentStatus)
        assertNull(database.activityLedgerDao().workoutFeedback("workout"))
        assertNull(database.activityLedgerDao().workoutFeedbackConsequence("feedback-workout"))
        assertEquals(
            LocalWorkoutFeedbackDeletionResult.Rejected(LocalWorkoutFeedbackDeletionIssue.FEEDBACK_NOT_FOUND),
            repository.deleteWorkoutFeedback("workout"),
        )
    }

    @Test
    fun deleteDirectFeedbackRefusesAppliedDecisionAndAcceptedLinkedActivity() = runBlocking {
        insertPlanGraph("applied")
        insertDirectFeedback("applied", appliedDecision = "reduce_next")
        val repository = LocalTrainingMutationRepository(database, nowEpochMillis = { now })
        assertEquals(
            LocalWorkoutFeedbackDeletionResult.Rejected(LocalWorkoutFeedbackDeletionIssue.CONSEQUENCE_APPLIED),
            repository.deleteWorkoutFeedback("applied"),
        )

        insertPlanGraph("linked")
        insertDirectFeedback("linked", appliedDecision = null)
        database.activityLedgerDao().saveActivity(
            ActivityEntity(
                activityId = "linked-activity", source = "manual", sourceRecordId = null,
                reviewState = ACTIVITY_REVIEW_STATE_ACCEPTED, occurredAtEpochMillis = now,
                durationSeconds = 1_800, distanceMeters = 5_000, averageHeartRateBpm = null,
                averageCadenceSpm = null, linkedWorkoutId = "linked", acceptedAtEpochMillis = now,
                createdAtEpochMillis = now, updatedAtEpochMillis = now,
            ),
        )
        assertEquals(
            LocalWorkoutFeedbackDeletionResult.Rejected(LocalWorkoutFeedbackDeletionIssue.LINKED_ACCEPTED_ACTIVITY),
            repository.deleteWorkoutFeedback("linked"),
        )
    }

    private suspend fun insertPlanGraph(workoutId: String) {
        val goalId = "goal-$workoutId"
        val planId = "plan-$workoutId"
        val weekId = "week-$workoutId"
        database.goalPlanDao().saveGoal(
            GoalEntity(goalId, "Test", null, "active", now, now, "foundation", "foundation_only", null, "consistency"),
        )
        database.goalPlanDao().createPlanGraph(
            PlanEntity(planId, goalId, "foundation", "active", today.toEpochDay() - 7, today.toEpochDay() + 7, now, now),
            listOf(PlanWeekEntity(weekId, planId, 1, today.toEpochDay() - 3, 5_000)),
            listOf(
                WorkoutEntity(
                    workoutId, planId, weekId, 0, "Easy", 5_000, null, "Easy", 5_000, null,
                    null, now, today.toEpochDay(), today.toEpochDay(), "easy", "easy", "distance", "distance",
                    currentStatus = "done",
                ),
            ),
        )
    }

    private suspend fun insertDirectFeedback(workoutId: String, appliedDecision: String?) {
        val feedbackId = "feedback-$workoutId"
        database.activityLedgerDao().saveWorkoutFeedback(
            WorkoutFeedbackEntity(feedbackId, workoutId, "done", false, false, null, now, 5_000),
        )
        database.activityLedgerDao().saveWorkoutFeedbackConsequence(
            WorkoutFeedbackConsequenceEntity(
                feedbackId, "near_plan", 0, null, 5_000, 5_000, "conservative", 0,
                "keep_plan", "keep_plan", false, appliedDecision = appliedDecision,
            ),
        )
    }
}
