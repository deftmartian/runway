package dev.deftmartian.runway

import dev.deftmartian.runway.data.GeneratedPlanGoalMetadata
import dev.deftmartian.runway.data.GeneratedPlanPersistenceMapper
import dev.deftmartian.runway.data.LocalPlanCandidate
import dev.deftmartian.runway.data.LocalPlanSetupRequest
import dev.deftmartian.runway.data.ProfileSettingsEntity
import dev.deftmartian.runway.data.RoutinePlanPersistenceMapper
import dev.deftmartian.runway.domain.CalibrationIntake
import dev.deftmartian.runway.domain.DateUtils
import dev.deftmartian.runway.domain.EstablishedTrainingIntake
import dev.deftmartian.runway.domain.GeneratedDistancePlan
import dev.deftmartian.runway.domain.GeneratedPlan
import dev.deftmartian.runway.domain.GoalKind
import dev.deftmartian.runway.domain.GoalPriority
import dev.deftmartian.runway.domain.OnboardingIssue
import dev.deftmartian.runway.domain.OnboardingSelection
import dev.deftmartian.runway.domain.OnboardingValidation
import dev.deftmartian.runway.domain.PlannerIntake
import dev.deftmartian.runway.domain.RaceDistance
import dev.deftmartian.runway.domain.RiskRating
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.domain.TargetDateBounds
import dev.deftmartian.runway.domain.TrainingPlanner
import dev.deftmartian.runway.domain.canLeaveRecoveryDayAfterLongRun
import dev.deftmartian.runway.domain.isRepeatableWeekCoherent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class OnboardingField {
    GOAL_KIND, START_MODE, RACE_DISTANCE, TARGET_DATE, PRIORITY,
    AVAILABILITY, TIME_ZONE, WEEKLY_DISTANCE, RUNS_PER_WEEK, LONGEST_RUN,
    LONG_RUN_DAY, CONCENTRATED_SCHEDULE, ROUTINE_WEEKLY_GOAL_METRIC, ROUTINE_WEEKLY_GOAL,
    ROUTINE_WEEKLY_CHANGE, ROUTINE_WEEKLY_TIME, PLAN_ASSESSMENT,
}

internal enum class OnboardingFieldError {
    INVALID_VALUE, INVALID_NUMBER, INVALID_DATE, OUT_OF_RANGE, REQUIRED,
    DUPLICATE_DAY, CONCENTRATED_SCHEDULE_CONFIRMATION,
    HIGH_INCREASE_CONFIRMATION, UNSUPPORTED_PLAN, INCONSISTENT_BASELINE,
    RECOVERY_SPACING,
}

internal data class StandaloneGoalMetadata(
    val goalKind: GoalKind,
    val startMode: StartMode,
    val raceDistance: RaceDistance?,
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

    data class Routine(
        val metadata: StandaloneGoalMetadata,
        val startDate: String,
        val selectedDays: List<Int>,
        val weeklyTargetDistanceMeters: Int?,
        val weeklyTargetDurationSeconds: Int?,
        val startingWeeklyDistanceMeters: Int?,
        val startingWeeklyDurationSeconds: Int?,
        val weeklyChangeDistanceMeters: Int?,
        val weeklyChangeDurationSeconds: Int?,
    ) : StandaloneOnboardingOutcome
    data class Planned(
        val metadata: StandaloneGoalMetadata,
        val intake: PlannerIntake,
        val plan: GeneratedPlan,
    ) : StandaloneOnboardingOutcome
}

/** Pure app-to-domain boundary. It neither writes state nor depends on UI controls. */
internal object StandaloneOnboardingAdapter {
    fun adapt(command: CreatePlanCommand, now: Instant = Instant.now()): StandaloneOnboardingOutcome {
        return evaluate(command, now, enforceConfirmations = true)
    }

    /**
     * Produces the exact candidate shown on Setup's Review step without accepting its risk.
     * Confirmation checks are intentionally deferred so the UI can explain the candidate first.
     */
    fun preview(command: CreatePlanCommand, now: Instant = Instant.now()): StandaloneOnboardingOutcome {
        return evaluate(command, now, enforceConfirmations = false)
    }

