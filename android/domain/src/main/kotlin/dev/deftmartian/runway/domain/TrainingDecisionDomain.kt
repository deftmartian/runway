package dev.deftmartian.runway.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Pure, deterministic decision arithmetic. It does not persist or mutate a plan. */
enum class LoadMetric { DISTANCE, DURATION, NONE }
enum class Risk { CONSERVATIVE, MODERATE, AGGRESSIVE, UNSAFE }
enum class Deviation { NEAR_PLAN, SHORT, OVER, SKIPPED, UNPLANNED, NOT_APPLICABLE }
enum class FeedbackStatus { DONE, SHORTENED, SKIPPED }
enum class PlanDecision { KEEP_PLAN, REDUCE_NEXT, NEXT_REST, REPEAT_PRESCRIPTION, REBALANCE_WEEK }
enum class ConsequenceKind {
    COMPLETED_AS_PLANNED,
    SHORTFALL,
    REPEATED_SHORTFALL,
    SKIP_CONTINUE,
    SKIP_REDUCE,
    REPEATED_SKIP,
    LOAD_SPIKE,
    HARD_EFFORT,
    ROUTINE_RUN_RECORDED,
    EXTRA_ACTIVITY,
    NEEDS_REVIEW,
}

data class LoadDelta(val metric: LoadMetric, val value: Int)
data class FeedbackInput(
    val targetDistanceMeters: Int,
    val targetDurationSeconds: Int? = null,
    val weekTargetDistanceMeters: Int,
    val completedDistanceMeters: Int? = null,
    val completedDurationSeconds: Int? = null,
    val status: FeedbackStatus,
    val feltHard: Boolean = false,
    val choice: PlanDecision? = null,
    val recentSkippedWorkouts: Int = 0,
    val recentShortenedWorkouts: Int = 0,
)
data class Consequence(
    val kind: ConsequenceKind,
    val deviation: Deviation,
    val metric: LoadMetric,
    val actualDifference: Int,
    val weeklyLoadDelta: LoadDelta?,
    val nextRunAdjustment: LoadDelta?,
    val risk: Risk,
    val recommendedDecision: PlanDecision,
    val options: Set<PlanDecision>,
    val appliedDecision: PlanDecision? = null,
    val comparisonStatus: String? = null,
    val planChangeAvailable: Boolean = true,
)
data class DecisionTarget(val targetDistanceMeters: Int, val targetDurationSeconds: Int? = null)
data class DecisionEffect(val metric: LoadMetric, val previousTarget: Int, val adjustment: Int, val newTarget: Int)

object Consequences {
    private val material = setOf(
        PlanDecision.KEEP_PLAN,
        PlanDecision.REDUCE_NEXT,
        PlanDecision.NEXT_REST,
        PlanDecision.REPEAT_PRESCRIPTION,
        PlanDecision.REBALANCE_WEEK,
    )

    fun calculate(input: FeedbackInput): Consequence {
        validateInput(input)
        val comparison = compare(input)
        return when (comparison.deviation) {
            Deviation.OVER -> overConsequence(input, comparison)
            Deviation.SHORT -> shortConsequence(input, comparison)
            Deviation.SKIPPED -> skippedConsequence(input, comparison)
            Deviation.NEAR_PLAN -> nearPlanConsequence(input, comparison)
            Deviation.NOT_APPLICABLE -> notApplicableConsequence(input, comparison)
            else -> buildConsequence(
                input,
                comparison,
                ConsequenceKind.COMPLETED_AS_PLANNED,
                0,
                Risk.CONSERVATIVE,
                PlanDecision.KEEP_PLAN,
            )
        }
    }

    private fun validateInput(input: FeedbackInput) {
        require(input.targetDistanceMeters >= 0 && input.weekTargetDistanceMeters >= 0)
        require(input.targetDurationSeconds == null || input.targetDurationSeconds > 0)
        require(input.completedDistanceMeters == null || input.completedDistanceMeters >= 0)
        require(input.completedDurationSeconds == null || input.completedDurationSeconds > 0)
        require(input.recentSkippedWorkouts >= 0 && input.recentShortenedWorkouts >= 0)
        if (input.status == FeedbackStatus.SKIPPED) {
            require(input.completedDistanceMeters in listOf(null, 0) && input.completedDurationSeconds == null)
        }
    }

    private fun buildConsequence(
        input: FeedbackInput,
        comparison: Comparison,
        kind: ConsequenceKind,
        adjustment: Int,
        risk: Risk,
        decision: PlanDecision,
        options: Set<PlanDecision> = defaultOptions(kind),
    ) = Consequence(
            kind = kind,
            deviation = comparison.deviation,
            metric = comparison.metric,
            actualDifference = comparison.difference,
            weeklyLoadDelta = comparison.delta(),
            nextRunAdjustment =
                if (!comparison.comparable && !input.feltHard) {
                    null
                } else {
                    comparison.adjustment(adjustment)
                },
            risk = risk,
            recommendedDecision = decision,
            options = options,
            comparisonStatus = "not_comparable".takeUnless { comparison.comparable },
            planChangeAvailable = comparison.comparable || input.feltHard,
        )

