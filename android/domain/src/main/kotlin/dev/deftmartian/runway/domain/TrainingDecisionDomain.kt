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
    PAIN_REPORTED,
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
    val pain: Boolean = false,
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
    private val material = setOf(PlanDecision.KEEP_PLAN, PlanDecision.REDUCE_NEXT, PlanDecision.NEXT_REST, PlanDecision.REPEAT_PRESCRIPTION, PlanDecision.REBALANCE_WEEK)

    fun calculate(input: FeedbackInput): Consequence {
        require(input.targetDistanceMeters >= 0 && input.weekTargetDistanceMeters >= 0)
        require(input.targetDurationSeconds == null || input.targetDurationSeconds > 0)
        require(input.completedDistanceMeters == null || input.completedDistanceMeters >= 0)
        require(input.completedDurationSeconds == null || input.completedDurationSeconds > 0)
        require(input.recentSkippedWorkouts >= 0 && input.recentShortenedWorkouts >= 0)
        if (input.status == FeedbackStatus.SKIPPED) require(input.completedDistanceMeters in listOf(null, 0) && input.completedDurationSeconds == null)
        val comparison = compare(input)
        val repeatedSkip = input.recentSkippedWorkouts > 0
        val repeatedShortfall = input.recentSkippedWorkouts + input.recentShortenedWorkouts > 0
        val weekly = comparison.delta()
        fun result(
            kind: ConsequenceKind,
            adjustment: Int,
            risk: Risk,
            decision: PlanDecision,
            options: Set<PlanDecision> =
                if (kind in setOf(ConsequenceKind.COMPLETED_AS_PLANNED, ConsequenceKind.NEEDS_REVIEW)) {
                    setOf(PlanDecision.KEEP_PLAN)
                } else {
                    material
                },
        ) = Consequence(
            kind = kind,
            deviation = comparison.deviation,
            metric = comparison.metric,
            actualDifference = comparison.difference,
            weeklyLoadDelta = weekly,
            nextRunAdjustment =
                if (!comparison.comparable && !input.pain && !input.feltHard) {
                    null
                } else {
                    comparison.adjustment(adjustment)
                },
            risk = risk,
            recommendedDecision = decision,
            options = options,
            comparisonStatus = "not_comparable".takeUnless { comparison.comparable },
            planChangeAvailable = comparison.comparable || input.pain || input.feltHard,
        )
        if (input.pain) return result(ConsequenceKind.PAIN_REPORTED, -max(if (comparison.metric == LoadMetric.DURATION) input.targetDurationSeconds ?: 0 else input.targetDistanceMeters, if (comparison.metric == LoadMetric.DURATION) 300 else 1_000), Risk.UNSAFE, PlanDecision.NEXT_REST, material - PlanDecision.REPEAT_PRESCRIPTION)
        return when (comparison.deviation) {
            Deviation.OVER -> {
                val guardrail = if (comparison.metric == LoadMetric.DISTANCE) max(input.weekTargetDistanceMeters, input.targetDistanceMeters).coerceAtLeast(1) else comparison.target.coerceAtLeast(1)
                val crosses = comparison.difference.toDouble() / guardrail > .10
                val severe = comparison.actual >= comparison.target * 2
                result(ConsequenceKind.LOAD_SPIKE, -max(if (comparison.metric == LoadMetric.DURATION) 300 else 1_000, roundLikeJavaScriptToInt(comparison.difference * .5)), if (input.feltHard && crosses && severe) Risk.UNSAFE else if (crosses) Risk.AGGRESSIVE else Risk.MODERATE, if (crosses) PlanDecision.REDUCE_NEXT else PlanDecision.KEEP_PLAN)
            }
            Deviation.SHORT -> {
                val large = abs(comparison.difference) > comparison.target * .4 || comparison.actual == 0
                result(if (repeatedShortfall) ConsequenceKind.REPEATED_SHORTFALL else ConsequenceKind.SHORTFALL, -max(if (comparison.metric == LoadMetric.DURATION) 300 else 500, roundLikeJavaScriptToInt(abs(comparison.difference) * if (input.feltHard || repeatedShortfall) .35 else .25)), if (large || input.feltHard || repeatedShortfall) Risk.MODERATE else Risk.CONSERVATIVE, if (input.feltHard || repeatedShortfall) PlanDecision.REPEAT_PRESCRIPTION else PlanDecision.KEEP_PLAN)
            }
            Deviation.SKIPPED -> result(if (repeatedSkip) ConsequenceKind.REPEATED_SKIP else if (input.choice == PlanDecision.REDUCE_NEXT) ConsequenceKind.SKIP_REDUCE else ConsequenceKind.SKIP_CONTINUE, -max(if (comparison.metric == LoadMetric.DURATION) 300 else 500, roundLikeJavaScriptToInt(comparison.target * if (input.feltHard) .3 else .2)), if (input.feltHard || repeatedSkip) Risk.MODERATE else Risk.CONSERVATIVE, if (input.feltHard || repeatedSkip) PlanDecision.REPEAT_PRESCRIPTION else PlanDecision.KEEP_PLAN)
            Deviation.NEAR_PLAN -> if (input.feltHard) result(ConsequenceKind.HARD_EFFORT, -max(if (comparison.metric == LoadMetric.DURATION) 300 else 1_000, roundLikeJavaScriptToInt(comparison.target * .15)), Risk.MODERATE, PlanDecision.REDUCE_NEXT) else result(ConsequenceKind.COMPLETED_AS_PLANNED, 0, Risk.CONSERVATIVE, PlanDecision.KEEP_PLAN)
            Deviation.NOT_APPLICABLE -> if (input.feltHard) {
                result(
                    ConsequenceKind.HARD_EFFORT,
                    -max(
                        if (comparison.metric == LoadMetric.DURATION) 300 else 1_000,
                        roundLikeJavaScriptToInt(comparison.target * .15),
                    ),
                    Risk.MODERATE,
                    PlanDecision.REDUCE_NEXT,
                    setOf(PlanDecision.KEEP_PLAN, PlanDecision.REDUCE_NEXT, PlanDecision.NEXT_REST),
                )
            } else {
                result(
                    ConsequenceKind.NEEDS_REVIEW,
                    0,
                    Risk.MODERATE,
                    PlanDecision.KEEP_PLAN,
                )
            }
            else -> result(ConsequenceKind.COMPLETED_AS_PLANNED, 0, Risk.CONSERVATIVE, PlanDecision.KEEP_PLAN)
        }
    }

    fun apply(consequence: Consequence, decision: PlanDecision): Consequence {
        require(decision in consequence.options) { "Decision is not available for this result." }
        return consequence.copy(appliedDecision = decision)
    }

    fun decisionEffect(consequence: Consequence, decision: PlanDecision, target: DecisionTarget, shareCount: Int = 1): DecisionEffect? {
        if (decision !in setOf(PlanDecision.REDUCE_NEXT, PlanDecision.REBALANCE_WEEK)) return null
        val metric = if ((target.targetDurationSeconds ?: 0) > 0) LoadMetric.DURATION else LoadMetric.DISTANCE
        val previous = if (metric == LoadMetric.DURATION) target.targetDurationSeconds ?: 0 else target.targetDistanceMeters
        if (previous <= 0) return null
        val matching = consequence.nextRunAdjustment?.takeIf { it.metric == metric }?.value?.let(::abs) ?: 0
        val total = matching.takeIf { it > 0 } ?: if (metric == LoadMetric.DURATION) max(300, roundLikeJavaScriptToInt(previous * .15)) else 500
        val reduction = if (decision == PlanDecision.REBALANCE_WEEK) ceil(total.toDouble() / max(1, shareCount)).toInt() else total
        val next = max(if (metric == LoadMetric.DURATION) 600 else 500, previous - reduction)
        return DecisionEffect(metric, previous, next - previous, next)
    }

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
        val metric = if ((input.targetDurationSeconds ?: 0) > 0) LoadMetric.DURATION else LoadMetric.DISTANCE
        val target = if (metric == LoadMetric.DURATION) input.targetDurationSeconds ?: 0 else input.targetDistanceMeters
        if (input.status == FeedbackStatus.SKIPPED) return Comparison(Deviation.SKIPPED, metric, -target, target, 0)
        if (target <= 0) return Comparison(Deviation.NOT_APPLICABLE, LoadMetric.NONE, 0, 0, 0)
        val recordedActual =
            if (metric == LoadMetric.DURATION) input.completedDurationSeconds else input.completedDistanceMeters
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
        return Comparison(if (difference < -threshold) Deviation.SHORT else if (difference > threshold) Deviation.OVER else Deviation.NEAR_PLAN, metric, difference, target, actual)
    }
}

