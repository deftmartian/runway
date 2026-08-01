package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalActivityReviewRepositoryInstrumentedTest {
    private lateinit var database: RunwayLedgerDatabase
    private val zone = ZoneId.of("America/Halifax")
    private val today = LocalDate.of(2026, 7, 30)
    private val now = today.atStartOfDay(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RunwayLedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking { database.profileSettingsDao().save(profile()) }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun reviewOnlyPainFeedbackDoesNotSetCurrentPain() = runBlocking {
        database.activityLedgerDao().saveActivity(activity("review", reviewState = "review"))

        val result = LocalActivityReviewRepository(database, nowEpochMillis = { 2_000 })
            .updateFeedback("review", feltHard = false, pain = true)

        assertFalse(assertFeedbackUpdated(result).appliedDecisionPreserved)
        assertFalse(requireNotNull(database.profileSettingsDao().get()).currentPain)
        assertEquals(true, requireNotNull(database.activityLedgerDao().activityFeedback("review")).pain)
    }

    @Test
    fun feedbackCorrectionPreservesAnAppliedExtraConsequence() = runBlocking {
        database.activityLedgerDao().saveActivity(
            activity("accepted", reviewState = ACTIVITY_REVIEW_STATE_ACCEPTED, extraConfirmed = true),
        )
        database.activityLedgerDao().saveActivityConsequence(
            ActivityConsequenceEntity(
                activityId = "accepted",
                classification = "extra_activity",
                distanceDifferenceMeters = 2_000,
                durationDifferenceSeconds = null,
                actualLoadMeters = 2_000,
                assessment = "conservative",
                recommendedDecision = "keep_plan",
                resolvedAtEpochMillis = 1_000,
                deviation = "unplanned",
                loadMetric = "distance",
                risk = "conservative",
                appliedDecision = "keep_plan",
            ),
        )

        val result = LocalActivityReviewRepository(database, nowEpochMillis = { 2_000 })
            .updateFeedback("accepted", feltHard = true, pain = false)

        assertEquals(true, assertFeedbackUpdated(result).appliedDecisionPreserved)
        val stored = requireNotNull(database.activityLedgerDao().activityConsequence("accepted"))
        assertEquals("keep_plan", stored.appliedDecision)
        assertEquals(1_000L, stored.resolvedAtEpochMillis)
        assertNotNull(database.activityLedgerDao().activityFeedback("accepted"))
    }

    @Test
    fun linkPersistsReviewFeedbackInTheSameAcceptanceTransaction() = runBlocking {
        database.activityLedgerDao().saveActivity(
            activity("linked-now", reviewState = "review", occurredAtEpochMillis = epochMillis(today)),
        )
        seedLinkableWorkout("linked-workout", today)

        val result = LocalActivityReviewRepository(database, nowEpochMillis = { now }).link(
            activityId = "linked-now",
            workoutId = "linked-workout",
            feltHard = true,
            pain = true,
        )

        check(result is LocalActivityReviewResult.Linked)
        val feedback = requireNotNull(database.activityLedgerDao().activityFeedback("linked-now"))
        assertEquals(true, feedback.feltHard)
        assertEquals(true, feedback.pain)
        assertEquals(true, requireNotNull(database.profileSettingsDao().get()).currentPain)
    }

    @Test
    fun linkingToAnOpenRoutineRecordsActualsWithoutAPlanChange() = runBlocking {
        database.activityLedgerDao().saveActivity(
            activity("routine-linked", reviewState = "review", occurredAtEpochMillis = epochMillis(today)),
        )
        seedLinkableWorkout("routine-workout", today, phaseType = "routine", prescriptionKind = "open")

        val result = LocalActivityReviewRepository(database, nowEpochMillis = { now }).link(
            activityId = "routine-linked",
            workoutId = "routine-workout",
            feltHard = true,
            pain = true,
        )

        check(result is LocalActivityReviewResult.Linked)
        assertFalse(result.consequence.planChangeAvailable)
        assertEquals("done", database.goalPlanDao().workout("routine-workout")?.currentStatus)
        val feedback = requireNotNull(database.activityLedgerDao().workoutFeedback("routine-workout"))
        assertEquals(2_000, feedback.completedDistanceMeters)
        assertEquals(900, feedback.completedDurationSeconds)
        assertFalse(requireNotNull(database.activityLedgerDao().workoutFeedbackConsequence(feedback.feedbackId)).planChangeAvailable)
        assertEquals(true, requireNotNull(database.profileSettingsDao().get()).currentPain)
    }

    @Test
    fun routineExtraStaysFactualAndDoesNotReshapeFutureOpenWorkouts() = runBlocking {
        seedLinkableWorkout("routine-future", today.plusDays(1), phaseType = "routine", prescriptionKind = "open")
        database.activityLedgerDao().saveActivity(
            activity("routine-extra", reviewState = "review", occurredAtEpochMillis = epochMillis(today)),
        )
        val before = requireNotNull(database.goalPlanDao().workout("routine-future"))

        val result = LocalActivityReviewRepository(database, nowEpochMillis = { now })
            .confirmAsExtra("routine-extra", feltHard = true, pain = true)

        check(result is LocalActivityReviewResult.AcceptedExtra)
        assertFalse(result.consequence.planChangeAvailable)
        val consequence = requireNotNull(database.activityLedgerDao().activityConsequence("routine-extra"))
        assertFalse(consequence.planChangeAvailable)
        assertEquals(before, database.goalPlanDao().workout("routine-future"))
        assertEquals(true, requireNotNull(database.profileSettingsDao().get()).currentPain)
    }

    @Test
    fun extraAcceptancePersistsReviewFeedbackAndCanReturnToReviewBeforeAPlanChange() = runBlocking {
        database.activityLedgerDao().saveActivity(
            activity("extra-now", reviewState = "review", occurredAtEpochMillis = epochMillis(today)),
        )
        val repository = LocalActivityReviewRepository(database, nowEpochMillis = { now })

        val accepted = repository.confirmAsExtra("extra-now", feltHard = true, pain = true)

        check(accepted is LocalActivityReviewResult.AcceptedExtra)
        assertEquals(true, requireNotNull(database.activityLedgerDao().activityFeedback("extra-now")).feltHard)
        assertEquals(true, requireNotNull(database.activityLedgerDao().activityFeedback("extra-now")).pain)
        assertNotNull(database.activityLedgerDao().activityConsequence("extra-now"))
        database.activityLedgerDao().saveActivityConsequenceOption(
            ActivityConsequenceOptionEntity("extra-now", "keep_plan"),
        )

        val returned = repository.returnExtraToReview("extra-now")

        check(returned is LocalActivityReviewResult.ReturnedToReview)
        val activity = requireNotNull(database.activityLedgerDao().activity("extra-now"))
        assertEquals("review", activity.reviewState)
        assertFalse(activity.extraPlanImpactConfirmed)
        assertNull(activity.acceptedAtEpochMillis)
        assertEquals(true, requireNotNull(database.activityLedgerDao().activityFeedback("extra-now")).pain)
        assertNull(database.activityLedgerDao().activityConsequence("extra-now"))
        assertEquals(emptyList<ActivityConsequenceOptionEntity>(), database.activityLedgerDao().activityConsequenceOptions("extra-now", 10))

        val rerun = repository.returnExtraToReview("extra-now")

        check(rerun is LocalActivityReviewResult.Rejected)
        assertEquals(LocalActivityReviewIssue.ACTIVITY_NOT_ACCEPTED_EXTRA, rerun.issue)
        assertEquals("review", requireNotNull(database.activityLedgerDao().activity("extra-now")).reviewState)
        assertEquals(true, requireNotNull(database.activityLedgerDao().activityFeedback("extra-now")).pain)
    }

    @Test
    fun appliedExtraConsequenceCannotReturnToReview() = runBlocking {
        database.activityLedgerDao().saveActivity(
            activity("applied-extra", reviewState = ACTIVITY_REVIEW_STATE_ACCEPTED, extraConfirmed = true),
        )
        database.activityLedgerDao().saveActivityConsequence(
            ActivityConsequenceEntity(
                activityId = "applied-extra",
                classification = "extra_activity",
                distanceDifferenceMeters = 2_000,
                durationDifferenceSeconds = null,
                actualLoadMeters = 2_000,
                assessment = "conservative",
                recommendedDecision = "keep_plan",
                resolvedAtEpochMillis = 1_000,
                deviation = "unplanned",
                loadMetric = "distance",
                risk = "conservative",
                appliedDecision = "keep_plan",
            ),
        )

        val result = LocalActivityReviewRepository(database, nowEpochMillis = { now })
            .returnExtraToReview("applied-extra")

        check(result is LocalActivityReviewResult.Rejected)
        assertEquals(LocalActivityReviewIssue.DERIVED_PLAN_CHANGE_REQUIRES_REVERSAL, result.issue)
        assertEquals(ACTIVITY_REVIEW_STATE_ACCEPTED, requireNotNull(database.activityLedgerDao().activity("applied-extra")).reviewState)
    }

    @Test
    fun historicalAcceptedPainRemainsActivityHistoryAndDoesNotSetCurrentPain() = runBlocking {
        database.activityLedgerDao().saveActivity(
            activity(
                id = "old-accepted",
                reviewState = ACTIVITY_REVIEW_STATE_ACCEPTED,
                extraConfirmed = true,
                occurredAtEpochMillis = epochMillis(today.minusDays(8)),
            ),
        )

        val result = LocalActivityReviewRepository(database, nowEpochMillis = { now })
            .updateFeedback("old-accepted", feltHard = false, pain = true)

        assertFalse(assertFeedbackUpdated(result).appliedDecisionPreserved)
        assertFalse(requireNotNull(database.profileSettingsDao().get()).currentPain)
        assertEquals(true, requireNotNull(database.activityLedgerDao().activityFeedback("old-accepted")).pain)
    }

    @Test
    fun extraAcceptanceUsesTheCurrentPainEvidenceWindow() = runBlocking {
        val repository = LocalActivityReviewRepository(database, nowEpochMillis = { now })
        database.activityLedgerDao().saveActivity(
            activity(
                id = "old-extra",
                reviewState = "review",
                occurredAtEpochMillis = epochMillis(today.minusDays(8)),
            ),
        )
        repository.updateFeedback("old-extra", feltHard = false, pain = true)
        repository.confirmAsExtra("old-extra")
        assertFalse(requireNotNull(database.profileSettingsDao().get()).currentPain)

        database.activityLedgerDao().saveActivity(
            activity(
                id = "recent-extra",
                reviewState = "review",
                occurredAtEpochMillis = epochMillis(today.minusDays(7)),
            ),
        )
        repository.updateFeedback("recent-extra", feltHard = false, pain = true)
        repository.confirmAsExtra("recent-extra")
        assertEquals(true, requireNotNull(database.profileSettingsDao().get()).currentPain)
    }

    @Test
    fun historicalLinkedPainDoesNotSetCurrentPain() = runBlocking {
        val activityDate = today.minusDays(8)
        seedLinkableWorkout("old-workout", activityDate)
        database.activityLedgerDao().saveActivity(
            activity(
                id = "old-linked",
                reviewState = "review",
                occurredAtEpochMillis = epochMillis(activityDate),
            ),
        )
        val repository = LocalActivityReviewRepository(database, nowEpochMillis = { now })
        repository.updateFeedback("old-linked", feltHard = false, pain = true)

        val result = repository.link("old-linked", "old-workout")

        check(result is LocalActivityReviewResult.Linked)
        assertFalse(requireNotNull(database.profileSettingsDao().get()).currentPain)
    }

    private fun assertFeedbackUpdated(
        result: LocalActivityReviewResult,
    ): LocalActivityReviewResult.FeedbackUpdated {
        check(result is LocalActivityReviewResult.FeedbackUpdated)
        return result
    }

    private fun profile() = ProfileSettingsEntity(
        timeZone = "America/Halifax",
        routeDataMode = "private",
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
        updatedAtEpochMillis = 1,
    )

    private fun activity(
        id: String,
        reviewState: String,
        extraConfirmed: Boolean = false,
        occurredAtEpochMillis: Long = 1_000,
    ) = ActivityEntity(
        activityId = id,
        source = "gpx",
        sourceRecordId = "digest-$id",
        reviewState = reviewState,
        occurredAtEpochMillis = occurredAtEpochMillis,
        durationSeconds = 900,
        distanceMeters = 2_000,
        averageHeartRateBpm = null,
        averageCadenceSpm = null,
        linkedWorkoutId = null,
        acceptedAtEpochMillis = if (reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED) 1_000 else null,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
        extraPlanImpactConfirmed = extraConfirmed,
    )

    private fun epochMillis(date: LocalDate): Long = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private suspend fun seedLinkableWorkout(
        workoutId: String,
        date: LocalDate,
        phaseType: String = "distance",
        prescriptionKind: String = "distance",
    ) {
        val goal = GoalEntity(
            goalId = "goal-$workoutId",
            title = "5K",
            targetDateEpochDay = today.plusWeeks(8).toEpochDay(),
            state = "active",
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            kind = phaseType,
            startMode = phaseType,
            raceDistanceMeters = 5_000,
            priority = "finish_healthy",
        )
        val plan = PlanEntity(
            planId = "plan-$workoutId",
            goalId = goal.goalId,
            phaseType = phaseType,
            state = "active",
            startEpochDay = date.minusDays(1).toEpochDay(),
            endEpochDay = today.plusWeeks(8).toEpochDay(),
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val week = PlanWeekEntity(
            weekId = "week-$workoutId",
            planId = plan.planId,
            ordinal = 1,
            startEpochDay = date.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toEpochDay(),
            generatedLoadMeters = 5_000,
        )
        val workout = WorkoutEntity(
            workoutId = workoutId,
            planId = plan.planId,
            weekId = week.weekId,
            position = 0,
            generatedPurpose = "Easy run",
            generatedDistanceMeters = 5_000,
            generatedDurationSeconds = null,
            currentPurpose = "Easy run",
            currentDistanceMeters = 5_000,
            currentDurationSeconds = null,
            tombstonedAtEpochMillis = null,
            updatedAtEpochMillis = now,
            generatedScheduledEpochDay = date.toEpochDay(),
            currentScheduledEpochDay = date.toEpochDay(),
            generatedWorkoutType = "easy",
            currentWorkoutType = "easy",
            generatedPrescriptionKind = prescriptionKind,
            currentPrescriptionKind = prescriptionKind,
        )
        database.goalPlanDao().saveGoal(goal)
        database.goalPlanDao().createPlanGraph(plan, listOf(week), listOf(workout))
    }
}
