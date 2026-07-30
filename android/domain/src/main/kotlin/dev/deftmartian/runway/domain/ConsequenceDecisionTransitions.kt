package dev.deftmartian.runway.domain

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/**
 * Pure counterpart to the bounded decision projection in workout-feedback.ts.
 * Callers own persistence, decision-source ownership, and audit/ledger records; this engine only
 * derives exactly the states that a preview and a subsequent apply must share.
 */
data class ConsequenceDecisionChange(
    val workoutId: String,
    val scheduledDate: LocalDate,
    val purpose: String,
    val before: WorkoutProposal,
    val after: WorkoutProposal,
)

data class ConsequenceDecisionProjection(
    val consequence: Consequence,
    val decision: PlanDecision,
    val changes: List<ConsequenceDecisionChange>,
)

data class ConsequenceDecisionApplication(
    val consequence: Consequence,
    val changes: List<ConsequenceDecisionChange>,
    val states: List<EffectiveWorkoutState>,
)

/** The caller supplies the complete plan snapshot when repeat-prescription confirmation matters. */
data class ConsequenceDecisionApplyOptions(
    val confirmRisk: Boolean = true,
    val weeks: List<EditWeek> = emptyList(),
    val hasInjuryRisk: Boolean = false,
)

object ConsequenceDecisionTransitions {
    fun preview(
        consequence: Consequence,
        decision: PlanDecision,
        originDate: LocalDate,
        originWorkout: EffectiveWorkoutState?,
        states: List<EffectiveWorkoutState>,
        today: LocalDate,
    ): ConsequenceDecisionProjection {
        val applied = Consequences.apply(consequence, decision)
        if (decision == PlanDecision.KEEP_PLAN) {
            return ConsequenceDecisionProjection(applied, decision, emptyList())
        }
        val candidates = states
            .filter { candidate ->
                candidate.status == "planned" &&
                    !candidate.current.isRemoved &&
                    candidate.current.type != WorkoutType.RACE &&
                    candidate.current.type != WorkoutType.REST &&
                    candidate.current.scheduledDate.isAfter(originDate) &&
                    !candidate.current.scheduledDate.isBefore(today)
            }
            .sortedWith(compareBy<EffectiveWorkoutState> { it.current.scheduledDate }.thenBy { it.id })
        val first = candidates.firstOrNull()
            ?: throw IllegalArgumentException("No future workout is available to change.")
        val affected = if (decision == PlanDecision.REBALANCE_WEEK) {
            val weekEnd = originDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(6)
            candidates.filter {
                !it.current.scheduledDate.isAfter(weekEnd) && isCompatible(applied, it.current)
            }.also {
                require(it.isNotEmpty()) {
                    "No compatible workouts remain in this week. Choose another option."
                }
            }
        } else {
            listOf(first)
        }
        val projectedConsequence = if (decision == PlanDecision.REDUCE_NEXT) {
            val effect = Consequences.decisionEffect(
                applied,
                decision,
                DecisionTarget(first.current.targetDistanceMeters, first.current.targetDurationSeconds),
            ) ?: throw IllegalArgumentException("The next workout has no amount that can be reduced.")
            applied.copy(
                nextRunAdjustment = LoadDelta(effect.metric, effect.adjustment),
            )
        } else {
            applied
        }
        return ConsequenceDecisionProjection(
            projectedConsequence,
            decision,
            affected.map { candidate ->
                ConsequenceDecisionChange(
                    workoutId = candidate.id,
                    scheduledDate = candidate.current.scheduledDate,
                    purpose = candidate.current.purpose,
                    before = candidate.current,
                    after = projectedState(
                        candidate = candidate.current,
                        originWorkout = originWorkout?.current,
                        decision = decision,
                        consequence = projectedConsequence,
                        shareCount = affected.size,
                    ),
                )
            },
        )
    }

