package dev.deftmartian.runway

import dev.deftmartian.runway.domain.CalibrationIntake
import dev.deftmartian.runway.domain.DateUtils
import dev.deftmartian.runway.domain.EstablishedTrainingIntake
import dev.deftmartian.runway.domain.Experience
import dev.deftmartian.runway.domain.FoundationIntake
import dev.deftmartian.runway.domain.GeneratedPlan
import dev.deftmartian.runway.domain.GoalKind
import dev.deftmartian.runway.domain.GoalPriority
import dev.deftmartian.runway.domain.InjuryFlags
import dev.deftmartian.runway.domain.OnboardingIssue
import dev.deftmartian.runway.domain.OnboardingSelection
import dev.deftmartian.runway.domain.OnboardingValidation
import dev.deftmartian.runway.domain.PlannerIntake
import dev.deftmartian.runway.domain.RaceDistance
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.domain.TargetDateBounds
import dev.deftmartian.runway.domain.TrainingPlanner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class OnboardingField {
    GOAL_KIND, START_MODE, RACE_DISTANCE, TARGET_DATE, PRIORITY, EXPERIENCE,
    AVAILABILITY, TIME_ZONE, WEEKLY_DISTANCE, RUNS_PER_WEEK, LONGEST_RUN,
    LONG_RUN_DAY, CALIBRATION_DURATION, HEALTH_NOTES, CONCENTRATED_SCHEDULE,
}

internal enum class OnboardingFieldError {
    INVALID_VALUE, INVALID_NUMBER, INVALID_DATE, OUT_OF_RANGE, REQUIRED,
    DUPLICATE_DAY, HEALTH_BLOCKS_PHASE, CONCENTRATED_SCHEDULE_CONFIRMATION,
}

internal data class StandaloneGoalMetadata(
    val goalKind: GoalKind,
    val startMode: StartMode,
    val raceDistance: RaceDistance?,
    /** Race goal date remains metadata during a foundation-to-goal phase. */
    val targetDate: String?,
    val priority: GoalPriority,
    val timeZone: String,
    val targetBounds: TargetDateBounds?,
)

internal sealed interface StandaloneOnboardingOutcome {
    data class Invalid(
        val fieldErrors: Map<OnboardingField, Set<OnboardingFieldError>>,
        val targetBounds: TargetDateBounds?,
    ) : StandaloneOnboardingOutcome

    data class PendingGoal(val metadata: StandaloneGoalMetadata) : StandaloneOnboardingOutcome
    data class Planned(
        val metadata: StandaloneGoalMetadata,
        val intake: PlannerIntake,
        val plan: GeneratedPlan,
    ) : StandaloneOnboardingOutcome
}

