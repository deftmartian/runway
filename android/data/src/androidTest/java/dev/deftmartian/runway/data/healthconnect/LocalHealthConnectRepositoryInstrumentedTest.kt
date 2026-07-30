package dev.deftmartian.runway.data.healthconnect

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deftmartian.runway.data.ACTIVITY_REVIEW_STATE_ACCEPTED
import dev.deftmartian.runway.data.ProfileSettingsEntity
import dev.deftmartian.runway.data.RunwayLedgerDatabase
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
class LocalHealthConnectRepositoryInstrumentedTest {
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
    fun discardModeNeverPersistsRouteCoordinatesAndReplayIsIdempotent() = runBlocking {
        database.profileSettingsDao().save(profile(routeDataMode = "discard"))
        val repository = LocalHealthConnectRepository(database) { 100L }

        val first = repository.reconcile("provider", running())
        val outcome = (first as LocalHealthConnectPersistenceResult.Applied).outcome as LocalHealthConnectOutcome.NewReview
        val activity = requireNotNull(database.activityLedgerDao().activity(outcome.activity.activityId))

        assertEquals(0, activity.routePointCount)
        assertFalse(activity.routeTraceRetained)
        assertTrue(activity.routeStartEndRedacted)
        assertNull(activity.maxSpeedMetersPerSecond)
        assertTrue(database.activityLedgerDao().routeSamples(activity.activityId, 1_000).isEmpty())
        assertEquals(
            LocalHealthConnectOutcome.Unchanged("record-1"),
            (repository.reconcile("provider", running()) as LocalHealthConnectPersistenceResult.Applied).outcome,
        )
    }

    @Test
    fun acceptedCorrectionAndReviewDeletionAreAtomicAndDoNotSilentlyAcceptData() = runBlocking {
        database.profileSettingsDao().save(profile(routeDataMode = "private"))
        val repository = LocalHealthConnectRepository(database) { 100L }
        val initial = (repository.reconcile("provider", running()) as LocalHealthConnectPersistenceResult.Applied).outcome as LocalHealthConnectOutcome.NewReview
        database.activityLedgerDao().saveActivity(
            requireNotNull(database.activityLedgerDao().activity(initial.activity.activityId)).copy(
                reviewState = ACTIVITY_REVIEW_STATE_ACCEPTED,
                acceptedAtEpochMillis = 101L,
            ),
        )

        val correction = (repository.reconcile("provider", running(distanceMeters = 5_100)) as LocalHealthConnectPersistenceResult.Applied).outcome
        assertTrue(correction is LocalHealthConnectOutcome.PendingCorrection)
        assertEquals(5_000, database.activityLedgerDao().activity(initial.activity.activityId)?.distanceMeters)
        assertEquals(5_100, database.importLedgerDao().pendingHealthConnectObservation(initial.mapping.mappingId)?.distanceMeters)
        assertTrue(database.importLedgerDao().pendingHealthConnectRouteSamples(initial.mapping.mappingId, 1_000).isNotEmpty())

        // A review-only deletion, by contrast, removes the review item and permanently tombstones
        // its external mapping so a later replay cannot resurrect it.
        val reviewInitial = (repository.reconcile("provider", running(recordId = "review-record")) as LocalHealthConnectPersistenceResult.Applied).outcome as LocalHealthConnectOutcome.NewReview
        val deleted = (repository.reconcile(
            "provider",
            HealthConnectObservation.Deleted("review-record", observedAtEpochMillis = 200L),
        ) as LocalHealthConnectPersistenceResult.Applied).outcome
        assertTrue(deleted is LocalHealthConnectOutcome.DeleteReview)
        assertNull(database.activityLedgerDao().activity(reviewInitial.activity.activityId))
        assertTrue(requireNotNull(database.importLedgerDao().healthConnectMapping("provider", "review-record")).tombstonedAtEpochMillis != null)
        assertNull(database.importLedgerDao().pendingHealthConnectObservation(reviewInitial.mapping.mappingId))
    }

