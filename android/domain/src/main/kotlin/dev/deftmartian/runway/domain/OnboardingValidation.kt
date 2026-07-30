package dev.deftmartian.runway.domain

import java.time.LocalDate

/** Pure onboarding constraints. This is intentionally independent of forms, Room, and network payloads. */
data class TargetDateBounds(val minimum: String, val maximum: String)
enum class OnboardingIssue {
    INVALID_TIME_ZONE, MISSING_EXPERIENCE, MISSING_START_MODE, INVALID_GOAL_MODE,
    MISSING_RACE_DISTANCE, MISSING_TARGET_DATE, TARGET_DATE_OUT_OF_BOUNDS,
    INSUFFICIENT_AVAILABLE_DAYS, INVALID_ESTABLISHED_BASELINE, INVALID_CALIBRATION_DURATION,
    INVALID_LONG_RUN_DAY, CONCENTRATED_SCHEDULE_NOT_CONFIRMED, HEALTH_BLOCKS_SCHEDULING
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
    val confirmConcentratedSchedule: Boolean = false
)

object OnboardingValidation {
    fun targetDateBounds(today: LocalDate, mode: StartMode): TargetDateBounds {
        val weeks = when (mode) { StartMode.ESTABLISHED -> 8; StartMode.CALIBRATION -> 10; StartMode.FOUNDATION_TO_GOAL -> 17; StartMode.FOUNDATION_ONLY -> 0 }
        require(weeks > 0) { "Foundation-only plans do not have a race target-date bound." }
        return TargetDateBounds(today.plusDays((weeks * 7).toLong()).toString(), today.plusDays(52L * 7 - 1).toString())
    }

    fun validate(selection: OnboardingSelection, today: LocalDate): Set<OnboardingIssue> = buildSet {
        val mode = selection.startMode
        if (!DateUtils.isValidTimeZone(selection.timeZone)) add(OnboardingIssue.INVALID_TIME_ZONE)
        if (selection.experience == null) add(OnboardingIssue.MISSING_EXPERIENCE)
        if (selection.availability.distinct().size != selection.availability.size || selection.availability.any { it !in 0..6 }) add(OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS)
        if (selection.injuryFlags.currentPain || selection.injuryFlags.medicalRestriction) add(OnboardingIssue.HEALTH_BLOCKS_SCHEDULING)
        if (selection.goalKind == GoalKind.RACE && mode == null) add(OnboardingIssue.MISSING_START_MODE)
        if (mode == StartMode.FOUNDATION_ONLY && selection.goalKind != GoalKind.FOUNDATION || mode != null && mode != StartMode.FOUNDATION_ONLY && selection.goalKind != GoalKind.RACE) add(OnboardingIssue.INVALID_GOAL_MODE)
        if (selection.goalKind == GoalKind.RACE) {
            if (selection.raceDistance == null) add(OnboardingIssue.MISSING_RACE_DISTANCE)
            if (selection.targetDate == null) add(OnboardingIssue.MISSING_TARGET_DATE) else if (mode != null && mode != StartMode.FOUNDATION_ONLY) {
                val bounds = targetDateBounds(today, mode)
                try { val target = DateUtils.parseIsoDate(selection.targetDate); if (target < DateUtils.parseIsoDate(bounds.minimum) || target > DateUtils.parseIsoDate(bounds.maximum)) add(OnboardingIssue.TARGET_DATE_OUT_OF_BOUNDS) } catch (_: IllegalArgumentException) { add(OnboardingIssue.TARGET_DATE_OUT_OF_BOUNDS) }
            }
        }
        val requiredDays = if (mode == StartMode.FOUNDATION_TO_GOAL || mode == StartMode.FOUNDATION_ONLY) 3 else 2
        if (selection.availability.distinct().size < requiredDays) add(OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS)
        if (mode == StartMode.ESTABLISHED && !selection.injuryFlags.currentPain && !selection.injuryFlags.medicalRestriction) {
            if (selection.currentWeeklyDistanceKm == null || selection.currentWeeklyDistanceKm !in 3.0..250.0 || selection.currentRunsPerWeek == null || selection.currentRunsPerWeek !in 2..5 || selection.longestRecentRunKm == null || selection.longestRecentRunKm <= 0 || selection.longestRecentRunKm > 80) add(OnboardingIssue.INVALID_ESTABLISHED_BASELINE)
            if (selection.preferredLongRunDay == null || selection.preferredLongRunDay !in selection.availability) add(OnboardingIssue.INVALID_LONG_RUN_DAY)
            if (selection.currentRunsPerWeek != null && selection.availability.size < selection.currentRunsPerWeek) add(OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS)
            if ((selection.raceDistance == RaceDistance.HALF || selection.raceDistance == RaceDistance.MARATHON) && selection.currentRunsPerWeek == 2 && !selection.confirmConcentratedSchedule) add(OnboardingIssue.CONCENTRATED_SCHEDULE_NOT_CONFIRMED)
        }
        if (mode == StartMode.CALIBRATION && selection.calibrationDurationMinutes !in 10..30) add(OnboardingIssue.INVALID_CALIBRATION_DURATION)
    }
}
