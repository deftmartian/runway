package dev.deftmartian.runway.domain

import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Preconditions for plan changes. Call these before constructing a preview so a rejected
 * operation cannot accidentally be presented as an available plan change.
 */
enum class PlanChangeIneligibility {
    NO_ACTIVE_PLAN,
    NOT_FUTURE,
    RACE_WORKOUT,
    LINKED_RESULT,
    OUTSIDE_PLAN_WINDOW,
    DAILY_LIMIT,
    WEEKLY_LIMIT,
}

data class PlanDateWindow(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "A plan window cannot end before it starts." }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
}

/** A visible planned row used solely to enforce schedule caps; only removed rows stop occupying a slot. */
data class ScheduledWorkoutSlot(
    val id: String,
    val date: LocalDate,
    val type: WorkoutType,
    val isRemoved: Boolean = false,
)

data class PlanChangeEligibilityInput(
    val activePlan: Boolean,
    val planWindow: PlanDateWindow?,
    val today: LocalDate,
    val proposedDate: LocalDate,
    val subjectId: String? = null,
    val subjectType: WorkoutType? = null,
    val subjectHasLinkedResult: Boolean = false,
    val scheduledSlots: List<ScheduledWorkoutSlot>,
)

data class PlanChangeEligibility(val reasons: Set<PlanChangeIneligibility>) {
    val allowed: Boolean get() = reasons.isEmpty()
}

object StandalonePlanRules {
    const val MAX_VISIBLE_WORKOUTS_PER_DAY = 2
    const val MAX_VISIBLE_WORKOUTS_PER_WEEK = 14
    const val MAX_PLAN_WEEKS = 52

    fun assessPlanChange(input: PlanChangeEligibilityInput): PlanChangeEligibility {
        val reasons = linkedSetOf<PlanChangeIneligibility>()
        if (!input.activePlan) reasons += PlanChangeIneligibility.NO_ACTIVE_PLAN
        if (!input.proposedDate.isAfter(input.today)) reasons += PlanChangeIneligibility.NOT_FUTURE
        if (input.subjectType == WorkoutType.RACE) reasons += PlanChangeIneligibility.RACE_WORKOUT
        if (input.subjectHasLinkedResult) reasons += PlanChangeIneligibility.LINKED_RESULT
        if (input.planWindow?.contains(input.proposedDate) != true) {
            reasons += PlanChangeIneligibility.OUTSIDE_PLAN_WINDOW
        }

        // An existing workout keeps its own slot when it moves. New workouts use a null subjectId.
        val otherVisible = input.scheduledSlots.filter { slot ->
            slot.id != input.subjectId && !slot.isRemoved
        }
        if (otherVisible.count { it.date == input.proposedDate } >= MAX_VISIBLE_WORKOUTS_PER_DAY) {
            reasons += PlanChangeIneligibility.DAILY_LIMIT
        }
        val proposedWeek = weekStart(input.proposedDate)
        if (otherVisible.count { weekStart(it.date) == proposedWeek } >= MAX_VISIBLE_WORKOUTS_PER_WEEK) {
            reasons += PlanChangeIneligibility.WEEKLY_LIMIT
        }
        return PlanChangeEligibility(reasons)
    }

    fun assessFeedback(input: FeedbackEligibilityInput): FeedbackEligibility {
        val reasons = linkedSetOf<FeedbackIneligibility>()
        if (input.scheduledDate.isAfter(input.today)) reasons += FeedbackIneligibility.NOT_DUE
        if (input.type == WorkoutType.REST || input.isRemoved) reasons += FeedbackIneligibility.NOT_PLANNED_RUN
        if (input.status != FeedbackWorkoutStatus.PLANNED) reasons += FeedbackIneligibility.NOT_PLANNED_RUN
        if (input.hasFeedback) reasons += FeedbackIneligibility.ALREADY_RECORDED
        return FeedbackEligibility(reasons)
    }

    fun assessContinuation(input: ContinuationEligibilityInput): ContinuationEligibility {
        val reasons = linkedSetOf<ContinuationIneligibility>()
        if (!input.activePlan) reasons += ContinuationIneligibility.NO_ACTIVE_BEGINNER_PLAN
        if (input.phase !in setOf(PlanPhase.FOUNDATION, PlanPhase.CALIBRATION)) {
            reasons += ContinuationIneligibility.NOT_A_BEGINNER_PHASE
        }
        if (input.targetDate.isAfter(input.today)) reasons += ContinuationIneligibility.PHASE_NOT_COMPLETE
        if (input.planWeeks >= MAX_PLAN_WEEKS) reasons += ContinuationIneligibility.PLAN_WEEK_LIMIT
        if (!input.hasRepeatableFinalWeek) reasons += ContinuationIneligibility.NO_FINAL_WEEK
        return ContinuationEligibility(reasons)
    }

