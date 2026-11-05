package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class LocalProfileStartInstrumentedTest {
    private lateinit var database: RunwayLedgerDatabase
    private val zone = ZoneId.of("America/Halifax")
    private val now = LocalDate.of(2026, 11, 4).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

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
    fun startingWithoutAPlanCreatesOnlyTheProfileAndAcceptsRuns() = runBlocking {
        val profiles = LocalProfileRepository(database) { now }

        assertEquals(LocalProfileStartResult.Started, profiles.startWithoutPlan(" America/Halifax "))

        val profile = requireNotNull(database.profileSettingsDao().get())
        assertEquals("America/Halifax", profile.timeZone)
        assertEquals("discard", profile.routeDataMode)
        assertEquals("discard", profile.heartRateDataMode)
        assertTrue(database.goalPlanDao().currentGoalIds(2).isEmpty())
        assertTrue(database.goalPlanDao().activePlanIds(2).isEmpty())

        val mutations = LocalTrainingMutationRepository(database, nowEpochMillis = { now }, newId = { "unplanned" })
        val recorded = mutations.recordManualRun(
            LocalManualRunCommand(LocalDate.of(2026, 11, 3), distanceMeters = 6_000, durationSeconds = 2_100),
        )
        assertTrue(recorded is LocalTrainingMutationResult.ManualRunRecorded)
        val review = LocalActivityReviewRepository(database, nowEpochMillis = { now })
        assertTrue(review.confirmAsExtra("unplanned") is LocalActivityReviewResult.AcceptedExtra)
        assertEquals("accepted", database.activityLedgerDao().activity("unplanned")?.reviewState)
    }

    @Test
    fun startingWithoutAPlanLeavesAnExistingProfileAndRejectsAnUnknownZone() = runBlocking {
        val profiles = LocalProfileRepository(database) { now }

        assertEquals(
            LocalProfileStartResult.Invalid(LocalProfileIssue.INVALID_TIME_ZONE),
            profiles.startWithoutPlan("Not/AZone"),
        )
        assertEquals(null, database.profileSettingsDao().get())

        profiles.startWithoutPlan("America/Halifax")
        assertEquals(LocalProfileStartResult.AlreadyStarted, profiles.startWithoutPlan("Europe/Berlin"))
        assertEquals("America/Halifax", database.profileSettingsDao().get()?.timeZone)
    }
}