    private fun defaultOptions(kind: ConsequenceKind): Set<PlanDecision> =
        if (kind in setOf(ConsequenceKind.COMPLETED_AS_PLANNED, ConsequenceKind.NEEDS_REVIEW)) {
            setOf(PlanDecision.KEEP_PLAN)
        } else {
            material
        }

    private fun overConsequence(input: FeedbackInput, comparison: Comparison): Consequence {
        val guardrail = if (comparison.metric == LoadMetric.DISTANCE) {
            max(input.weekTargetDistanceMeters, input.targetDistanceMeters).coerceAtLeast(1)
        } else {
            comparison.target.coerceAtLeast(1)
        }
        val crosses = comparison.difference.toDouble() / guardrail > .10
        val severe = comparison.actual >= comparison.target * 2
        val minimumReduction = if (comparison.metric == LoadMetric.DURATION) 300 else 1_000
        val reduction = -max(minimumReduction, roundTrainingValueToInt(comparison.difference * .5))
        val risk = when {
            input.feltHard && crosses && severe -> Risk.UNSAFE
            crosses -> Risk.AGGRESSIVE
            else -> Risk.MODERATE
        }
        val decision = if (crosses) PlanDecision.REDUCE_NEXT else PlanDecision.KEEP_PLAN
        return buildConsequence(input, comparison, ConsequenceKind.LOAD_SPIKE, reduction, risk, decision)
    }

    private fun shortConsequence(input: FeedbackInput, comparison: Comparison): Consequence {
        val repeated = input.recentSkippedWorkouts + input.recentShortenedWorkouts > 0
        val large = abs(comparison.difference) > comparison.target * .4 || comparison.actual == 0
        val kind = if (repeated) ConsequenceKind.REPEATED_SHORTFALL else ConsequenceKind.SHORTFALL
        val multiplier = if (input.feltHard || repeated) .35 else .25
        val minimumReduction = if (comparison.metric == LoadMetric.DURATION) 300 else 500
        val reduction = -max(
            minimumReduction,
            roundTrainingValueToInt(abs(comparison.difference) * multiplier),
        )
        val needsRepeat = input.feltHard || repeated
        val risk = if (large || needsRepeat) Risk.MODERATE else Risk.CONSERVATIVE
        val decision = if (needsRepeat) {
            PlanDecision.REPEAT_PRESCRIPTION
        } else {
            PlanDecision.KEEP_PLAN
        }
        return buildConsequence(input, comparison, kind, reduction, risk, decision)
    }

    private fun skippedConsequence(input: FeedbackInput, comparison: Comparison): Consequence {
        val repeated = input.recentSkippedWorkouts > 0
        val kind = when {
            repeated -> ConsequenceKind.REPEATED_SKIP
            input.choice == PlanDecision.REDUCE_NEXT -> ConsequenceKind.SKIP_REDUCE
            else -> ConsequenceKind.SKIP_CONTINUE
        }
        val multiplier = if (input.feltHard) .3 else .2
        val minimumReduction = if (comparison.metric == LoadMetric.DURATION) 300 else 500
        val reduction = -max(
            minimumReduction,
            roundTrainingValueToInt(comparison.target * multiplier),
        )
        val elevated = input.feltHard || repeated
        val risk = if (elevated) Risk.MODERATE else Risk.CONSERVATIVE
        val decision = if (elevated) PlanDecision.REPEAT_PRESCRIPTION else PlanDecision.KEEP_PLAN
        return buildConsequence(input, comparison, kind, reduction, risk, decision)
    }

    private fun nearPlanConsequence(input: FeedbackInput, comparison: Comparison): Consequence {
        if (!input.feltHard) {
            return buildConsequence(
                input,
                comparison,
                ConsequenceKind.COMPLETED_AS_PLANNED,
                0,
                Risk.CONSERVATIVE,
                PlanDecision.KEEP_PLAN,
            )
        }
        val minimumReduction = if (comparison.metric == LoadMetric.DURATION) 300 else 1_000
        val reduction = -max(minimumReduction, roundTrainingValueToInt(comparison.target * .15))
        return buildConsequence(
            input,
            comparison,
            ConsequenceKind.HARD_EFFORT,
            reduction,
            Risk.MODERATE,
            PlanDecision.REDUCE_NEXT,
        )
    }