    private fun weekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
}

enum class FeedbackIneligibility { NOT_DUE, NOT_PLANNED_RUN, ALREADY_RECORDED }
enum class FeedbackWorkoutStatus { PLANNED, DONE, SKIPPED }

data class FeedbackEligibilityInput(
    val today: LocalDate,
    val scheduledDate: LocalDate,
    val type: WorkoutType,
    val status: FeedbackWorkoutStatus,
    val isRemoved: Boolean = false,
    val hasFeedback: Boolean = false,
)

data class FeedbackEligibility(val reasons: Set<FeedbackIneligibility>) {
    val allowed: Boolean get() = reasons.isEmpty()
}

enum class ContinuationIneligibility {
    NO_ACTIVE_BEGINNER_PLAN,
    NOT_A_BEGINNER_PHASE,
    PHASE_NOT_COMPLETE,
    PLAN_WEEK_LIMIT,
    NO_FINAL_WEEK,
}

data class ContinuationEligibilityInput(
    val activePlan: Boolean,
    val phase: PlanPhase,
    val targetDate: LocalDate,
    val today: LocalDate,
    val planWeeks: Int,
    val hasRepeatableFinalWeek: Boolean,
)

data class ContinuationEligibility(val reasons: Set<ContinuationIneligibility>) {
    val allowed: Boolean get() = reasons.isEmpty()
}

/** The source relationship remains explicit even when a result no longer belongs to the active plan. */
enum class AcceptedEvidenceProvenance { ACTIVE_PLAN, ARCHIVED_PLAN, UNLINKED }

data class AcceptedActivityEvidence(
    val id: String,
    val accepted: Boolean,
    val provenance: AcceptedEvidenceProvenance,
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val averageHeartRate: Int?,
)

data class EvidenceProvenanceStats(
    val acceptedActivityCount: Int,
    val distanceMeters: Int,
    val durationSeconds: Int,
)

data class AcceptedEvidenceStats(
    val acceptedActivityCount: Int,
    val distanceMeters: Int,
    val durationSeconds: Int,
    /** Aggregate only observations with a valid distance and duration pair; do not average paces. */
    val averagePaceSecondsPerKm: Double?,
    /** Aggregate only observations with both HR and a positive duration, weighted by duration. */
    val averageHeartRate: Int?,
    val byProvenance: Map<AcceptedEvidenceProvenance, EvidenceProvenanceStats>,
)

object AcceptedEvidenceStatistics {
    fun summarize(evidence: List<AcceptedActivityEvidence>): AcceptedEvidenceStats {
        require(evidence.map { it.id }.distinct().size == evidence.size) {
            "Accepted activity evidence must not contain duplicate IDs."
        }
        val accepted = evidence.filter { it.accepted }
        fun positive(value: Int?) = value?.takeIf { it > 0 }
        val paired = accepted.mapNotNull { row ->
            val distance = positive(row.distanceMeters) ?: return@mapNotNull null
            val duration = positive(row.durationSeconds) ?: return@mapNotNull null
            distance to duration
        }
        val pairedDistance = paired.sumOf { it.first }
        val pairedDuration = paired.sumOf { it.second }
        val heartRateRows = accepted.mapNotNull { row ->
            val heartRate = positive(row.averageHeartRate) ?: return@mapNotNull null
            val duration = positive(row.durationSeconds) ?: return@mapNotNull null
            heartRate.toLong() * duration to duration
        }
        val heartRateDuration = heartRateRows.sumOf { it.second }
        val provenance = AcceptedEvidenceProvenance.entries.associateWith { kind ->
            val rows = accepted.filter { it.provenance == kind }
            EvidenceProvenanceStats(
                acceptedActivityCount = rows.size,
                distanceMeters = rows.sumOf { positive(it.distanceMeters) ?: 0 },
                durationSeconds = rows.sumOf { positive(it.durationSeconds) ?: 0 },
            )
        }
        return AcceptedEvidenceStats(
            acceptedActivityCount = accepted.size,
            distanceMeters = accepted.sumOf { positive(it.distanceMeters) ?: 0 },
            durationSeconds = accepted.sumOf { positive(it.durationSeconds) ?: 0 },
            averagePaceSecondsPerKm = if (pairedDistance > 0) pairedDuration.toDouble() / (pairedDistance / 1_000.0) else null,
            averageHeartRate = if (heartRateDuration > 0) {
                // Match the prior product's displayed integer HR without averaging averages.
                kotlin.math.round((heartRateRows.sumOf { it.first }).toDouble() / heartRateDuration).toInt()
            } else {
                null
            },
            byProvenance = provenance,
        )
    }
}