    private fun evaluate(
        command: CreatePlanCommand,
        now: Instant,
        enforceConfirmations: Boolean,
    ): StandaloneOnboardingOutcome {
        val errors = linkedMapOf<OnboardingField, MutableSet<OnboardingFieldError>>()
        fun error(field: OnboardingField, value: OnboardingFieldError) {
            errors.getOrPut(field) { linkedSetOf() }.add(value)
        }
        fun <T> enumValue(field: OnboardingField, raw: String, values: Map<String, T>): T? =
            values[raw.trim().lowercase()] ?: run {
                error(field, OnboardingFieldError.INVALID_VALUE)
                null
            }

        val goalKind = enumValue(
            OnboardingField.GOAL_KIND,
            command.goalKind,
            mapOf(
                "race" to GoalKind.RACE,
                "routine" to GoalKind.ROUTINE,
            ),
        )
        val startMode = enumValue(OnboardingField.START_MODE, command.startMode, mapOf(
            "established" to StartMode.ESTABLISHED,
            "calibration" to StartMode.CALIBRATION,
            "routine" to StartMode.ROUTINE,
        ))
        val priority = enumValue(
            OnboardingField.PRIORITY,
            if (goalKind == GoalKind.ROUTINE) "consistency" else command.priority,
            mapOf("finish_healthy" to GoalPriority.FINISH_HEALTHY, "consistency" to GoalPriority.CONSISTENCY),
        )
        val raceDistance = command.raceDistance.trim().lowercase()
            .takeIf { goalKind == GoalKind.RACE && it.isNotEmpty() }
            ?.let {
            mapOf("5k" to RaceDistance.FIVE_K, "10k" to RaceDistance.TEN_K, "half" to RaceDistance.HALF, "marathon" to RaceDistance.MARATHON)[it]
                ?: run {
                    error(OnboardingField.RACE_DISTANCE, OnboardingFieldError.INVALID_VALUE)
                    null
                }
        }
        val timeZone = command.timeZone.trim()
        if (!DateUtils.isValidTimeZone(timeZone)) error(OnboardingField.TIME_ZONE, OnboardingFieldError.INVALID_VALUE)
        val zone = timeZone.takeIf(DateUtils::isValidTimeZone)?.let(ZoneId::of)
        val today = zone?.let { now.atZone(it).toLocalDate() }
        val targetDate = command.targetDate.trim()
            .takeIf { goalKind == GoalKind.RACE && it.isNotEmpty() }
        if (targetDate != null && runCatching { DateUtils.parseIsoDate(targetDate) }.isFailure) {
            error(OnboardingField.TARGET_DATE, OnboardingFieldError.INVALID_DATE)
        }
        if (command.availability.distinct().size != command.availability.size) error(OnboardingField.AVAILABILITY, OnboardingFieldError.DUPLICATE_DAY)

        if (goalKind == null || startMode == null || priority == null || zone == null || today == null) {
            return StandaloneOnboardingOutcome.Invalid(errors, null)
        }
        // Parse only inputs visible for the selected path so stale hidden values cannot invalidate
        // a different starting point. Entered baselines still receive precise field errors.
        val established = goalKind == GoalKind.RACE && startMode == StartMode.ESTABLISHED
        val routineBaseline = goalKind == GoalKind.ROUTINE
        val needsDistanceBaseline = established || (routineBaseline && command.routineGoalMetric == "distance")
        val routineGoalMetric = if (goalKind == GoalKind.ROUTINE) {
            enumValue(
                OnboardingField.ROUTINE_WEEKLY_GOAL_METRIC,
                command.routineGoalMetric,
                mapOf("distance" to "distance", "time" to "time"),
            )
        } else {
            null
        }
        val routineGoalKm = if (routineGoalMetric == "distance") {
            strictDecimal(
                command.routineWeeklyGoalKm,
                OnboardingField.ROUTINE_WEEKLY_GOAL,
                ::error,
            )
        } else {
            null
        }
        val routineGoalMinutes = if (routineGoalMetric == "time") {
            strictDecimal(
                command.routineWeeklyGoalMinutes,
                OnboardingField.ROUTINE_WEEKLY_GOAL,
                ::error,
            )
        } else {
            null
        }
        val weeklyKm = if (needsDistanceBaseline) strictDecimal(command.currentWeeklyDistanceKm, OnboardingField.WEEKLY_DISTANCE, ::error) else null
        val weeklyMinutes = if (routineBaseline && routineGoalMetric == "time") {
            strictDecimal(command.currentWeeklyMinutes, OnboardingField.ROUTINE_WEEKLY_TIME, ::error)
        } else {
            null
        }
        val runs = if (established || routineBaseline) strictWhole(command.currentRunsPerWeek, OnboardingField.RUNS_PER_WEEK, ::error) else null
        val longestKm = if (established) strictDecimal(command.longestRecentRunKm, OnboardingField.LONGEST_RUN, ::error) else null
        val routineChangeKm = if (routineBaseline && routineGoalMetric == "distance") {
            strictDecimal(command.routineWeeklyChangeKm, OnboardingField.ROUTINE_WEEKLY_CHANGE, ::error)
        } else {
            null
        }
        val routineChangeMinutes = if (routineBaseline && routineGoalMetric == "time") {
            strictDecimal(command.routineWeeklyChangeMinutes, OnboardingField.ROUTINE_WEEKLY_CHANGE, ::error)
        } else {
            null
        }
        val longRunDay = if (established) strictWhole(command.preferredLongRunDay, OnboardingField.LONG_RUN_DAY, ::error) else null
        val bounds = if (goalKind == GoalKind.RACE) {
            OnboardingValidation.targetDateBounds(today, startMode)
        } else {
            null
        }
        val selection = OnboardingSelection(
            goalKind, startMode, raceDistance, targetDate, command.availability, timeZone,
            weeklyKm, runs, longestKm, longRunDay,
            command.confirmConcentratedSchedule || !enforceConfirmations,
        )
        OnboardingValidation.validate(selection, today).forEach { issue -> mapIssue(issue, ::error) }
        if (established) {
            validateEstablishedBaseline(
                command,
                weeklyKm,
                runs,
                longestKm,
                longRunDay,
                raceDistance,
                requireLongRunDay = established,
                error = ::error,
            )
        }
        if (goalKind == GoalKind.ROUTINE) {
            validateRoutineWeeklyGoal(command, routineGoalMetric, routineGoalKm, routineGoalMinutes, ::error)
            if (routineBaseline) {
                validateRoutineBaseline(
                    command = command,
                    metric = routineGoalMetric,
                    weeklyKm = weeklyKm,
                    weeklyMinutes = weeklyMinutes,
                    runs = runs,
                    changeKm = routineChangeKm,
                    changeMinutes = routineChangeMinutes,
                    error = ::error,
                )
            }
        }
        if (goalKind == GoalKind.RACE && raceDistance == null) error(OnboardingField.RACE_DISTANCE, OnboardingFieldError.REQUIRED)
        if (goalKind == GoalKind.RACE && targetDate == null) error(OnboardingField.TARGET_DATE, OnboardingFieldError.REQUIRED)
        if (errors.isNotEmpty()) return StandaloneOnboardingOutcome.Invalid(errors.mapValues { it.value.toSet() }, bounds)
        val metadata = StandaloneGoalMetadata(goalKind, startMode, raceDistance, targetDate, priority, timeZone, bounds)
        if (startMode == StartMode.ROUTINE) {
            val goalDistance = routineGoalKm?.let(::kilometersToMeters)
            val goalDuration = routineGoalMinutes?.let(::minutesToSeconds)
            val startingDistance = if (routineBaseline && routineGoalMetric == "distance") {
                readableDistanceMeters(requireNotNull(weeklyKm))
            } else {
                goalDistance
            }
            val startingDuration = if (routineBaseline && routineGoalMetric == "time") {
                readableDurationSeconds(requireNotNull(weeklyMinutes))
            } else {
                goalDuration
            }
            return StandaloneOnboardingOutcome.Routine(
                metadata = metadata,
                startDate = nextPlanStartDate(today),
                selectedDays = command.availability.sorted(),
                weeklyTargetDistanceMeters = goalDistance,
                weeklyTargetDurationSeconds = goalDuration,
                startingWeeklyDistanceMeters = startingDistance,
                startingWeeklyDurationSeconds = startingDuration,
                weeklyChangeDistanceMeters = if (goalDistance != null) {
                    if (startingDistance == goalDistance) 0 else readableDistanceMeters(requireNotNull(routineChangeKm))
                } else {
                    null
                },
                weeklyChangeDurationSeconds = if (goalDuration != null) {
                    if (startingDuration == goalDuration) 0 else readableDurationSeconds(requireNotNull(routineChangeMinutes))
                } else {
                    null
                },
            )
        }
        val startDate = nextPlanStartDate(today)
        val intake = when (startMode) {
            StartMode.ESTABLISHED -> EstablishedTrainingIntake(priority, command.availability, requireNotNull(raceDistance), requireNotNull(targetDate), kilometersToMeters(requireNotNull(weeklyKm)), requireNotNull(runs), kilometersToMeters(requireNotNull(longestKm)), requireNotNull(longRunDay), startDate)
            StartMode.CALIBRATION -> CalibrationIntake(goalKind, raceDistance, command.availability, startDate)
            StartMode.ROUTINE -> error("Routine setup does not use the training planner")
        }
        val plan = TrainingPlanner.generatePlan(intake, today)
        if (enforceConfirmations && plan is GeneratedDistancePlan) {
            when {
                plan.risk == RiskRating.UNSAFE ->
                    error(OnboardingField.PLAN_ASSESSMENT, OnboardingFieldError.UNSUPPORTED_PLAN)
                plan.risk == RiskRating.AGGRESSIVE &&
                    command.confirmedPlanKey != generatedPlanConfirmationKey(plan) ->
                    error(
                        OnboardingField.PLAN_ASSESSMENT,
                        OnboardingFieldError.HIGH_INCREASE_CONFIRMATION,
                    )
            }
            if (errors.isNotEmpty()) {
                return StandaloneOnboardingOutcome.Invalid(
                    errors.mapValues { it.value.toSet() },
                    bounds,
                )
            }
        }
        return StandaloneOnboardingOutcome.Planned(metadata, intake, plan)
    }

