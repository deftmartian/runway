package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.Consequences
import dev.deftmartian.runway.domain.ExtraActivityInput
import dev.deftmartian.runway.domain.ExtraActivityTargets
import dev.deftmartian.runway.domain.FeedbackInput
import dev.deftmartian.runway.domain.FeedbackStatus
import dev.deftmartian.runway.domain.PlanDecision
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTrainingMutationRulesTest {
    @Test
    fun `feedback measurements follow timed distance and skipped contracts`() {
        assertEquals(
            LocalTrainingMutationIssue.COMPLETED_DISTANCE_REQUIRED,
            feedbackMeasurementIssue(workout(), LocalWorkoutFeedbackCommand("workout", FeedbackStatus.DONE)),
        )
        assertNull(
            feedbackMeasurementIssue(
                workout(),
                LocalWorkoutFeedbackCommand("workout", FeedbackStatus.DONE, completedDistanceMeters = 5_000),
            ),
        )
        assertEquals(
            LocalTrainingMutationIssue.COMPLETED_DURATION_REQUIRED,
            feedbackMeasurementIssue(
                workout().copy(currentDurationSeconds = 1_800),
                LocalWorkoutFeedbackCommand("workout", FeedbackStatus.DONE, completedDistanceMeters = 5_000),
            ),
        )
        assertEquals(
            LocalTrainingMutationIssue.SKIPPED_WITH_MEASUREMENTS,
            feedbackMeasurementIssue(
                workout(),
                LocalWorkoutFeedbackCommand("workout", FeedbackStatus.SKIPPED, completedDistanceMeters = 1),
            ),
        )
    }

    @Test
    fun `manual run requires bounded exact distance or duration`() {
        assertEquals(
            LocalTrainingMutationIssue.INVALID_MEASUREMENT,
            manualMeasurementIssue(LocalManualRunCommand(LocalDate.of(2026, 7, 30))),
        )
        assertNull(
            manualMeasurementIssue(
                LocalManualRunCommand(LocalDate.of(2026, 7, 30), durationSeconds = 1_800),
            ),
        )
    }

    @Test
    fun `activity decision apply rejects a stored consequence after its canonical evidence changes`() {
        val calculated = dev.deftmartian.runway.domain.calculateExtraActivityConsequence(
            ExtraActivityInput(distanceMeters = 2_000),
            ExtraActivityTargets(
                nextRunTargetDistanceMeters = 5_000,
                weekTargetDistanceMeters = 12_000,
                weekTargetDurationSeconds = 0,
            ),
        )
        fun storageValue(decision: PlanDecision) = when (decision) {
            PlanDecision.KEEP_PLAN -> "keep_plan"
            PlanDecision.REDUCE_NEXT -> "reduce_next"
            PlanDecision.NEXT_REST -> "next_rest"
            PlanDecision.REPEAT_PRESCRIPTION -> "repeat_prescription"
            PlanDecision.REBALANCE_WEEK -> "rebalance_week"
        }
        val stored = ActivityConsequenceEntity(
            activityId = "activity",
            classification = calculated.kind.name.lowercase(),
            distanceDifferenceMeters = calculated.actualDifference,
            durationDifferenceSeconds = null,
            actualLoadMeters = calculated.actualDifference,
            assessment = calculated.risk.name.lowercase(),
            recommendedDecision = storageValue(calculated.recommendedDecision),
            resolvedAtEpochMillis = null,
            deviation = calculated.deviation.name.lowercase(),
            loadMetric = calculated.metric.name.lowercase(),
            risk = calculated.risk.name.lowercase(),
            planChangeAvailable = calculated.planChangeAvailable,
        )
        val options = calculated.options.map { ActivityConsequenceOptionEntity("activity", storageValue(it)) }

        assertTrue(stored.matches(calculated, options))
        assertTrue(!stored.copy(actualLoadMeters = 1_999).matches(calculated, options))
    }

    @Test
    fun `all choices preview exact effects without mutating source workouts`() {
        val origin = workout().copy(
            currentPurpose = "Original easy run",
            currentDistanceMeters = 4_000,
        )
        val candidates = listOf(
            workout("next", day = 20, distance = 6_000),
            workout("later", day = 21, distance = 5_000),
        )

        val keep = preview(PlanDecision.KEEP_PLAN, origin, candidates)
        val reduce = preview(PlanDecision.REDUCE_NEXT, origin, candidates)
        val rest = preview(PlanDecision.NEXT_REST, origin, candidates)
        val repeated = preview(PlanDecision.REPEAT_PRESCRIPTION, origin, candidates)
        val rebalanced = preview(PlanDecision.REBALANCE_WEEK, origin, candidates)

        assertTrue(keep.changes.isEmpty())
        assertTrue(requireNotNull(reduce.changes.single().after.currentDistanceMeters) < 6_000)
        assertEquals("rest", rest.changes.single().after.currentWorkoutType)
        assertEquals(4_000, repeated.changes.single().after.currentDistanceMeters)
        assertEquals(2, rebalanced.changes.size)
        assertEquals(6_000, candidates.first().currentDistanceMeters)
    }

    @Test
    fun `apply rejects double submission and any stale workout state`() {
        val origin = workout()
        val candidates = listOf(workout("next", day = 20, distance = 6_000))
        val input = input(PlanDecision.REDUCE_NEXT, origin, candidates)
        val preview = LocalConsequenceDecisionEngine.preview(input) as LocalDecisionResult.Preview

        assertTrue(
            LocalConsequenceDecisionEngine.apply(preview, input, alreadyApplied = false) is
                LocalDecisionApplyResult.Ready,
        )
        assertEquals(
            LocalDecisionIssue.ALREADY_APPLIED,
            (LocalConsequenceDecisionEngine.apply(preview, input, alreadyApplied = true) as
                LocalDecisionApplyResult.Rejected).issue,
        )
        val changed = input.copy(candidates = candidates.map { it.copy(updatedAtEpochMillis = 99) })
        assertEquals(
            LocalDecisionIssue.STALE_PREVIEW,
            (LocalConsequenceDecisionEngine.apply(preview, changed, alreadyApplied = false) as
                LocalDecisionApplyResult.Rejected).issue,
        )
    }

    @Test
    fun `backfilled extra consequences preview and apply the same future workout target`() {
        val originDay = 10L
        val todayDay = 13L
        val past = workout("past-after-extra", day = 11, distance = 15_000)
        val future = workout("future-target", day = todayDay, distance = 5_000)
        val eligible = eligibleFutureDecisionWorkouts(
            candidates = listOf(past, future),
            originEpochDay = originDay,
            todayEpochDay = todayDay,
        )
        assertEquals(listOf("future-target"), eligible.map { it.workoutId })

        val consequence = dev.deftmartian.runway.domain.calculateExtraActivityConsequence(
            ExtraActivityInput(distanceMeters = 3_000),
            ExtraActivityTargets(
                nextRunTargetDistanceMeters = requireNotNull(eligible.single().currentDistanceMeters),
                weekTargetDistanceMeters = 20_000,
                weekTargetDurationSeconds = 0,
            ),
        )
        val input = LocalDecisionInput(
            source = LocalDecisionSource(LocalDecisionSourceKind.Activity, "backfilled-extra", "v1"),
            decision = PlanDecision.REDUCE_NEXT,
            consequence = consequence,
            originEpochDay = originDay,
            todayEpochDay = todayDay,
            originWorkout = null,
            candidates = listOf(past, future),
        )
        val preview = LocalConsequenceDecisionEngine.preview(input) as LocalDecisionResult.Preview
        assertEquals("future-target", preview.changes.single().after.workoutId)
        assertTrue(
            LocalConsequenceDecisionEngine.apply(preview, input, alreadyApplied = false) is
                LocalDecisionApplyResult.Ready,
        )
    }

    private fun preview(
        decision: PlanDecision,
        origin: WorkoutEntity,
        candidates: List<WorkoutEntity>,
    ) = LocalConsequenceDecisionEngine.preview(input(decision, origin, candidates)) as LocalDecisionResult.Preview

    private fun input(
        decision: PlanDecision,
        origin: WorkoutEntity,
        candidates: List<WorkoutEntity>,
    ) = LocalDecisionInput(
        source = LocalDecisionSource(LocalDecisionSourceKind.WorkoutFeedback, "feedback", "v1"),
        decision = decision,
        consequence = Consequences.calculate(
            FeedbackInput(
                targetDistanceMeters = 5_000,
                weekTargetDistanceMeters = 15_000,
                completedDistanceMeters = 2_000,
                status = FeedbackStatus.SHORTENED,
                recentShortenedWorkouts = 1,
            ),
        ),
        originEpochDay = 19,
        todayEpochDay = 19,
        originWorkout = origin,
        candidates = candidates,
    )

    private fun workout(
        id: String = "workout",
        day: Long = 19,
        distance: Int = 5_000,
    ) = WorkoutEntity(
        workoutId = id,
        planId = "plan",
        weekId = "week",
        position = 0,
        generatedPurpose = "Easy run",
        generatedDistanceMeters = distance,
        generatedDurationSeconds = null,
        currentPurpose = "Easy run",
        currentDistanceMeters = distance,
        currentDurationSeconds = null,
        tombstonedAtEpochMillis = null,
        updatedAtEpochMillis = 1,
        generatedScheduledEpochDay = day,
        currentScheduledEpochDay = day,
        generatedWorkoutType = "easy",
        currentWorkoutType = "easy",
        generatedPrescriptionKind = "distance",
        currentPrescriptionKind = "distance",
    )
}
