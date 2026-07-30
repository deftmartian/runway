package dev.deftmartian.runway

import dev.deftmartian.runway.data.LocalLoadReadModel
import dev.deftmartian.runway.data.LocalCurrentSignalReadModel
import dev.deftmartian.runway.data.LocalHealthNoticeReadModel
import dev.deftmartian.runway.data.LocalHistoryAdjustmentReadModel
import dev.deftmartian.runway.data.LocalHistoryReadModel
import dev.deftmartian.runway.data.LocalPlanPhase
import dev.deftmartian.runway.data.LocalPlanHistoryReadModel
import dev.deftmartian.runway.data.LocalPlanState
import dev.deftmartian.runway.data.LocalPrescriptionReadModel
import dev.deftmartian.runway.data.LocalStatsReadModel
import dev.deftmartian.runway.data.LocalTimedBlockReadModel
import dev.deftmartian.runway.data.LocalTimedIntervalStructureReadModel
import dev.deftmartian.runway.data.LocalTimedSegmentReadModel
import dev.deftmartian.runway.data.LocalWeekStatsReadModel
import dev.deftmartian.runway.data.LocalWorkoutAdjustmentReadModel
import dev.deftmartian.runway.data.LocalWorkoutReadModel
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSurfaceNativeMapperTest {
    @Test
    fun `workout projection carries distinct generated and current timed structures`() {
        val generated = timedPrescription(warmup = 300, repetitions = 4, runSeconds = 120)
        val current = timedPrescription(warmup = 120, repetitions = 3, runSeconds = 90)

        val native = LocalWorkoutReadModel(
            workoutId = "timed",
            planId = "plan",
            weekOrdinal = 1,
            scheduledEpochDay = 20_000,
            status = "planned",
            isRest = false,
            isEdited = true,
            generated = generated,
            current = current,
            actual = null,
        ).toNativeWorkout()

        assertEquals(4, native.generatedIntervalStructure?.blocks?.single()?.repetitions)
        assertEquals(120, native.generatedIntervalStructure?.blocks?.single()?.segments?.first()?.durationSeconds)
        assertEquals(3, native.intervalStructure?.blocks?.single()?.repetitions)
        assertEquals(90, native.intervalStructure?.blocks?.single()?.segments?.first()?.durationSeconds)
    }

    @Test
    fun `workout projection carries persisted undo identity`() {
        val prescription = timedPrescription(warmup = 60, repetitions = 2, runSeconds = 90)
        val native = LocalWorkoutReadModel(
            workoutId = "adjusted",
            planId = "plan",
            weekOrdinal = 1,
            scheduledEpochDay = 20_000,
            status = "planned",
            isRest = false,
            isEdited = true,
            generated = prescription,
            current = prescription,
            actual = null,
            adjustment = LocalWorkoutAdjustmentReadModel(
                workoutId = "adjusted",
                adjustmentId = "adjustment-1",
                kind = "edit",
                createdAtEpochMillis = 1,
            ),
        ).toNativeWorkout()

        assertEquals("adjustment-1", native.adjustment?.id)
        assertEquals("edit", native.adjustment?.kind)
    }

    @Test
    fun `stats projection keeps weekly accepted context instead of erasing it`() {
        val native = LocalStatsReadModel(
            weeks = listOf(
                LocalWeekStatsReadModel(
                    planId = "active-plan",
                    planState = LocalPlanState.ACTIVE,
                    phase = LocalPlanPhase.DISTANCE,
                    weekOrdinal = 3,
                    startEpochDay = 20_000,
                    generated = LocalLoadReadModel(12_000, null),
                    current = LocalLoadReadModel(11_000, null),
                    actual = LocalLoadReadModel(10_000, 3_500),
                    plannedRuns = 3,
                    completedRuns = 2,
                    missedRuns = 1,
                    painFlags = 1,
                    hardFlags = 2,
                    weightedPaceSecondsPerKilometre = 350.0,
                    durationWeightedHeartRateBpm = 151,
                    skippedRuns = 2,
                ),
            ),
            recordedTotals = emptyList(),
            totalRuns = 2,
            totalDistanceMeters = 10_000,
            totalDurationSeconds = 3_500,
            longestRunMeters = 6_000,
            weightedPaceSecondsPerKilometre = 350.0,
            durationWeightedHeartRateBpm = 151,
            isComplete = true,
            currentSignal = LocalCurrentSignalReadModel(
                risk = "unsafe",
                reasons = listOf("Pain was reported on the latest accepted run."),
                source = "activity",
                healthNotice = LocalHealthNoticeReadModel(
                    level = "paused",
                    heading = "Pain is present now",
                    message = "The schedule is not clearance to continue.",
                ),
            ),
            todayEpochDay = 20_003,
        ).toNativeStats()

        val week = requireNotNull(native.history).weeklySummaries.single()
        assertEquals(1, native.active?.summary?.painFlags)
        assertEquals(1, week.painFlags)
        assertEquals(2, week.hardFlags)
        assertEquals(2, week.skippedRuns)
        assertEquals(350.0, week.averagePaceSecondsPerKm)
        assertEquals(151, week.averageHeartRate)
        assertEquals("unsafe", requireNotNull(native.history).currentSignal?.risk)
        assertEquals("Pain is present now", native.history.currentSignal?.healthNotice?.heading)
    }

    @Test
    fun `history summary keeps overdue skipped and pain counts distinct`() {
        val plan = LocalPlanHistoryReadModel(
            planId = "plan",
            goalId = "goal",
            goalTitle = "Foundation",
            state = LocalPlanState.ACTIVE,
            phase = LocalPlanPhase.FOUNDATION,
            startEpochDay = 20_000,
            endEpochDay = 20_060,
            completedAtEpochMillis = null,
            archivedAtEpochMillis = null,
            plannedRuns = 27,
            completedRuns = 2,
            actual = LocalLoadReadModel(null, 3_100),
            lifecycle = emptyList(),
            missedRuns = 1,
            skippedRuns = 2,
            painFlags = 1,
        )

        val native = LocalHistoryReadModel(
            plans = listOf(plan),
            unlinkedActivities = emptyList(),
            hasMorePlans = false,
            hasMoreActivities = false,
        ).toNativeHistory()

        val summary = requireNotNull(native.activeItem?.summary)
        assertEquals(1, summary.missedRuns)
        assertEquals(2, summary.skippedRuns)
        assertEquals(1, summary.painFlags)
    }

    @Test
    fun `history detail projection carries auditable effect and reversal`() {
        val native = LocalPlanHistoryReadModel(
            planId = "plan",
            goalId = "goal",
            goalTitle = "5K plan",
            state = LocalPlanState.ARCHIVED,
            phase = LocalPlanPhase.DISTANCE,
            startEpochDay = 20_000,
            endEpochDay = 20_030,
            completedAtEpochMillis = null,
            archivedAtEpochMillis = 2_000,
            plannedRuns = 3,
            completedRuns = 2,
            actual = LocalLoadReadModel(9_000, 3_100),
            lifecycle = emptyList(),
            adjustments = listOf(
                LocalHistoryAdjustmentReadModel(
                    id = "effect",
                    triggerType = "edit",
                    createdAtEpochMillis = 1_000,
                    reversedAtEpochMillis = 2_000,
                    reversalReason = "Changed my mind",
                    reason = "Moved around work",
                    scheduledEpochDay = 20_005,
                    workoutType = "easy",
                    prescriptionKind = "distance",
                    distanceMeters = 4_500,
                    durationSeconds = null,
                    removed = false,
                ),
            ),
        ).toNativeHistoryDetail("UTC", 20_040)

        val detail = requireNotNull(native.detail)
        val change = detail.timeline.single()
        assertEquals("effect", change.id)
        assertEquals("edit", change.triggerType)
        assertEquals("Changed my mind", change.reversalReason)
        assertEquals(4_500.0, change.newState?.targetDistanceMeters)
        assertEquals("1970-01-01", detail.cutoffDate)
    }

    private fun timedPrescription(
        warmup: Int,
        repetitions: Int,
        runSeconds: Int,
    ) = LocalPrescriptionReadModel(
        workoutType = "easy",
        prescriptionKind = "timed",
        load = LocalLoadReadModel(distanceMeters = null, durationSeconds = 1_000),
        intensity = "easy",
        purpose = "Run/walk",
        reason = null,
        warmupSeconds = warmup,
        cooldownSeconds = 60,
        intervalStructure = LocalTimedIntervalStructureReadModel(
            warmupSeconds = warmup,
            cooldownSeconds = 60,
            blocks = listOf(
                LocalTimedBlockReadModel(
                    repetitions = repetitions,
                    segments = listOf(
                        LocalTimedSegmentReadModel("run", runSeconds),
                        LocalTimedSegmentReadModel("walk", 60),
                    ),
                ),
            ),
        ),
    )
}