    fun apply(
        consequence: Consequence,
        decision: PlanDecision,
        originDate: LocalDate,
        originWorkout: EffectiveWorkoutState?,
        states: List<EffectiveWorkoutState>,
        today: LocalDate,
        options: ConsequenceDecisionApplyOptions = ConsequenceDecisionApplyOptions(),
    ): ConsequenceDecisionApplication {
        val projection = preview(consequence, decision, originDate, originWorkout, states, today)
        if (decision == PlanDecision.REPEAT_PRESCRIPTION) {
            val change = projection.changes.single()
            val current = states.firstOrNull { it.id == change.workoutId }
                ?: throw IllegalArgumentException("An affected workout is missing from the decision apply.")
            val editPreview = WorkoutEdits.preview(
                current = current,
                recommended = null,
                proposed = change.after,
                states = states,
                weeks = options.weeks,
                today = today,
                rebalance = false,
                hasInjuryRisk = options.hasInjuryRisk,
                operation = "edit",
            )
            require(!editPreview.requiresConfirmation || options.confirmRisk) {
                "Review and confirm the elevated repeated prescription before applying it."
            }
        }
        val afterById = projection.changes.associateBy({ it.workoutId }, { it.after })
        return ConsequenceDecisionApplication(
            consequence = projection.consequence,
            changes = projection.changes,
            states = states.map { state -> afterById[state.id]?.let { state.copy(current = it) } ?: state },
        )
    }

    private fun isCompatible(consequence: Consequence, target: WorkoutProposal): Boolean {
        val metric = if ((target.targetDurationSeconds ?: 0) > 0) LoadMetric.DURATION else LoadMetric.DISTANCE
        return consequence.nextRunAdjustment?.metric == metric
    }

    private fun projectedState(
        candidate: WorkoutProposal,
        originWorkout: WorkoutProposal?,
        decision: PlanDecision,
        consequence: Consequence,
        shareCount: Int,
    ): WorkoutProposal = when (decision) {
        PlanDecision.NEXT_REST -> candidate.copy(
            type = WorkoutType.REST,
            prescriptionKind = PrescriptionKind.REST,
            targetDistanceMeters = 0,
            targetDurationSeconds = null,
            intervalStructure = null,
            intensity = "rest",
            purpose = "Recovery day",
            reason = "The runner explicitly chose rest after reviewing the recorded result.",
        )
        PlanDecision.REPEAT_PRESCRIPTION -> {
            require(originWorkout != null && originWorkout.type != WorkoutType.RACE && originWorkout.type != WorkoutType.REST) {
                "This result has no prescription that can be repeated."
            }
            candidate.copy(
                type = originWorkout.type,
                prescriptionKind = originWorkout.prescriptionKind,
                targetDistanceMeters = originWorkout.targetDistanceMeters,
                targetDurationSeconds = originWorkout.targetDurationSeconds,
                intervalStructure = originWorkout.intervalStructure,
                intensity = originWorkout.intensity,
                purpose = originWorkout.purpose,
                reason = "The runner explicitly chose to repeat the earlier prescription.",
                sourceRefs = originWorkout.sourceRefs,
            )
        }
        PlanDecision.REDUCE_NEXT, PlanDecision.REBALANCE_WEEK -> {
            val effect = Consequences.decisionEffect(
                consequence,
                decision,
                DecisionTarget(candidate.targetDistanceMeters, candidate.targetDurationSeconds),
                shareCount,
            ) ?: throw IllegalArgumentException("This workout has no amount that can be reduced.")
            if (effect.metric == LoadMetric.DURATION) {
                candidate.copy(
                    targetDurationSeconds = effect.newTarget,
                    intervalStructure = WorkoutEdits.resizeTimedIntervalStructure(
                        candidate.intervalStructure,
                        effect.newTarget,
                    ),
                    reason = "The runner explicitly reduced this timed workout after reviewing a result.",
                )
            } else {
                candidate.copy(
                    targetDistanceMeters = effect.newTarget,
                    reason = if (decision == PlanDecision.REBALANCE_WEEK) {
                        "The runner explicitly rebalanced the remaining week."
                    } else {
                        "The runner explicitly reduced this workout after reviewing a result."
                    },
                )
            }
        }
        PlanDecision.KEEP_PLAN -> error("Keep-plan decisions do not produce workout state changes.")
    }
}