    private fun notApplicableConsequence(input: FeedbackInput, comparison: Comparison): Consequence {
        if (!input.feltHard) {
            return buildConsequence(
                input,
                comparison,
                ConsequenceKind.NEEDS_REVIEW,
                0,
                Risk.MODERATE,
                PlanDecision.KEEP_PLAN,
            )
        }
        val minimumReduction = if (comparison.metric == LoadMetric.DURATION) 300 else 1_000
        return buildConsequence(
            input,
            comparison,
            ConsequenceKind.HARD_EFFORT,
            -max(minimumReduction, roundTrainingValueToInt(comparison.target * .15)),
            Risk.MODERATE,
            PlanDecision.REDUCE_NEXT,
            setOf(PlanDecision.KEEP_PLAN, PlanDecision.REDUCE_NEXT, PlanDecision.NEXT_REST),
        )
    }

    /**
     * A weekly routine tracks whether a run happened, not a prescribed load. Feedback remains
     * useful context in its own right, but it must not create a synthetic shortfall or alter a
     * future open-run slot.
     */
    fun recordRoutine(
        status: FeedbackStatus,
        feltHard: Boolean = false,
    ): Consequence {
        val kind = when (status) {
            FeedbackStatus.DONE, FeedbackStatus.SHORTENED, FeedbackStatus.SKIPPED ->
                ConsequenceKind.ROUTINE_RUN_RECORDED
        }
        return Consequence(
            kind = kind,
            deviation = Deviation.NOT_APPLICABLE,
            metric = LoadMetric.NONE,
            actualDifference = 0,
            weeklyLoadDelta = null,
            nextRunAdjustment = null,
            risk = if (feltHard) Risk.MODERATE else Risk.CONSERVATIVE,
            recommendedDecision = PlanDecision.KEEP_PLAN,
            options = setOf(PlanDecision.KEEP_PLAN),
            comparisonStatus = "not_comparable",
            planChangeAvailable = false,
        )
    }

    fun apply(consequence: Consequence, decision: PlanDecision): Consequence {
        require(decision in consequence.options) { "Decision is not available for this result." }
        return consequence.copy(appliedDecision = decision)
    }

    fun decisionEffect(consequence: Consequence, decision: PlanDecision, target: DecisionTarget, shareCount: Int = 1): DecisionEffect? {
        if (decision !in setOf(PlanDecision.REDUCE_NEXT, PlanDecision.REBALANCE_WEEK)) return null
        val (metric, previous) = target.metricAndValue()
        if (previous <= 0) return null
        val matching = consequence.nextRunAdjustment?.takeIf { it.metric == metric }?.value?.let(::abs) ?: 0
        val total = matching.takeIf { it > 0 } ?: defaultReduction(metric, previous)
        val reduction = if (decision == PlanDecision.REBALANCE_WEEK) ceil(total.toDouble() / max(1, shareCount)).toInt() else total
        val next = max(minimumTarget(metric), previous - reduction)
        return DecisionEffect(metric, previous, next - previous, next)
    }

    private fun DecisionTarget.metricAndValue(): Pair<LoadMetric, Int> =
        if ((targetDurationSeconds ?: 0) > 0) {
            LoadMetric.DURATION to (targetDurationSeconds ?: 0)
        } else {
            LoadMetric.DISTANCE to targetDistanceMeters
        }

    private fun defaultReduction(metric: LoadMetric, previous: Int): Int =
        if (metric == LoadMetric.DURATION) max(300, roundTrainingValueToInt(previous * .15)) else 500

    private fun minimumTarget(metric: LoadMetric): Int =
        if (metric == LoadMetric.DURATION) 600 else 500

    private data class Comparison(
        val deviation: Deviation,
        val metric: LoadMetric,
        val difference: Int,
        val target: Int,
        val actual: Int,
        val comparable: Boolean = true,
    ) {
        fun delta(value: Int = difference) =
            if (metric == LoadMetric.NONE || !comparable) null else LoadDelta(metric, value)

        fun adjustment(value: Int) =
            if (metric == LoadMetric.NONE) null else LoadDelta(metric, value)
    }
    private fun compare(input: FeedbackInput): Comparison {
        val metric = input.loadMetric()
        val target = input.targetFor(metric)
        if (input.status == FeedbackStatus.SKIPPED) return Comparison(Deviation.SKIPPED, metric, -target, target, 0)
        if (target <= 0) return Comparison(Deviation.NOT_APPLICABLE, LoadMetric.NONE, 0, 0, 0)
        val recordedActual = input.actualFor(metric)
        if (recordedActual == null) {
            return Comparison(
                deviation = Deviation.NOT_APPLICABLE,
                metric = metric,
                difference = 0,
                target = target,
                actual = 0,
                comparable = false,
            )
        }
        val actual = recordedActual
        val difference = actual - target
        // TypeScript deliberately compares the exact fractional 15% threshold. Rounding here
        // changes the result at boundaries such as 9,999 m (1,499.85 m tolerance).
        val threshold = max(if (metric == LoadMetric.DURATION) 300.0 else 500.0, target * .15)
        val deviation = deviationFor(difference, threshold)
        return Comparison(deviation, metric, difference, target, actual)
    }