data class MatchActivity(val activityDate: LocalDate, val distanceMeters: Int, val durationSeconds: Int? = null)
data class MatchWorkout(val id: String, val scheduledDate: LocalDate, val targetDistanceMeters: Int, val targetDurationSeconds: Int? = null)
fun selectAutoWorkoutMatch(activity: MatchActivity, candidates: Iterable<MatchWorkout>): String? {
    val ranked = candidates.mapNotNull { candidate ->
        val ratio = if (candidate.targetDistanceMeters > 0) {
            val difference = abs(activity.distanceMeters - candidate.targetDistanceMeters)
            difference.takeIf { it <= max(500.0, candidate.targetDistanceMeters * .15) }?.toDouble()?.div(candidate.targetDistanceMeters)
        } else if ((candidate.targetDurationSeconds ?: 0) > 0 && (activity.durationSeconds ?: 0) > 0) {
            val difference = abs((activity.durationSeconds ?: 0) - (candidate.targetDurationSeconds ?: 0))
            difference.takeIf { it <= max(300.0, (candidate.targetDurationSeconds ?: 0) * .15) }?.toDouble()?.div(candidate.targetDurationSeconds ?: 1)
        } else null
        ratio?.let { Triple(candidate, abs(java.time.temporal.ChronoUnit.DAYS.between(activity.activityDate, candidate.scheduledDate)), it) }
    }.sortedWith(compareBy<Triple<MatchWorkout, Long, Double>> { it.second }.thenBy { it.third }.thenBy { it.first.id })
    val best = ranked.firstOrNull() ?: return null
    val second = ranked.getOrNull(1)
    return if (second != null && second.second == best.second && abs(second.third - best.third) < 1e-12) null else best.first.id
}