    private fun mapIssue(issue: OnboardingIssue, error: (OnboardingField, OnboardingFieldError) -> Unit) = when (issue) {
        OnboardingIssue.INVALID_TIME_ZONE -> error(OnboardingField.TIME_ZONE, OnboardingFieldError.INVALID_VALUE)
        OnboardingIssue.MISSING_START_MODE, OnboardingIssue.INVALID_GOAL_MODE -> error(OnboardingField.START_MODE, OnboardingFieldError.INVALID_VALUE)
        OnboardingIssue.MISSING_RACE_DISTANCE -> error(OnboardingField.RACE_DISTANCE, OnboardingFieldError.REQUIRED)
        OnboardingIssue.MISSING_TARGET_DATE -> error(OnboardingField.TARGET_DATE, OnboardingFieldError.REQUIRED)
        OnboardingIssue.TARGET_DATE_OUT_OF_BOUNDS -> error(OnboardingField.TARGET_DATE, OnboardingFieldError.OUT_OF_RANGE)
        OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS -> error(OnboardingField.AVAILABILITY, OnboardingFieldError.OUT_OF_RANGE)
        // The domain reports this aggregate invariant; this adapter reports the actionable field.
        OnboardingIssue.INVALID_ESTABLISHED_BASELINE -> Unit
        OnboardingIssue.INVALID_LONG_RUN_DAY -> error(OnboardingField.LONG_RUN_DAY, OnboardingFieldError.INVALID_VALUE)
        OnboardingIssue.INSUFFICIENT_RECOVERY_SPACING ->
            error(OnboardingField.AVAILABILITY, OnboardingFieldError.RECOVERY_SPACING)
        OnboardingIssue.CONCENTRATED_SCHEDULE_NOT_CONFIRMED -> error(OnboardingField.CONCENTRATED_SCHEDULE, OnboardingFieldError.CONCENTRATED_SCHEDULE_CONFIRMATION)
    }