    private fun FeedbackInput.loadMetric(): LoadMetric =
        if ((targetDurationSeconds ?: 0) > 0) LoadMetric.DURATION else LoadMetric.DISTANCE

    private fun FeedbackInput.targetFor(metric: LoadMetric): Int =
        if (metric == LoadMetric.DURATION) targetDurationSeconds ?: 0 else targetDistanceMeters

    private fun FeedbackInput.actualFor(metric: LoadMetric): Int? =
        if (metric == LoadMetric.DURATION) completedDurationSeconds else completedDistanceMeters

    private fun deviationFor(difference: Int, threshold: Double): Deviation = when {
        difference < -threshold -> Deviation.SHORT
        difference > threshold -> Deviation.OVER
        else -> Deviation.NEAR_PLAN
    }
}

data class MatchActivity(val activityDate: LocalDate, val distanceMeters: Int, val durationSeconds: Int? = null)
data class MatchWorkout(val id: String, val scheduledDate: LocalDate, val targetDistanceMeters: Int, val targetDurationSeconds: Int? = null)
const val ACTIVITY_WORKOUT_MATCH_WINDOW_DAYS = 3L

private data class RankedWorkoutMatch(val workout: MatchWorkout, val dateDifference: Long, val loadDifference: Double)

fun selectAutoWorkoutMatch(activity: MatchActivity, candidates: Iterable<MatchWorkout>): String? {
    val ranked = candidates.mapNotNull { candidate -> rankedWorkoutMatch(activity, candidate) }
        .sortedWith(
            compareBy<RankedWorkoutMatch> { it.dateDifference }
                .thenBy { it.loadDifference }
                .thenBy { it.workout.id },
        )
    val best = ranked.firstOrNull() ?: return null
    val second = ranked.getOrNull(1)
    return if (second?.ties(best) == true) null else best.workout.id
}

private fun rankedWorkoutMatch(activity: MatchActivity, workout: MatchWorkout): RankedWorkoutMatch? {
    val ratio = workoutMatchRatio(activity, workout) ?: return null
    val days = abs(java.time.temporal.ChronoUnit.DAYS.between(activity.activityDate, workout.scheduledDate))
    return RankedWorkoutMatch(workout, days, ratio)
}

private fun workoutMatchRatio(activity: MatchActivity, workout: MatchWorkout): Double? {
    if (workout.targetDistanceMeters > 0) {
        val difference = abs(activity.distanceMeters - workout.targetDistanceMeters)
        return difference.takeIf { it <= max(500.0, workout.targetDistanceMeters * .15) }
            ?.toDouble()
            ?.div(workout.targetDistanceMeters)
    }
    val targetDuration = workout.targetDurationSeconds ?: 0
    val actualDuration = activity.durationSeconds ?: 0
    if (targetDuration <= 0 || actualDuration <= 0) return null
    val difference = abs(actualDuration - targetDuration)
    return difference.takeIf { it <= max(300.0, targetDuration * .15) }
        ?.toDouble()
        ?.div(targetDuration)
}

private fun RankedWorkoutMatch.ties(other: RankedWorkoutMatch): Boolean =
    dateDifference == other.dateDifference && abs(loadDifference - other.loadDifference) < 1e-12

data class ExtraActivityInput(val distanceMeters: Int, val durationSeconds: Int? = null, val feltHard: Boolean = false)
data class ExtraActivityTargets(val nextRunTargetDistanceMeters: Int, val nextRunTargetDurationSeconds: Int? = null, val weekTargetDistanceMeters: Int, val weekTargetDurationSeconds: Int)
fun isHistoricalExtraActivity(activityDate: LocalDate, today: LocalDate) =
    activityDate.isBefore(today.minusDays(7))

fun historicalExtraActivityReview(consequence: Consequence) =
    consequence.copy(
        nextRunAdjustment = null,
        planChangeAvailable = false,
        options = emptySet(),
    )

fun calculateExtraActivityConsequence(input: ExtraActivityInput, targets: ExtraActivityTargets): Consequence {
    val timed = (targets.nextRunTargetDurationSeconds ?: 0) > 0
    val missingNative = if (timed) (input.durationSeconds ?: 0) <= 0 else input.distanceMeters <= 0
    if (missingNative) {
        return missingExtraActivityConsequence(input, targets, timed)
    }
    return comparableExtraActivityConsequence(input, targets, timed)
}

