package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeUiModelHelpersTest {

    @Test
    fun `timed prescription text preserves warmup repeats segments and cooldown`() {
        val text = formatTimedStructure(
            TimedIntervalStructureDto(
                warmupSeconds = 300,
                cooldownSeconds = 120,
                blocks = listOf(
                    TimedBlockDto(
                        repetitions = 4,
                        segments = listOf(
                            TimedSegmentDto("run", 120),
                            TimedSegmentDto("walk", 60),
                        ),
                    ),
                ),
            ),
        )

        assertEquals("Warm up 5 min · 4 × Run 2 min / Walk 1 min · Cool down 2 min", text)
    }

    @Test
    fun `interval resizing preserves structure and exact requested duration`() {
        val source = TimedIntervalStructureDto(
            warmupSeconds = 300,
            cooldownSeconds = 300,
            blocks = listOf(
                TimedBlockDto(
                    repetitions = 2,
                    segments = listOf(
                        TimedSegmentDto("run", 180),
                        TimedSegmentDto("walk", 60),
                    ),
                ),
            ),
        )

        val resized = resizeIntervalStructure(source, 1_800)

        assertEquals(listOf("run", "walk"), resized.blocks.single().segments.map { it.kind })
        assertEquals(1_800, totalSeconds(resized))
    }

    @Test
    fun `missing interval structure becomes one bounded run segment`() {
        val resized = resizeIntervalStructure(null, 900)

        assertEquals(0, resized.warmupSeconds)
        assertEquals(0, resized.cooldownSeconds)
        assertEquals(1, resized.blocks.single().repetitions)
        assertEquals("run", resized.blocks.single().segments.single().kind)
        assertEquals(900, resized.blocks.single().segments.single().durationSeconds)
        assertEquals(900, totalSeconds(resized))
    }

    @Test
    fun `calendar places an unplanned run beside its rest prescription without hiding either`() {
        val rest = workout(id = "rest-1", date = "2026-07-29", type = "rest")
        val run = activity(id = "activity-1", date = "2026-07-29")

        val placement = placeCalendarActivities(listOf(rest), listOf(run))

        assertEquals(listOf("activity-1"), placement.byWorkoutId["rest-1"]?.map { it.id })
        assertEquals(emptyList<NativeActivity>(), placement.unplaced)
    }

    @Test
    fun `calendar honors an explicit workout link before same-day placement`() {
        val rest = workout(id = "rest-1", date = "2026-07-29", type = "rest")
        val planned = workout(id = "run-1", date = "2026-07-29", type = "easy")
        val run = activity(id = "activity-1", date = "2026-07-29", workoutId = "run-1")

        val placement = placeCalendarActivities(listOf(rest, planned), listOf(run))

        assertEquals(listOf("activity-1"), placement.byWorkoutId["run-1"]?.map { it.id })
        assertEquals(null, placement.byWorkoutId["rest-1"])
    }

    @Test
    fun `linked activity consequence routes its choice to workout feedback`() {
        val activity = activity(id = "activity-1", date = "2026-07-29").copy(
            consequence = NativeConsequence(
                kind = "short",
                appliedDecision = null,
                recommendedDecision = "reduce_next",
                deviation = "short",
                risk = "conservative",
                planChangeAvailable = true,
                options = listOf("reduce_next"),
                comparisonStatus = "ready",
                sourceKind = "WorkoutFeedback",
                sourceId = "workout-feedback-activity-1",
            ),
        )

        val pending = requireNotNull(pendingActivityPlanDecision(activity, "reduce_next"))

        assertEquals("feedback", pending.source)
        assertEquals("workout-feedback-activity-1", pending.sourceId)
    }

    @Test
    fun `plan-free stats preserves recorded facts without inventing a recommendation`() {
        val recorded = NativeRecordedHistorySummary(
            totalRuns = 7,
            totalDistanceMeters = 32_100.0,
            totalDurationSeconds = 10_800.0,
            longestRunMeters = 8_100.0,
            currentPlanRuns = 0,
            currentPlanDistanceMeters = 0.0,
            archivedPlanRuns = 5,
            archivedPlanDistanceMeters = 25_100.0,
            unlinkedRuns = 2,
            unlinkedDistanceMeters = 7_000.0,
        )
        val heartRate = NativeHeartRateSample(
            windowDays = 90,
            windowStart = "2026-04-30",
            windowEnd = "2026-07-28",
            sampleCount = 4,
            averageHeartRate = 141,
            highZoneSeconds = 600.0,
            latest = NativeHeartRateObservation("2026-07-27", 144, 168),
            oldest = NativeHeartRateObservation("2026-05-02", 138, null),
        )
        val history = NativeTrainingHistory(
            weeklySummaries = emptyList(),
            todayIso = "2026-07-28",
            currentSignal = null,
            hasAcceptedActivities = true,
            recordedSummary = recorded,
            heartRateSample = heartRate,
        )

        val summary = noActiveStatsSummary(history)

        assertEquals(
            "There is no active plan or recommendation. Recorded work remains available below.",
            summary.statusMessage,
        )
        assertEquals(recorded, summary.recordedHistory)
        assertEquals(heartRate, summary.acceptedHeartRate)
    }

    @Test
    fun `plan-free stats does not imply that past plans are a recommendation`() {
        val summary = noActiveStatsSummary(history = null)

        assertEquals(
            "There is no active plan or recommendation. Add and review a run, or build a plan, to see comparisons here.",
            summary.statusMessage,
        )
        assertEquals(null, summary.recordedHistory)
        assertEquals(null, summary.acceptedHeartRate)
    }

    @Test
    fun `stats waits for recorded work before showing comparisons`() {
        val empty = NativeTrainingHistory(
            weeklySummaries = emptyList(), todayIso = "2026-07-28", currentSignal = null,
            hasAcceptedActivities = false, recordedSummary = NativeRecordedHistorySummary(
                totalRuns = 0, totalDistanceMeters = 0.0, totalDurationSeconds = 0.0,
                longestRunMeters = 0.0, currentPlanRuns = 0, currentPlanDistanceMeters = 0.0,
                archivedPlanRuns = 0, archivedPlanDistanceMeters = 0.0,
                unlinkedRuns = 0, unlinkedDistanceMeters = 0.0,
            ), heartRateSample = null,
        )

        assertFalse(hasRecordedStatsHistory(empty))
        assertTrue(hasRecordedStatsHistory(empty.copy(recentFeedbackCount = 1)))
        assertTrue(hasRecordedStatsHistory(empty.copy(hasAcceptedActivities = true)))
        assertTrue(hasRecordedStatsHistory(empty.copy(recordedSummary = empty.recordedSummary?.copy(totalRuns = 1))))
        assertTrue(
            hasRecordedStatsHistory(
                empty.copy(
                    weeklySummaries = listOf(
                        NativeWeekSummary(
                            weekNumber = 1,
                            startDate = "2026-07-27",
                            targetDistanceMeters = 5_000.0,
                            completedDistanceMeters = 0.0,
                            completedDurationSeconds = 0.0,
                            plannedRuns = 1,
                            completedRuns = 0,
                            missedRuns = 0,
                            skippedRuns = 1,
                            painFlags = 0,
                            hardFlags = 0,
                            averagePaceSecondsPerKm = null,
                            averageHeartRate = null,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun workout(id: String, date: String, type: String) = NativeWorkout(
        id = id,
        weekId = null,
        weekNumber = null,
        scheduledDate = date,
        type = type,
        status = "planned",
        targetDistanceMeters = null,
        targetDurationSeconds = null,
        prescriptionKind = if (type == "rest") "rest" else "distance",
        intervalStructure = null,
        intensity = if (type == "rest") "rest" else "easy",
        purpose = null,
        reason = null,
        isRemoved = false,
        isEdited = false,
        adjustment = null,
    )

    private fun activity(
        id: String,
        date: String,
        workoutId: String? = null,
    ) = NativeActivity(
        id = id,
        workoutId = workoutId,
        source = "manual",
        reviewState = "accepted",
        occurredDate = date,
        activityDate = date,
        distanceMeters = 3_000.0,
        durationSeconds = 1_800.0,
        averagePaceSecondsPerKm = null,
        averageHeartRate = null,
        maxHeartRate = null,
        heartRateSummary = null,
        feltHard = false,
        pain = false,
        extraPlanImpactConfirmed = true,
        consequence = null,
        routeSummary = null,
        matchedWorkoutPurpose = null,
        matchedWorkoutDate = null,
    )

    private fun totalSeconds(structure: TimedIntervalStructureDto): Int =
        (structure.warmupSeconds ?: 0) +
            (structure.cooldownSeconds ?: 0) +
            structure.blocks.sumOf { block ->
                (block.repetitions ?: 1) *
                    block.segments.sumOf { segment -> segment.durationSeconds ?: 0 }
            }
}
