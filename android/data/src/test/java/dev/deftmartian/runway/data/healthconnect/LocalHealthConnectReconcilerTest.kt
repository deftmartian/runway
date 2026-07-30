package dev.deftmartian.runway.data.healthconnect

import dev.deftmartian.runway.data.ActivityEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHealthConnectReconcilerTest {
    @Test
    fun `new record creates review mapping with stable external identity`() {
        val outcome = LocalHealthConnectReconciler.reduce(run(), empty()) as LocalHealthConnectOutcome.NewReview

        assertEquals("record-1", outcome.mapping.externalRecordId)
        assertEquals(outcome.activity.activityId, outcome.mapping.activityId)
        assertEquals(localHealthConnectActivityId("provider", "record-1"), outcome.activity.activityId)
        assertTrue(outcome.mapping.mappingId.matches(UUID_V5))
    }

    @Test
    fun `exact replay is unchanged`() {
        val initial = LocalHealthConnectReconciler.reduce(run(), empty()) as LocalHealthConnectOutcome.NewReview
        val replay = LocalHealthConnectReconciler.reduce(
            run(),
            LocalHealthConnectRecordState(
                mapping = initial.mapping,
                activity = LocalHealthConnectMappedActivity(initial.activity.activityId, LocalActivityReviewState.Review),
            ),
        )

        assertEquals(LocalHealthConnectOutcome.Unchanged("record-1"), replay)
    }

    @Test
    fun `accepted activity correction remains pending rather than overwriting activity`() {
        val initial = LocalHealthConnectReconciler.reduce(run(), empty()) as LocalHealthConnectOutcome.NewReview
        val outcome = LocalHealthConnectReconciler.reduce(
            run(distanceMeters = 5_100),
            LocalHealthConnectRecordState(
                mapping = initial.mapping,
                activity = LocalHealthConnectMappedActivity(initial.activity.activityId, LocalActivityReviewState.Accepted),
            ),
        ) as LocalHealthConnectOutcome.PendingCorrection

        assertEquals(initial.activity.activityId, outcome.activityId)
        assertEquals(LocalHealthConnectPendingAction.Correction, outcome.mapping.pendingAction)
        assertEquals(5_100, outcome.proposed.distanceMeters)
    }

    @Test
    fun `an explicitly observed route change remains a pending correction`() {
        val initial = LocalHealthConnectReconciler.reduce(run(), empty()) as LocalHealthConnectOutcome.NewReview
        val outcome = LocalHealthConnectReconciler.reduce(
            observation = run(),
            state = LocalHealthConnectRecordState(
                mapping = initial.mapping,
                activity = LocalHealthConnectMappedActivity(
                    initial.activity.activityId,
                    LocalActivityReviewState.Accepted,
                ),
            ),
            routeChanged = true,
        )

        assertTrue(outcome is LocalHealthConnectOutcome.PendingCorrection)
    }

    @Test
    fun `review activity is updated and accepted deletion requires retain or delete decision`() {
        val initial = LocalHealthConnectReconciler.reduce(run(), empty()) as LocalHealthConnectOutcome.NewReview
        val updated = LocalHealthConnectReconciler.reduce(
            run(distanceMeters = 5_100),
            LocalHealthConnectRecordState(initial.mapping, LocalHealthConnectMappedActivity(initial.activity.activityId, LocalActivityReviewState.Review)),
        ) as LocalHealthConnectOutcome.ReviewUpdate
        val deletion = LocalHealthConnectReconciler.reduce(
            HealthConnectObservation.Deleted("record-1", observedAtEpochMillis = 99),
            LocalHealthConnectRecordState(updated.mapping, LocalHealthConnectMappedActivity(initial.activity.activityId, LocalActivityReviewState.Accepted)),
        ) as LocalHealthConnectOutcome.PendingDelete

        assertEquals(initial.activity.activityId, updated.activity.activityId)
        assertEquals(setOf(LocalHealthConnectDeleteDecision.DeleteFromRunway, LocalHealthConnectDeleteDecision.RetainInRunway), deletion.decisions)
        assertEquals(LocalHealthConnectPendingAction.SourceDelete, deletion.mapping.pendingAction)
    }

    @Test
    fun `review deletion removes activity and tombstoned replay stays unchanged`() {
        val initial = LocalHealthConnectReconciler.reduce(run(), empty()) as LocalHealthConnectOutcome.NewReview
        val deleted = LocalHealthConnectReconciler.reduce(
            HealthConnectObservation.Deleted("record-1", observedAtEpochMillis = 99),
            LocalHealthConnectRecordState(initial.mapping, LocalHealthConnectMappedActivity(initial.activity.activityId, LocalActivityReviewState.Review)),
        ) as LocalHealthConnectOutcome.DeleteReview
        val replay = LocalHealthConnectReconciler.reduce(
            run(distanceMeters = 5_100),
            LocalHealthConnectRecordState(deleted.mapping, null, tombstoned = true),
        )

        assertEquals(initial.activity.activityId, deleted.activityId)
        assertEquals(LocalHealthConnectOutcome.Unchanged("record-1"), replay)
    }

    @Test
    fun `new record retains duplicate candidate without discarding Health Connect activity`() {
        val outcome = LocalHealthConnectReconciler.reduce(
            run(),
            empty().copy(duplicateCandidateActivityId = "manual-activity"),
        ) as LocalHealthConnectOutcome.DuplicateCandidate

        assertEquals("manual-activity", outcome.existingActivityId)
        assertEquals("manual-activity", outcome.mapping.duplicateCandidateActivityId)
        assertFalse(outcome.activity.activityId == outcome.existingActivityId)
    }

    @Test
    fun `duplicate matcher requires a close start distance and duration from a non Health Connect activity`() {
        val observation = run()

        assertTrue(activity().isConservativeDuplicateOf(observation))
        assertFalse(activity(occurredAtEpochMillis = observation.startedAtEpochMillis + 600_001).isConservativeDuplicateOf(observation))
        assertFalse(activity(distanceMeters = 5_201).isConservativeDuplicateOf(observation))
        assertFalse(activity(durationSeconds = 1_981).isConservativeDuplicateOf(observation))
        assertFalse(activity(source = "health_connect").isConservativeDuplicateOf(observation))
        assertFalse(activity(distanceMeters = null).isConservativeDuplicateOf(observation))
    }

    private fun empty() = LocalHealthConnectRecordState(null, null)

    private fun run(distanceMeters: Int = 5_000) = HealthConnectObservation.RunningUpsert(
        recordId = "record-1",
        provider = "provider",
        runningType = LocalHealthConnectRunningType.Running,
        originKey = "org.example.tracker",
        originLabel = "Tracker",
        startedAtEpochMillis = 1_000,
        durationSeconds = 1_800,
        distanceMeters = distanceMeters,
        averageHeartRateBpm = 145,
        heartRate = listOf(LocalHealthConnectHeartRatePoint(0, 140)),
        route = listOf(LocalHealthConnectRoutePoint(0, 45_000_000, -63_000_000)),
    )

    private fun activity(
        source: String = "manual",
        occurredAtEpochMillis: Long = 1_000,
        distanceMeters: Int? = 5_100,
        durationSeconds: Int? = 1_900,
    ) = ActivityEntity(
        activityId = "candidate-$source-$occurredAtEpochMillis-$distanceMeters-$durationSeconds",
        source = source,
        sourceRecordId = "candidate-record",
        reviewState = "accepted",
        occurredAtEpochMillis = occurredAtEpochMillis,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        averageHeartRateBpm = null,
        averageCadenceSpm = null,
        linkedWorkoutId = null,
        acceptedAtEpochMillis = 1,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private companion object {
        val UUID_V5 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}
