package dev.deftmartian.runway.domain

import java.time.LocalDate

/** Pure onboarding constraints. This is intentionally independent of forms, Room, and network payloads. */
data class TargetDateBounds(val minimum: String, val maximum: String)

enum class OnboardingIssue {
    INVALID_TIME_ZONE,
    MISSING_START_MODE,
    INVALID_GOAL_MODE,
    MISSING_RACE_DISTANCE,
    MISSING_TARGET_DATE,
    TARGET_DATE_OUT_OF_BOUNDS,
    INSUFFICIENT_AVAILABLE_DAYS,
    INVALID_ESTABLISHED_BASELINE,
    INVALID_LONG_RUN_DAY,
    INSUFFICIENT_RECOVERY_SPACING,
    CONCENTRATED_SCHEDULE_NOT_CONFIRMED,
}

data class OnboardingSelection(
    val goalKind: GoalKind,
    val startMode: StartMode?,
    val raceDistance: RaceDistance?,
    val targetDate: String?,
    val availability: List<Int>,
    val timeZone: String,
    val currentWeeklyDistanceKm: Double? = null,
    val currentRunsPerWeek: Int? = null,
    val longestRecentRunKm: Double? = null,
    val preferredLongRunDay: Int? = null,
    val confirmConcentratedSchedule: Boolean = false,
)

/**
 * A reported established baseline must describe one week that could be repeated:
 * the longest run is part of the weekly total, every other run is at least 500 m,
 * and no run is longer than the reported longest run.
 */
fun isRepeatableWeekCoherent(
    weeklyDistanceKm: Double,
    runsPerWeek: Int,
    longestRunKm: Double,
): Boolean =
    longestRunKm + 0.5 * (runsPerWeek - 1) <= weeklyDistanceKm &&
        weeklyDistanceKm <= longestRunKm * runsPerWeek

fun canLeaveRecoveryDayAfterLongRun(
    availability: List<Int>,
    currentRunsPerWeek: Int,
    preferredLongRunDay: Int,
    raceDistance: RaceDistance?,
): Boolean {
    if (preferredLongRunDay !in availability || currentRunsPerWeek !in 2..5) return false
    val plannedRuns = minOf(
        currentRunsPerWeek,
        if (raceDistance == RaceDistance.HALF) 4 else 5,
    )
    val recoveryDay = (preferredLongRunDay + 1) % 7
    return availability.distinct().count { it != recoveryDay } >= plannedRuns
}

object OnboardingValidation {
    fun targetDateBounds(today: LocalDate, mode: StartMode): TargetDateBounds {
        val weeks = when (mode) {
            StartMode.ESTABLISHED -> 8
            StartMode.CALIBRATION -> 10
            StartMode.ROUTINE -> 0
        }
        require(weeks > 0) { "Only race plans have a target-date bound." }
        return TargetDateBounds(
            today.plusDays((weeks * 7).toLong()).toString(),
            today.plusDays(52L * 7 - 1).toString(),
        )
    }

    fun validate(selection: OnboardingSelection, today: LocalDate): Set<OnboardingIssue> = buildSet {
        val mode = selection.startMode
        if (!DateUtils.isValidTimeZone(selection.timeZone)) add(OnboardingIssue.INVALID_TIME_ZONE)
        if (hasInvalidAvailability(selection)) add(OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS)
        if (selection.goalKind in setOf(GoalKind.RACE, GoalKind.ROUTINE) && mode == null) {
            add(OnboardingIssue.MISSING_START_MODE)
        }
        if (hasInvalidGoalMode(selection.goalKind, mode)) add(OnboardingIssue.INVALID_GOAL_MODE)

        if (selection.goalKind == GoalKind.RACE) {
            validateRaceGoal(selection, today, mode, this)
        }

        val requiredDays = when (mode) {
            StartMode.ROUTINE -> 1
            else -> 2
        }
        if (selection.availability.distinct().size < requiredDays) {
            add(OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS)
        }
        if (mode == StartMode.ESTABLISHED) {
            validateEstablishedBaseline(selection, this)
        }
    }

    private fun hasInvalidAvailability(selection: OnboardingSelection): Boolean =
        selection.availability.distinct().size != selection.availability.size ||
            selection.availability.any { it !in 0..6 }

    private fun hasInvalidGoalMode(goalKind: GoalKind, mode: StartMode?): Boolean =
        mode == StartMode.ROUTINE && goalKind != GoalKind.ROUTINE ||
            mode != null && mode != StartMode.ROUTINE && goalKind != GoalKind.RACE

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
        } else if (mode != null) {
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

    private fun validateEstablishedBaseline(
        selection: OnboardingSelection,
        issues: MutableSet<OnboardingIssue>,
    ) {
        if (hasInvalidEstablishedBaseline(selection)) {
            issues += OnboardingIssue.INVALID_ESTABLISHED_BASELINE
        }
        if (hasInvalidLongRunDay(selection)) {
            issues += OnboardingIssue.INVALID_LONG_RUN_DAY
        }
        if (hasTooFewAvailableDays(selection)) {
            issues += OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS
        }
        if (hasInsufficientRecoverySpacing(selection)) {
            issues += OnboardingIssue.INSUFFICIENT_RECOVERY_SPACING
        }
        if (requiresConcentratedScheduleConfirmation(selection)) {
            issues += OnboardingIssue.CONCENTRATED_SCHEDULE_NOT_CONFIRMED
        }
    }

    private fun hasInvalidLongRunDay(selection: OnboardingSelection): Boolean =
        selection.preferredLongRunDay == null || selection.preferredLongRunDay !in selection.availability

    private fun hasTooFewAvailableDays(selection: OnboardingSelection): Boolean =
        selection.currentRunsPerWeek?.let { selection.availability.size < it } == true

    private fun hasInsufficientRecoverySpacing(selection: OnboardingSelection): Boolean {
        val runsPerWeek = selection.currentRunsPerWeek ?: return false
        val longRunDay = selection.preferredLongRunDay ?: return false
        return !canLeaveRecoveryDayAfterLongRun(
            selection.availability,
            runsPerWeek,
            longRunDay,
            selection.raceDistance,
        )
    }

    private fun requiresConcentratedScheduleConfirmation(selection: OnboardingSelection): Boolean =
        selection.raceDistance in setOf(RaceDistance.HALF, RaceDistance.MARATHON) &&
            selection.currentRunsPerWeek == 2 &&
            !selection.confirmConcentratedSchedule

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
            longestRun > 80 ||
            !isRepeatableWeekCoherent(weeklyDistance, runsPerWeek, longestRun)
    }
}
