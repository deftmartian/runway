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
    if (preferredLongRunDay !in availability || currentRunsPerWeek !in MIN_BASELINE_RUNS..MAX_BASELINE_RUNS) return false
    val plannedRuns = plannedRunDays(currentRunsPerWeek, raceDistance)
    val recoveryDay = (preferredLongRunDay + 1) % 7
    return availability.distinct().count { it != recoveryDay } >= plannedRuns
}

/** A reported week may have 2 to 7 runs; the plan schedules at most [plannedRunDays] of them. */
const val MIN_BASELINE_RUNS = 2
const val MAX_BASELINE_RUNS = 7

/** Run days a generated race plan uses: the reported count, capped at 4 for a half and 5 otherwise. */
fun plannedRunDays(currentRunsPerWeek: Int, raceDistance: RaceDistance?): Int =
    minOf(currentRunsPerWeek, if (raceDistance == RaceDistance.HALF) 4 else 5)

object OnboardingValidation {
    fun targetDateBounds(today: LocalDate, mode: StartMode): TargetDateBounds {
        val weeks = when (mode) {
            // Shorter lead times are allowed; the plan assessment says when the ramp is steep.
            StartMode.ESTABLISHED -> 4
            StartMode.CALIBRATION -> 6
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
    }

    private fun hasInvalidLongRunDay(selection: OnboardingSelection): Boolean =
        selection.preferredLongRunDay == null || selection.preferredLongRunDay !in selection.availability

    private fun hasTooFewAvailableDays(selection: OnboardingSelection): Boolean =
        selection.currentRunsPerWeek?.let {
            selection.availability.size < plannedRunDays(it, selection.raceDistance)
        } == true

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

    private fun hasInvalidEstablishedBaseline(selection: OnboardingSelection): Boolean {
        val weeklyDistance = selection.currentWeeklyDistanceKm
        val runsPerWeek = selection.currentRunsPerWeek
        val longestRun = selection.longestRecentRunKm
        return weeklyDistance == null ||
            weeklyDistance !in 3.0..250.0 ||
            runsPerWeek == null ||
            runsPerWeek !in MIN_BASELINE_RUNS..MAX_BASELINE_RUNS ||
            longestRun == null ||
            longestRun <= 0 ||
            longestRun > 80 ||
            !isRepeatableWeekCoherent(weeklyDistance, runsPerWeek, longestRun)
    }
}