/** Pure app-to-domain boundary. It neither writes state nor depends on UI controls. */
internal object StandaloneOnboardingAdapter {
    fun adapt(command: CreatePlanCommand, now: Instant = Instant.now()): StandaloneOnboardingOutcome {
        val errors = linkedMapOf<OnboardingField, MutableSet<OnboardingFieldError>>()
        fun error(field: OnboardingField, value: OnboardingFieldError) {
            errors.getOrPut(field) { linkedSetOf() }.add(value)
        }
        fun <T> enumValue(field: OnboardingField, raw: String, values: Map<String, T>): T? =
            values[raw.trim().lowercase()] ?: run { error(field, OnboardingFieldError.INVALID_VALUE); null }

        val goalKind = enumValue(OnboardingField.GOAL_KIND, command.goalKind, mapOf("race" to GoalKind.RACE, "foundation" to GoalKind.FOUNDATION))
        val startMode = enumValue(OnboardingField.START_MODE, command.startMode, mapOf(
            "established" to StartMode.ESTABLISHED,
            "foundation_to_goal" to StartMode.FOUNDATION_TO_GOAL,
            "foundation_only" to StartMode.FOUNDATION_ONLY,
            "calibration" to StartMode.CALIBRATION,
        ))
        val priority = enumValue(OnboardingField.PRIORITY, command.priority, mapOf("finish_healthy" to GoalPriority.FINISH_HEALTHY, "consistency" to GoalPriority.CONSISTENCY))
        val experience = enumValue(OnboardingField.EXPERIENCE, command.experience, mapOf("new" to Experience.NEW, "returning" to Experience.RETURNING, "comfortable" to Experience.COMFORTABLE))
        val raceDistance = command.raceDistance.trim().lowercase().takeIf(String::isNotEmpty)?.let {
            mapOf("5k" to RaceDistance.FIVE_K, "10k" to RaceDistance.TEN_K, "half" to RaceDistance.HALF, "marathon" to RaceDistance.MARATHON)[it]
                ?: run { error(OnboardingField.RACE_DISTANCE, OnboardingFieldError.INVALID_VALUE); null }
        }
        val timeZone = command.timeZone.trim()
        if (!DateUtils.isValidTimeZone(timeZone)) error(OnboardingField.TIME_ZONE, OnboardingFieldError.INVALID_VALUE)
        val zone = timeZone.takeIf(DateUtils::isValidTimeZone)?.let(ZoneId::of)
        val today = zone?.let { now.atZone(it).toLocalDate() }
        val targetDate = command.targetDate.trim().takeIf(String::isNotEmpty)
        if (targetDate != null && runCatching { DateUtils.parseIsoDate(targetDate) }.isFailure) {
            error(OnboardingField.TARGET_DATE, OnboardingFieldError.INVALID_DATE)
        }
        if (command.injuryNotes.trim().length > 240) error(OnboardingField.HEALTH_NOTES, OnboardingFieldError.OUT_OF_RANGE)
        if (command.availability.distinct().size != command.availability.size) error(OnboardingField.AVAILABILITY, OnboardingFieldError.DUPLICATE_DAY)

        val weeklyKm = strictDecimal(command.currentWeeklyDistanceKm, OnboardingField.WEEKLY_DISTANCE, ::error)
        val runs = strictWhole(command.currentRunsPerWeek, OnboardingField.RUNS_PER_WEEK, ::error)
        val longestKm = strictDecimal(command.longestRecentRunKm, OnboardingField.LONGEST_RUN, ::error)
        val calibrationMinutes = strictWhole(command.calibrationDurationMinutes, OnboardingField.CALIBRATION_DURATION, ::error)
        val longRunDay = strictWhole(command.preferredLongRunDay, OnboardingField.LONG_RUN_DAY, ::error)

        if (goalKind == null || startMode == null || priority == null || experience == null || zone == null || today == null) {
            return StandaloneOnboardingOutcome.Invalid(errors, null)
        }
        val bounds = if (startMode == StartMode.FOUNDATION_ONLY) null else OnboardingValidation.targetDateBounds(today, startMode)
        val flags = InjuryFlags(command.recentInjury, command.currentPain, command.recurringPain, command.medicalRestriction, command.injuryNotes.trim())
        val selection = OnboardingSelection(
            goalKind, startMode, raceDistance, targetDate, experience, command.availability, timeZone, flags,
            weeklyKm, runs, longestKm, longRunDay, calibrationMinutes, command.confirmConcentratedSchedule,
        )
        OnboardingValidation.validate(selection, today).forEach { issue -> mapIssue(issue, ::error) }
        if (goalKind == GoalKind.RACE && raceDistance == null) error(OnboardingField.RACE_DISTANCE, OnboardingFieldError.REQUIRED)
        if (goalKind == GoalKind.RACE && targetDate == null) error(OnboardingField.TARGET_DATE, OnboardingFieldError.REQUIRED)
        val blocked = flags.currentPain || flags.medicalRestriction
        // HEALTH_BLOCKS_PHASE is an outcome, not a malformed goal: retain all other errors.
        if (errors.isNotEmpty()) return StandaloneOnboardingOutcome.Invalid(errors.mapValues { it.value.toSet() }, bounds)
        val metadata = StandaloneGoalMetadata(goalKind, startMode, raceDistance, targetDate, priority, timeZone, bounds)
        if (blocked) return StandaloneOnboardingOutcome.PendingGoal(metadata)
        val startDate = nextPlanStartDate(today)
        val intake = when (startMode) {
            StartMode.ESTABLISHED -> EstablishedTrainingIntake(priority, experience, command.availability, flags, requireNotNull(raceDistance), requireNotNull(targetDate), kilometersToMeters(requireNotNull(weeklyKm)), requireNotNull(runs), kilometersToMeters(requireNotNull(longestKm)), requireNotNull(longRunDay), startDate)
            StartMode.FOUNDATION_TO_GOAL, StartMode.FOUNDATION_ONLY -> FoundationIntake(startMode, goalKind, raceDistance, command.availability, flags, startDate)
            StartMode.CALIBRATION -> CalibrationIntake(goalKind, raceDistance, command.availability, flags, requireNotNull(calibrationMinutes) * 60, startDate)
        }
        return StandaloneOnboardingOutcome.Planned(metadata, intake, TrainingPlanner.generatePlan(intake, today))
    }

