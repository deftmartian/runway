package dev.deftmartian.runway.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DomainBoundaryTest {
    @Test fun `date utilities reject invalid calendar dates and preserve named-zone dates`() {
        try { DateUtils.parseIsoDate("2026-02-29"); throw AssertionError("expected invalid date") } catch (_: IllegalArgumentException) { }
        assertEquals("2026-05-11", DateUtils.weekStart(LocalDate.parse("2026-05-15")))
        assertEquals("2026-01-01", DateUtils.toIsoDate(Instant.parse("2025-12-31T13:00:00Z"), "Pacific/Auckland"))
        assertTrue(DateUtils.isValidTimeZone("America/Halifax"))
        assertFalse(DateUtils.isValidTimeZone("not/a-zone"))
    }

    @Test fun `assessment presentation describes arithmetic without medical claims`() {
        assertEquals(TrainingAssessmentAttention.BLOCKED, TrainingAssessments.presentRamp(RiskRating.UNSAFE).attention)
        assertEquals("10.1% needed each week · 7.5% runway default", TrainingAssessments.formatRampEvidence(10.05, 7.5))
        assertEquals("25.0% of weekly load; outside-default boundary 25%.", TrainingAssessments.formatLoadChangeEvidence(25.0, RiskRating.UNSAFE))
    }

    @Test fun `baseline excludes incomplete and invalid observations then controls transition`() {
        val baseline = PhaseTransitions.deriveBaseline(listOf(BaselineObservation(3000, 1200, true), BaselineObservation(-1, 300, true), BaselineObservation(5000, 1800, false), BaselineObservation(3500, null, true), BaselineObservation(0, 0, true)), 2.0)
        assertEquals(4, baseline.activityCount)
        assertEquals(6500, baseline.totalDistanceMeters)
        assertEquals(3250, baseline.weeklyDistanceMeters)
        assertEquals(2.0, baseline.runsPerWeek, 0.0)
        assertTrue(PhaseTransitions.canUseDistancePlannerBaseline(baseline))
        assertEquals(PhaseTransitionOption.CONFIRM_RACE_BASELINE, PhaseTransitions.options(PlanPhase.CALIBRATION, GoalKind.RACE, baseline, true).recommended)
    }

    @Test fun `onboarding bounds and validation use start-mode-specific dates`() {
        val today = LocalDate.parse("2026-01-01")
        assertEquals(TargetDateBounds("2026-02-26", "2026-12-30"), OnboardingValidation.targetDateBounds(today, StartMode.ESTABLISHED))
        assertEquals(TargetDateBounds("2026-04-30", "2026-12-30"), OnboardingValidation.targetDateBounds(today, StartMode.FOUNDATION_TO_GOAL))
        val selection = OnboardingSelection(GoalKind.RACE, StartMode.CALIBRATION, RaceDistance.FIVE_K, "2026-03-11", listOf(2, 6), "America/Halifax", InjuryFlags(), calibrationDurationMinutes = 20)
        assertTrue(OnboardingIssue.TARGET_DATE_OUT_OF_BOUNDS in OnboardingValidation.validate(selection, today))
        assertFalse(OnboardingIssue.TARGET_DATE_OUT_OF_BOUNDS in OnboardingValidation.validate(selection.copy(targetDate = "2026-03-12"), today))
    }
}
