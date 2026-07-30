package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.Consequence
import dev.deftmartian.runway.domain.Consequences
import dev.deftmartian.runway.domain.DecisionTarget
import dev.deftmartian.runway.domain.LoadMetric
import dev.deftmartian.runway.domain.PlanDecision
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Pure preview/apply contract. Persistence must re-read state and apply all returned effects atomically. */
object LocalConsequenceDecisionEngine {
    fun preview(input: LocalDecisionInput): LocalDecisionResult {
        if (!input.consequence.planChangeAvailable || input.consequence.appliedDecision != null) {
            return rejected(LocalDecisionIssue.PROPOSAL_NOT_AVAILABLE)
        }
        if (input.decision !in input.consequence.options) return rejected(LocalDecisionIssue.DECISION_NOT_OFFERED)
        val eligible = eligibleFutureDecisionWorkouts(
            input.candidates,
            input.originEpochDay,
            input.todayEpochDay,
        )
        val changes = try {
            when (input.decision) {
                PlanDecision.KEEP_PLAN -> emptyList()
                PlanDecision.REDUCE_NEXT -> eligible.firstOrNull()?.let { listOf(reduce(it, input.consequence, 1)) }
                PlanDecision.NEXT_REST -> eligible.firstOrNull()?.let { listOf(rest(it)) }
                PlanDecision.REPEAT_PRESCRIPTION -> {
                    val origin = input.originWorkout
                        ?.takeIf { it.currentWorkoutType !in setOf("rest", "race") }
                        ?: return rejected(LocalDecisionIssue.NO_REPEATABLE_PRESCRIPTION)
                    eligible.firstOrNull()?.let { listOf(repeat(it, origin)) }
                }
                PlanDecision.REBALANCE_WEEK -> {
                    val end = LocalDate.ofEpochDay(input.originEpochDay)
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(6).toEpochDay()
                    val affected = eligible.filter { it.currentScheduledEpochDay <= end && compatible(input.consequence, it) }
                    if (affected.isEmpty()) return rejected(LocalDecisionIssue.NO_COMPATIBLE_WORKOUT)
                    affected.map { reduce(it, input.consequence, affected.size) }
                }
            }
        } catch (error: LocalDecisionProjectionException) {
            return rejected(error.issue)
        } ?: return rejected(LocalDecisionIssue.NO_FUTURE_WORKOUT)
        return LocalDecisionResult.Preview(
            token = decisionToken(input, eligible),
            source = input.source,
            decision = input.decision,
            changes = changes,
        )
    }

    fun apply(
        preview: LocalDecisionResult.Preview,
        current: LocalDecisionInput,
        alreadyApplied: Boolean,
    ): LocalDecisionApplyResult {
        if (alreadyApplied || current.consequence.appliedDecision != null) {
            return LocalDecisionApplyResult.Rejected(LocalDecisionIssue.ALREADY_APPLIED)
        }
        val refreshed = preview(current)
        if (refreshed !is LocalDecisionResult.Preview || refreshed.token != preview.token || refreshed.changes != preview.changes) {
            return LocalDecisionApplyResult.Rejected(LocalDecisionIssue.STALE_PREVIEW)
        }
        return LocalDecisionApplyResult.Ready(
            source = preview.source,
            decision = preview.decision,
            changes = preview.changes,
        )
    }

    private fun reduce(workout: WorkoutEntity, consequence: Consequence, shareCount: Int): LocalWorkoutDecisionChange {
        val effect = Consequences.decisionEffect(
            consequence,
            if (shareCount == 1) PlanDecision.REDUCE_NEXT else PlanDecision.REBALANCE_WEEK,
            DecisionTarget(workout.currentDistanceMeters ?: 0, workout.currentDurationSeconds),
            shareCount,
        ) ?: throw LocalDecisionProjectionException(LocalDecisionIssue.NO_REDUCIBLE_AMOUNT)
        val after = if (effect.metric == LoadMetric.DURATION) {
            workout.copy(
                currentDurationSeconds = effect.newTarget,
                currentReason = "The runner explicitly reduced this timed workout after reviewing a result.",
            )
        } else {
            workout.copy(
                currentDistanceMeters = effect.newTarget,
                currentReason = if (shareCount > 1) {
                    "The runner explicitly rebalanced the remaining week."
                } else {
                    "The runner explicitly reduced this workout after reviewing a result."
                },
            )
        }
        return LocalWorkoutDecisionChange(workout, after, effect.adjustment)
    }

    private fun rest(workout: WorkoutEntity) = LocalWorkoutDecisionChange(
        before = workout,
        after = workout.copy(
            currentWorkoutType = "rest",
            currentPrescriptionKind = "rest",
            currentDistanceMeters = null,
            currentDurationSeconds = null,
            currentIntensity = "rest",
            currentPurpose = "Recovery day",
            currentReason = "The runner explicitly chose rest after reviewing the recorded result.",
            currentWarmupSeconds = null,
            currentCooldownSeconds = null,
        ),
        adjustment = null,
    )