    private fun mapIssue(issue: OnboardingIssue, error: (OnboardingField, OnboardingFieldError) -> Unit) = when (issue) {
        OnboardingIssue.INVALID_TIME_ZONE -> error(OnboardingField.TIME_ZONE, OnboardingFieldError.INVALID_VALUE)
        OnboardingIssue.MISSING_EXPERIENCE -> error(OnboardingField.EXPERIENCE, OnboardingFieldError.REQUIRED)
        OnboardingIssue.MISSING_START_MODE, OnboardingIssue.INVALID_GOAL_MODE -> error(OnboardingField.START_MODE, OnboardingFieldError.INVALID_VALUE)
        OnboardingIssue.MISSING_RACE_DISTANCE -> error(OnboardingField.RACE_DISTANCE, OnboardingFieldError.REQUIRED)
        OnboardingIssue.MISSING_TARGET_DATE -> error(OnboardingField.TARGET_DATE, OnboardingFieldError.REQUIRED)
        OnboardingIssue.TARGET_DATE_OUT_OF_BOUNDS -> error(OnboardingField.TARGET_DATE, OnboardingFieldError.OUT_OF_RANGE)
        OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS -> error(OnboardingField.AVAILABILITY, OnboardingFieldError.OUT_OF_RANGE)
        OnboardingIssue.INVALID_ESTABLISHED_BASELINE -> error(OnboardingField.WEEKLY_DISTANCE, OnboardingFieldError.OUT_OF_RANGE)
        OnboardingIssue.INVALID_CALIBRATION_DURATION -> error(OnboardingField.CALIBRATION_DURATION, OnboardingFieldError.OUT_OF_RANGE)
        OnboardingIssue.INVALID_LONG_RUN_DAY -> error(OnboardingField.LONG_RUN_DAY, OnboardingFieldError.INVALID_VALUE)
        OnboardingIssue.CONCENTRATED_SCHEDULE_NOT_CONFIRMED -> error(OnboardingField.CONCENTRATED_SCHEDULE, OnboardingFieldError.CONCENTRATED_SCHEDULE_CONFIRMATION)
        OnboardingIssue.HEALTH_BLOCKS_SCHEDULING -> Unit
    }

    private fun strictDecimal(raw: String, field: OnboardingField, error: (OnboardingField, OnboardingFieldError) -> Unit): Double? {
        val normalized = raw.trim(); if (normalized.isEmpty()) return null
        if (!DECIMAL.matches(normalized)) { error(field, OnboardingFieldError.INVALID_NUMBER); return null }
        return normalized.toDoubleOrNull()?.takeIf(Double::isFinite) ?: run { error(field, OnboardingFieldError.INVALID_NUMBER); null }
    }
    private fun strictWhole(raw: String, field: OnboardingField, error: (OnboardingField, OnboardingFieldError) -> Unit): Int? {
        val normalized = raw.trim(); if (normalized.isEmpty()) return null
        if (!WHOLE.matches(normalized)) { error(field, OnboardingFieldError.INVALID_NUMBER); return null }
        return normalized.toIntOrNull() ?: run { error(field, OnboardingFieldError.INVALID_NUMBER); null }
    }
    private fun kilometersToMeters(value: Double): Int = kotlin.math.floor(value * 1000 + .5).toInt()
    private fun nextPlanStartDate(today: LocalDate): String = today.plusDays(((8 - today.dayOfWeek.value) % 7).toLong()).toString()
    private val DECIMAL = Regex("(?:0|[1-9]\\d*)(?:\\.\\d+)?")
    private val WHOLE = Regex("(?:0|[1-9]\\d*)")
}
