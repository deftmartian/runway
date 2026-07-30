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

        assertTrueFeedbackUpdated(result)
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

        assertTrueFeedbackUpdated(result)
        val stored = requireNotNull(database.activityLedgerDao().activityConsequence("accepted"))
        assertEquals("keep_plan", stored.appliedDecision)
        assertEquals(1_000L, stored.resolvedAtEpochMillis)
        assertNotNull(database.activityLedgerDao().activityFeedback("accepted"))
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

        assertTrueFeedbackUpdated(result)
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

    private fun assertTrueFeedbackUpdated(result: LocalActivityReviewResult) {
        check(result is LocalActivityReviewResult.FeedbackUpdated)
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

    private suspend fun seedLinkableWorkout(workoutId: String, date: LocalDate) {
        val goal = GoalEntity(
            goalId = "goal-$workoutId",
            title = "5K",
            targetDateEpochDay = today.plusWeeks(8).toEpochDay(),
            state = "active",
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            kind = "race",
            startMode = "established",
            raceDistanceMeters = 5_000,
            priority = "finish_healthy",
        )
        val plan = PlanEntity(
            planId = "plan-$workoutId",
            goalId = goal.goalId,
            phaseType = "distance",
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
            generatedPrescriptionKind = "distance",
            currentPrescriptionKind = "distance",
        )
        database.goalPlanDao().saveGoal(goal)
        database.goalPlanDao().createPlanGraph(plan, listOf(week), listOf(workout))
    }
}