    @Test
    fun acceptedCorrectionCanBeAppliedOrRejectedExactlyOnce() = runBlocking {
        database.profileSettingsDao().save(profile(routeDataMode = "private"))
        val repository = LocalHealthConnectRepository(database) { 500L }

        val accepted = acceptedActivity(repository, "accept-record")
        repository.reconcile("provider", running(recordId = "accept-record", distanceMeters = 5_100))
        assertEquals(
            LocalHealthConnectPendingResolutionResult.CorrectionAccepted(accepted.activityId),
            repository.acceptPendingCorrection("provider", "accept-record"),
        )
        val corrected = requireNotNull(database.activityLedgerDao().activity(accepted.activityId))
        assertEquals(ACTIVITY_REVIEW_STATE_ACCEPTED, corrected.reviewState)
        assertEquals(101L, corrected.acceptedAtEpochMillis)
        assertEquals(5_100, corrected.distanceMeters)
        val acceptedMapping = requireNotNull(database.importLedgerDao().healthConnectMapping("provider", "accept-record"))
        assertFalse(acceptedMapping.correctionPending)
        assertFalse(acceptedMapping.deletePending)
        assertNull(database.importLedgerDao().pendingHealthConnectObservation(acceptedMapping.mappingId))
        assertTrue(database.importLedgerDao().pendingHealthConnectRouteSamples(acceptedMapping.mappingId, 1_000).isEmpty())
        assertEquals(
            LocalHealthConnectPendingResolutionResult.AlreadyResolved(
                acceptedMapping.mappingId,
                LocalHealthConnectPendingAction.Correction,
            ),
            repository.acceptPendingCorrection("provider", "accept-record"),
        )

        val rejected = acceptedActivity(repository, "reject-record")
        repository.reconcile("provider", running(recordId = "reject-record", distanceMeters = 5_200))
        assertEquals(
            LocalHealthConnectPendingResolutionResult.CorrectionRejected(rejected.activityId),
            repository.rejectPendingCorrection("provider", "reject-record"),
        )
        assertEquals(5_000, database.activityLedgerDao().activity(rejected.activityId)?.distanceMeters)
        val rejectedMapping = requireNotNull(database.importLedgerDao().healthConnectMapping("provider", "reject-record"))
        assertFalse(rejectedMapping.correctionPending)
        assertNull(database.importLedgerDao().pendingHealthConnectObservation(rejectedMapping.mappingId))
        assertEquals(
            LocalHealthConnectOutcome.Unchanged("reject-record"),
            (repository.reconcile("provider", running(recordId = "reject-record", distanceMeters = 5_200)) as LocalHealthConnectPersistenceResult.Applied).outcome,
        )
    }

    @Test
    fun providerDeletionCanDeleteOrDetachAcceptedLocalActivityExactlyOnce() = runBlocking {
        database.profileSettingsDao().save(profile(routeDataMode = "private"))
        val repository = LocalHealthConnectRepository(database) { 800L }

        val retained = acceptedActivity(repository, "retain-record")
        repository.reconcile("provider", HealthConnectObservation.Deleted("retain-record", observedAtEpochMillis = 700L))
        assertEquals(
            LocalHealthConnectPendingResolutionResult.ProviderDeletionRetained(retained.activityId),
            repository.retainLocallyAfterProviderDeletion("provider", "retain-record"),
        )
        assertEquals(ACTIVITY_REVIEW_STATE_ACCEPTED, database.activityLedgerDao().activity(retained.activityId)?.reviewState)
        val retainedMapping = requireNotNull(database.importLedgerDao().healthConnectMapping("provider", "retain-record"))
        assertEquals("detached", retainedMapping.lifecycleState)
        assertFalse(retainedMapping.correctionPending)
        assertFalse(retainedMapping.deletePending)
        assertTrue(retainedMapping.tombstonedAtEpochMillis != null)
        assertNull(database.importLedgerDao().pendingHealthConnectObservation(retainedMapping.mappingId))
        assertEquals(
            LocalHealthConnectPendingResolutionResult.AlreadyResolved(
                retainedMapping.mappingId,
                LocalHealthConnectPendingAction.SourceDelete,
            ),
            repository.retainLocallyAfterProviderDeletion("provider", "retain-record"),
        )
        assertEquals(
            LocalHealthConnectOutcome.Unchanged("retain-record"),
            (repository.reconcile("provider", running(recordId = "retain-record")) as LocalHealthConnectPersistenceResult.Applied).outcome,
        )

        val deleted = acceptedActivity(repository, "delete-record")
        repository.reconcile("provider", HealthConnectObservation.Deleted("delete-record", observedAtEpochMillis = 701L))
        assertEquals(
            LocalHealthConnectPendingResolutionResult.ProviderDeletionDeleted(deleted.activityId),
            repository.deleteFromRunwayAfterProviderDeletion("provider", "delete-record"),
        )
        assertNull(database.activityLedgerDao().activity(deleted.activityId))
        val deletedMapping = requireNotNull(database.importLedgerDao().healthConnectMapping("provider", "delete-record"))
        assertEquals("tombstoned", deletedMapping.lifecycleState)
        assertFalse(deletedMapping.correctionPending)
        assertFalse(deletedMapping.deletePending)
        assertTrue(deletedMapping.tombstonedAtEpochMillis != null)
        assertEquals(
            LocalHealthConnectPendingResolutionResult.AlreadyResolved(
                deletedMapping.mappingId,
                LocalHealthConnectPendingAction.SourceDelete,
            ),
            repository.deleteFromRunwayAfterProviderDeletion("provider", "delete-record"),
        )
    }

