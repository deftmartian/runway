package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.GoalKind
import dev.deftmartian.runway.domain.PhaseTransitionOption
import dev.deftmartian.runway.domain.PlanPhase
import dev.deftmartian.runway.domain.RaceDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlanLifecyclePreparationTest {
    @Test
    fun `review derives a two-week race baseline only from the supplied accepted links`() {
        val review = LocalPlanLifecyclePreparation.prepareReview(
            phaseType = "calibration",
            goalKind = "race",
            acceptedLinkedActivities = listOf(
                activity("a", 3_000, 1_500),
                activity("b", 4_000, 2_000),
                activity("c", 3_000, 1_600),
                activity("d", 5_000, 2_500),
            ),
            observedWeekCount = 2,
            availabilityDays = listOf(1, 3, 6),
        )

        assertEquals(PlanPhase.CALIBRATION, review.phase)
        assertEquals(GoalKind.RACE, review.goalKind)
        assertEquals(4, review.baseline.activityCount)
        assertEquals(15_000, review.baseline.totalDistanceMeters)
        assertEquals(7_500, review.baseline.weeklyDistanceMeters)
        assertEquals(7_600, review.baseline.totalDurationSeconds)
        assertEquals(5_000, review.baseline.longestActivityMeters)
        assertEquals(2.0, review.baseline.runsPerWeek, 0.0)
        assertEquals(PhaseTransitionOption.CONFIRM_RACE_BASELINE, review.transition.recommended)
        assertTrue(review.transition.options.contains(PhaseTransitionOption.CONTINUE_CALIBRATION))
    }

    @Test
    fun `foundation goal review never offers race confirmation`() {
        val review = LocalPlanLifecyclePreparation.prepareReview(
            phaseType = "foundation",
            goalKind = "foundation",
            acceptedLinkedActivities = listOf(
                activity("a", 3_000, 1_500),
                activity("b", 3_000, 1_500),
            ),
            observedWeekCount = 1,
            availabilityDays = listOf(1, 3, 6),
        )

        assertEquals(
            listOf(PhaseTransitionOption.ANOTHER_FOUNDATION_WEEK),
            review.transition.options,
        )
        assertFalse(review.transition.options.contains(PhaseTransitionOption.CONFIRM_RACE_BASELINE))
    }

    @Test
    fun `preparation rejects ambiguous observations and invalid availability`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalPlanLifecyclePreparation.prepareReview(
                "foundation",
                "race",
                listOf(activity("duplicate", 3_000, 1_500), activity("duplicate", 3_000, 1_500)),
                1,
                listOf(1, 3, 6),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalPlanLifecyclePreparation.prepareReview(
                "foundation",
                "race",
                listOf(activity("a", 3_000, 1_500)),
                1,
                listOf(1, 1, 7),
            )
        }
    }

    @Test
    fun `planner boundary normalizes run count and supported race distances`() {
        val review = LocalPlanLifecyclePreparation.prepareReview(
            "calibration",
            "race",
            listOf(activity("a", 3_000, 1_500)),
            2,
            listOf(1, 3),
        )

        assertEquals(2, LocalPlanLifecyclePreparation.normalizedRuns(review.baseline))
        assertEquals(RaceDistance.FIVE_K, LocalPlanLifecyclePreparation.raceDistance(5_000))
        assertEquals(RaceDistance.HALF, LocalPlanLifecyclePreparation.raceDistance(21_100))
        assertEquals(RaceDistance.HALF, LocalPlanLifecyclePreparation.raceDistance(21_097))
        assertEquals(RaceDistance.MARATHON, LocalPlanLifecyclePreparation.raceDistance(42_195))
        assertTrue(LocalPlanLifecyclePreparation.validOperationId("retry-key"))
        assertFalse(LocalPlanLifecyclePreparation.validOperationId(""))
    }

    @Test
    fun `legacy experience values do not change a phase to race preview`() {
        val activities = listOf(
            activity("a", 4_000, 1_800),
            activity("b", 4_000, 1_800),
            activity("c", 4_000, 1_800),
            activity("d", 4_000, 1_800),
        )
        val availability = listOf(1, 3, 6)
        val review = LocalPlanLifecyclePreparation.prepareReview(
            phaseType = "calibration",
            goalKind = "race",
            acceptedLinkedActivities = activities,
            observedWeekCount = 2,
            availabilityDays = availability,
        )
        val phasePlan = PlanEntity(
            planId = "phase-plan",
            goalId = "goal",
            phaseType = "calibration",
            state = "active",
            startEpochDay = 0,
            endEpochDay = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )
        val goal = GoalEntity(
            goalId = "goal",
            title = "5K",
            targetDateEpochDay = java.time.LocalDate.parse("2026-05-01").toEpochDay(),
            state = "active",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            kind = "race",
            startMode = "calibration",
            raceDistanceMeters = 5_000,
            priority = "finish_healthy",
        )
        fun preview(experienceLevel: String) = requireNotNull(
            LocalPlanLifecyclePreparation.prepareRacePlan(
                phasePlan = phasePlan,
                goal = goal,
                profile = profile(experienceLevel),
                review = review,
                acceptedLinkedActivities = activities,
                availabilityDays = availability,
                todayEpochDay = java.time.LocalDate.parse("2026-01-01").toEpochDay(),
            ),
        ).preview

        assertEquals(preview("new"), preview("comfortable"))
        assertEquals(preview("new"), preview("not_specified"))
    }

    private fun activity(id: String, distanceMeters: Int, durationSeconds: Int) =
        LocalAcceptedLinkedActivity(id, distanceMeters, durationSeconds)

    private fun profile(experienceLevel: String) = ProfileSettingsEntity(
        timeZone = "America/Halifax",
        routeDataMode = "discard",
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
        updatedAtEpochMillis = 3,
        experienceLevel = experienceLevel,
    )
}