private fun missingExtraActivityConsequence(
    input: ExtraActivityInput,
    targets: ExtraActivityTargets,
    timed: Boolean,
): Consequence {
    val metric = if (timed) LoadMetric.DURATION else LoadMetric.DISTANCE
    val next = if (timed) targets.nextRunTargetDurationSeconds ?: 0 else targets.nextRunTargetDistanceMeters
    val adjustment = input.feltHard.takeIf { it }?.let {
        val minimumReduction = if (timed) 300 else 1_000
        LoadDelta(metric, -max(minimumReduction, roundTrainingValueToInt(next * .15)))
    }
    val decision = if (input.feltHard) PlanDecision.REDUCE_NEXT else PlanDecision.KEEP_PLAN
    val options = if (adjustment != null) {
        setOf(PlanDecision.KEEP_PLAN, PlanDecision.REDUCE_NEXT, PlanDecision.NEXT_REST)
    } else {
        setOf(PlanDecision.KEEP_PLAN, PlanDecision.NEXT_REST)
    }
    return Consequence(
        ConsequenceKind.EXTRA_ACTIVITY,
        Deviation.UNPLANNED,
        LoadMetric.NONE,
        0,
        null,
        adjustment,
        Risk.MODERATE,
        decision,
        options,
        comparisonStatus = "not_comparable",
    )
}

private fun comparableExtraActivityConsequence(
    input: ExtraActivityInput,
    targets: ExtraActivityTargets,
    timed: Boolean,
): Consequence {
    val metric = if (timed) LoadMetric.DURATION else LoadMetric.DISTANCE
    val actual = extraActivityActual(input, timed)
    val weekly = extraActivityWeeklyTarget(targets, timed)
    val share = if (weekly > 0) actual.toDouble() / weekly else 0.0
    val next = extraActivityNextTarget(targets, timed)
    val minimumReduction = if (timed) 300 else 1_000
    val adjustmentMultiplier = if (input.feltHard) .6 else .5
    val adjustment = -min(
        next,
        max(minimumReduction, roundTrainingValueToInt(actual * adjustmentMultiplier)),
    )
    val risk = extraActivityRisk(input.feltHard, share)
    val decision = if (share > .1 || input.feltHard) PlanDecision.REDUCE_NEXT else PlanDecision.KEEP_PLAN
    return Consequence(
        ConsequenceKind.EXTRA_ACTIVITY,
        Deviation.UNPLANNED,
        metric,
        actual,
        LoadDelta(metric, actual),
        LoadDelta(metric, adjustment),
        risk,
        decision,
        setOf(
            PlanDecision.KEEP_PLAN,
            PlanDecision.REDUCE_NEXT,
            PlanDecision.NEXT_REST,
            PlanDecision.REBALANCE_WEEK,
        ),
    )
}

private fun extraActivityActual(input: ExtraActivityInput, timed: Boolean) =
    if (timed) input.durationSeconds ?: 0 else input.distanceMeters

private fun extraActivityWeeklyTarget(targets: ExtraActivityTargets, timed: Boolean) =
    if (timed) targets.weekTargetDurationSeconds else targets.weekTargetDistanceMeters

private fun extraActivityNextTarget(targets: ExtraActivityTargets, timed: Boolean) =
    if (timed) targets.nextRunTargetDurationSeconds ?: 0 else targets.nextRunTargetDistanceMeters

private fun extraActivityRisk(feltHard: Boolean, share: Double): Risk = when {
    feltHard && share > .2 -> Risk.UNSAFE
    share > .1 -> Risk.AGGRESSIVE
    feltHard -> Risk.MODERATE
    else -> Risk.CONSERVATIVE
}

enum class PrescriptionKind { DISTANCE, TIMED, OPEN, REST }
data class TimedIntervalStructure(
    val warmupSeconds: Int,
    val cooldownSeconds: Int,
    val blocks: List<RunWalkBlock>,
)
data class WorkoutProposal(
    val weekId: String,
    val scheduledDate: LocalDate,
    val type: WorkoutType,
    val prescriptionKind: PrescriptionKind,
    val targetDistanceMeters: Int,
    val targetDurationSeconds: Int? = null,
    val intervalStructure: TimedIntervalStructure? = null,
    val intensity: String = "easy",
    val purpose: String,
    val reason: String = "",
    val sourceRefs: List<String> = emptyList(),
    val isRemoved: Boolean = false,
)

data class EffectiveWorkoutState(
    val id: String,
    val status: String = "planned",
    val generated: WorkoutProposal,
    val current: WorkoutProposal = generated,
)
data class EditWeek(val id: String, val number: Int)
data class WeekLoad(val distanceMeters: Int, val durationSeconds: Int)
data class SpacingConflict(val workoutId: String, val scheduledDate: LocalDate, val purpose: String)
data class WorkoutChange(
    val workoutId: String,
    val selected: Boolean,
    val before: WorkoutProposal,
    val after: WorkoutProposal,
    val relativeChangePercent: Double?,
    val changeShareOfWeekPercent: Double?,
    val risk: Risk,
)

