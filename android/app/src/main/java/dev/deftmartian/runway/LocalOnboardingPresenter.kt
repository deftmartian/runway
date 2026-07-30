package dev.deftmartian.runway

import dev.deftmartian.runway.data.LocalSettingsReadModel
import dev.deftmartian.runway.domain.OnboardingValidation
import dev.deftmartian.runway.domain.StartMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure projection for setup. The phone zone is only a fresh-install fallback; once a local
 * profile exists, its validated training zone owns date boundaries.
 */
internal fun LocalSettingsReadModel.toNativeOnboardingPayload(
    now: Instant = Instant.now(),
    fallbackZone: ZoneId = ZoneId.systemDefault(),
): NativeOnboardingPayload {
    val currentGoal = activePlan?.let {
        NativeGoalSummary(
            id = it.goalId,
            title = it.goalTitle,
            distance = it.raceDistanceMeters.toRaceDistanceLabel(),
            targetDate = it.goalTargetEpochDay?.let(LocalDate::ofEpochDay)?.toString(),
            priority = it.goalPriority,
            state = "active",
            risk = it.riskAssessment,
        )
    } ?: pendingGoal?.let {
        NativeGoalSummary(
            id = it.goalId,
            title = it.title,
            distance = it.raceDistanceMeters.toRaceDistanceLabel(),
            targetDate = it.targetEpochDay?.let(LocalDate::ofEpochDay)?.toString(),
            priority = it.priority,
            state = "pending",
            risk = null,
        )
    }
    val zone = profile?.timeZone
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: fallbackZone
    val today = now.atZone(zone).toLocalDate()
    val established = OnboardingValidation.targetDateBounds(today, StartMode.ESTABLISHED)
    val calibration = OnboardingValidation.targetDateBounds(today, StartMode.CALIBRATION)
    val foundation = OnboardingValidation.targetDateBounds(today, StartMode.FOUNDATION_TO_GOAL)
    val initial = if (profile == null && currentGoal == null) {
        null
    } else {
        NativePlanInitialValues(
            startMode = activePlan?.startMode ?: pendingGoal?.startMode,
            raceDistance = (activePlan?.raceDistanceMeters ?: pendingGoal?.raceDistanceMeters).toRaceDistanceWireValue(),
            targetDate = (activePlan?.goalTargetEpochDay ?: pendingGoal?.targetEpochDay)?.let(LocalDate::ofEpochDay)?.toString(),
            priority = activePlan?.goalPriority ?: pendingGoal?.priority,
            currentWeeklyDistanceKm = profile?.baselineDistanceMeters.toKilometreInput(),
            currentRunsPerWeek = profile?.currentRunsPerWeek?.toString(),
            longestRecentRunKm = profile?.longestRecentRunMeters.toKilometreInput(),
            calibrationDurationMinutes = profile?.calibrationDurationSeconds
                ?.let { (it / 60).toString() },
            preferredLongRunDay = profile?.preferredLongRunDay?.toString(),
            timeZone = profile?.timeZone ?: zone.id,
            availability = profile?.availabilityDays.orEmpty(),
            recentInjury = profile?.recentInjury,
            currentPain = profile?.currentPain,
            recurringPain = profile?.recurringPain,
            medicalRestriction = profile?.medicalRestriction,
            injuryNotes = profile?.privateNotes,
        )
    }
    return NativeOnboardingPayload(
        initialValues = initial,
        minimumTargetDate = established.minimum,
        minimumCalibrationTargetDate = calibration.minimum,
        minimumFoundationTargetDate = foundation.minimum,
        maximumTargetDate = established.maximum,
        currentGoal = currentGoal,
    )
}

private fun Int?.toKilometreInput(): String? = this?.let { meters ->
    if (meters % 1_000 == 0) {
        (meters / 1_000).toString()
    } else {
        (meters / 1_000.0).toString().trimEnd('0').trimEnd('.')
    }
}

private fun Int?.toRaceDistanceWireValue(): String? = when (this) {
    5_000 -> "5k"
    10_000 -> "10k"
    21_100 -> "half"
    42_200 -> "marathon"
    else -> null
}

private fun Int?.toRaceDistanceLabel(): String? = when (this) {
    5_000 -> "5K"
    10_000 -> "10K"
    21_100 -> "Half marathon"
    42_200 -> "Marathon"
    else -> null
}
