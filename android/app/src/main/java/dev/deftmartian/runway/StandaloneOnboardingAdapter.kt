package dev.deftmartian.runway

import dev.deftmartian.runway.data.GeneratedPlanGoalMetadata
import dev.deftmartian.runway.data.GeneratedPlanPersistenceMapper
import dev.deftmartian.runway.data.GoalEntity
import dev.deftmartian.runway.data.LocalPlanCandidate
import dev.deftmartian.runway.data.LocalPlanSetupRequest
import dev.deftmartian.runway.data.ProfileSettingsEntity
import dev.deftmartian.runway.data.RoutinePlanPersistenceMapper
import dev.deftmartian.runway.domain.CalibrationIntake
import dev.deftmartian.runway.domain.DateUtils
import dev.deftmartian.runway.domain.EstablishedTrainingIntake
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class OnboardingField {
    GOAL_KIND, START_MODE, RACE_DISTANCE, TARGET_DATE, PRIORITY,
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
    data class Routine(
        val metadata: StandaloneGoalMetadata,
        val startDate: String,
        val selectedDays: List<Int>,
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
                "foundation" to GoalKind.FOUNDATION,
                "routine" to GoalKind.ROUTINE,
            ),
        )
        val startMode = enumValue(OnboardingField.START_MODE, command.startMode, mapOf(
            "established" to StartMode.ESTABLISHED,
            "foundation_to_goal" to StartMode.FOUNDATION_TO_GOAL,
            "foundation_only" to StartMode.FOUNDATION_ONLY,
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
        if (command.injuryNotes.trim().length > 240) error(OnboardingField.HEALTH_NOTES, OnboardingFieldError.OUT_OF_RANGE)
        if (command.availability.distinct().size != command.availability.size) error(OnboardingField.AVAILABILITY, OnboardingFieldError.DUPLICATE_DAY)

        if (goalKind == null || startMode == null || priority == null || zone == null || today == null) {
            return StandaloneOnboardingOutcome.Invalid(errors, null)
        }
        // These inputs only exist for their respective start paths. Parsing every hidden form
        // value made a new/foundation runner fail validation because an unrelated stale value was
        // malformed. Established runners still receive precise errors for every baseline field.
        val established = startMode == StartMode.ESTABLISHED
        val calibration = startMode == StartMode.CALIBRATION
        val flags = InjuryFlags(command.recentInjury, command.currentPain, command.recurringPain, command.medicalRestriction, command.injuryNotes.trim())
        val needsEstablishedBaseline = established && !flags.currentPain && !flags.medicalRestriction
        val weeklyKm = if (needsEstablishedBaseline) strictDecimal(command.currentWeeklyDistanceKm, OnboardingField.WEEKLY_DISTANCE, ::error) else null
        val runs = if (needsEstablishedBaseline) strictWhole(command.currentRunsPerWeek, OnboardingField.RUNS_PER_WEEK, ::error) else null
        val longestKm = if (needsEstablishedBaseline) strictDecimal(command.longestRecentRunKm, OnboardingField.LONGEST_RUN, ::error) else null
        val calibrationMinutes = if (calibration && !flags.currentPain && !flags.medicalRestriction) {
            strictWhole(command.calibrationDurationMinutes, OnboardingField.CALIBRATION_DURATION, ::error)
        } else null
        val longRunDay = if (needsEstablishedBaseline) strictWhole(command.preferredLongRunDay, OnboardingField.LONG_RUN_DAY, ::error) else null
        val bounds = if (goalKind == GoalKind.RACE) {
            OnboardingValidation.targetDateBounds(today, startMode)
        } else {
            null
        }
        val selection = OnboardingSelection(
            goalKind, startMode, raceDistance, targetDate, command.availability, timeZone, flags,
            weeklyKm, runs, longestKm, longRunDay,
            calibrationMinutes ?: if (calibration && (flags.currentPain || flags.medicalRestriction)) 10 else null,
            command.confirmConcentratedSchedule,
        )
        OnboardingValidation.validate(selection, today).forEach { issue -> mapIssue(issue, ::error) }
        if (needsEstablishedBaseline) {
            validateEstablishedBaseline(command, weeklyKm, runs, longestKm, longRunDay, ::error)
        }
        if (goalKind == GoalKind.RACE && raceDistance == null) error(OnboardingField.RACE_DISTANCE, OnboardingFieldError.REQUIRED)
        if (goalKind == GoalKind.RACE && targetDate == null) error(OnboardingField.TARGET_DATE, OnboardingFieldError.REQUIRED)
        val blocked = flags.currentPain || flags.medicalRestriction
        // HEALTH_BLOCKS_PHASE is an outcome, not a malformed goal: retain all other errors.
        if (errors.isNotEmpty()) return StandaloneOnboardingOutcome.Invalid(errors.mapValues { it.value.toSet() }, bounds)
        val metadata = StandaloneGoalMetadata(goalKind, startMode, raceDistance, targetDate, priority, timeZone, bounds)
        if (blocked) return StandaloneOnboardingOutcome.PendingGoal(metadata)
        if (startMode == StartMode.ROUTINE) {
            return StandaloneOnboardingOutcome.Routine(
                metadata = metadata,
                startDate = today.toString(),
                selectedDays = command.availability.sorted(),
            )
        }
        val startDate = nextPlanStartDate(today)
        val intake = when (startMode) {
            StartMode.ESTABLISHED -> EstablishedTrainingIntake(priority, command.availability, flags, requireNotNull(raceDistance), requireNotNull(targetDate), kilometersToMeters(requireNotNull(weeklyKm)), requireNotNull(runs), kilometersToMeters(requireNotNull(longestKm)), requireNotNull(longRunDay), startDate)
            StartMode.FOUNDATION_TO_GOAL, StartMode.FOUNDATION_ONLY -> FoundationIntake(startMode, goalKind, raceDistance, command.availability, flags, startDate)
            StartMode.CALIBRATION -> CalibrationIntake(goalKind, raceDistance, command.availability, flags, requireNotNull(calibrationMinutes) * 60, startDate)
            StartMode.ROUTINE -> error("Routine setup does not use the training planner")
        }
        return StandaloneOnboardingOutcome.Planned(metadata, intake, TrainingPlanner.generatePlan(intake, today))
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
        OnboardingIssue.INVALID_CALIBRATION_DURATION -> error(OnboardingField.CALIBRATION_DURATION, OnboardingFieldError.OUT_OF_RANGE)
        OnboardingIssue.INVALID_LONG_RUN_DAY -> error(OnboardingField.LONG_RUN_DAY, OnboardingFieldError.INVALID_VALUE)
        OnboardingIssue.CONCENTRATED_SCHEDULE_NOT_CONFIRMED -> error(OnboardingField.CONCENTRATED_SCHEDULE, OnboardingFieldError.CONCENTRATED_SCHEDULE_CONFIRMATION)
        OnboardingIssue.HEALTH_BLOCKS_SCHEDULING -> Unit
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
    private fun validateEstablishedBaseline(
        command: CreatePlanCommand,
        weeklyKm: Double?,
        runs: Int?,
        longestKm: Double?,
        longRunDay: Int?,
        error: (OnboardingField, OnboardingFieldError) -> Unit,
    ) {
        when {
            weeklyKm == null && command.currentWeeklyDistanceKm.isBlank() -> error(OnboardingField.WEEKLY_DISTANCE, OnboardingFieldError.REQUIRED)
            weeklyKm != null && weeklyKm !in 3.0..250.0 -> error(OnboardingField.WEEKLY_DISTANCE, OnboardingFieldError.OUT_OF_RANGE)
        }
        when {
            runs == null && command.currentRunsPerWeek.isBlank() -> error(OnboardingField.RUNS_PER_WEEK, OnboardingFieldError.REQUIRED)
            runs != null && runs !in 2..5 -> error(OnboardingField.RUNS_PER_WEEK, OnboardingFieldError.OUT_OF_RANGE)
        }
        when {
            longestKm == null && command.longestRecentRunKm.isBlank() -> error(OnboardingField.LONGEST_RUN, OnboardingFieldError.REQUIRED)
            longestKm != null && (longestKm <= 0.0 || longestKm > 80.0) -> error(OnboardingField.LONGEST_RUN, OnboardingFieldError.OUT_OF_RANGE)
        }
        if (longRunDay == null && command.preferredLongRunDay.isBlank()) {
            error(OnboardingField.LONG_RUN_DAY, OnboardingFieldError.REQUIRED)
        }
    }
    private fun nextPlanStartDate(today: LocalDate): String = today.plusDays(((8 - today.dayOfWeek.value) % 7).toLong()).toString()
    private val DECIMAL = Regex("(?:0|[1-9]\\d*)(?:\\.\\d+)?")
    private val WHOLE = Regex("(?:0|[1-9]\\d*)")
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
            is StandaloneOnboardingOutcome.PendingGoal -> outcome.metadata
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
                        targetDistanceMeters = metadata.raceDistance?.meters(),
                        priority = metadata.priority,
                        createdAtEpochMillis = occurredAtEpochMillis,
                    ),
                ),
            )
            is StandaloneOnboardingOutcome.PendingGoal -> LocalPlanCandidate.Pending(
                GoalEntity(
                    goalId = goalId,
                    title = title(metadata),
                    targetDateEpochDay = metadata.targetDate?.let { LocalDate.parse(it).toEpochDay() },
                    state = "pending",
                    createdAtEpochMillis = occurredAtEpochMillis,
                    updatedAtEpochMillis = occurredAtEpochMillis,
                    kind = metadata.goalKind.name.lowercase(),
                    startMode = metadata.startMode.name.lowercase(),
                    raceDistanceMeters = metadata.raceDistance?.meters(),
                    priority = metadata.priority.storageValue(),
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
        val hasEstablishedBaseline = established && !command.currentPain && !command.medicalRestriction
        val calibration = mode == StartMode.CALIBRATION
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
            recentInjury = command.recentInjury,
            currentPain = command.currentPain,
            recurringPain = command.recurringPain,
            medicalRestriction = command.medicalRestriction,
            privateNotes = command.injuryNotes.trim().takeIf(String::isNotEmpty),
            updatedAtEpochMillis = now,
            baselineDistanceMeters = command.currentWeeklyDistanceKm.trim().toDoubleOrNull()
                ?.takeIf { hasEstablishedBaseline }?.let(::kilometersToMeters),
            baselineDurationSeconds = null,
            baselineConfirmed = hasEstablishedBaseline,
            currentRunsPerWeek = command.currentRunsPerWeek.trim().toIntOrNull()?.takeIf { hasEstablishedBaseline },
            longestRecentRunMeters = command.longestRecentRunKm.trim().toDoubleOrNull()
                ?.takeIf { hasEstablishedBaseline }?.let(::kilometersToMeters),
            calibrationDurationSeconds = command.calibrationDurationMinutes.trim().toIntOrNull()
                ?.takeIf { calibration }?.times(60),
            confirmConcentratedSchedule = hasEstablishedBaseline && command.confirmConcentratedSchedule,
            preferredLongRunDay = command.preferredLongRunDay.trim().toIntOrNull()?.takeIf { hasEstablishedBaseline },
            experienceLevel = "not_specified",
        )
    }

    private fun title(metadata: StandaloneGoalMetadata): String = when (metadata.goalKind) {
        GoalKind.FOUNDATION -> "Foundation"
        GoalKind.ROUTINE -> "Weekly running routine"
        GoalKind.RACE -> "${requireNotNull(metadata.raceDistance).label()} ${when (metadata.startMode) {
            StartMode.FOUNDATION_TO_GOAL -> "foundation"
            StartMode.CALIBRATION -> "calibration"
            StartMode.ROUTINE -> error("A race goal cannot use routine mode")
            else -> "plan"
        }}"
    }

    private fun RaceDistance.meters(): Int = when (this) {
        RaceDistance.FIVE_K -> 5_000
        RaceDistance.TEN_K -> 10_000
        RaceDistance.HALF -> 21_097
        RaceDistance.MARATHON -> 42_195
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
            "runway-plan-setup-v1",
            command.goalKind.normalizedToken(),
            command.startMode.normalizedToken(),
            command.raceDistance.normalizedToken(),
            command.targetDate.trim(),
            command.priority.normalizedToken(),
            command.currentWeeklyDistanceKm.normalizedDecimal(),
            command.currentRunsPerWeek.normalizedWhole(),
            command.longestRecentRunKm.normalizedDecimal(),
            command.calibrationDurationMinutes.normalizedWhole(),
            command.availability.distinct().sorted().joinToString(","),
            command.preferredLongRunDay.normalizedWhole(),
            command.timeZone.trim(),
            command.recentInjury.flag(),
            command.currentPain.flag(),
            command.recurringPain.flag(),
            command.medicalRestriction.flag(),
            command.injuryNotes.trim(),
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