    private fun repeat(candidate: WorkoutEntity, origin: WorkoutEntity) = LocalWorkoutDecisionChange(
        before = candidate,
        after = candidate.copy(
            currentWorkoutType = origin.currentWorkoutType,
            currentPrescriptionKind = origin.currentPrescriptionKind,
            currentDistanceMeters = origin.currentDistanceMeters,
            currentDurationSeconds = origin.currentDurationSeconds,
            currentIntensity = origin.currentIntensity,
            currentPurpose = origin.currentPurpose,
            currentReason = "The runner explicitly chose to repeat the earlier prescription.",
            currentWarmupSeconds = origin.currentWarmupSeconds,
            currentCooldownSeconds = origin.currentCooldownSeconds,
        ),
        adjustment = null,
    )

    private fun compatible(consequence: Consequence, workout: WorkoutEntity) = when (consequence.metric) {
        LoadMetric.DURATION -> (workout.currentDurationSeconds ?: 0) > 0
        LoadMetric.DISTANCE -> (workout.currentDurationSeconds ?: 0) <= 0 && (workout.currentDistanceMeters ?: 0) > 0
        LoadMetric.NONE -> false
    }

    private fun decisionToken(input: LocalDecisionInput, eligible: List<WorkoutEntity>): String {
        val material = buildList {
            add(input.source.kind.name)
            add(input.source.sourceId)
            add(input.source.version)
            add(input.decision.name)
            eligible.forEach {
                add(listOf(it.workoutId, it.updatedAtEpochMillis, it.currentScheduledEpochDay, it.currentWorkoutType,
                    it.currentPrescriptionKind, it.currentStatus, it.currentDistanceMeters, it.currentDurationSeconds,
                    it.currentIntensity, it.currentPurpose, it.currentReason, it.tombstonedAtEpochMillis).joinToString("|"))
            }
        }.joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun rejected(issue: LocalDecisionIssue) = LocalDecisionResult.Rejected(issue)
}

/**
 * Shared target boundary for previewing and applying an explicit consequence decision.
 * An activity's consequence must be calculated from this same ordered future-workout set.
 */
internal fun eligibleFutureDecisionWorkouts(
    candidates: List<WorkoutEntity>,
    originEpochDay: Long,
    todayEpochDay: Long,
): List<WorkoutEntity> = candidates
    .filter {
        it.currentStatus == "planned" &&
            it.tombstonedAtEpochMillis == null &&
            it.currentWorkoutType !in setOf("rest", "race") &&
            it.currentScheduledEpochDay > originEpochDay &&
            it.currentScheduledEpochDay >= todayEpochDay
    }
    .sortedWith(compareBy(WorkoutEntity::currentScheduledEpochDay, WorkoutEntity::workoutId))
    .take(52)

data class LocalDecisionSource(val kind: LocalDecisionSourceKind, val sourceId: String, val version: String)
enum class LocalDecisionSourceKind { WorkoutFeedback, Activity }

data class LocalDecisionInput(
    val source: LocalDecisionSource,
    val decision: PlanDecision,
    val consequence: Consequence,
    val originEpochDay: Long,
    val todayEpochDay: Long,
    val originWorkout: WorkoutEntity?,
    val candidates: List<WorkoutEntity>,
)

data class LocalWorkoutDecisionChange(
    val before: WorkoutEntity,
    val after: WorkoutEntity,
    val adjustment: Int?,
)

sealed interface LocalDecisionResult {
    data class Preview(
        val token: String,
        val source: LocalDecisionSource,
        val decision: PlanDecision,
        val changes: List<LocalWorkoutDecisionChange>,
    ) : LocalDecisionResult
    data class Rejected(val issue: LocalDecisionIssue) : LocalDecisionResult
}

sealed interface LocalDecisionApplyResult {
    data class Ready(
        val source: LocalDecisionSource,
        val decision: PlanDecision,
        val changes: List<LocalWorkoutDecisionChange>,
    ) : LocalDecisionApplyResult
    data class Rejected(val issue: LocalDecisionIssue) : LocalDecisionApplyResult
}

enum class LocalDecisionIssue {
    PROPOSAL_NOT_AVAILABLE,
    DECISION_NOT_OFFERED,
    NO_FUTURE_WORKOUT,
    NO_COMPATIBLE_WORKOUT,
    NO_REPEATABLE_PRESCRIPTION,
    NO_REDUCIBLE_AMOUNT,
    ALREADY_APPLIED,
    STALE_PREVIEW,
}

private class LocalDecisionProjectionException(val issue: LocalDecisionIssue) : RuntimeException()
