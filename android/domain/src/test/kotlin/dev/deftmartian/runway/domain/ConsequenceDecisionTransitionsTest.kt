package dev.deftmartian.runway.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsequenceDecisionTransitionsTest {
    private val originDate = LocalDate.parse("2026-07-28")
    private val today = LocalDate.parse("2026-07-28")

    @Test fun `timed reduction shares preview and apply state and resizes intervals`() {
        val consequence = Consequences.calculate(
            FeedbackInput(0, 1800, 0, completedDurationSeconds = 1800, status = FeedbackStatus.DONE, feltHard = true),
        )
        val candidate = timed("next", "2026-07-30", 1800, structure(1800))
        val preview = ConsequenceDecisionTransitions.preview(consequence, PlanDecision.REDUCE_NEXT, originDate, null, listOf(candidate), today)
        val applied = ConsequenceDecisionTransitions.apply(consequence, PlanDecision.REDUCE_NEXT, originDate, null, listOf(candidate), today)
        assertEquals(1500, preview.changes.single().after.targetDurationSeconds)
        assertEquals(preview.changes, applied.changes)
        assertEquals(preview.changes.single().after, applied.states.single().current)
        assertEquals(1500, intervalTotal(applied.states.single().current.intervalStructure!!))
        assertEquals(-300, applied.consequence.nextRunAdjustment?.value)
    }

    @Test fun `rebalance changes only compatible planned future workouts in origin week`() {
        val consequence = Consequences.calculate(
            FeedbackInput(0, 1800, 0, completedDurationSeconds = 1800, status = FeedbackStatus.DONE, feltHard = true),
        )
        val candidates = listOf(
            timed("a", "2026-07-30", 1800),
            timed("b", "2026-08-01", 1200),
            distance("distance", "2026-07-31", 5000),
            timed("race", "2026-08-01", 1200, type = WorkoutType.RACE),
            timed("past", "2026-07-27", 1200),
            timed("later", "2026-08-04", 1800),
        )
        val projection = ConsequenceDecisionTransitions.preview(consequence, PlanDecision.REBALANCE_WEEK, originDate, null, candidates, today)
        assertEquals(listOf("a", "b"), projection.changes.map { it.workoutId })
        assertEquals(listOf(1650, 1050), projection.changes.map { it.after.targetDurationSeconds })
        assertTrue(projection.changes.all { it.after.reason == "The runner explicitly reduced this timed workout after reviewing a result." })
    }

    @Test fun `next rest and repeat preserve source semantics while excluding rest race and origin day`() {
        val consequence = Consequences.calculate(
            FeedbackInput(4000, weekTargetDistanceMeters = 12000, status = FeedbackStatus.SKIPPED),
        )
        val origin = distance("origin", "2026-07-28", 4000, purpose = "Earlier easy", sourceRefs = listOf("source-a"))
        val candidates = listOf(
            distance("same-day", "2026-07-28", 3000),
            distance("race", "2026-07-29", 3000, type = WorkoutType.RACE),
            distance("rest", "2026-07-29", 0, type = WorkoutType.REST, kind = PrescriptionKind.REST),
            distance("next", "2026-07-30", 6000),
        )
        val rested = ConsequenceDecisionTransitions.preview(consequence, PlanDecision.NEXT_REST, originDate, origin, candidates, today).changes.single().after
        assertEquals(WorkoutType.REST, rested.type)
        assertEquals(PrescriptionKind.REST, rested.prescriptionKind)
        assertNull(rested.targetDurationSeconds)
        assertEquals("The runner explicitly chose rest after reviewing the recorded result.", rested.reason)
        val repeated = ConsequenceDecisionTransitions.preview(consequence, PlanDecision.REPEAT_PRESCRIPTION, originDate, origin, candidates, today).changes.single().after
        assertEquals(4000, repeated.targetDistanceMeters)
        assertEquals("Earlier easy", repeated.purpose)
        assertEquals(listOf("source-a"), repeated.sourceRefs)
        assertEquals("The runner explicitly chose to repeat the earlier prescription.", repeated.reason)
    }

    @Test fun `keep plan is a validated no-op and unavailable choices fail`() {
        val consequence = Consequences.calculate(FeedbackInput(3000, weekTargetDistanceMeters = 9000, status = FeedbackStatus.DONE))
        val noOp = ConsequenceDecisionTransitions.apply(consequence, PlanDecision.KEEP_PLAN, originDate, null, emptyList(), today)
        assertTrue(noOp.changes.isEmpty())
        assertEquals(PlanDecision.KEEP_PLAN, noOp.consequence.appliedDecision)
        try {
            ConsequenceDecisionTransitions.preview(consequence, PlanDecision.NEXT_REST, originDate, null, emptyList(), today)
            throw AssertionError("expected unavailable decision")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `repeat rejects a race origin and rebalance rejects a week with no native-unit match`() {
        val consequence = Consequences.calculate(
            FeedbackInput(0, 1200, 0, completedDurationSeconds = 1200, status = FeedbackStatus.DONE, feltHard = true),
        )
        val candidate = timed("next", "2026-07-30", 1200)
        val raceOrigin = timed("race-origin", "2026-07-28", 1200, type = WorkoutType.RACE)
        try {
            ConsequenceDecisionTransitions.preview(consequence, PlanDecision.REPEAT_PRESCRIPTION, originDate, raceOrigin, listOf(candidate), today)
            throw AssertionError("expected race origin rejection")
        } catch (_: IllegalArgumentException) { }
        try {
            ConsequenceDecisionTransitions.preview(consequence, PlanDecision.REBALANCE_WEEK, originDate, null, listOf(distance("distance", "2026-07-30", 3000)), today)
            throw AssertionError("expected incompatible rebalance rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `repeat application enforces the same elevated-change confirmation as workout editing`() {
        val consequence = Consequences.calculate(FeedbackInput(4000, weekTargetDistanceMeters = 12000, status = FeedbackStatus.SKIPPED))
        val origin = distance("origin", "2026-07-28", 4000)
        val candidate = distance("next", "2026-07-30", 6000)
        try {
            ConsequenceDecisionTransitions.apply(
                consequence, PlanDecision.REPEAT_PRESCRIPTION, originDate, origin, listOf(candidate), today,
                ConsequenceDecisionApplyOptions(confirmRisk = false, weeks = listOf(EditWeek("week", 1))),
            )
            throw AssertionError("expected confirmation requirement")
        } catch (_: IllegalArgumentException) { }
        val applied = ConsequenceDecisionTransitions.apply(
            consequence, PlanDecision.REPEAT_PRESCRIPTION, originDate, origin, listOf(candidate), today,
            ConsequenceDecisionApplyOptions(confirmRisk = true, weeks = listOf(EditWeek("week", 1))),
        )
        assertEquals(4000, applied.states.single().current.targetDistanceMeters)
    }

    private fun distance(id: String, date: String, meters: Int, type: WorkoutType = WorkoutType.EASY, kind: PrescriptionKind = PrescriptionKind.DISTANCE, purpose: String = "Easy run", sourceRefs: List<String> = emptyList()) = EffectiveWorkoutState(id, generated = WorkoutProposal("week", LocalDate.parse(date), type, kind, meters, intensity = if (type == WorkoutType.REST) "rest" else "easy", purpose = purpose, sourceRefs = sourceRefs))
    private fun timed(id: String, date: String, seconds: Int, intervals: TimedIntervalStructure? = null, type: WorkoutType = WorkoutType.EASY) = EffectiveWorkoutState(id, generated = WorkoutProposal("week", LocalDate.parse(date), type, PrescriptionKind.TIMED, 0, seconds, intervals, "easy", "Easy timed run"))
    private fun structure(total: Int) = TimedIntervalStructure(300, 300, listOf(RunWalkBlock(1, listOf(PrescriptionSegment(SegmentKind.RUN, total - 600)))))
    private fun intervalTotal(value: TimedIntervalStructure) = value.warmupSeconds + value.cooldownSeconds + value.blocks.sumOf { block -> block.repetitions * block.segments.sumOf { it.durationSeconds } }
}
