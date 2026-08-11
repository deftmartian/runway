package dev.deftmartian.runway

import dev.deftmartian.runway.data.LocalAboutReadModel
import dev.deftmartian.runway.data.LocalActivePlanReadModel
import dev.deftmartian.runway.data.LocalPlanPhase
import dev.deftmartian.runway.data.LocalPlanState
import dev.deftmartian.runway.data.LocalPendingGoalReadModel
import dev.deftmartian.runway.data.LocalProfileReadModel
import dev.deftmartian.runway.data.LocalSettingsReadModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LocalOnboardingPresenterTest {
    @Test
    fun `fresh setup uses phone zone for truthful bounds without inventing profile values`() {
        val payload = LocalSettingsReadModel(
            profile = null,
            activePlan = null,
            about = LocalAboutReadModel(versionName = null, buildRevision = null),
        ).toNativeOnboardingPayload(
            now = Instant.parse("2026-07-30T12:00:00Z"),
            fallbackZone = ZoneId.of("America/Halifax"),
        )

        assertNull(payload.initialValues)
        assertNull(payload.currentGoal)
        assertEquals("2026-09-24", payload.minimumTargetDate)
        assertEquals("2026-10-08", payload.minimumCalibrationTargetDate)
        assertEquals("2027-07-28", payload.maximumTargetDate)
    }

    @Test
    fun `replacement setup restores local profile and identifies the active goal`() {
        val target = LocalDate.parse("2026-11-01").toEpochDay()
        val payload = LocalSettingsReadModel(
            profile = LocalProfileReadModel(
                timeZone = "America/Halifax",
                routeDataMode = "private",
                heartRateDataMode = "discard",
                availabilityDays = listOf(1, 3, 6),
                heartRateSettingsSource = "none",
                sexForEstimates = "not_specified",
                ageYears = null,
                maxHeartRateBpm = null,
                zone2FloorBpm = null,
                zone3FloorBpm = null,
                zone4FloorBpm = null,
                zone5FloorBpm = null,
                baselineDistanceMeters = 12_500,
                baselineDurationSeconds = 4_200,
                currentRunsPerWeek = 3,
                longestRecentRunMeters = 6_000,
                preferredLongRunDay = 6,
            ),
            activePlan = LocalActivePlanReadModel(
                planId = "plan",
                goalId = "goal",
                goalTitle = "Autumn 10K",
                goalKind = "race",
                startMode = "established",
                raceDistanceMeters = 10_000,
                goalTargetEpochDay = target,
                goalPriority = "finish_healthy",
                phase = LocalPlanPhase.DISTANCE,
                state = LocalPlanState.ACTIVE,
                startEpochDay = LocalDate.parse("2026-08-01").toEpochDay(),
                endEpochDay = target,
                riskAssessment = "conservative",
                latestLifecycleEvent = null,
            ),
            about = LocalAboutReadModel(versionName = null, buildRevision = null),
        ).toNativeOnboardingPayload(Instant.parse("2026-07-30T12:00:00Z"))

        assertEquals("12.5", payload.initialValues?.currentWeeklyDistanceKm)
        assertEquals("3", payload.initialValues?.currentRunsPerWeek)
        assertEquals("10k", payload.initialValues?.raceDistance)
        assertEquals("goal", payload.currentGoal?.id)
        assertEquals("Autumn 10K", payload.currentGoal?.title)
        assertEquals("2026-11-01", payload.currentGoal?.targetDate)
    }

    @Test
    fun `released race distance values remain visible and editable`() {
        val payload = LocalSettingsReadModel(
            profile = profile(),
            activePlan = null,
            pendingGoal = LocalPendingGoalReadModel(
                goalId = "pending-goal",
                title = "Marathon later",
                goalKind = "race",
                startMode = "calibration",
                raceDistanceMeters = 42_195,
                targetEpochDay = LocalDate.parse("2026-11-01").toEpochDay(),
                priority = "finish_healthy",
            ),
            about = LocalAboutReadModel(versionName = null, buildRevision = null),
        ).toNativeOnboardingPayload(Instant.parse("2026-07-30T12:00:00Z"))

        assertEquals("Marathon", payload.currentGoal?.distance)
        assertEquals("marathon", payload.initialValues?.raceDistance)
    }

    @Test
    fun `routine adjustment restores its start endpoint and weekly change`() {
        val payload = LocalSettingsReadModel(
            profile = profile().copy(
                availabilityDays = listOf(1, 3, 6),
                baselineDistanceMeters = 12_000,
                currentRunsPerWeek = 3,
            ),
            activePlan = LocalActivePlanReadModel(
                planId = "routine-plan",
                goalId = "routine-goal",
                goalTitle = "Weekly distance routine",
                goalKind = "routine",
                startMode = "routine",
                raceDistanceMeters = null,
                goalTargetEpochDay = null,
                goalPriority = "consistency",
                phase = LocalPlanPhase.ROUTINE,
                state = LocalPlanState.ACTIVE,
                startEpochDay = LocalDate.parse("2026-07-27").toEpochDay(),
                endEpochDay = null,
                riskAssessment = null,
                latestLifecycleEvent = null,
                sessionsPerWeek = 3,
                routineWeeklyTargetMeters = 15_000,
                routineWeeklyChangeMeters = 1_000,
            ),
            about = LocalAboutReadModel(versionName = null, buildRevision = null),
        ).toNativeOnboardingPayload(Instant.parse("2026-08-11T12:00:00Z"))

        val initial = requireNotNull(payload.initialValues)
        assertEquals("routine", initial.goalKind)
        assertEquals("12", initial.currentWeeklyDistanceKm)
        assertEquals("15", initial.routineWeeklyGoalKm)
        assertEquals("1", initial.routineWeeklyChangeKm)
        assertEquals(listOf(1, 3, 6), initial.availability)
    }

    private fun profile() = LocalProfileReadModel(
        timeZone = "America/Halifax",
        routeDataMode = "discard",
        heartRateDataMode = "discard",
        availabilityDays = listOf(1, 3, 6),
        heartRateSettingsSource = "none",
        sexForEstimates = "not_specified",
        ageYears = null,
        maxHeartRateBpm = null,
        zone2FloorBpm = null,
        zone3FloorBpm = null,
        zone4FloorBpm = null,
        zone5FloorBpm = null,
        baselineDistanceMeters = null,
        baselineDurationSeconds = null,
        currentRunsPerWeek = null,
        longestRecentRunMeters = null,
        preferredLongRunDay = null,
    )
}
