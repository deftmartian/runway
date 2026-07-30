package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.EstablishedTrainingIntake
import dev.deftmartian.runway.domain.Experience
import dev.deftmartian.runway.domain.GoalKind
import dev.deftmartian.runway.domain.GoalPriority
import dev.deftmartian.runway.domain.InjuryFlags
import dev.deftmartian.runway.domain.RaceDistance
import dev.deftmartian.runway.domain.StartMode
import dev.deftmartian.runway.domain.TrainingPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlanSetupPreparationTest {
    @Test
    fun `pending blocked goal is valid without a plan graph`() {
        val result = LocalPlanSetupPreparation.prepare(
            request(
                LocalPlanCandidate.Pending(
                    GoalEntity("pending-goal", "5K later", null, "pending", 10, 10, "race", "foundation_to_goal", 5_000, "finish_healthy"),
                ),
            ),
        )

        assertEquals(LocalPlanSetupPreparation.Ready, result)
    }

    @Test
    fun `pending goal cannot masquerade as an active plan setup`() {
        val result = LocalPlanSetupPreparation.prepare(
            request(
                LocalPlanCandidate.Pending(
                    GoalEntity("bad-pending", "5K later", null, "active", 10, 10, "race", "foundation_to_goal", 5_000, "finish_healthy"),
                ),
            ),
        )

        assertEquals(
            LocalPlanSetupPreparation.Invalid(LocalPlanSetupError.PENDING_GOAL_MUST_USE_PENDING_STATE),
            result,
        )
    }

    @Test
    fun `preparation rejects duplicate available days before a transaction can change profile state`() {
        val result = LocalPlanSetupPreparation.prepare(
            request(
                LocalPlanCandidate.Pending(pendingGoal()),
                availabilityDays = listOf(1, 1, 6),
            ),
        )

        assertEquals(LocalPlanSetupPreparation.Invalid(LocalPlanSetupError.INVALID_AVAILABILITY), result)
    }

    @Test
    fun `generated graph must keep every child attached to the supplied plan identity`() {
        val generated = TrainingPlanner.generatePlan(
            EstablishedTrainingIntake(
                priority = GoalPriority.FINISH_HEALTHY,
                experience = Experience.RETURNING,
                availability = listOf(1, 3, 6),
                injuryFlags = InjuryFlags(),
                raceDistance = RaceDistance.FIVE_K,
                targetDate = "2026-08-30",
                currentWeeklyDistanceMeters = 8_000,
                currentRunsPerWeek = 3,
                longestRecentRunMeters = 4_000,
                preferredLongRunDay = 6,
                startDate = "2026-06-01",
            ),
        )
        val graph = GeneratedPlanPersistenceMapper.map(
            generated,
            GeneratedPlanGoalMetadata(
                goalId = "goal-5k",
                planId = "plan-5k",
                title = "5K",
                goalKind = GoalKind.RACE,
                startMode = StartMode.ESTABLISHED,
                goalTargetDate = "2026-08-30",
                targetDistanceMeters = 5_000,
                createdAtEpochMillis = 10,
            ),
        )

        val result = LocalPlanSetupPreparation.prepare(
            request(LocalPlanCandidate.Generated(graph.copy(weeks = graph.weeks.map { it.copy(planId = "wrong-plan") }))),
        )

        assertEquals(LocalPlanSetupPreparation.Invalid(LocalPlanSetupError.INVALID_GENERATED_GRAPH), result)
    }

    private fun request(
        candidate: LocalPlanCandidate,
        availabilityDays: List<Int> = listOf(1, 3, 6),
    ) = LocalPlanSetupRequest(
        profile = profile(),
        availabilityDays = availabilityDays,
        candidate = candidate,
        confirmReplaceActive = false,
        archiveAtEpochMillis = 10,
    )

    private fun pendingGoal() = GoalEntity(
        goalId = "pending-goal",
        title = "5K later",
        targetDateEpochDay = null,
        state = "pending",
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 10,
        kind = "race",
        startMode = "foundation_to_goal",
        raceDistanceMeters = 5_000,
        priority = "finish_healthy",
    )

    private fun profile() = ProfileSettingsEntity(
        timeZone = "America/Halifax",
        routeDataMode = "private",
        heartRateSettingsSource = "none",
        maxHeartRateBpm = null,
        zone2FloorBpm = null,
        zone3FloorBpm = null,
        zone4FloorBpm = null,
        zone5FloorBpm = null,
        recentInjury = false,
        currentPain = false,
        recurringPain = false,
        medicalRestriction = false,
        privateNotes = null,
        updatedAtEpochMillis = 10,
    )
}