    private fun strictDecimal(raw: String, field: OnboardingField, error: (OnboardingField, OnboardingFieldError) -> Unit): Double? {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return null
        if (!DECIMAL.matches(normalized)) {
            error(field, OnboardingFieldError.INVALID_NUMBER)
            return null
        }
        return normalized.toDoubleOrNull()?.takeIf(Double::isFinite) ?: run {
            error(field, OnboardingFieldError.INVALID_NUMBER)
            null
        }
    }
    private fun strictWhole(raw: String, field: OnboardingField, error: (OnboardingField, OnboardingFieldError) -> Unit): Int? {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return null
        if (!WHOLE.matches(normalized)) {
            error(field, OnboardingFieldError.INVALID_NUMBER)
            return null
        }
        return normalized.toIntOrNull() ?: run {
            error(field, OnboardingFieldError.INVALID_NUMBER)
            null
        }
    }
    private fun kilometersToMeters(value: Double): Int = kotlin.math.floor(value * 1000 + .5).toInt()
    private fun minutesToSeconds(value: Double): Int = kotlin.math.floor(value * 60 + .5).toInt()
    private fun validateEstablishedBaseline(
        command: CreatePlanCommand,
        weeklyKm: Double?,
        runs: Int?,
        longestKm: Double?,
        longRunDay: Int?,
        raceDistance: RaceDistance?,
        requireLongRunDay: Boolean,
        error: (OnboardingField, OnboardingFieldError) -> Unit,
    ) {
        validateWeeklyDistance(command.currentWeeklyDistanceKm, weeklyKm, error)
        validateRunsPerWeek(command.currentRunsPerWeek, runs, error)
        validateLongestRun(command.longestRecentRunKm, longestKm, error)
        if (longRunDayIsMissing(command, longRunDay, requireLongRunDay)) {
            error(OnboardingField.LONG_RUN_DAY, OnboardingFieldError.REQUIRED)
        }
        if (baselineIsInconsistent(weeklyKm, runs, longestKm)) {
            error(OnboardingField.WEEKLY_DISTANCE, OnboardingFieldError.INCONSISTENT_BASELINE)
            error(OnboardingField.LONGEST_RUN, OnboardingFieldError.INCONSISTENT_BASELINE)
        }
        if (recoverySpacingIsInvalid(command, runs, longRunDay, raceDistance, requireLongRunDay)) {
            error(OnboardingField.AVAILABILITY, OnboardingFieldError.RECOVERY_SPACING)
        }
    }

