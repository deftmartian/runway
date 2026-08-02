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
        assertEquals("2026-11-26", payload.minimumFoundationTargetDate)
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
                recentInjury = true,
                currentPain = false,
                recurringPain = false,
                medicalRestriction = false,
                privateNotes = "private",
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
                calibrationDurationSeconds = 1_200,
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
    fun `pending health-blocked goal remains visible and prepopulates explicit replacement`() {
        val payload = LocalSettingsReadModel(
            profile = profile(currentPain = false),
            activePlan = null,
            pendingGoal = LocalPendingGoalReadModel(
                goalId = "pending-goal",
                title = "5K later",
                goalKind = "race",
                startMode = "foundation_to_goal",
                raceDistanceMeters = 5_000,
                targetEpochDay = LocalDate.parse("2026-11-01").toEpochDay(),
                priority = "finish_healthy",
            ),
            about = LocalAboutReadModel(versionName = null, buildRevision = null),
        ).toNativeOnboardingPayload(Instant.parse("2026-07-30T12:00:00Z"))

        assertEquals("pending", payload.currentGoal?.state)
        assertEquals("pending-goal", payload.currentGoal?.id)
        assertEquals("foundation_to_goal", payload.initialValues?.startMode)
        assertEquals("5k", payload.initialValues?.raceDistance)
        assertEquals("2026-11-01", payload.initialValues?.targetDate)
    }

    @Test
    fun `released race distance values remain visible and editable`() {
        val payload = LocalSettingsReadModel(
            profile = profile(currentPain = false),
            activePlan = null,
            pendingGoal = LocalPendingGoalReadModel(
                goalId = "pending-goal",
                title = "Marathon later",
                goalKind = "race",
                startMode = "foundation_to_goal",
                raceDistanceMeters = 42_195,
                targetEpochDay = LocalDate.parse("2026-11-01").toEpochDay(),
                priority = "finish_healthy",
            ),
            about = LocalAboutReadModel(versionName = null, buildRevision = null),
        ).toNativeOnboardingPayload(Instant.parse("2026-07-30T12:00:00Z"))

        assertEquals("Marathon", payload.currentGoal?.distance)
        assertEquals("marathon", payload.initialValues?.raceDistance)
    }

    private fun profile(currentPain: Boolean) = LocalProfileReadModel(
        timeZone = "America/Halifax",
        routeDataMode = "discard",
        heartRateDataMode = "discard",
        availabilityDays = listOf(1, 3, 6),
        recentInjury = false,
        currentPain = currentPain,
        recurringPain = false,
        medicalRestriction = false,
        privateNotes = null,
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
        calibrationDurationSeconds = null,
        preferredLongRunDay = null,
    )
}