data class ExtraActivityInput(val distanceMeters: Int, val durationSeconds: Int? = null, val feltHard: Boolean = false, val pain: Boolean = false)
data class ExtraActivityTargets(val nextRunTargetDistanceMeters: Int, val nextRunTargetDurationSeconds: Int? = null, val weekTargetDistanceMeters: Int, val weekTargetDurationSeconds: Int)
fun isHistoricalExtraActivity(activityDate: LocalDate, today: LocalDate) = activityDate.isBefore(today.minusDays(7))
fun historicalExtraActivityReview(consequence: Consequence) = consequence.copy(nextRunAdjustment = null, planChangeAvailable = false, options = emptySet())
fun calculateExtraActivityConsequence(input: ExtraActivityInput, targets: ExtraActivityTargets): Consequence {
    val timed = (targets.nextRunTargetDurationSeconds ?: 0) > 0
    val missingNative = if (timed) (input.durationSeconds ?: 0) <= 0 else input.distanceMeters <= 0
    val metric = if (timed) LoadMetric.DURATION else LoadMetric.DISTANCE
    if (missingNative) {
        val next = if (timed) targets.nextRunTargetDurationSeconds ?: 0 else targets.nextRunTargetDistanceMeters
        val adjustment = if (input.pain) null else if (input.feltHard) LoadDelta(metric, -max(if (timed) 300 else 1_000, roundLikeJavaScriptToInt(next * .15))) else null
        return Consequence(if (input.pain) ConsequenceKind.PAIN_REPORTED else ConsequenceKind.EXTRA_ACTIVITY, Deviation.UNPLANNED, LoadMetric.NONE, 0, null, adjustment, if (input.pain) Risk.UNSAFE else Risk.MODERATE, if (input.pain) PlanDecision.NEXT_REST else if (input.feltHard) PlanDecision.REDUCE_NEXT else PlanDecision.KEEP_PLAN, if (input.pain) setOf(PlanDecision.KEEP_PLAN, PlanDecision.NEXT_REST) else if (adjustment != null) setOf(PlanDecision.KEEP_PLAN, PlanDecision.REDUCE_NEXT, PlanDecision.NEXT_REST) else setOf(PlanDecision.KEEP_PLAN, PlanDecision.NEXT_REST), comparisonStatus = "not_comparable")
    }
    val actual = if (timed) input.durationSeconds ?: 0 else input.distanceMeters
    val weekly = if (timed) targets.weekTargetDurationSeconds else targets.weekTargetDistanceMeters
    val share = if (weekly > 0) actual.toDouble() / weekly else 0.0
    val next = if (timed) targets.nextRunTargetDurationSeconds ?: 0 else targets.nextRunTargetDistanceMeters
    val adjustment = -min(next, max(if (timed) 300 else 1_000, roundLikeJavaScriptToInt(actual * if (input.feltHard) .6 else .5)))
    if (input.pain) return Consequence(ConsequenceKind.PAIN_REPORTED, Deviation.UNPLANNED, metric, actual, LoadDelta(metric, actual), LoadDelta(metric, -max(max(if (timed) 300 else 1_000, next), roundLikeJavaScriptToInt(actual * .5))), Risk.UNSAFE, PlanDecision.NEXT_REST, setOf(PlanDecision.KEEP_PLAN, PlanDecision.REDUCE_NEXT, PlanDecision.NEXT_REST, PlanDecision.REBALANCE_WEEK))
    val risk = if (input.feltHard && share > .2) Risk.UNSAFE else if (share > .1) Risk.AGGRESSIVE else if (input.feltHard) Risk.MODERATE else Risk.CONSERVATIVE
    return Consequence(ConsequenceKind.EXTRA_ACTIVITY, Deviation.UNPLANNED, metric, actual, LoadDelta(metric, actual), LoadDelta(metric, adjustment), risk, if (share > .1 || input.feltHard) PlanDecision.REDUCE_NEXT else PlanDecision.KEEP_PLAN, setOf(PlanDecision.KEEP_PLAN, PlanDecision.REDUCE_NEXT, PlanDecision.NEXT_REST, PlanDecision.REBALANCE_WEEK))
}