data class EditPreview(
    val operation: String,
    val recommended: WorkoutProposal?,
    val current: WorkoutProposal,
    val proposed: WorkoutProposal,
    val workoutChanges: List<WorkoutChange>,
    val weekLoads: Map<String, Pair<WeekLoad, WeekLoad>>,
    val spacingConflicts: List<SpacingConflict>,
    val affectedFutureWorkoutIds: List<String>,
    val weeklyLoadChangePercent: Double,
    val projectedRampPercent: Double,
    val projectedRampRisk: Risk,
    val prescriptionBasisChanged: Boolean,
    val risk: Risk,
    val requiresConfirmation: Boolean,
)

object WorkoutEdits {
    /** Persistence owns adjustment history; these pure transitions keep generated/current distinct. */
    fun apply(state: EffectiveWorkoutState, proposal: WorkoutProposal): EffectiveWorkoutState =
        state.copy(current = proposal)

    fun reset(state: EffectiveWorkoutState): EffectiveWorkoutState = state.copy(current = state.generated)

    /** An undo caller supplies the immediately preceding persisted effective proposal. */
    fun undo(state: EffectiveWorkoutState, previous: WorkoutProposal): EffectiveWorkoutState =
        state.copy(current = previous)

    fun preview(
        current: EffectiveWorkoutState,
        recommended: WorkoutProposal?,
        proposed: WorkoutProposal,
        states: List<EffectiveWorkoutState>,
        weeks: List<EditWeek>,
        today: LocalDate,
        rebalance: Boolean = false,
        operation: String = "edit",
    ): EditPreview {
        assertProposal(proposed)
        val before = states.associateBy { it.id }
        val rebalanced = if (rebalance) rebalance(current, proposed, before.values.toList(), today) else emptyMap()
        val selectedAfter = before + (current.id to current.copy(current = proposed))
        val after = selectedAfter.mapValues { (id, value) ->
            rebalanced[id]?.let { value.copy(current = it) } ?: value
        }
        val changedIds = listOf(current.id) + rebalanced.keys
        val changes = changedIds.distinct().map { id ->
            change(before.getValue(id), after.getValue(id), states, id == current.id)
        }
        val affected = setOf(current.current.weekId, proposed.weekId) + rebalanced.values.map { it.weekId }
        val loads = affected.associateWith { weekId -> weekLoad(before.values, weekId) to weekLoad(after.values, weekId) }
        val conflicts = if (proposed.isRemoved || proposed.type == WorkoutType.REST) {
            emptyList()
        } else {
            after.values
                .filter { state ->
                    state.id != current.id &&
                        state.status == "planned" &&
                        !state.current.isRemoved &&
                        state.current.type !in setOf(WorkoutType.REST, WorkoutType.RACE) &&
                        !state.current.scheduledDate.isBefore(today) &&
                        abs(
                            java.time.temporal.ChronoUnit.DAYS.between(
                                state.current.scheduledDate,
                                proposed.scheduledDate,
                            ),
                        ) <= 1
                }
                .map { state ->
                    SpacingConflict(state.id, state.current.scheduledDate, state.current.purpose)
                }
        }
        val destination = loads.getValue(proposed.weekId)
        val previous = weeks.indexOfFirst { it.id == proposed.weekId }.takeIf { it > 0 }?.let { index -> weekLoad(after.values, weeks[index - 1].id) } ?: destination.first
        val isRemovedOrRest = proposed.isRemoved || proposed.type == WorkoutType.REST
        val duration = proposed.prescriptionKind == PrescriptionKind.TIMED ||
            (isRemovedOrRest && current.current.prescriptionKind == PrescriptionKind.TIMED)
        val projected = if (duration) destination.second.durationSeconds else destination.second.distanceMeters
        val prior = if (duration) previous.durationSeconds else previous.distanceMeters
        val fallback = max(if (duration) destination.first.durationSeconds else destination.first.distanceMeters, 1)
        val ramp = percent(projected - prior, if (prior > 0) prior else fallback)
        val share = loads.values.maxOfOrNull { (old, new) ->
            val beforeLoad = if (duration) old.durationSeconds else old.distanceMeters
            val afterLoad = if (duration) new.durationSeconds else new.distanceMeters
            abs(afterLoad - beforeLoad).toDouble() / max(if (beforeLoad > 0) beforeLoad else afterLoad, 1)
        } ?: 0.0
        val risk = changes.fold(riskFromShare(share)) { high, item -> higher(high, item.risk) }
        val basis = changes.any {
            val beforeMetric = metric(it.before).first
            val afterMetric = metric(it.after).first
            beforeMetric != LoadMetric.NONE &&
                afterMetric != LoadMetric.NONE &&
                beforeMetric != afterMetric
        }
        val requiresConfirmation = risk != Risk.CONSERVATIVE || conflicts.isNotEmpty() || basis
        return EditPreview(
            operation,
            recommended,
            current.current,
            proposed,
            changes,
            loads,
            conflicts,
            rebalanced.keys.toList(),
            round1(share * 100),
            ramp,
            rampRisk(ramp),
            basis,
            risk,
            requiresConfirmation,
        )
    }