    private fun validateWeeklyDistance(
        raw: String,
        value: Double?,
        error: (OnboardingField, OnboardingFieldError) -> Unit,
    ) = when {
        value == null && raw.isBlank() -> error(OnboardingField.WEEKLY_DISTANCE, OnboardingFieldError.REQUIRED)
        value != null && value !in 3.0..250.0 -> error(OnboardingField.WEEKLY_DISTANCE, OnboardingFieldError.OUT_OF_RANGE)
        else -> Unit
    }

    private fun validateRoutineWeeklyGoal(
        command: CreatePlanCommand,
        metric: String?,
        distanceKm: Double?,
        durationMinutes: Double?,
        error: (OnboardingField, OnboardingFieldError) -> Unit,
    ) {
        val runCount = command.availability.distinct().size
        when {
            metric == "distance" && distanceKm == null && command.routineWeeklyGoalKm.isBlank() ->
                error(OnboardingField.ROUTINE_WEEKLY_GOAL, OnboardingFieldError.REQUIRED)
            metric == "time" && durationMinutes == null && command.routineWeeklyGoalMinutes.isBlank() ->
                error(OnboardingField.ROUTINE_WEEKLY_GOAL, OnboardingFieldError.REQUIRED)
            metric == null ->
                error(OnboardingField.ROUTINE_WEEKLY_GOAL, OnboardingFieldError.REQUIRED)
            !routineGoalFitsRunCount(metric, distanceKm, durationMinutes, runCount) ->
                error(OnboardingField.ROUTINE_WEEKLY_GOAL, OnboardingFieldError.OUT_OF_RANGE)
        }
    }

    private fun routineGoalFitsRunCount(
        metric: String?,
        distanceKm: Double?,
        durationMinutes: Double?,
        runCount: Int,
    ): Boolean = when (metric) {
        "distance" -> distanceKm != null && isHalfKilometreValue(distanceKm) &&
            distanceKm in (0.5 * runCount)..minOf(250.0, 100.0 * runCount)
        "time" -> durationMinutes != null &&
            isFiveMinuteValue(durationMinutes) &&
            durationMinutes in (10.0 * runCount)..(360.0 * runCount)
        else -> false
    }

    private fun validateRoutineBaseline(
        command: CreatePlanCommand,
        metric: String?,
        weeklyKm: Double?,
        weeklyMinutes: Double?,
        runs: Int?,
        changeKm: Double?,
        changeMinutes: Double?,
        error: (OnboardingField, OnboardingFieldError) -> Unit,
    ) {
        val scheduledRuns = command.availability.distinct().size
        if (!routineBaselineAmountIsValid(metric, weeklyKm, weeklyMinutes, scheduledRuns)) {
            val field = if (metric == "time") {
                OnboardingField.ROUTINE_WEEKLY_TIME
            } else {
                OnboardingField.WEEKLY_DISTANCE
            }
            error(field, OnboardingFieldError.OUT_OF_RANGE)
        }
        if (runs == null || runs !in 1..7) {
            error(OnboardingField.RUNS_PER_WEEK, OnboardingFieldError.OUT_OF_RANGE)
        }
        if (!routineChangeIsValid(command, metric, weeklyKm, weeklyMinutes, changeKm, changeMinutes)) {
            error(OnboardingField.ROUTINE_WEEKLY_CHANGE, OnboardingFieldError.OUT_OF_RANGE)
        }
    }

    private fun routineBaselineAmountIsValid(
        metric: String?,
        weeklyKm: Double?,
        weeklyMinutes: Double?,
        scheduledRuns: Int,
    ): Boolean = when (metric) {
        "distance" -> weeklyKm != null && weeklyKm in (0.5 * scheduledRuns)..250.0
        "time" -> weeklyMinutes != null && weeklyMinutes in (10.0 * scheduledRuns)..4_200.0
        else -> false
    }

    private fun routineChangeIsValid(
        command: CreatePlanCommand,
        metric: String?,
        weeklyKm: Double?,
        weeklyMinutes: Double?,
        changeKm: Double?,
        changeMinutes: Double?,
    ): Boolean = when (metric) {
        "distance" -> routineDistanceChangeIsValid(command, weeklyKm, changeKm)
        "time" -> routineTimeChangeIsValid(command, weeklyMinutes, changeMinutes)
        else -> false
    }

