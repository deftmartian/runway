package dev.deftmartian.runway

import dev.deftmartian.runway.data.LocalLoadReadModel
import dev.deftmartian.runway.data.LocalCalendarReadModel
import dev.deftmartian.runway.data.LocalCurrentSignalReadModel
import dev.deftmartian.runway.data.LocalHealthNoticeReadModel
import dev.deftmartian.runway.data.LocalHistoryWeekReadModel
import dev.deftmartian.runway.data.LocalHistoryWorkoutReadModel
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
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSurfaceNativeMapperTest {
    @Test
    fun `calendar projection carries the active routine discriminator without inference`() {
        val native = LocalCalendarReadModel(
            fromEpochDay = 20_000,
            throughEpochDay = 20_030,
            activePlanId = "routine-plan",
            profileExists = true,
            pendingDecisionCount = 0,
            pendingDecisionCountIsExact = true,
            hasMoreActivities = false,
            days = emptyList(),
            activePlanPhase = LocalPlanPhase.ROUTINE,
        ).toNativeCalendar()

        assertEquals("routine", native.activePlanPhase)
    }

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
            profileExists = true,
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
    fun `stats keeps completed setup distinct from an active plan`() {
        val native = LocalStatsReadModel(
            weeks = emptyList(),
            profileExists = true,
            recordedTotals = emptyList(),
            totalRuns = 0,
            totalDistanceMeters = 0,
            totalDurationSeconds = 0,
            longestRunMeters = null,
            weightedPaceSecondsPerKilometre = null,
            durationWeightedHeartRateBpm = null,
            isComplete = true,
        ).toNativeStats()

        assertEquals(false, native.onboardingRequired)
        assertEquals(null, native.active)
    }

    @Test
    fun `routine cadence survives the stats projection`() {
        val native = LocalStatsReadModel(
            weeks = listOf(
                LocalWeekStatsReadModel(
                    planId = "routine-plan",
                    planState = LocalPlanState.ACTIVE,
                    phase = LocalPlanPhase.ROUTINE,
                    weekOrdinal = 1,
                    startEpochDay = 20_000,
                    generated = LocalLoadReadModel(null, null),
                    current = LocalLoadReadModel(null, null),
                    actual = LocalLoadReadModel(null, null),
                    plannedRuns = 2,
                    completedRuns = 3,
                    missedRuns = 0,
                    painFlags = 0,
                    hardFlags = 0,
                    weightedPaceSecondsPerKilometre = null,
                    durationWeightedHeartRateBpm = null,
                    skippedRuns = 0,
                    plannedRunsRecorded = 1,
                    extraRuns = 2,
                ),
            ),
            profileExists = true,
            recordedTotals = emptyList(),
            totalRuns = 0,
            totalDistanceMeters = 0,
            totalDurationSeconds = 0,
            longestRunMeters = null,
            weightedPaceSecondsPerKilometre = null,
            durationWeightedHeartRateBpm = null,
            isComplete = true,
            activeSessionsPerWeek = 3,
        ).toNativeStats()

        assertEquals("routine", native.active?.plan?.phase)
        assertEquals(3, native.active?.plan?.sessionsPerWeek)
        assertEquals(null, native.active?.plan?.targetDate)
        assertEquals(1, native.active?.summary?.plannedRunsRecorded)
        assertEquals(2, native.active?.summary?.extraRuns)
        assertEquals(1, native.history?.weeklySummaries?.single()?.plannedRunsRecorded)
        assertEquals(2, native.history?.weeklySummaries?.single()?.extraRuns)
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
    fun `history keeps activity paging reachable after plan paging ends`() {
        val native = LocalHistoryReadModel(
            plans = emptyList(),
            unlinkedActivities = emptyList(),
            hasMorePlans = false,
            hasMoreActivities = true,
            nextPlanOffset = null,
            nextActivityOffset = 512,
        ).toNativeHistory()

        assertEquals(null, native.history?.nextOffset)
        assertEquals(512, native.history?.nextActivityOffset)
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

    @Test
    fun `routine history detail carries its fixed weekly cadence`() {
        val detail = requireNotNull(
            LocalPlanHistoryReadModel(
                planId = "routine-plan",
                goalId = "routine-goal",
                goalTitle = "Weekly running routine",
                state = LocalPlanState.ACTIVE,
                phase = LocalPlanPhase.ROUTINE,
                startEpochDay = 20_000,
                endEpochDay = null,
                completedAtEpochMillis = null,
                archivedAtEpochMillis = null,
                plannedRuns = 3,
                completedRuns = 0,
                actual = LocalLoadReadModel(null, null),
                lifecycle = emptyList(),
                sessionsPerWeek = 3,
            ).toNativeHistoryDetail("UTC", 20_040).detail,
        )

        assertEquals("routine", detail.plan?.phase)
        assertEquals(3, detail.plan?.sessionsPerWeek)
        assertEquals(null, detail.plan?.targetDate)
    }

    @Test
    fun `history detail preserves generated current and removed workout prescriptions`() {
        val generated = timedPrescription(warmup = 300, repetitions = 4, runSeconds = 120)
        val current = timedPrescription(warmup = 120, repetitions = 3, runSeconds = 90).copy(
            workoutType = "recovery",
            purpose = "Moved after a hard day",
        )
        val plan = LocalPlanHistoryReadModel(
            planId = "plan",
            goalId = "goal",
            goalTitle = "5K plan",
            state = LocalPlanState.ARCHIVED,
            phase = LocalPlanPhase.DISTANCE,
            startEpochDay = 20_000,
            endEpochDay = 20_030,
            completedAtEpochMillis = null,
            archivedAtEpochMillis = 2_000,
            plannedRuns = 1,
            completedRuns = 0,
            actual = LocalLoadReadModel(null, null),
            lifecycle = emptyList(),
            weeks = listOf(
                LocalHistoryWeekReadModel(
                    weekId = "week",
                    ordinal = 1,
                    startEpochDay = 20_000,
                    generated = LocalLoadReadModel(null, 1_000),
                    current = LocalLoadReadModel(null, 750),
                    actual = LocalLoadReadModel(null, null),
                    riskAssessment = null,
                    isDownWeek = false,
                    isTaperWeek = false,
                    workouts = listOf(
                        LocalHistoryWorkoutReadModel(
                            workoutId = "removed",
                            status = "tombstoned",
                            generatedScheduledEpochDay = 20_001,
                            currentScheduledEpochDay = 20_003,
                            generated = generated,
                            current = current,
                            isRemoved = true,
                            result = null,
                        ),
                    ),
                ),
            ),
        )

        val workout = requireNotNull(plan.toNativeHistoryDetail("UTC", 20_040).detail)
            .weeks.single().workouts.single()

        assertTrue(workout.isRemoved == true)
        assertEquals("2024-10-05", workout.generated.scheduledDate)
        assertEquals("2024-10-07", workout.current.scheduledDate)
        assertEquals("easy", workout.generated.type)
        assertEquals("recovery", workout.current.type)
        assertEquals("Run/walk", workout.generated.purpose)
        assertEquals("Moved after a hard day", workout.current.purpose)
        assertEquals(4, workout.generated.intervalStructure?.blocks?.single()?.repetitions)
        assertEquals(3, workout.current.intervalStructure?.blocks?.single()?.repetitions)
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
