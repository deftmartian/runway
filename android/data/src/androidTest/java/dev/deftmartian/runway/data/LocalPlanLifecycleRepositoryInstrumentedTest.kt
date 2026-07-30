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
class LocalPlanLifecycleRepositoryInstrumentedTest {
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
    fun reviewUsesOnlyAcceptedLinkedActivitiesAndContinuationRetryAddsOneWeek() = runBlocking {
        seedCalibrationPlan()
        val activityDao = database.activityLedgerDao()
        listOf("workout-1", "workout-2", "workout-3", "workout-4").forEachIndexed { index, workoutId ->
            activityDao.saveActivity(activity("accepted-$index", workoutId, "accepted", 3_000))
        }
        activityDao.saveActivity(activity("unlinked", null, "accepted", 99_000))
        activityDao.saveActivity(activity("pending", "workout-4", "review", 99_000))

        val repository = LocalPlanLifecycleRepository(database)
        val review = repository.phaseReview("phase-plan", todayEpochDay = 113)
        assertTrue(review is LocalPhaseReviewResult.Available)
        val baseline = (review as LocalPhaseReviewResult.Available).review.baseline
        assertEquals(4, baseline.activityCount)
        assertEquals(12_000, baseline.totalDistanceMeters)
        assertEquals(6_000, baseline.weeklyDistanceMeters)

        assertEquals(
            LocalPlanLifecycleResult.ConfirmationRequired,
            repository.confirmRaceBaseline(
                LocalRaceBaselineConfirmationRequest(
                    phasePlanId = "phase-plan",
                    newPlanId = "distance-plan",
                    operationId = "confirm-baseline",
                    todayEpochDay = 113,
                    occurredAtEpochMillis = 1_500,
                    explicitlyConfirmed = false,
                ),
            ),
        )
        assertEquals(false, database.goalPlanDao().planExists("distance-plan"))

        val request = LocalContinuePhaseRequest(
            planId = "phase-plan",
            operationId = "continue-once",
            todayEpochDay = 113,
            occurredAtEpochMillis = 2_000,
        )
        assertEquals(
            LocalPlanLifecycleResult.PhaseContinued("phase-plan", 116, alreadyApplied = false),
            repository.continueBeginnerPhase(request),
        )
        assertEquals(3, database.goalPlanDao().weeksForPlan("phase-plan", 100).size)
        assertEquals(
            LocalPlanLifecycleResult.PhaseContinued("phase-plan", 116, alreadyApplied = true),
            repository.continueBeginnerPhase(request),
        )
        assertEquals(3, database.goalPlanDao().weeksForPlan("phase-plan", 100).size)
    }

    @Test
    fun delayedBaselineAndContinuationStartOnTheNextSchedulingBoundary() = runBlocking {
        seedCalibrationPlan()
        val activityDao = database.activityLedgerDao()
        listOf("workout-1", "workout-2", "workout-3", "workout-4").forEachIndexed { index, workoutId ->
            activityDao.saveActivity(activity("accepted-$index", workoutId, "accepted", 3_000))
        }
        val repository = LocalPlanLifecycleRepository(database)

        val delayedToday = 126L
        assertEquals(
            LocalPlanLifecycleResult.RacePlanStarted("distance-plan", alreadyApplied = false),
            repository.confirmRaceBaseline(
                LocalRaceBaselineConfirmationRequest(
                    phasePlanId = "phase-plan",
                    newPlanId = "distance-plan",
                    operationId = "delayed-confirmation",
                    todayEpochDay = delayedToday,
                    occurredAtEpochMillis = 2_000,
                    explicitlyConfirmed = true,
                ),
            ),
        )
        val racePlan = database.goalPlanDao().plan("distance-plan") ?: error("race plan missing")
        assertEquals(130L, racePlan.startEpochDay)
        assertTrue(
            database.goalPlanDao().allWorkoutsForPlan("distance-plan", 1_000)
                .all { it.currentScheduledEpochDay >= racePlan.startEpochDay },
        )

    }

    @Test
    fun delayedContinuationStartsOnTheNextSchedulingBoundary() = runBlocking {
        seedCalibrationPlan()
        assertEquals(
            LocalPlanLifecycleResult.PhaseContinued("phase-plan", 136, alreadyApplied = false),
            LocalPlanLifecycleRepository(database).continueBeginnerPhase(
                LocalContinuePhaseRequest("phase-plan", "delayed-continuation", 130, 3_000),
            ),
        )
        val week = database.goalPlanDao().weeksForPlan("phase-plan", 100).maxBy { it.ordinal }
        assertEquals(130L, week.startEpochDay)
        assertTrue(
            database.goalPlanDao().workoutsForWeek(week.weekId, 100)
                .all { it.currentScheduledEpochDay >= week.startEpochDay },
        )
    }

    private suspend fun seedCalibrationPlan() {
        database.profileSettingsDao().replaceOnboardingInputs(profile(), listOf(1, 3, 6))
        database.goalPlanDao().saveGoal(
            GoalEntity(
                "race-goal",
                "5K",
                180,
                "active",
                1_000,
                1_000,
                "race",
                "calibration",
                5_000,
                "finish_healthy",
            ),
        )
        val plan = PlanEntity(
            "phase-plan",
            "race-goal",
            "calibration",
            "active",
            100,
            113,
            1_000,
            1_000,
        )
        val weeks = listOf(
            PlanWeekEntity("week-1", plan.planId, 0, 100, 6_000),
            PlanWeekEntity("week-2", plan.planId, 1, 107, 6_000),
        )
        val workouts = listOf(
            workout("workout-1", "week-1", 100, 0),
            workout("workout-2", "week-1", 103, 1),
            workout("workout-3", "week-2", 107, 0),
            workout("workout-4", "week-2", 110, 1),
        )
        database.goalPlanDao().createPlanGraph(plan, weeks, workouts)
    }

    private fun workout(id: String, weekId: String, day: Long, position: Int) = WorkoutEntity(
        workoutId = id,
        planId = "phase-plan",
        weekId = weekId,
        position = position,
        generatedPurpose = "Easy run",
        generatedDistanceMeters = 3_000,
        generatedDurationSeconds = 1_500,
        currentPurpose = "Easy run",
        currentDistanceMeters = 3_000,
        currentDurationSeconds = 1_500,
        tombstonedAtEpochMillis = null,
        updatedAtEpochMillis = 1_000,
        generatedScheduledEpochDay = day,
        currentScheduledEpochDay = day,
        generatedWorkoutType = "easy",
        currentWorkoutType = "easy",
        generatedPrescriptionKind = "distance",
        currentPrescriptionKind = "distance",
    )

    private fun activity(id: String, workoutId: String?, state: String, distance: Int) = ActivityEntity(
        activityId = id,
        source = "test",
        sourceRecordId = id,
        reviewState = state,
        occurredAtEpochMillis = 1_000,
        durationSeconds = 1_500,
        distanceMeters = distance,
        averageHeartRateBpm = null,
        averageCadenceSpm = null,
        linkedWorkoutId = workoutId,
        acceptedAtEpochMillis = if (state == "accepted") 1_000 else null,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
    )

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
        updatedAtEpochMillis = 1_000,
    )
}