    private fun routineDistanceChangeIsValid(
        command: CreatePlanCommand,
        weeklyKm: Double?,
        changeKm: Double?,
    ): Boolean {
        if (weeklyKm != null && routineGoalDistance(command) == readableDistanceMeters(weeklyKm)) {
            return true
        }
        return changeKm != null && changeKm in 0.5..250.0 && isHalfKilometreValue(changeKm)
    }

    private fun routineTimeChangeIsValid(
        command: CreatePlanCommand,
        weeklyMinutes: Double?,
        changeMinutes: Double?,
    ): Boolean {
        if (weeklyMinutes != null && routineGoalDuration(command) == readableDurationSeconds(weeklyMinutes)) {
            return true
        }
        return changeMinutes != null && changeMinutes in 5.0..4_200.0 && isFiveMinuteValue(changeMinutes)
    }

    private fun routineGoalDistance(command: CreatePlanCommand) =
        command.routineWeeklyGoalKm.toDoubleOrNull()?.let(::kilometersToMeters)

    private fun routineGoalDuration(command: CreatePlanCommand) =
        command.routineWeeklyGoalMinutes.toDoubleOrNull()?.let(::minutesToSeconds)

    private fun isHalfKilometreValue(value: Double) =
        kotlin.math.abs(value * 2 - kotlin.math.round(value * 2)) < 0.000_001

    private fun isFiveMinuteValue(value: Double) =
        kotlin.math.abs(value / 5 - kotlin.math.round(value / 5)) < 0.000_001

    private fun readableDistanceMeters(kilometres: Double): Int =
        kotlin.math.floor(kilometres * 2 + .5).toInt() * 500

    private fun readableDurationSeconds(minutes: Double): Int =
        kotlin.math.floor(minutes / 5 + .5).toInt() * 300

    private fun validateRunsPerWeek(
        raw: String,
        value: Int?,
        error: (OnboardingField, OnboardingFieldError) -> Unit,
    ) = when {
        value == null && raw.isBlank() -> error(OnboardingField.RUNS_PER_WEEK, OnboardingFieldError.REQUIRED)
        value != null && value !in 2..5 -> error(OnboardingField.RUNS_PER_WEEK, OnboardingFieldError.OUT_OF_RANGE)
        else -> Unit
    }

    private fun validateLongestRun(
        raw: String,
        value: Double?,
        error: (OnboardingField, OnboardingFieldError) -> Unit,
    ) = when {
        value == null && raw.isBlank() -> error(OnboardingField.LONGEST_RUN, OnboardingFieldError.REQUIRED)
        value != null && value !in 0.0..80.0 -> error(OnboardingField.LONGEST_RUN, OnboardingFieldError.OUT_OF_RANGE)
        value == 0.0 -> error(OnboardingField.LONGEST_RUN, OnboardingFieldError.OUT_OF_RANGE)
        else -> Unit
    }

    private fun longRunDayIsMissing(
        command: CreatePlanCommand,
        longRunDay: Int?,
        required: Boolean,
    ) = required && longRunDay == null && command.preferredLongRunDay.isBlank()

    private fun baselineIsInconsistent(weeklyKm: Double?, runs: Int?, longestKm: Double?): Boolean {
        val complete = weeklyKm != null && weeklyKm in 3.0..250.0 &&
            runs != null && runs in 2..5 && longestKm != null && longestKm > 0.0 && longestKm <= 80.0
        return complete && !isRepeatableWeekCoherent(requireNotNull(weeklyKm), requireNotNull(runs), requireNotNull(longestKm))
    }

    private fun recoverySpacingIsInvalid(
        command: CreatePlanCommand,
        runs: Int?,
        longRunDay: Int?,
        raceDistance: RaceDistance?,
        required: Boolean,
    ): Boolean {
        if (!required || runs == null || runs !in 2..5 || longRunDay == null) return false
        return !canLeaveRecoveryDayAfterLongRun(command.availability, runs, longRunDay, raceDistance)
    }
    private fun nextPlanStartDate(today: LocalDate): String = today.plusDays(((8 - today.dayOfWeek.value) % 7).toLong()).toString()
    private val DECIMAL = Regex("(?:0|[1-9]\\d*)(?:\\.\\d+)?")
    private val WHOLE = Regex("(?:0|[1-9]\\d*)")
}

/**
 * Stable description of the exact generated schedule a runner reviewed.
 * This is an in-memory confirmation boundary, not a security credential.
 */
