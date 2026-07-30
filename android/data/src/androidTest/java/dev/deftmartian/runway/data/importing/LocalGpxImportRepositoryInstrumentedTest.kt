package dev.deftmartian.runway.data.importing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deftmartian.runway.data.ProfileSettingsEntity
import dev.deftmartian.runway.data.RunwayLedgerDatabase
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalGpxImportRepositoryInstrumentedTest {
    private lateinit var database: RunwayLedgerDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RunwayLedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun freshPrivacyDefaultsDiscardRouteAndHeartRateAtThePersistenceBoundary() = runBlocking {
        database.profileSettingsDao().save(profile(routeMode = "discard", heartRateMode = "discard"))
        val outcome = LocalGpxImportRepository(database) { NOW_EPOCH_MILLIS }
            .import(ByteArrayInputStream(gpx("45.001").toByteArray()))
        val activityId = (outcome as LocalGpxImportOutcome.Imported).activityId
        val activity = requireNotNull(database.activityLedgerDao().activity(activityId))

        assertEquals(0, activity.routePointCount)
        assertFalse(activity.routeTraceRetained)
        assertTrue(activity.routeStartEndRedacted)
        assertEquals(0, activity.heartRatePointCount)
        assertEquals(0, activity.heartRateSourceSampleCount)
        assertFalse(activity.heartRateSeriesRetained)
        assertNull(activity.averageHeartRateBpm)
        assertNull(activity.maxHeartRateBpm)
        assertTrue(database.activityLedgerDao().routeSamples(activityId, 10).isEmpty())
        assertTrue(database.activityLedgerDao().heartRateSamples(activityId, 10).isEmpty())
    }

    @Test
    fun explicitPrivateRetentionKeepsBoundedRouteAndHeartRateEvidence() = runBlocking {
        database.profileSettingsDao().save(profile(routeMode = "private", heartRateMode = "private"))
        val outcome = LocalGpxImportRepository(database) { NOW_EPOCH_MILLIS }
            .import(ByteArrayInputStream(gpx("45.002").toByteArray()))
        val activityId = (outcome as LocalGpxImportOutcome.Imported).activityId
        val activity = requireNotNull(database.activityLedgerDao().activity(activityId))

        assertTrue(activity.routeTraceRetained)
        assertTrue(activity.heartRateSeriesRetained)
        assertEquals(2, database.activityLedgerDao().routeSamples(activityId, 10).size)
        assertEquals(2, database.activityLedgerDao().heartRateSamples(activityId, 10).size)
        assertEquals(130, activity.averageHeartRateBpm)
        assertEquals(140, activity.maxHeartRateBpm)
    }

    private fun profile(routeMode: String, heartRateMode: String) = ProfileSettingsEntity(
        timeZone = "America/Halifax",
        routeDataMode = routeMode,
        heartRateDataMode = heartRateMode,
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
        updatedAtEpochMillis = NOW_EPOCH_MILLIS,
        baselineConfirmed = false,
        confirmConcentratedSchedule = false,
    )

    private fun gpx(secondLatitude: String): String =
        """
        <gpx xmlns:gpxtpx="https://www.garmin.com/xmlschemas/TrackPointExtension/v1">
          <trk><trkseg>
            <trkpt lat="45.000" lon="-63.000">
              <time>2026-05-14T12:00:00Z</time>
              <extensions><gpxtpx:hr>120</gpxtpx:hr></extensions>
            </trkpt>
            <trkpt lat="$secondLatitude" lon="-63.000">
              <time>2026-05-14T12:01:00Z</time>
              <extensions><gpxtpx:hr>140</gpxtpx:hr></extensions>
            </trkpt>
          </trkseg></trk>
        </gpx>
        """.trimIndent()

    private companion object {
        const val NOW_EPOCH_MILLIS = 1_800_000_000_000L
    }
}
