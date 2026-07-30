package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    ) = ActivityEntity(
        activityId = id,
        source = "gpx",
        sourceRecordId = "digest-$id",
        reviewState = reviewState,
        occurredAtEpochMillis = 1_000,
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
}
