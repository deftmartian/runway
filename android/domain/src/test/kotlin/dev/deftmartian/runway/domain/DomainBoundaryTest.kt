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
        try {
            PhaseTransitions.options(PlanPhase.ROUTINE, GoalKind.ROUTINE, baseline, false)
            throw AssertionError("A routine must not expose a beginner phase transition.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `onboarding bounds and validation use start-mode-specific dates`() {
        val today = LocalDate.parse("2026-01-01")
        assertEquals(TargetDateBounds("2026-02-26", "2026-12-30"), OnboardingValidation.targetDateBounds(today, StartMode.ESTABLISHED))
        assertEquals(TargetDateBounds("2026-04-30", "2026-12-30"), OnboardingValidation.targetDateBounds(today, StartMode.FOUNDATION_TO_GOAL))
        val selection = OnboardingSelection(GoalKind.RACE, StartMode.CALIBRATION, RaceDistance.FIVE_K, "2026-03-11", listOf(2, 6), "America/Halifax", InjuryFlags(), calibrationDurationMinutes = 20)
        assertTrue(OnboardingIssue.TARGET_DATE_OUT_OF_BOUNDS in OnboardingValidation.validate(selection, today))
        assertFalse(OnboardingIssue.TARGET_DATE_OUT_OF_BOUNDS in OnboardingValidation.validate(selection.copy(targetDate = "2026-03-12"), today))
    }

    @Test fun `established baseline must describe one repeatable week`() {
        assertTrue(isRepeatableWeekCoherent(10.0, 3, 5.0))
        assertTrue(isRepeatableWeekCoherent(6.0, 3, 5.0))
        assertTrue(isRepeatableWeekCoherent(15.0, 3, 5.0))
        assertFalse(isRepeatableWeekCoherent(5.5, 3, 5.0))
        assertFalse(isRepeatableWeekCoherent(15.1, 3, 5.0))

        val today = LocalDate.parse("2026-01-01")
        val established = OnboardingSelection(
            goalKind = GoalKind.RACE,
            startMode = StartMode.ESTABLISHED,
            raceDistance = RaceDistance.FIVE_K,
            targetDate = "2026-03-12",
            availability = listOf(1, 3, 6),
            timeZone = "America/Halifax",
            injuryFlags = InjuryFlags(),
            currentWeeklyDistanceKm = 10.0,
            currentRunsPerWeek = 3,
            longestRecentRunKm = 5.0,
            preferredLongRunDay = 6,
        )
        assertFalse(OnboardingIssue.INVALID_ESTABLISHED_BASELINE in OnboardingValidation.validate(established, today))
        assertTrue(OnboardingIssue.INVALID_ESTABLISHED_BASELINE in OnboardingValidation.validate(established.copy(currentWeeklyDistanceKm = 5.5), today))
        assertTrue(OnboardingIssue.INVALID_ESTABLISHED_BASELINE in OnboardingValidation.validate(established.copy(currentWeeklyDistanceKm = 15.1), today))
        assertTrue(
            OnboardingIssue.INSUFFICIENT_RECOVERY_SPACING in OnboardingValidation.validate(
                established.copy(availability = listOf(0, 1, 6)),
                today,
            ),
        )
        assertTrue(canLeaveRecoveryDayAfterLongRun(listOf(0, 1, 3, 5, 6), 5, 6, RaceDistance.HALF))
    }

    @Test fun `weekly routine needs only one to seven unique weekdays`() {
        val today = LocalDate.parse("2026-01-01")
        val routine = OnboardingSelection(
            goalKind = GoalKind.ROUTINE,
            startMode = StartMode.ROUTINE,
            raceDistance = null,
            targetDate = null,
            availability = listOf(1),
            timeZone = "America/Halifax",
            injuryFlags = InjuryFlags(),
        )

        assertTrue(OnboardingValidation.validate(routine, today).isEmpty())
        assertTrue(OnboardingValidation.validate(routine.copy(availability = (0..6).toList()), today).isEmpty())
        assertEquals(
            setOf(OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS),
            OnboardingValidation.validate(routine.copy(availability = emptyList()), today),
        )
        assertEquals(
            setOf(OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS),
            OnboardingValidation.validate(routine.copy(availability = listOf(1, 1)), today),
        )
        assertEquals(
            setOf(OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS),
            OnboardingValidation.validate(routine.copy(availability = listOf(7)), today),
        )
        assertEquals(
            setOf(OnboardingIssue.HEALTH_BLOCKS_SCHEDULING),
            OnboardingValidation.validate(routine.copy(injuryFlags = InjuryFlags(currentPain = true)), today),
        )
        assertEquals(
            setOf(OnboardingIssue.MISSING_START_MODE, OnboardingIssue.INSUFFICIENT_AVAILABLE_DAYS),
            OnboardingValidation.validate(routine.copy(startMode = null), today),
        )
    }
}
