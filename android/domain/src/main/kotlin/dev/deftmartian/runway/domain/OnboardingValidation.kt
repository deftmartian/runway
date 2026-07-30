package dev.deftmartian.runway.domain

import java.time.LocalDate

/** Pure onboarding constraints. This is intentionally independent of forms, Room, and network payloads. */
data class TargetDateBounds(val minimum: String, val maximum: String)

enum class OnboardingIssue {
    INVALID_TIME_ZONE,
    MISSING_EXPERIENCE,
    MISSING_START_MODE,
    INVALID_GOAL_MODE,
    MISSING_RACE_DISTANCE,
    MISSING_TARGET_DATE,
    TARGET_DATE_OUT_OF_BOUNDS,
    INSUFFICIENT_AVAILABLE_DAYS,
    INVALID_ESTABLISHED_BASELINE,
    INVALID_CALIBRATION_DURATION,
    INVALID_LONG_RUN_DAY,
    CONCENTRATED_SCHEDULE_NOT_CONFIRMED,
    HEALTH_BLOCKS_SCHEDULING,
}

data class OnboardingSelection(
    val goalKind: GoalKind,
    val startMode: StartMode?,
    val raceDistance: RaceDistance?,
    val targetDate: String?,
    val experience: Experience?,
    val availability: List<Int>,
    val timeZone: String,
    val injuryFlags: InjuryFlags,
    val currentWeeklyDistanceKm: Double? = null,
    val currentRunsPerWeek: Int? = null,
    val longestRecentRunKm: Double? = null,
    val preferredLongRunDay: Int? = null,
    val calibrationDurationMinutes: Int? = null,
    val confirmConcentratedSchedule: Boolean = false,
)

object OnboardingValidation {
    fun targetDateBounds(today: LocalDate, mode: StartMode): TargetDateBounds {
        val weeks = when (mode) {
            StartMode.ESTABLISHED -> 8
            StartMode.CALIBRATION -> 10
            StartMode.FOUNDATION_TO_GOAL -> 17
            StartMode.FOUNDATION_ONLY -> 0
        }
        require(weeks > 0) { "Foundation-only plans do not have a race target-date bound." }
        return TargetDateBounds(
            today.plusDays((weeks * 7).toLong()).toString(),
            today.plusDays(52L * 7 - 1).toString(),
        )
    }

    fun validate(selection: OnboardingSelection, today: LocalDate): Set<OnboardingIssue> = buildSet {
        val mode = selection.startMode
        if (!DateUtils.isValidTimeZone(selection.timeZone)) add(OnboardingIssue.INVALID_TIME_ZONE)
        if (selection.experience == null) add(OnboardingIssue.MISSING_EXPERIENCE)
        if (hasInvalidAvailability(selection)) add(OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS)
        if (selection.injuryFlags.currentPain || selection.injuryFlags.medicalRestriction) {
            add(OnboardingIssue.HEALTH_BLOCKS_SCHEDULING)
        }
        if (selection.goalKind == GoalKind.RACE && mode == null) {
            add(OnboardingIssue.MISSING_START_MODE)
        }
        if (hasInvalidGoalMode(selection.goalKind, mode)) add(OnboardingIssue.INVALID_GOAL_MODE)

        if (selection.goalKind == GoalKind.RACE) {
            validateRaceGoal(selection, today, mode, this)
        }

        val requiredDays = when (mode) {
            StartMode.FOUNDATION_TO_GOAL, StartMode.FOUNDATION_ONLY -> 3
            else -> 2
        }
        if (selection.availability.distinct().size < requiredDays) {
            add(OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS)
        }
        if (mode == StartMode.ESTABLISHED && canScheduleWithHealthFlags(selection)) {
            validateEstablishedBaseline(selection, this)
        }
        if (mode == StartMode.CALIBRATION && selection.calibrationDurationMinutes !in 10..30) {
            add(OnboardingIssue.INVALID_CALIBRATION_DURATION)
        }
    }

    private fun hasInvalidAvailability(selection: OnboardingSelection): Boolean =
        selection.availability.distinct().size != selection.availability.size ||
            selection.availability.any { it !in 0..6 }

    private fun hasInvalidGoalMode(goalKind: GoalKind, mode: StartMode?): Boolean =
        mode == StartMode.FOUNDATION_ONLY && goalKind != GoalKind.FOUNDATION ||
            mode != null && mode != StartMode.FOUNDATION_ONLY && goalKind != GoalKind.RACE

    private fun validateRaceGoal(
        selection: OnboardingSelection,
        today: LocalDate,
        mode: StartMode?,
        issues: MutableSet<OnboardingIssue>,
    ) {
        if (selection.raceDistance == null) issues += OnboardingIssue.MISSING_RACE_DISTANCE
        val targetDate = selection.targetDate
        if (targetDate == null) {
            issues += OnboardingIssue.MISSING_TARGET_DATE
        } else if (mode != null && mode != StartMode.FOUNDATION_ONLY) {
            val bounds = targetDateBounds(today, mode)
            if (!isTargetDateWithinBounds(targetDate, bounds)) {
                issues += OnboardingIssue.TARGET_DATE_OUT_OF_BOUNDS
            }
        }
    }

    private fun isTargetDateWithinBounds(targetDate: String, bounds: TargetDateBounds): Boolean =
        try {
            val target = DateUtils.parseIsoDate(targetDate)
            target >= DateUtils.parseIsoDate(bounds.minimum) &&
                target <= DateUtils.parseIsoDate(bounds.maximum)
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun canScheduleWithHealthFlags(selection: OnboardingSelection): Boolean =
        !selection.injuryFlags.currentPain && !selection.injuryFlags.medicalRestriction

    private fun validateEstablishedBaseline(
        selection: OnboardingSelection,
        issues: MutableSet<OnboardingIssue>,
    ) {
        if (hasInvalidEstablishedBaseline(selection)) {
            issues += OnboardingIssue.INVALID_ESTABLISHED_BASELINE
        }
        if (selection.preferredLongRunDay == null || selection.preferredLongRunDay !in selection.availability) {
            issues += OnboardingIssue.INVALID_LONG_RUN_DAY
        }
        if (
            selection.currentRunsPerWeek != null &&
            selection.availability.size < selection.currentRunsPerWeek
        ) {
            issues += OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS
        }
        val isLongDistanceGoal =
            selection.raceDistance == RaceDistance.HALF || selection.raceDistance == RaceDistance.MARATHON
        if (isLongDistanceGoal && selection.currentRunsPerWeek == 2 && !selection.confirmConcentratedSchedule) {
            issues += OnboardingIssue.CONCENTRATED_SCHEDULE_NOT_CONFIRMED
        }
    }

    private fun hasInvalidEstablishedBaseline(selection: OnboardingSelection): Boolean {
        val weeklyDistance = selection.currentWeeklyDistanceKm
        val runsPerWeek = selection.currentRunsPerWeek
        val longestRun = selection.longestRecentRunKm
        return weeklyDistance == null ||
            weeklyDistance !in 3.0..250.0 ||
            runsPerWeek == null ||
            runsPerWeek !in 2..5 ||
            longestRun == null ||
            longestRun <= 0 ||
            longestRun > 80
    }
}