internal fun generatedPlanConfirmationKey(plan: GeneratedDistancePlan): String = buildString {
    append(plan.startDate).append('|').append(plan.targetDate).append('|').append(plan.risk.name)
    plan.weeks.forEach { week ->
        append('|').append(week.weekNumber)
            .append(':').append(week.startDate)
            .append(':').append(week.trainingTargetDistanceMeters)
            .append(':').append(week.eventDistanceMeters)
            .append(':').append(week.targetDistanceMeters)
            .append(':').append(week.longRunMeters)
            .append(':').append(week.risk.name)
            .append(':').append(week.isDownWeek)
            .append(':').append(week.isTaper)
        week.workouts.forEach { workout ->
            append('[').append(workout.scheduledDate)
                .append(':').append(workout.type.name)
                .append(':').append(workout.targetDistanceMeters)
                .append(':').append(workout.targetDurationSeconds ?: 0)
                .append(':').append(workout.intensity)
                .append(']')
        }
    }
}

/**
 * Pure bridge from a validated onboarding result to the one local persistence boundary. The
 * operation ID is supplied by the action coordinator and is deliberately the only identity seed:
 * replaying the same action maps to the same goal and plan instead of silently creating another.
 */
internal object StandaloneOnboardingPersistenceMapper {
    fun map(
        command: CreatePlanCommand,
        outcome: StandaloneOnboardingOutcome,
    ): LocalPlanSetupRequest {
        require(command.operationId.isNotBlank()) { "operationId must not be blank" }
        require(command.occurredAtEpochMillis >= 0) {
            "occurredAtEpochMillis must not be negative"
        }
        require(outcome !is StandaloneOnboardingOutcome.Invalid) { "Cannot persist invalid onboarding" }

        val operationId = command.operationId
        val occurredAtEpochMillis = command.occurredAtEpochMillis
        val metadata = when (outcome) {
            is StandaloneOnboardingOutcome.Planned -> outcome.metadata
            is StandaloneOnboardingOutcome.Routine -> outcome.metadata
            is StandaloneOnboardingOutcome.Invalid -> error("unreachable")
        }
        val goalId = stableId("goal", operationId)
        val planId = stableId("plan", operationId)
        val profile = profile(command, metadata.startMode, occurredAtEpochMillis)
        val candidate = when (outcome) {
            is StandaloneOnboardingOutcome.Planned -> LocalPlanCandidate.Generated(
                GeneratedPlanPersistenceMapper.map(
                    outcome.plan,
                    GeneratedPlanGoalMetadata(
                        goalId = goalId,
                        planId = planId,
                        title = title(metadata),
                        goalKind = metadata.goalKind,
                        startMode = metadata.startMode,
                        goalTargetDate = metadata.targetDate,
                        targetDistanceMeters = metadata.raceDistance?.meters,
                        priority = metadata.priority,
                        createdAtEpochMillis = occurredAtEpochMillis,
                    ),
                ),
            )
            is StandaloneOnboardingOutcome.Routine -> LocalPlanCandidate.Routine(
                RoutinePlanPersistenceMapper.map(
                    goalId = goalId,
                    planId = planId,
                    title = title(metadata),
                    priority = metadata.priority.storageValue(),
                    startEpochDay = LocalDate.parse(outcome.startDate).toEpochDay(),
                    selectedDays = outcome.selectedDays,
                    weeklyTargetDistanceMeters = outcome.weeklyTargetDistanceMeters,
                    weeklyTargetDurationSeconds = outcome.weeklyTargetDurationSeconds,
                    startingWeeklyDistanceMeters = outcome.startingWeeklyDistanceMeters,
                    startingWeeklyDurationSeconds = outcome.startingWeeklyDurationSeconds,
                    weeklyChangeDistanceMeters = outcome.weeklyChangeDistanceMeters,
                    weeklyChangeDurationSeconds = outcome.weeklyChangeDurationSeconds,
                    createdAtEpochMillis = occurredAtEpochMillis,
                ),
            )
            is StandaloneOnboardingOutcome.Invalid -> error("unreachable")
        }
        return LocalPlanSetupRequest(
            operationId = operationId,
            operationFingerprint = setupFingerprint(command),
            profile = profile,
            availabilityDays = command.availability,
            candidate = candidate,
            confirmReplaceCurrent = command.confirmReplace,
            archiveAtEpochMillis = occurredAtEpochMillis,
        )
    }