    @Test
    fun pendingResolutionRejectsWrongAndStaleCanonicalStateWithoutClearingIt() = runBlocking {
        database.profileSettingsDao().save(profile(routeDataMode = "private"))
        val repository = LocalHealthConnectRepository(database) { 900L }
        val accepted = acceptedActivity(repository, "stale-record")
        repository.reconcile("provider", running(recordId = "stale-record", distanceMeters = 5_100))
        val mapping = requireNotNull(database.importLedgerDao().healthConnectMapping("provider", "stale-record"))

        assertEquals(
            LocalHealthConnectPendingResolutionResult.WrongPendingAction(
                mapping.mappingId,
                LocalHealthConnectPendingAction.SourceDelete,
                LocalHealthConnectPendingAction.Correction,
            ),
            repository.retainLocallyAfterProviderDeletion("provider", "stale-record"),
        )
        database.activityLedgerDao().saveActivity(
            requireNotNull(database.activityLedgerDao().activity(accepted.activityId)).copy(
                reviewState = "review",
                acceptedAtEpochMillis = null,
            ),
        )
        assertEquals(
            LocalHealthConnectPendingResolutionResult.UnexpectedActivityState(mapping.mappingId, "review"),
            repository.acceptPendingCorrection("provider", "stale-record"),
        )
        assertTrue(requireNotNull(database.importLedgerDao().healthConnectMapping("provider", "stale-record")).correctionPending)
        assertTrue(database.importLedgerDao().pendingHealthConnectObservation(mapping.mappingId) != null)
    }

    private suspend fun acceptedActivity(
        repository: LocalHealthConnectRepository,
        recordId: String,
    ): LocalHealthConnectActivity = ((repository.reconcile("provider", running(recordId = recordId)) as LocalHealthConnectPersistenceResult.Applied)
        .outcome as LocalHealthConnectOutcome.NewReview).also { initial ->
        database.activityLedgerDao().saveActivity(
            requireNotNull(database.activityLedgerDao().activity(initial.activity.activityId)).copy(
                reviewState = ACTIVITY_REVIEW_STATE_ACCEPTED,
                acceptedAtEpochMillis = 101L,
            ),
        )
    }.activity

    private fun profile(routeDataMode: String) = ProfileSettingsEntity(
        timeZone = "America/Halifax",
        routeDataMode = routeDataMode,
        heartRateSettingsSource = "custom",
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
        updatedAtEpochMillis = 1L,
    )

    private fun running(recordId: String = "record-1", distanceMeters: Int = 5_000) =
        HealthConnectObservation.RunningUpsert(
            recordId = recordId,
            provider = "provider",
            runningType = LocalHealthConnectRunningType.Running,
            originKey = "org.example.tracker",
            originLabel = "Tracker",
            startedAtEpochMillis = 1_000L,
            durationSeconds = 1_800,
            distanceMeters = distanceMeters,
            averageHeartRateBpm = 145,
            averageSpeedMetersPerSecond = 2.8,
            heartRate = listOf(LocalHealthConnectHeartRatePoint(0, 140)),
            heartRateSourceSampleCount = 1,
            route = listOf(LocalHealthConnectRoutePoint(0, 45_000_000, -63_000_000)),
            routeSourcePointCount = 1,
        )
}