    fun assertProposal(proposal: WorkoutProposal) {
        require(proposal.purpose.trim().length in 2..120)
        require(proposal.type != WorkoutType.RACE) { "Race events are changed through goal setup." }
        when (proposal.prescriptionKind) {
            PrescriptionKind.REST -> require(validRestProposal(proposal))
            PrescriptionKind.DISTANCE -> require(validDistanceProposal(proposal))
            PrescriptionKind.TIMED -> require(validTimedProposal(proposal))
            PrescriptionKind.OPEN -> require(validOpenProposal(proposal))
        }
    }

    private fun validRestProposal(proposal: WorkoutProposal): Boolean =
        proposal.type == WorkoutType.REST &&
            proposal.intensity == "rest" &&
            proposal.targetDistanceMeters == 0 &&
            proposal.targetDurationSeconds == null &&
            proposal.intervalStructure == null

    private fun validDistanceProposal(proposal: WorkoutProposal): Boolean =
        proposal.type !in setOf(WorkoutType.REST, WorkoutType.RACE) &&
            proposal.intensity == "easy" &&
            proposal.targetDistanceMeters in 100..100_000 &&
            proposal.targetDurationSeconds == null &&
            proposal.intervalStructure == null

    private fun validTimedProposal(proposal: WorkoutProposal): Boolean {
        val duration = proposal.targetDurationSeconds ?: return false
        return proposal.type !in setOf(WorkoutType.REST, WorkoutType.RACE) &&
            proposal.intensity == "easy" &&
            proposal.targetDistanceMeters == 0 &&
            duration in 600..21_600 &&
            validIntervals(proposal.intervalStructure, duration)
    }

    private fun validOpenProposal(proposal: WorkoutProposal): Boolean =
        proposal.type == WorkoutType.EASY &&
            proposal.intensity == "easy" &&
            proposal.targetDistanceMeters == 0 &&
            proposal.targetDurationSeconds == null &&
            proposal.intervalStructure == null
    fun rampRisk(percent: Double): Risk {
        return when {
            percent > 18 -> Risk.UNSAFE
            percent > 12 -> Risk.AGGRESSIVE
            percent > 8 -> Risk.MODERATE
            else -> Risk.CONSERVATIVE
        }
    }
    private fun rebalance(selected: EffectiveWorkoutState, proposed: WorkoutProposal, states: List<EffectiveWorkoutState>, today: LocalDate): Map<String, WorkoutProposal> {
        if (
            proposed.isRemoved ||
            proposed.type == WorkoutType.REST ||
            proposed.prescriptionKind in setOf(PrescriptionKind.REST, PrescriptionKind.OPEN)
        ) return emptyMap()
        val before = weekLoad(states, proposed.weekId)
        val selectedAfter = states.map { if (it.id == selected.id) it.copy(current = proposed) else it }
        val after = weekLoad(selectedAfter, proposed.weekId)
        val delta = if (proposed.prescriptionKind == PrescriptionKind.TIMED) after.durationSeconds - before.durationSeconds else after.distanceMeters - before.distanceMeters
        val candidates = selectedAfter.filter { state ->
            isRebalanceCandidate(state, selected, proposed, today)
        }
        if (delta == 0 || candidates.isEmpty()) return emptyMap()
        val share = roundTrainingValueToInt(delta.toDouble() / candidates.size)
        return candidates.associate {
            it.id to if (proposed.prescriptionKind == PrescriptionKind.TIMED) {
                val duration = max(600, (it.current.targetDurationSeconds ?: 600) - share)
                it.current.copy(
                    targetDurationSeconds = duration,
                    intervalStructure = resizeTimedIntervalStructure(it.current.intervalStructure, duration),
                    reason = "Rebalanced after an explicit workout edit.",
                )
            } else {
                it.current.copy(
                    targetDistanceMeters = max(500, it.current.targetDistanceMeters - share),
                    reason = "Rebalanced after an explicit workout edit.",
                )
            }
        }
    }

    private fun isRebalanceCandidate(
        state: EffectiveWorkoutState,
        selected: EffectiveWorkoutState,
        proposed: WorkoutProposal,
        today: LocalDate,
    ): Boolean =
        belongsToRebalanceSet(state, selected, proposed) &&
            isMutablePlannedWorkout(state, today)

    private fun belongsToRebalanceSet(
        state: EffectiveWorkoutState,
        selected: EffectiveWorkoutState,
        proposed: WorkoutProposal,
    ): Boolean =
        state.id != selected.id &&
            state.current.weekId == proposed.weekId &&
            state.current.prescriptionKind == proposed.prescriptionKind

