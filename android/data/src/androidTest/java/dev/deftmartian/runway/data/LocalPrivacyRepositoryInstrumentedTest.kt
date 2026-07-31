package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalPrivacyRepositoryInstrumentedTest {
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
    fun savingAnUnchangedDiscardModeDoesNotSilentlyDeleteRetainedData() = runBlocking {
        seedInconsistentRetainedData()
        val repository = LocalPrivacyRepository(database) { 2_000L }

        assertEquals(
            RouteDataModeUpdate.Unchanged(RouteDataMode.Discard),
            repository.updateRouteDataMode(RouteDataMode.Discard),
        )
        assertEquals(
            HeartRateDataModeUpdate.Unchanged(HeartRateDataMode.Discard),
            repository.updateHeartRateDataMode(HeartRateDataMode.Discard),
        )

        val activity = requireNotNull(database.activityLedgerDao().activity(ACTIVITY_ID))
        assertEquals(1, database.activityLedgerDao().routeSamples(ACTIVITY_ID, 10).size)
        assertEquals(1, database.activityLedgerDao().heartRateSamples(ACTIVITY_ID, 10).size)
        assertEquals(1, activity.routePointCount)
        assertEquals(1, activity.heartRatePointCount)
        assertEquals(true, activity.routeTraceRetained)
        assertEquals(true, activity.heartRateSeriesRetained)
        assertEquals(1_000L, requireNotNull(database.profileSettingsDao().get()).updatedAtEpochMillis)
    }

    private suspend fun seedInconsistentRetainedData() {
        database.profileSettingsDao().save(
            ProfileSettingsEntity(
                timeZone = "America/Halifax",
                routeDataMode = "discard",
                heartRateDataMode = "discard",
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
                updatedAtEpochMillis = 1_000L,
            ),
        )
        database.activityLedgerDao().saveActivity(
            ActivityEntity(
                activityId = ACTIVITY_ID,
                source = "gpx",
                sourceRecordId = "synthetic-privacy-record",
                reviewState = "accepted",
                occurredAtEpochMillis = 1_000L,
                durationSeconds = 600,
                distanceMeters = 1_000,
                averageHeartRateBpm = 120,
                averageCadenceSpm = null,
                linkedWorkoutId = null,
                acceptedAtEpochMillis = 1_000L,
                createdAtEpochMillis = 1_000L,
                updatedAtEpochMillis = 1_000L,
                maxHeartRateBpm = 130,
                routePointCount = 1,
                heartRatePointCount = 1,
                heartRateSourceSampleCount = 1,
                routeTraceRetained = true,
                heartRateSeriesRetained = true,
                extraPlanImpactConfirmed = true,
            ),
        )
        database.activityLedgerDao().insertRouteSamples(
            listOf(
                RouteSampleEntity(
                    activityId = ACTIVITY_ID,
                    ordinal = 0,
                    latitudeE6 = 0,
                    longitudeE6 = 0,
                    elapsedSeconds = 0,
                    elevationMeters = null,
                ),
            ),
        )
        database.activityLedgerDao().insertHeartRateSamples(
            listOf(
                HeartRateSampleEntity(
                    activityId = ACTIVITY_ID,
                    ordinal = 0,
                    elapsedSeconds = 0,
                    beatsPerMinute = 120,
                ),
            ),
        )
    }

    private companion object {
        const val ACTIVITY_ID = "synthetic-privacy-activity"
    }
}