enum class PrescriptionKind { DISTANCE, TIMED, REST }
data class TimedIntervalStructure(
    val warmupSeconds: Int,
    val cooldownSeconds: Int,
    val blocks: List<RunWalkBlock>,
)
data class WorkoutProposal(val weekId: String, val scheduledDate: LocalDate, val type: WorkoutType, val prescriptionKind: PrescriptionKind, val targetDistanceMeters: Int, val targetDurationSeconds: Int? = null, val intervalStructure: TimedIntervalStructure? = null, val intensity: String = "easy", val purpose: String, val reason: String = "", val sourceRefs: List<String> = emptyList(), val isRemoved: Boolean = false)
data class EffectiveWorkoutState(val id: String, val status: String = "planned", val generated: WorkoutProposal, val current: WorkoutProposal = generated)
data class EditWeek(val id: String, val number: Int)
data class WeekLoad(val distanceMeters: Int, val durationSeconds: Int)
data class SpacingConflict(val workoutId: String, val scheduledDate: LocalDate, val purpose: String)
data class WorkoutChange(val workoutId: String, val selected: Boolean, val before: WorkoutProposal, val after: WorkoutProposal, val relativeChangePercent: Double?, val changeShareOfWeekPercent: Double?, val risk: Risk)
data class EditPreview(val operation: String, val recommended: WorkoutProposal?, val current: WorkoutProposal, val proposed: WorkoutProposal, val workoutChanges: List<WorkoutChange>, val weekLoads: Map<String, Pair<WeekLoad, WeekLoad>>, val spacingConflicts: List<SpacingConflict>, val affectedFutureWorkoutIds: List<String>, val weeklyLoadChangePercent: Double, val projectedRampPercent: Double, val projectedRampRisk: Risk, val prescriptionBasisChanged: Boolean, val risk: Risk, val requiresConfirmation: Boolean)

object WorkoutEdits {
    /** Persistence owns adjustment history; these pure transitions keep generated/current distinct. */
    fun apply(state: EffectiveWorkoutState, proposal: WorkoutProposal): EffectiveWorkoutState =
        state.copy(current = proposal)

    fun reset(state: EffectiveWorkoutState): EffectiveWorkoutState = state.copy(current = state.generated)

    /** An undo caller supplies the immediately preceding persisted effective proposal. */
    fun undo(state: EffectiveWorkoutState, previous: WorkoutProposal): EffectiveWorkoutState =
        state.copy(current = previous)