    private fun isMutablePlannedWorkout(state: EffectiveWorkoutState, today: LocalDate): Boolean =
        !state.current.scheduledDate.isBefore(today) &&
            state.status == "planned" &&
            !state.current.isRemoved &&
            state.current.type != WorkoutType.RACE
    private fun change(before: EffectiveWorkoutState, after: EffectiveWorkoutState, states: List<EffectiveWorkoutState>, selected: Boolean): WorkoutChange {
        val a = metric(before.current)
        val b = metric(after.current)
        val week = weekLoad(states, before.current.weekId)
        val amount = max(a.second, b.second)
        val base = max(max(if (a.first == LoadMetric.DURATION || b.first == LoadMetric.DURATION) week.durationSeconds else week.distanceMeters, amount), 1)
        val share = if (a.first == LoadMetric.NONE || b.first == LoadMetric.NONE || a.first != b.first) amount.toDouble() / base else abs(b.second - a.second).toDouble() / base
        val relative = if (a.first == LoadMetric.NONE || b.first == LoadMetric.NONE || a.first != b.first) null else percent(abs(b.second - a.second), max(max(a.second, b.second), 1))
        return WorkoutChange(before.id, selected, before.current, after.current, relative, round1(share * 100), riskFromShare(share))
    }
    private fun weekLoad(states: Iterable<EffectiveWorkoutState>, weekId: String): WeekLoad =
        states
            .filter { state ->
                state.current.weekId == weekId &&
                    !state.current.isRemoved &&
                    state.current.type !in setOf(WorkoutType.REST, WorkoutType.RACE)
            }
            .fold(WeekLoad(0, 0)) { load, state ->
                WeekLoad(
                    load.distanceMeters + state.current.targetDistanceMeters,
                    load.durationSeconds + (state.current.targetDurationSeconds ?: 0),
                )
            }

    private fun metric(value: WorkoutProposal): Pair<LoadMetric, Int> = when {
        value.isRemoved || value.type == WorkoutType.REST ||
            value.prescriptionKind in setOf(PrescriptionKind.REST, PrescriptionKind.OPEN) ->
            LoadMetric.NONE to 0
        value.prescriptionKind == PrescriptionKind.TIMED -> {
            LoadMetric.DURATION to (value.targetDurationSeconds ?: 0)
        }
        else -> LoadMetric.DISTANCE to value.targetDistanceMeters
    }

    private fun riskFromShare(share: Double) = when {
        share > .25 -> Risk.UNSAFE
        share > .15 -> Risk.AGGRESSIVE
        share > .10 -> Risk.MODERATE
        else -> Risk.CONSERVATIVE
    }
    private fun higher(a: Risk, b: Risk) = if (a.ordinal >= b.ordinal) a else b
    private fun percent(delta: Int, base: Int) = round1(delta.toDouble() / max(base, 1) * 100)
    private fun round1(value: Double) = roundTrainingValueToOneDecimal(value)

    private fun validIntervals(structure: TimedIntervalStructure?, totalDurationSeconds: Int): Boolean =
        structure != null &&
            structure.warmupSeconds >= 0 &&
            structure.cooldownSeconds >= 0 &&
            structure.blocks.isNotEmpty() &&
            structure.blocks.all { block ->
                block.repetitions in 1..100 &&
                    block.segments.isNotEmpty() &&
                    block.segments.all { segment ->
                        segment.kind in setOf(SegmentKind.RUN, SegmentKind.WALK) &&
                            segment.durationSeconds > 0
                    }
            } &&
            structure.warmupSeconds + structure.cooldownSeconds + blocksDuration(structure.blocks) == totalDurationSeconds

    private fun blocksDuration(blocks: List<RunWalkBlock>): Int =
        blocks.sumOf { block -> block.repetitions * block.segments.sumOf { it.durationSeconds } }

    fun resizeTimedIntervalStructure(structure: TimedIntervalStructure?, totalDurationSeconds: Int): TimedIntervalStructure? {
        if (structure == null) return null
        val currentTotal = structure.warmupSeconds + structure.cooldownSeconds + blocksDuration(structure.blocks)
        if (currentTotal <= 0 || totalDurationSeconds <= 0) return structure.copy()
        val factor = totalDurationSeconds.toDouble() / currentTotal
        val resized = TimedIntervalStructure(
            warmupSeconds = max(0, roundTrainingValueToInt(structure.warmupSeconds * factor)),
            cooldownSeconds = max(0, roundTrainingValueToInt(structure.cooldownSeconds * factor)),
            blocks = structure.blocks.map { block ->
                block.copy(segments = block.segments.map { segment ->
                    segment.copy(durationSeconds = max(1, roundTrainingValueToInt(segment.durationSeconds * factor)))
                })
            },
        )
        val resizedTotal = resized.warmupSeconds + resized.cooldownSeconds + blocksDuration(resized.blocks)
        return resized.copy(cooldownSeconds = max(0, resized.cooldownSeconds + totalDurationSeconds - resizedTotal))
    }
}
