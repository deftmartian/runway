package dev.deftmartian.runway.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingDecisionDomainTest {
    @Test
    fun `timed result without duration stays factual and needs review instead of becoming a shortfall`() {
        val consequence = Consequences.calculate(
            FeedbackInput(
                targetDistanceMeters = 0,
                targetDurationSeconds = 1_800,
                weekTargetDistanceMeters = 0,
                completedDistanceMeters = 4_000,
                completedDurationSeconds = null,
                status = FeedbackStatus.DONE,
            ),
        )

        assertEquals(ConsequenceKind.NEEDS_REVIEW, consequence.kind)
        assertEquals(Deviation.NOT_APPLICABLE, consequence.deviation)
        assertEquals("not_comparable", consequence.comparisonStatus)
        assertEquals(null, consequence.weeklyLoadDelta)
        assertEquals(false, consequence.planChangeAvailable)
    }

    @Test
    fun `missing native measurement still lets explicit hard effort offer a conservative choice without inventing load`() {
        val consequence = Consequences.calculate(
            FeedbackInput(
                targetDistanceMeters = 0,
                targetDurationSeconds = 1_800,
                weekTargetDistanceMeters = 0,
                completedDistanceMeters = 4_000,
                completedDurationSeconds = null,
                status = FeedbackStatus.DONE,
                feltHard = true,
            ),
        )

        assertEquals(ConsequenceKind.HARD_EFFORT, consequence.kind)
        assertEquals("not_comparable", consequence.comparisonStatus)
        assertEquals(null, consequence.weeklyLoadDelta)
        assertEquals(PlanDecision.REDUCE_NEXT, consequence.recommendedDecision)
        assertEquals(LoadDelta(LoadMetric.DURATION, -300), consequence.nextRunAdjustment)
    }

    @Test fun `pain never offers repeat and recommends rest`() {
        val result = Consequences.calculate(feedback(pain = true))
        assertEquals(ConsequenceKind.PAIN_REPORTED, result.kind)
        assertEquals(PlanDecision.NEXT_REST, result.recommendedDecision)
        assertFalse(PlanDecision.REPEAT_PRESCRIPTION in result.options)
    }

    @Test fun `timed feedback uses duration material threshold and native adjustment`() {
        val result = Consequences.calculate(feedback(targetDuration = 1_200, completedDuration = 1_501))
        assertEquals(Deviation.OVER, result.deviation)
        assertEquals(LoadMetric.DURATION, result.metric)
        assertEquals(-300, result.nextRunAdjustment?.value)
        val effect = Consequences.decisionEffect(result, PlanDecision.REDUCE_NEXT, DecisionTarget(0, 1_200))
        assertEquals(900, effect?.newTarget)
    }

    @Test fun `repeated skip requires a previous skip rather than a shortened run`() {
        val shortenedOnly = Consequences.calculate(feedback(status = FeedbackStatus.SKIPPED, recentShortened = 1))
        val repeated = Consequences.calculate(feedback(status = FeedbackStatus.SKIPPED, recentSkipped = 1))
        assertEquals(ConsequenceKind.SKIP_CONTINUE, shortenedOnly.kind)
        assertEquals(ConsequenceKind.REPEATED_SKIP, repeated.kind)
    }

    @Test fun `auto match rejects a tied best candidate and does not invent timed distance`() {
        val day = LocalDate.parse("2026-07-20")
        assertNull(selectAutoWorkoutMatch(MatchActivity(day, 5_000), listOf(MatchWorkout("a", day, 5_000), MatchWorkout("b", day, 5_000))))
        assertEquals("timed", selectAutoWorkoutMatch(MatchActivity(day, 0, 1_000), listOf(MatchWorkout("timed", day, 0, 1_000))))
        assertNull(selectAutoWorkoutMatch(MatchActivity(day, 5_000, null), listOf(MatchWorkout("timed", day, 0, 1_000))))
    }

    @Test fun `fractional material bounds remain strict like TypeScript`() {
        val result = Consequences.calculate(
            FeedbackInput(9_999, weekTargetDistanceMeters = 12_000, completedDistanceMeters = 11_499, status = FeedbackStatus.DONE)
        )
        assertEquals(Deviation.OVER, result.deviation)
        val day = LocalDate.parse("2026-07-20")
        assertNull(selectAutoWorkoutMatch(MatchActivity(day, 11_499), listOf(MatchWorkout("planned", day, 9_999))))
    }

    @Test fun `timed extra without duration stays non comparable and historical extra cannot alter plan`() {
        val result = calculateExtraActivityConsequence(ExtraActivityInput(5_000), ExtraActivityTargets(0, 1_200, 0, 3_600))
        assertEquals(LoadMetric.NONE, result.metric)
        assertEquals("not_comparable", result.comparisonStatus)
        val historical = historicalExtraActivityReview(result)
        assertFalse(historical.planChangeAvailable)
        assertTrue(historical.options.isEmpty())
        assertTrue(isHistoricalExtraActivity(LocalDate.parse("2026-07-14"), LocalDate.parse("2026-07-22")))
        assertFalse(isHistoricalExtraActivity(LocalDate.parse("2026-07-15"), LocalDate.parse("2026-07-22")))
    }

    @Test fun `edit preview uses week share detects spacing and keeps reset undo generated distinct`() {
        val start = LocalDate.parse("2026-07-20")
        val generated = runProposal("week", start, 3_000)
        val selected = EffectiveWorkoutState("one", generated = generated)
        val peer = EffectiveWorkoutState("two", generated = runProposal("week", start.plusDays(1), 3_000))
        val proposed = generated.copy(targetDistanceMeters = 4_000)
        val preview = WorkoutEdits.preview(selected, generated, proposed, listOf(selected, peer), listOf(EditWeek("week", 1)), start)
        assertEquals(16.7, preview.weeklyLoadChangePercent, 0.0)
        assertEquals(Risk.AGGRESSIVE, preview.risk)
        assertTrue(preview.spacingConflicts.isNotEmpty())
        assertTrue(preview.requiresConfirmation)
        val applied = WorkoutEdits.apply(selected, proposed)
        assertEquals(4_000, applied.current.targetDistanceMeters)
        assertEquals(3_000, WorkoutEdits.reset(applied).current.targetDistanceMeters)
        assertEquals(4_000, WorkoutEdits.undo(selected, proposed).current.targetDistanceMeters)
    }

    @Test fun `basis changes and add remove proposals require honest preview`() {
        val day = LocalDate.parse("2026-07-20")
        val distance = EffectiveWorkoutState("one", generated = runProposal("week", day, 3_000))
        val timed = timedProposal("week", day, 1_200)
        val conversion = WorkoutEdits.preview(distance, distance.generated, timed, listOf(distance), listOf(EditWeek("week", 1)), day)
        assertTrue(conversion.prescriptionBasisChanged)
        assertTrue(conversion.requiresConfirmation)
        val removed = distance.generated.copy(type = WorkoutType.REST, prescriptionKind = PrescriptionKind.REST, targetDistanceMeters = 0, intensity = "rest", purpose = "Recovery", isRemoved = true)
        val removal = WorkoutEdits.preview(distance, distance.generated, removed, listOf(distance), listOf(EditWeek("week", 1)), day, operation = "remove")
        assertEquals("remove", removal.operation)
        assertTrue(removal.spacingConflicts.isEmpty())
    }

    private fun feedback(
        status: FeedbackStatus = FeedbackStatus.DONE,
        targetDuration: Int? = null,
        completedDuration: Int? = null,
        pain: Boolean = false,
        recentSkipped: Int = 0,
        recentShortened: Int = 0,
    ) = FeedbackInput(3_000, targetDuration, 12_000, completedDistanceMeters = if (targetDuration == null && status != FeedbackStatus.SKIPPED) 3_000 else null, completedDurationSeconds = completedDuration, status = status, pain = pain, recentSkippedWorkouts = recentSkipped, recentShortenedWorkouts = recentShortened)

    private fun runProposal(week: String, date: LocalDate, distance: Int) = WorkoutProposal(week, date, WorkoutType.EASY, PrescriptionKind.DISTANCE, distance, purpose = "Easy run")

    private fun timedProposal(week: String, date: LocalDate, duration: Int) = WorkoutProposal(
        weekId = week,
        scheduledDate = date,
        type = WorkoutType.EASY,
        prescriptionKind = PrescriptionKind.TIMED,
        targetDistanceMeters = 0,
        targetDurationSeconds = duration,
        intervalStructure = TimedIntervalStructure(
            warmupSeconds = 120,
            cooldownSeconds = 120,
            blocks = listOf(RunWalkBlock(1, listOf(PrescriptionSegment(SegmentKind.RUN, duration - 240)))),
        ),
        purpose = "Easy timed run",
    )

    @Test fun `timed edit requires complete intervals and rebalance scales them`() {
        val day = LocalDate.parse("2026-07-20")
        val selected = EffectiveWorkoutState("one", generated = timedProposal("week", day, 1_200))
        val peer = EffectiveWorkoutState("two", generated = timedProposal("week", day.plusDays(2), 1_200))
        val proposed = selected.current.copy(targetDurationSeconds = 1_400, intervalStructure = WorkoutEdits.resizeTimedIntervalStructure(selected.current.intervalStructure, 1_400))
        val preview = WorkoutEdits.preview(selected, selected.generated, proposed, listOf(selected, peer), listOf(EditWeek("week", 1)), day, rebalance = true)
        val rebalanced = preview.workoutChanges.single { it.workoutId == "two" }.after
        assertEquals(1_000, rebalanced.targetDurationSeconds)
        assertEquals(1_000, rebalanced.intervalStructure?.let { it.warmupSeconds + it.cooldownSeconds + it.blocks.sumOf { block -> block.repetitions * block.segments.sumOf { segment -> segment.durationSeconds } } })
        assertEquals("Rebalanced after an explicit workout edit.", rebalanced.reason)
        val invalid = selected.current.copy(intervalStructure = null)
        try {
            WorkoutEdits.assertProposal(invalid)
            throw AssertionError("Expected timed proposal without intervals to fail")
        } catch (_: IllegalArgumentException) {
        }
    }
}
