package dev.deftmartian.runway

import dev.deftmartian.runway.domain.GeneratedCalibrationPlan
import dev.deftmartian.runway.domain.GeneratedDistancePlan
import dev.deftmartian.runway.domain.GeneratedFoundationPlan
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.data.LocalPlanCandidate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test fun `established baseline errors identify each missing field`() {
        val result = StandaloneOnboardingAdapter.adapt(
            command(
                startMode = "established",
                targetDate = "2026-04-01",
                currentWeeklyDistanceKm = "",
                currentRunsPerWeek = "",
                longestRecentRunKm = "",
            ),
            utcNow,
        ) as StandaloneOnboardingOutcome.Invalid

        assertTrue(OnboardingFieldError.REQUIRED in result.fieldErrors.getValue(OnboardingField.WEEKLY_DISTANCE))
        assertTrue(OnboardingFieldError.REQUIRED in result.fieldErrors.getValue(OnboardingField.RUNS_PER_WEEK))
        assertTrue(OnboardingFieldError.REQUIRED in result.fieldErrors.getValue(OnboardingField.LONGEST_RUN))
    }

    @Test fun `foundation ignores established-only stale baseline fields`() {
        val result = StandaloneOnboardingAdapter.adapt(
            command(
                goalKind = "foundation",
                startMode = "foundation_only",
                raceDistance = "",
                targetDate = "",
                currentWeeklyDistanceKm = "not-a-number",
                currentRunsPerWeek = "three",
                longestRecentRunKm = "none",
            ),
            utcNow,
        )

        assertTrue(result is StandaloneOnboardingOutcome.Planned)
    }

    @Test fun `persistence boundary maps all four start paths with deterministic identities`() {
        val cases = listOf(
            command(startMode = "established", targetDate = "2026-04-01") to "distance",
            command(startMode = "foundation_to_goal", targetDate = "2026-05-01") to "foundation",
            command(goalKind = "foundation", startMode = "foundation_only", raceDistance = "", targetDate = "") to "foundation",
            command(startMode = "calibration", targetDate = "2026-03-15", availability = listOf(2, 6)) to "calibration",
        )
        cases.forEachIndexed { index, (command, phase) ->
            val outcome = StandaloneOnboardingAdapter.adapt(command, utcNow)
            val request = StandaloneOnboardingPersistenceMapper.map(command, outcome, "setup-$index", 1234)
            val generated = request.candidate as LocalPlanCandidate.Generated

            assertEquals(phase, generated.graph.plan.phaseType)
            assertEquals("active", generated.graph.goal.state)
            assertEquals(command.confirmReplace, request.confirmReplaceCurrent)
            assertEquals(command.availability, request.availabilityDays)
            assertEquals(1234, request.archiveAtEpochMillis)
            assertEquals(request, StandaloneOnboardingPersistenceMapper.map(command, outcome, "setup-$index", 1234))
        }
    }

    @Test fun `persistence boundary retains health-blocked goal without inventing a graph`() {
        val command = command(
            startMode = "established",
            targetDate = "2026-04-01",
            currentWeeklyDistanceKm = "",
            currentRunsPerWeek = "",
            longestRecentRunKm = "",
            currentPain = true,
        ).copy(confirmReplace = true)
        val outcome = StandaloneOnboardingAdapter.adapt(command, utcNow)
        val request = StandaloneOnboardingPersistenceMapper.map(command, outcome, "blocked-operation", 5678)
        val pending = request.candidate as LocalPlanCandidate.Pending

        assertEquals("pending", pending.goal.state)
        assertEquals("2026-04-01", java.time.LocalDate.ofEpochDay(requireNotNull(pending.goal.targetDateEpochDay)).toString())
        assertTrue(request.profile.currentPain)
        assertNull(request.profile.baselineDistanceMeters)
        assertFalse(request.profile.baselineConfirmed)
        assertTrue(request.confirmReplaceCurrent)
    }

    @Test fun `persistence profile keeps established baseline and operation ids differ`() {
        val command = command(startMode = "established", targetDate = "2026-04-01")
        val outcome = StandaloneOnboardingAdapter.adapt(command, utcNow)
        val first = StandaloneOnboardingPersistenceMapper.map(command, outcome, "one", 999)
        val second = StandaloneOnboardingPersistenceMapper.map(command, outcome, "two", 999)
        val firstGraph = (first.candidate as LocalPlanCandidate.Generated).graph
        val secondGraph = (second.candidate as LocalPlanCandidate.Generated).graph

        assertEquals(12_000, first.profile.baselineDistanceMeters)
        assertEquals(3, first.profile.currentRunsPerWeek)
        assertEquals(8_000, first.profile.longestRecentRunMeters)
        assertTrue(first.profile.baselineConfirmed)
        assertEquals(6, first.profile.preferredLongRunDay)
        assertEquals("discard", first.profile.routeDataMode)
        assertEquals("discard", first.profile.heartRateDataMode)
        assertEquals("not_specified", first.profile.experienceLevel)
        assertNotEquals(firstGraph.goal.goalId, secondGraph.goal.goalId)
        assertNotEquals(firstGraph.plan.planId, secondGraph.plan.planId)
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
    ) = CreatePlanCommand(goalKind, startMode, raceDistance, targetDate, "finish_healthy", currentWeeklyDistanceKm, currentRunsPerWeek, longestRecentRunKm, "20", availability, "6", timeZone, false, currentPain, false, false, "", false, false)
}