    fun preview(current: EffectiveWorkoutState, recommended: WorkoutProposal?, proposed: WorkoutProposal, states: List<EffectiveWorkoutState>, weeks: List<EditWeek>, today: LocalDate, rebalance: Boolean = false, hasInjuryRisk: Boolean = false, operation: String = "edit"): EditPreview {
        assertProposal(proposed)
        val before = states.associateBy { it.id }
        val rebalanced = if (rebalance) rebalance(current, proposed, before.values.toList(), today) else emptyMap()
        val selectedAfter = before + (current.id to current.copy(current = proposed))
        val after = selectedAfter.mapValues { (id, value) -> rebalanced[id]?.let { value.copy(current = it) } ?: value }
        val changedIds = listOf(current.id) + rebalanced.keys
        val changes = changedIds.distinct().map { id -> change(before.getValue(id), after.getValue(id), states, id == current.id) }
        val affected = setOf(current.current.weekId, proposed.weekId) + rebalanced.values.map { it.weekId }
        val loads = affected.associateWith { weekId -> weekLoad(before.values, weekId) to weekLoad(after.values, weekId) }
        val conflicts = if (proposed.isRemoved || proposed.type == WorkoutType.REST) emptyList() else after.values.filter { it.id != current.id && it.status == "planned" && !it.current.isRemoved && it.current.type !in setOf(WorkoutType.REST, WorkoutType.RACE) && !it.current.scheduledDate.isBefore(today) && abs(java.time.temporal.ChronoUnit.DAYS.between(it.current.scheduledDate, proposed.scheduledDate)) <= 1 }.map { SpacingConflict(it.id, it.current.scheduledDate, it.current.purpose) }
        val destination = loads.getValue(proposed.weekId)
        val previous = weeks.indexOfFirst { it.id == proposed.weekId }.takeIf { it > 0 }?.let { index -> weekLoad(after.values, weeks[index - 1].id) } ?: destination.first
        val duration = proposed.prescriptionKind == PrescriptionKind.TIMED || ((proposed.isRemoved || proposed.type == WorkoutType.REST) && current.current.prescriptionKind == PrescriptionKind.TIMED)
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
        return EditPreview(operation, recommended, current.current, proposed, changes, loads, conflicts, rebalanced.keys.toList(), round1(share * 100), ramp, rampRisk(ramp, hasInjuryRisk), basis, risk, risk != Risk.CONSERVATIVE || conflicts.isNotEmpty() || basis)
    }

