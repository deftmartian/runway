package dev.deftmartian.runway

import dev.deftmartian.runway.domain.GeneratedCalibrationPlan
import dev.deftmartian.runway.domain.GeneratedDistancePlan
import dev.deftmartian.runway.domain.RiskRating
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.data.LocalPlanCandidate
import java.time.Instant
import org.junit.Assert.assertEquals
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

    @Test fun `high increase plan is previewed and requires explicit confirmation`() {
        val command = command(
            startMode = "established",
            raceDistance = "half",
            targetDate = "2026-03-29",
            currentWeeklyDistanceKm = "20",
            currentRunsPerWeek = "3",
            longestRecentRunKm = "10",
        )

        val preview = StandaloneOnboardingAdapter.preview(command, utcNow)
            as StandaloneOnboardingOutcome.Planned
        val previewPlan = preview.plan as GeneratedDistancePlan
        assertEquals(RiskRating.AGGRESSIVE, previewPlan.risk)

        val unconfirmed = StandaloneOnboardingAdapter.adapt(command, utcNow)
            as StandaloneOnboardingOutcome.Invalid
        assertTrue(
            OnboardingFieldError.HIGH_INCREASE_CONFIRMATION in
                unconfirmed.fieldErrors.getValue(OnboardingField.PLAN_ASSESSMENT),
        )

        val confirmed = StandaloneOnboardingAdapter.adapt(
            command.copy(
                confirmedPlanKey = generatedPlanConfirmationKey(
                    previewPlan,
                ),
            ),
            utcNow,
        ) as StandaloneOnboardingOutcome.Planned
        assertEquals(preview.plan, confirmed.plan)

        val movedSchedule = StandaloneOnboardingAdapter.adapt(
            command.copy(
                availability = listOf(1, 4, 6),
                confirmedPlanKey = generatedPlanConfirmationKey(previewPlan),
            ),
            utcNow,
        ) as StandaloneOnboardingOutcome.Invalid
        assertTrue(
            OnboardingFieldError.HIGH_INCREASE_CONFIRMATION in
                movedSchedule.fieldErrors.getValue(OnboardingField.PLAN_ASSESSMENT),
        )
    }

    @Test fun `unsupported plan can be previewed but cannot be created`() {
        val command = command(
            startMode = "established",
            raceDistance = "half",
            targetDate = "2026-02-26",
            currentWeeklyDistanceKm = "15",
            currentRunsPerWeek = "3",
            longestRecentRunKm = "10",
        )

        val preview = StandaloneOnboardingAdapter.preview(command, utcNow)
            as StandaloneOnboardingOutcome.Planned
        assertEquals(RiskRating.UNSAFE, preview.plan.risk)

        val rejected = StandaloneOnboardingAdapter.adapt(command, utcNow)
            as StandaloneOnboardingOutcome.Invalid
        assertTrue(
            OnboardingFieldError.UNSUPPORTED_PLAN in
                rejected.fieldErrors.getValue(OnboardingField.PLAN_ASSESSMENT),
        )
    }

    @Test fun `weekly routine starts a full week with a distance goal`() {
        val result = StandaloneOnboardingAdapter.adapt(
            command(
                goalKind = "routine",
                startMode = "routine",
                raceDistance = "stale-race-value",
                targetDate = "not-a-date",
                availability = listOf(1, 3, 6),
                currentWeeklyDistanceKm = "12",
                currentRunsPerWeek = "3",
                longestRecentRunKm = "none",
            ),
            utcNow,
        ) as StandaloneOnboardingOutcome.Routine

        assertEquals(StartMode.ROUTINE, result.metadata.startMode)
        assertEquals(null, result.metadata.targetBounds)
        assertEquals(null, result.metadata.targetDate)
        assertEquals(null, result.metadata.raceDistance)
        assertEquals("2026-01-05", result.startDate)
        assertEquals(listOf(1, 3, 6), result.selectedDays)
        assertEquals(15_000, result.weeklyTargetDistanceMeters)
        assertEquals(12_000, result.startingWeeklyDistanceMeters)
        assertEquals(1_000, result.weeklyChangeDistanceMeters)
        assertNull(result.weeklyTargetDurationSeconds)
    }

    @Test fun `calibration command creates open baseline runs`() {
        val result = StandaloneOnboardingAdapter.adapt(command(startMode = "calibration", targetDate = "2026-03-15", availability = listOf(2, 6)), utcNow) as StandaloneOnboardingOutcome.Planned
        assertTrue(result.plan is GeneratedCalibrationPlan)
        assertNull((result.plan as GeneratedCalibrationPlan).summary.sessionDurationSeconds)
        assertEquals("2026-03-12", result.metadata.targetBounds?.minimum)
    }

    @Test fun `duplicate and unconfirmed concentrated schedules return typed errors`() {
        val duplicate = StandaloneOnboardingAdapter.adapt(command(startMode = "established", targetDate = "2026-04-01", availability = listOf(1, 1, 6)), utcNow) as StandaloneOnboardingOutcome.Invalid
        assertTrue(OnboardingFieldError.DUPLICATE_DAY in duplicate.fieldErrors.getValue(OnboardingField.AVAILABILITY))
        val concentrated = StandaloneOnboardingAdapter.adapt(command(startMode = "established", raceDistance = "half", targetDate = "2026-04-01", availability = listOf(2, 6), currentRunsPerWeek = "2"), utcNow) as StandaloneOnboardingOutcome.Invalid
        assertTrue(OnboardingFieldError.CONCENTRATED_SCHEDULE_CONFIRMATION in concentrated.fieldErrors.getValue(OnboardingField.CONCENTRATED_SCHEDULE))
    }

    @Test fun `established schedule preserves a recovery day after the long run`() {
        val result = StandaloneOnboardingAdapter.adapt(
            command(
                startMode = "established",
                targetDate = "2026-04-01",
                availability = listOf(0, 1, 6),
            ),
            utcNow,
        ) as StandaloneOnboardingOutcome.Invalid

        assertTrue(
            OnboardingFieldError.RECOVERY_SPACING in
                result.fieldErrors.getValue(OnboardingField.AVAILABILITY),
        )
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

    @Test fun `established baseline must describe one repeatable week`() {
        val noRoomForOtherRuns = StandaloneOnboardingAdapter.adapt(
            command(
                startMode = "established",
                targetDate = "2026-04-01",
                currentWeeklyDistanceKm = "10",
                currentRunsPerWeek = "3",
                longestRecentRunKm = "10",
            ),
            utcNow,
        ) as StandaloneOnboardingOutcome.Invalid
        assertTrue(
            OnboardingFieldError.INCONSISTENT_BASELINE in
                noRoomForOtherRuns.fieldErrors.getValue(OnboardingField.WEEKLY_DISTANCE),
        )
        assertTrue(
            OnboardingFieldError.INCONSISTENT_BASELINE in
                noRoomForOtherRuns.fieldErrors.getValue(OnboardingField.LONGEST_RUN),
        )

        val longestBelowAverage = StandaloneOnboardingAdapter.adapt(
            command(
                startMode = "established",
                targetDate = "2026-04-01",
                currentWeeklyDistanceKm = "30",
                currentRunsPerWeek = "3",
                longestRecentRunKm = "8",
            ),
            utcNow,
        ) as StandaloneOnboardingOutcome.Invalid
        assertTrue(
            OnboardingFieldError.INCONSISTENT_BASELINE in
                longestBelowAverage.fieldErrors.getValue(OnboardingField.LONGEST_RUN),
        )
    }

    @Test fun `persistence boundary maps both distance start paths with deterministic identities`() {
        val cases = listOf(
            command(startMode = "established", targetDate = "2026-04-01") to "distance",
            command(startMode = "calibration", targetDate = "2026-03-15", availability = listOf(2, 6)) to "calibration",
        )
        cases.forEachIndexed { index, (command, phase) ->
            val operation = command.copy(
                operationId = "setup-$index",
                occurredAtEpochMillis = 1234,
            )
            val outcome = StandaloneOnboardingAdapter.adapt(operation, utcNow)
            val request = StandaloneOnboardingPersistenceMapper.map(operation, outcome)
            val generated = request.candidate as LocalPlanCandidate.Generated

            assertEquals(phase, generated.graph.plan.phaseType)
            assertEquals("active", generated.graph.goal.state)
            assertEquals(operation.confirmReplace, request.confirmReplaceCurrent)
            assertEquals(operation.availability, request.availabilityDays)
            assertEquals(1234, request.archiveAtEpochMillis)
            assertEquals(request, StandaloneOnboardingPersistenceMapper.map(operation, outcome))
        }
    }

    @Test fun `persistence boundary divides the weekly distance across selected runs`() {
        val operation = command(
            goalKind = "routine",
            startMode = "routine",
            raceDistance = "",
            targetDate = "",
            availability = listOf(1, 3, 6),
        ).copy(operationId = "routine-setup", occurredAtEpochMillis = 4_321)
        val outcome = StandaloneOnboardingAdapter.adapt(operation, utcNow)

        val first = StandaloneOnboardingPersistenceMapper.map(operation, outcome)
        val second = StandaloneOnboardingPersistenceMapper.map(operation, outcome)
        val graph = (first.candidate as LocalPlanCandidate.Routine).graph

        assertEquals("routine", graph.goal.kind)
        assertEquals("routine", graph.plan.phaseType)
        assertNull(graph.goal.targetDateEpochDay)
        assertNull(graph.plan.endEpochDay)
        assertEquals(3, graph.days.size)
        assertEquals(12_000, graph.plan.summaryBaselineMeters)
        assertEquals(
            listOf(12_000, 13_000, 14_000, 15_000),
            graph.weeks.take(4).map { it.generatedLoadMeters },
        )
        assertTrue(graph.workouts.all {
            it.currentPrescriptionKind == "distance" &&
                it.currentDistanceMeters?.rem(500) == 0 &&
                it.currentDurationSeconds == null
        })
        assertEquals(12_000, first.profile.baselineDistanceMeters)
        assertTrue(first.profile.baselineConfirmed)
        assertEquals(first, second)
    }

    @Test fun `weekly routine accepts time and prescribes timed runs`() {
        val operation = command(
            goalKind = "routine",
            startMode = "routine",
            raceDistance = "",
            targetDate = "",
            availability = listOf(1, 3, 6),
        ).copy(
            routineGoalMetric = "time",
            routineWeeklyGoalKm = "",
            routineWeeklyGoalMinutes = "120",
            currentWeeklyMinutes = "120",
        )

        val outcome = StandaloneOnboardingAdapter.adapt(operation, utcNow)
            as StandaloneOnboardingOutcome.Routine
        val graph = (
            StandaloneOnboardingPersistenceMapper.map(operation, outcome).candidate
                as LocalPlanCandidate.Routine
            ).graph

        assertNull(outcome.weeklyTargetDistanceMeters)
        assertEquals(7_200, outcome.weeklyTargetDurationSeconds)
        assertTrue(graph.weeks.all { it.generatedLoadDurationSeconds == 7_200 })
        assertTrue(graph.workouts.all {
            it.currentPrescriptionKind == "timed" &&
                it.currentDistanceMeters == null &&
                it.currentDurationSeconds == 2_400
        })
    }

    @Test fun `weekly routine requires a bounded distance goal`() {
        val missing = StandaloneOnboardingAdapter.adapt(
            command(
                goalKind = "routine",
                startMode = "routine",
                raceDistance = "",
                targetDate = "",
            ).copy(routineWeeklyGoalKm = ""),
            utcNow,
        ) as StandaloneOnboardingOutcome.Invalid
        assertTrue(
            OnboardingFieldError.REQUIRED in
                missing.fieldErrors.getValue(OnboardingField.ROUTINE_WEEKLY_GOAL),
        )
        val tooLarge = StandaloneOnboardingAdapter.adapt(
            command(
                goalKind = "routine",
                startMode = "routine",
                raceDistance = "",
                targetDate = "",
            ).copy(routineWeeklyGoalKm = "251"),
            utcNow,
        ) as StandaloneOnboardingOutcome.Invalid
        assertTrue(
            OnboardingFieldError.OUT_OF_RANGE in
                tooLarge.fieldErrors.getValue(OnboardingField.ROUTINE_WEEKLY_GOAL),
        )
    }

    @Test fun `weekly routine turns an entered baseline into a visible ramp`() {
        val operation = command(
            goalKind = "routine",
            startMode = "routine",
            raceDistance = "",
            targetDate = "",
            availability = listOf(1, 3, 6),
        ).copy(
            operationId = "routine-with-baseline",
        )
        val outcome = StandaloneOnboardingAdapter.adapt(operation, utcNow)
        val request = StandaloneOnboardingPersistenceMapper.map(operation, outcome)
        val graph = (request.candidate as LocalPlanCandidate.Routine).graph

        assertEquals(12_000, request.profile.baselineDistanceMeters)
        assertEquals(3, request.profile.currentRunsPerWeek)
        assertNull(request.profile.longestRecentRunMeters)
        assertTrue(request.profile.baselineConfirmed)
        assertTrue(graph.workouts.all { it.currentPrescriptionKind == "distance" })
        assertEquals(15_000, graph.plan.routineTargetDistanceMeters)
        assertEquals(1_000, graph.plan.routineWeeklyChangeDistanceMeters)
        assertEquals(
            listOf(12_000, 13_000, 14_000, 15_000, 15_000),
            graph.weeks.take(5).map { it.generatedLoadMeters },
        )
        graph.weeks.forEach { week ->
            assertEquals(
                week.generatedLoadMeters,
                graph.workouts.filter { it.weekId == week.weekId }.sumOf { it.currentDistanceMeters ?: 0 },
            )
        }
    }

    @Test fun `weekly time routine ramps from current capability to the final goal`() {
        val operation = command(
            goalKind = "routine",
            startMode = "routine",
            raceDistance = "",
            targetDate = "",
            availability = listOf(1, 3, 6),
        ).copy(
            baselineMode = "entered",
            routineGoalMetric = "time",
            routineWeeklyGoalKm = "",
            routineWeeklyGoalMinutes = "120",
            currentWeeklyMinutes = "90",
            routineWeeklyChangeMinutes = "15",
        )

        val outcome = StandaloneOnboardingAdapter.adapt(operation, utcNow)
        val request = StandaloneOnboardingPersistenceMapper.map(operation, outcome)
        val graph = (request.candidate as LocalPlanCandidate.Routine).graph

        assertNull(request.profile.baselineDistanceMeters)
        assertEquals(5_400, request.profile.baselineDurationSeconds)
        assertEquals(7_200, graph.plan.routineTargetDurationSeconds)
        assertEquals(900, graph.plan.routineWeeklyChangeDurationSeconds)
        assertEquals(
            listOf(5_400, 6_300, 7_200, 7_200),
            graph.weeks.take(4).map { it.generatedLoadDurationSeconds },
        )
    }

    @Test fun `removed foundation inputs are rejected`() {
        assertTrue(
            StandaloneOnboardingAdapter.adapt(
                command(
                    goalKind = "foundation",
                    startMode = "foundation_only",
                    raceDistance = "",
                    targetDate = "",
                ),
                utcNow,
            ) is StandaloneOnboardingOutcome.Invalid,
        )
    }

    @Test fun `persistence profile keeps established baseline and operation ids differ`() {
        val command = command(startMode = "established", targetDate = "2026-04-01")
        val outcome = StandaloneOnboardingAdapter.adapt(command, utcNow)
        val first = StandaloneOnboardingPersistenceMapper.map(
            command.copy(operationId = "one", occurredAtEpochMillis = 999),
            outcome,
        )
        val second = StandaloneOnboardingPersistenceMapper.map(
            command.copy(operationId = "two", occurredAtEpochMillis = 999),
            outcome,
        )
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

    @Test fun `setup fingerprint binds form semantics but excludes confirmation and attempt time`() {
        val command = command(startMode = "established", targetDate = "2026-04-01")
        val outcome = StandaloneOnboardingAdapter.adapt(command, utcNow)
        val initial = StandaloneOnboardingPersistenceMapper.map(command, outcome)
        val confirmed = command.copy(
            confirmReplace = true,
            occurredAtEpochMillis = command.occurredAtEpochMillis + 1_000,
        )
        val equivalentNumber = command.copy(currentWeeklyDistanceKm = "12.0")
        val edited = command.copy(currentWeeklyDistanceKm = "12.5")

        assertEquals(
            initial.operationFingerprint,
            StandaloneOnboardingPersistenceMapper.map(
                confirmed,
                StandaloneOnboardingAdapter.adapt(confirmed, utcNow),
            ).operationFingerprint,
        )
        assertEquals(
            initial.operationFingerprint,
            StandaloneOnboardingPersistenceMapper.map(
                equivalentNumber,
                StandaloneOnboardingAdapter.adapt(equivalentNumber, utcNow),
            ).operationFingerprint,
        )
        assertNotEquals(
            initial.operationFingerprint,
            StandaloneOnboardingPersistenceMapper.map(
                edited,
                StandaloneOnboardingAdapter.adapt(edited, utcNow),
            ).operationFingerprint,
        )
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
        confirmedPlanKey: String? = null,
        routineWeeklyGoalKm: String = "15",
        routineWeeklyChangeKm: String = "1",
    ) = CreatePlanCommand(
        goalKind = goalKind,
        startMode = startMode,
        raceDistance = raceDistance,
        targetDate = targetDate,
        priority = "finish_healthy",
        currentWeeklyDistanceKm = currentWeeklyDistanceKm,
        currentRunsPerWeek = currentRunsPerWeek,
        longestRecentRunKm = longestRecentRunKm,
        availability = availability,
        preferredLongRunDay = "6",
        timeZone = timeZone,
        confirmConcentratedSchedule = false,
        confirmedPlanKey = confirmedPlanKey,
        confirmReplace = false,
        operationId = "test-setup-operation",
        occurredAtEpochMillis = 1234,
        routineWeeklyGoalKm = routineWeeklyGoalKm,
        routineWeeklyChangeKm = routineWeeklyChangeKm,
    )
}
