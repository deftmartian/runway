package dev.deftmartian.runway.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class LocalSurfaceMappersTest {
    @Test
    fun `calendar preserves generated current actual and rest while excluding review evidence`() {
        val day = LocalDate.parse("2026-07-30").toEpochDay()
        val run = workout(
            id = "run",
            day = day,
            generatedDistance = 5_000,
            currentDistance = 4_000,
        )
        val rest = workout(id = "rest", day = day + 1, type = "rest")
        val plan = planSlice(workouts = listOf(run, rest))
        val accepted = activity("accepted", "accepted", "run", 3_900, 1_500, "2026-07-30T12:00:00Z")
        val review = activity("review", "review", "run", 99_000, 20_000, "2026-07-30T13:00:00Z")

        val mapped = LocalSurfaceMappers.calendar(
            LocalCalendarLedgerSlice(
                fromEpochDay = day,
                throughEpochDay = day + 1,
                timeZone = "UTC",
                pendingReviewCount = 1,
                plans = listOf(plan),
                activities = listOf(accepted, review),
            ),
        )

        val mappedRun = mapped.days.first().workouts.single()
        assertEquals(5_000, mappedRun.generated.load.distanceMeters)
        assertEquals(4_000, mappedRun.current.load.distanceMeters)
        assertEquals(3_900, mappedRun.actual?.load?.distanceMeters)
        assertTrue(mappedRun.isEdited)
        assertTrue(mapped.days.last().workouts.single().isRest)
        assertEquals(1, mapped.pendingReviewCount)
    }

    @Test
    fun `stats use accepted paired evidence and retain active archived and unlinked provenance`() {
        val activeWorkout = workout("active-run", 10, generatedDistance = 6_000, currentDistance = 5_000)
        val archivedWorkout = workout("archived-run", 3, planId = "archived-plan", weekId = "archived-week", generatedDistance = 10_000, currentDistance = 10_000)
        val active = planSlice(workouts = listOf(activeWorkout))
        val archived = planSlice(
            planId = "archived-plan",
            weekId = "archived-week",
            state = "archived",
            workouts = listOf(archivedWorkout),
        )
        val activities = listOf(
            activity("a", "accepted", "active-run", 5_000, 1_800, "2026-07-10T12:00:00Z", averageHeartRate = 150),
            activity("b", "accepted", "archived-run", 10_000, 4_000, "2026-07-11T12:00:00Z", averageHeartRate = 160),
            activity("c", "accepted", null, 2_000, 1_000, "2026-07-12T12:00:00Z", averageHeartRate = 140),
            activity("ignored", "review", null, 100_000, 50_000, "2026-07-13T12:00:00Z", averageHeartRate = 220),
        )

        val mapped = LocalSurfaceMappers.stats(LocalStatsLedgerSlice(listOf(active, archived), activities))

        assertEquals(3, mapped.totalRuns)
        assertEquals(17_000, mapped.totalDistanceMeters)
        assertEquals(6_800, mapped.totalDurationSeconds)
        assertEquals(400.0, mapped.weightedPaceSecondsPerKilometre!!, 0.001)
        assertEquals(154, mapped.durationWeightedHeartRateBpm)
        assertEquals(
            setOf(LocalPlanProvenance.ACTIVE, LocalPlanProvenance.ARCHIVED, LocalPlanProvenance.UNLINKED),
            mapped.recordedTotals.map { it.provenance }.toSet(),
        )
        assertEquals(6_000, mapped.weeks.first { it.planId == "plan" }.generated.distanceMeters)
        assertEquals(5_000, mapped.weeks.first { it.planId == "plan" }.current.distanceMeters)
    }

    @Test
    fun `history keeps phase lifecycle and accepted unlinked evidence`() {
        val plan = planSlice(
            state = "completed",
            workouts = listOf(workout("run", 10)),
            lifecycle = listOf(
                PlanLifecycleEventEntity("event", "plan", "completed", 99, 1, 1, "Target reached"),
            ),
        )
        val mapped = LocalSurfaceMappers.history(
            LocalHistoryLedgerSlice(
                plans = listOf(plan),
                activities = listOf(
                    activity("linked", "accepted", "run", 5_000, 1_800, "2026-07-10T12:00:00Z"),
                    activity("extra", "accepted", null, 2_000, 900, "2026-07-11T12:00:00Z"),
                    activity("pending", "review", null, 9_000, 3_000, "2026-07-12T12:00:00Z"),
                ),
                hasMorePlans = false,
                hasMoreActivities = false,
            ),
        )

        assertEquals(LocalPlanState.COMPLETED, mapped.plans.single().state)
        assertEquals(LocalPlanPhase.DISTANCE, mapped.plans.single().phase)
        assertEquals("completed", mapped.plans.single().lifecycle.single().eventType)
        assertEquals(listOf("extra"), mapped.unlinkedActivities.map { it.activityId })
    }

    @Test
    fun `settings expose local profile lifecycle and standalone placeholders`() {
        val mapped = LocalSurfaceMappers.settings(
            LocalSettingsLedgerSlice(
                profile = profile(),
                availabilityDays = listOf(
                    ProfileAvailabilityDayEntity(dayOfWeek = 6),
                    ProfileAvailabilityDayEntity(dayOfWeek = 1),
                ),
                activePlan = planSlice(
                    lifecycle = listOf(
                        PlanLifecycleEventEntity("event", "plan", "continued", 50, null, null, null),
                    ),
                ),
                versionName = null,
                buildRevision = null,
            ),
        )

        assertEquals(listOf(1, 6), mapped.profile?.availabilityDays)
        assertEquals(LocalPlanPhase.DISTANCE, mapped.activePlan?.phase)
        assertEquals("continued", mapped.activePlan?.latestLifecycleEvent?.eventType)
        assertEquals("Standalone", mapped.about.mode)
        assertEquals("Stored on this device", mapped.about.dataLocation)
        assertNull(mapped.about.versionName)
        assertFalse(mapped.about.exportStatus.isBlank())
    }

    @Test
    fun `activity evidence preserves bounded route and heart rate observations`() {
        val base = activity("evidence", "review", null, 2_000, 900, "2026-07-12T12:00:00Z")
        val mapped = LocalSurfaceMappers.activityEvidence(
            base.copy(
                route = listOf(
                    RouteSampleEntity(
                        activityId = "evidence",
                        ordinal = 0,
                        latitudeE6 = 44_000_000,
                        longitudeE6 = -63_000_000,
                        elapsedSeconds = 0,
                        elevationMeters = 10.0,
                    ),
                ),
                heartRate = listOf(
                    HeartRateSampleEntity(
                        activityId = "evidence",
                        ordinal = 0,
                        elapsedSeconds = 10,
                        beatsPerMinute = 145,
                    ),
                ),
            ),
        )

        assertEquals(44_000_000, mapped.evidence.route.single().latitudeE6)
        assertEquals(145, mapped.evidence.heartRate.single().beatsPerMinute)
    }

    private fun planSlice(
        planId: String = "plan",
        weekId: String = "week",
        state: String = "active",
        workouts: List<WorkoutEntity> = emptyList(),
        lifecycle: List<PlanLifecycleEventEntity> = emptyList(),
    ) = LocalPlanLedgerSlice(
        goal = GoalEntity("goal-$planId", "Goal $planId", 100, state, 1, 1, "race", "established", 5_000, "finish_healthy"),
        plan = PlanEntity(planId, "goal-$planId", "distance", state, 1, 100, 1, 1),
        weeks = listOf(PlanWeekEntity(weekId, planId, 1, 1, 10_000)),
        workouts = workouts,
        lifecycle = lifecycle,
    )

    private fun workout(
        id: String,
        day: Long,
        planId: String = "plan",
        weekId: String = "week",
        type: String = "easy",
        generatedDistance: Int? = if (type == "rest") null else 5_000,
        currentDistance: Int? = generatedDistance,
    ) = WorkoutEntity(
        workoutId = id,
        planId = planId,
        weekId = weekId,
        position = 0,
        generatedPurpose = if (type == "rest") "Rest" else "Easy run",
        generatedDistanceMeters = generatedDistance,
        generatedDurationSeconds = null,
        currentPurpose = if (type == "rest") "Rest" else "Easy run",
        currentDistanceMeters = currentDistance,
        currentDurationSeconds = null,
        tombstonedAtEpochMillis = null,
        updatedAtEpochMillis = 1,
        generatedScheduledEpochDay = day,
        currentScheduledEpochDay = day,
        generatedWorkoutType = type,
        currentWorkoutType = type,
        generatedPrescriptionKind = if (type == "rest") "rest" else "distance",
        currentPrescriptionKind = if (type == "rest") "rest" else "distance",
    )

    private fun activity(
        id: String,
        reviewState: String,
        workoutId: String?,
        distance: Int,
        duration: Int,
        occurredAt: String,
        averageHeartRate: Int? = null,
    ) = LocalActivityLedgerSlice(
        ActivityEntity(
            activityId = id,
            source = "gpx",
            sourceRecordId = id,
            reviewState = reviewState,
            occurredAtEpochMillis = Instant.parse(occurredAt).toEpochMilli(),
            durationSeconds = duration,
            distanceMeters = distance,
            averageHeartRateBpm = averageHeartRate,
            averageCadenceSpm = null,
            linkedWorkoutId = workoutId,
            acceptedAtEpochMillis = if (reviewState == "accepted") 2 else null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        ),
    )

    private fun profile() = ProfileSettingsEntity(
        timeZone = "America/Halifax",
        routeDataMode = "discard",
        heartRateSettingsSource = "custom",
        maxHeartRateBpm = 190,
        zone2FloorBpm = 120,
        zone3FloorBpm = 140,
        zone4FloorBpm = 160,
        zone5FloorBpm = 180,
        recentInjury = false,
        currentPain = false,
        recurringPain = false,
        medicalRestriction = false,
        privateNotes = null,
        updatedAtEpochMillis = 1,
    )
}