    fun assertProposal(proposal: WorkoutProposal) {
        require(proposal.purpose.trim().length in 2..120)
        require(proposal.type != WorkoutType.RACE) { "Race events are changed through goal setup." }
        when (proposal.prescriptionKind) {
            PrescriptionKind.REST -> require(proposal.type == WorkoutType.REST && proposal.intensity == "rest" && proposal.targetDistanceMeters == 0 && proposal.targetDurationSeconds == null && proposal.intervalStructure == null)
            PrescriptionKind.DISTANCE -> require(proposal.type !in setOf(WorkoutType.REST, WorkoutType.RACE) && proposal.intensity == "easy" && proposal.targetDistanceMeters in 100..100_000 && proposal.targetDurationSeconds == null && proposal.intervalStructure == null)
            PrescriptionKind.TIMED -> require(proposal.type !in setOf(WorkoutType.REST, WorkoutType.RACE) && proposal.intensity == "easy" && proposal.targetDistanceMeters == 0 && proposal.targetDurationSeconds != null && proposal.targetDurationSeconds in 600..21_600 && validIntervals(proposal.intervalStructure, proposal.targetDurationSeconds))
        }
    }
    fun rampRisk(percent: Double, injury: Boolean = false): Risk = when { percent > 18 - if (injury) 2 else 0 -> Risk.UNSAFE; percent > 12 - if (injury) 2 else 0 -> Risk.AGGRESSIVE; percent > 8 - if (injury) 2 else 0 -> Risk.MODERATE; else -> Risk.CONSERVATIVE }
    private fun rebalance(selected: EffectiveWorkoutState, proposed: WorkoutProposal, states: List<EffectiveWorkoutState>, today: LocalDate): Map<String, WorkoutProposal> {
        if (proposed.isRemoved || proposed.type == WorkoutType.REST || proposed.prescriptionKind == PrescriptionKind.REST) return emptyMap()
        val before = weekLoad(states, proposed.weekId)
        val selectedAfter = states.map { if (it.id == selected.id) it.copy(current = proposed) else it }
        val after = weekLoad(selectedAfter, proposed.weekId)
        val delta = if (proposed.prescriptionKind == PrescriptionKind.TIMED) after.durationSeconds - before.durationSeconds else after.distanceMeters - before.distanceMeters
        val candidates = selectedAfter.filter { it.id != selected.id && it.current.weekId == proposed.weekId && !it.current.scheduledDate.isBefore(today) && it.status == "planned" && !it.current.isRemoved && it.current.type != WorkoutType.RACE && it.current.prescriptionKind == proposed.prescriptionKind }
        if (delta == 0 || candidates.isEmpty()) return emptyMap()
        val share = roundLikeJavaScriptToInt(delta.toDouble() / candidates.size)
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
    private fun change(before: EffectiveWorkoutState, after: EffectiveWorkoutState, states: List<EffectiveWorkoutState>, selected: Boolean): WorkoutChange {
        val a = metric(before.current); val b = metric(after.current)
        val week = weekLoad(states, before.current.weekId)
        val amount = max(a.second, b.second)
        val base = max(max(if (a.first == LoadMetric.DURATION || b.first == LoadMetric.DURATION) week.durationSeconds else week.distanceMeters, amount), 1)
        val share = if (a.first == LoadMetric.NONE || b.first == LoadMetric.NONE || a.first != b.first) amount.toDouble() / base else abs(b.second - a.second).toDouble() / base
        val relative = if (a.first == LoadMetric.NONE || b.first == LoadMetric.NONE || a.first != b.first) null else percent(abs(b.second - a.second), max(max(a.second, b.second), 1))
        return WorkoutChange(before.id, selected, before.current, after.current, relative, round1(share * 100), riskFromShare(share))
    }
    private fun weekLoad(states: Iterable<EffectiveWorkoutState>, weekId: String): WeekLoad = states.filter { it.current.weekId == weekId && !it.current.isRemoved && it.current.type !in setOf(WorkoutType.REST, WorkoutType.RACE) }.fold(WeekLoad(0, 0)) { load, state -> WeekLoad(load.distanceMeters + state.current.targetDistanceMeters, load.durationSeconds + (state.current.targetDurationSeconds ?: 0)) }
    private fun metric(value: WorkoutProposal): Pair<LoadMetric, Int> = when { value.isRemoved || value.type == WorkoutType.REST || value.prescriptionKind == PrescriptionKind.REST -> LoadMetric.NONE to 0; value.prescriptionKind == PrescriptionKind.TIMED -> LoadMetric.DURATION to (value.targetDurationSeconds ?: 0); else -> LoadMetric.DISTANCE to value.targetDistanceMeters }
    private fun riskFromShare(share: Double) = when { share > .25 -> Risk.UNSAFE; share > .15 -> Risk.AGGRESSIVE; share > .10 -> Risk.MODERATE; else -> Risk.CONSERVATIVE }
    private fun higher(a: Risk, b: Risk) = if (a.ordinal >= b.ordinal) a else b
    private fun percent(delta: Int, base: Int) = round1(delta.toDouble() / max(base, 1) * 100)
    private fun round1(value: Double) = roundOneDecimalLikeJavaScript(value)

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
            warmupSeconds = max(0, roundLikeJavaScriptToInt(structure.warmupSeconds * factor)),
            cooldownSeconds = max(0, roundLikeJavaScriptToInt(structure.cooldownSeconds * factor)),
            blocks = structure.blocks.map { block ->
                block.copy(segments = block.segments.map { segment ->
                    segment.copy(durationSeconds = max(1, roundLikeJavaScriptToInt(segment.durationSeconds * factor)))
                })
            },
        )
        val resizedTotal = resized.warmupSeconds + resized.cooldownSeconds + blocksDuration(resized.blocks)
        return resized.copy(cooldownSeconds = max(0, resized.cooldownSeconds + totalDurationSeconds - resizedTotal))
    }
}