    private fun profile(command: CreatePlanCommand, mode: StartMode, now: Long): ProfileSettingsEntity {
        val established = mode == StartMode.ESTABLISHED
        val hasEstablishedBaseline = established || mode == StartMode.ROUTINE
        return ProfileSettingsEntity(
            timeZone = command.timeZone.trim(),
            routeDataMode = "discard",
            heartRateDataMode = "discard",
            heartRateSettingsSource = "none",
            maxHeartRateBpm = null,
            zone2FloorBpm = null,
            zone3FloorBpm = null,
            zone4FloorBpm = null,
            zone5FloorBpm = null,
            updatedAtEpochMillis = now,
            baselineDistanceMeters = command.currentWeeklyDistanceKm.trim().toDoubleOrNull()
                ?.takeIf { hasEstablishedBaseline && command.routineGoalMetric != "time" }
                ?.let(::kilometersToMeters),
            baselineDurationSeconds = command.currentWeeklyMinutes.trim().toDoubleOrNull()
                ?.takeIf { mode == StartMode.ROUTINE && command.routineGoalMetric == "time" }
                ?.let { kotlin.math.floor(it * 60 + .5).toInt() },
            baselineConfirmed = hasEstablishedBaseline,
            currentRunsPerWeek = command.currentRunsPerWeek.trim().toIntOrNull()?.takeIf { hasEstablishedBaseline },
            longestRecentRunMeters = command.longestRecentRunKm.trim().toDoubleOrNull()
                ?.takeIf { established }?.let(::kilometersToMeters),
            confirmConcentratedSchedule = established && command.confirmConcentratedSchedule,
            preferredLongRunDay = command.preferredLongRunDay.trim().toIntOrNull()?.takeIf { established },
            experienceLevel = "not_specified",
        )
    }

    private fun title(metadata: StandaloneGoalMetadata): String = when (metadata.goalKind) {
        GoalKind.ROUTINE -> "Weekly running routine"
        GoalKind.RACE -> "${requireNotNull(metadata.raceDistance).label()} ${when (metadata.startMode) {
            StartMode.CALIBRATION -> "baseline check-in"
            StartMode.ROUTINE -> error("A race goal cannot use routine mode")
            else -> "plan"
        }}"
    }

    private fun RaceDistance.label(): String = when (this) {
        RaceDistance.FIVE_K -> "5K"
        RaceDistance.TEN_K -> "10K"
        RaceDistance.HALF -> "Half marathon"
        RaceDistance.MARATHON -> "Marathon"
    }
    private fun GoalPriority.storageValue(): String = when (this) {
        GoalPriority.FINISH_HEALTHY -> "finish_healthy"
        GoalPriority.CONSISTENCY -> "consistency"
    }
    private fun kilometersToMeters(value: Double): Int = kotlin.math.floor(value * 1000 + .5).toInt()

    /**
     * Versioned, length-prefixed encoding prevents delimiter ambiguity. Confirmation to replace an
     * existing plan and attempt time are deliberately excluded: they may change between a blocked
     * first submit and the exact committed retry without changing the requested plan.
     */
    private fun setupFingerprint(command: CreatePlanCommand): String {
        val fields = listOf(
            "runway-plan-setup-v6",
            command.goalKind.normalizedToken(),
            command.startMode.normalizedToken(),
            if (command.goalKind == "routine") "entered" else command.baselineMode.normalizedToken(),
            command.raceDistance.normalizedToken(),
            command.targetDate.trim(),
            command.priority.normalizedToken(),
            command.currentWeeklyDistanceKm.normalizedDecimal(),
            command.routineGoalMetric.normalizedToken(),
            command.routineWeeklyGoalKm.normalizedDecimal(),
            command.routineWeeklyGoalMinutes.normalizedDecimal(),
            command.currentWeeklyMinutes.normalizedDecimal(),
            command.routineWeeklyChangeKm.normalizedDecimal(),
            command.routineWeeklyChangeMinutes.normalizedDecimal(),
            command.currentRunsPerWeek.normalizedWhole(),
            command.longestRecentRunKm.normalizedDecimal(),
            command.availability.distinct().sorted().joinToString(","),
            command.preferredLongRunDay.normalizedWhole(),
            command.timeZone.trim(),
            command.confirmConcentratedSchedule.flag(),
        )
        val canonical = buildString {
            fields.forEach { value ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                append(bytes.size).append(':').append(value)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun String.normalizedToken(): String = trim().lowercase()
    private fun String.normalizedWhole(): String = trim().toIntOrNull()?.toString() ?: trim()
    private fun String.normalizedDecimal(): String =
        trim().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: trim()
    private fun Boolean.flag(): String = if (this) "1" else "0"

    private fun stableId(prefix: String, operationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(operationId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$prefix-$digest"
    }
}
