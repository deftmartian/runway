package dev.deftmartian.runway

import dev.deftmartian.runway.domain.GeneratedCalibrationPlan
import dev.deftmartian.runway.domain.GeneratedDistancePlan
import dev.deftmartian.runway.domain.GeneratedFoundationPlan
import dev.deftmartian.runway.domain.StartMode
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneOnboardingAdapterTest {
    private val utcNow = Instant.parse("2026-01-01T12:00:00Z")

    @Test fun `established command becomes a typed distance intake and plan`() {
        val result = StandaloneOnboardingAdapter.adapt(command(startMode = "established", targetDate = "2026-04-01"), utcNow) as StandaloneOnboardingOutcome.Planned
        assertTrue(result.intake is dev.deftmartian.runway.domain.EstablishedTrainingIntake)
        assertTrue(result.plan is GeneratedDistancePlan)
        assertEquals("2026-01-05", result.plan.startDate)
        assertEquals("2026-02-26", result.metadata.targetBounds?.minimum)
    }

    @Test fun `foundation to goal keeps race target metadata while creating foundation phase`() {
        val result = StandaloneOnboardingAdapter.adapt(command(startMode = "foundation_to_goal", targetDate = "2026-05-01"), utcNow) as StandaloneOnboardingOutcome.Planned
        assertTrue(result.intake is dev.deftmartian.runway.domain.FoundationIntake)
        assertTrue(result.plan is GeneratedFoundationPlan)
        assertEquals("2026-05-01", result.metadata.targetDate)
        assertEquals("2026-04-30", result.metadata.targetBounds?.minimum)
        assertEquals("2026-03-08", result.plan.targetDate)
    }

    @Test fun `foundation only creates a foundation plan without race bounds`() {
        val result = StandaloneOnboardingAdapter.adapt(command(goalKind = "foundation", startMode = "foundation_only", raceDistance = "", targetDate = ""), utcNow) as StandaloneOnboardingOutcome.Planned
        assertEquals(StartMode.FOUNDATION_ONLY, result.metadata.startMode)
        assertEquals(null, result.metadata.targetBounds)
        assertTrue(result.plan is GeneratedFoundationPlan)
    }

    @Test fun `calibration command creates timed baseline`() {
        val result = StandaloneOnboardingAdapter.adapt(command(startMode = "calibration", targetDate = "2026-03-15", availability = listOf(2, 6)), utcNow) as StandaloneOnboardingOutcome.Planned
        assertTrue(result.plan is GeneratedCalibrationPlan)
        assertEquals(1200, (result.plan as GeneratedCalibrationPlan).summary.sessionDurationSeconds)
        assertEquals("2026-03-12", result.metadata.targetBounds?.minimum)
    }

    @Test fun `duplicate and unconfirmed concentrated schedules return typed errors`() {
        val duplicate = StandaloneOnboardingAdapter.adapt(command(startMode = "established", targetDate = "2026-04-01", availability = listOf(1, 1, 6)), utcNow) as StandaloneOnboardingOutcome.Invalid
        assertTrue(OnboardingFieldError.DUPLICATE_DAY in duplicate.fieldErrors.getValue(OnboardingField.AVAILABILITY))
        val concentrated = StandaloneOnboardingAdapter.adapt(command(startMode = "established", raceDistance = "half", targetDate = "2026-04-01", availability = listOf(2, 6), currentRunsPerWeek = "2"), utcNow) as StandaloneOnboardingOutcome.Invalid
        assertTrue(OnboardingFieldError.CONCENTRATED_SCHEDULE_CONFIRMATION in concentrated.fieldErrors.getValue(OnboardingField.CONCENTRATED_SCHEDULE))
    }

    @Test fun `pain or restriction returns pending goal instead of fabricating a phase`() {
        val result = StandaloneOnboardingAdapter.adapt(command(startMode = "established", targetDate = "2026-04-01", currentWeeklyDistanceKm = "", currentRunsPerWeek = "", longestRecentRunKm = "", currentPain = true, availability = listOf(2, 6)), utcNow)
        assertTrue(result is StandaloneOnboardingOutcome.PendingGoal)
        assertEquals("2026-04-01", (result as StandaloneOnboardingOutcome.PendingGoal).metadata.targetDate)
    }

    @Test fun `time zone determines date bounds and next monday start`() {
        val result = StandaloneOnboardingAdapter.adapt(command(startMode = "established", targetDate = "2026-03-01", timeZone = "America/Halifax"), Instant.parse("2026-01-01T00:30:00Z")) as StandaloneOnboardingOutcome.Planned
        assertEquals("2026-02-25", result.metadata.targetBounds?.minimum)
        assertEquals("2026-01-05", result.plan.startDate)
    }

    private fun command(
        goalKind: String = "race",
        startMode: String,
        raceDistance: String = "5k",
        targetDate: String,
        availability: List<Int> = listOf(1, 3, 6),
        currentWeeklyDistanceKm: String = "12",
        currentRunsPerWeek: String = "3",
        longestRecentRunKm: String = "8",
        timeZone: String = "UTC",
        currentPain: Boolean = false,
    ) = CreatePlanCommand(goalKind, startMode, raceDistance, targetDate, "finish_healthy", currentWeeklyDistanceKm, currentRunsPerWeek, longestRecentRunKm, "returning", "20", availability, "6", timeZone, false, currentPain, false, false, "", false, false)
}
