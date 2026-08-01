package dev.deftmartian.runway.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class RoomLocalSurfaceLedgerReaderInstrumentedTest {
    private lateinit var database: RunwayLedgerDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RunwayLedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun inboxKeepsUnresolvedAcceptedExtrasVisibleWithoutInflatingReviewCount() = runBlocking {
        val dao = database.activityLedgerDao()
        listOf(
            activity("review-new", occurredAt = 800, reviewState = "review"),
            activity("review-old", occurredAt = 100, reviewState = "review"),
            activity("extra-new", occurredAt = 1_000),
            activity("extra-mid", occurredAt = 900),
            activity("extra-old", occurredAt = 50),
            activity("extra-applied", occurredAt = 1_100),
            activity("extra-resolved", occurredAt = 1_050),
            activity("extra-not-actionable", occurredAt = 1_025),
        ).forEach { dao.saveActivity(it) }
        listOf(
            consequence("extra-new"),
            consequence("extra-mid"),
            consequence("extra-old"),
            consequence("extra-applied", appliedDecision = "keep_plan"),
            consequence("extra-resolved", resolvedAt = 2_000),
            consequence("extra-not-actionable", planChangeAvailable = false),
        ).forEach { dao.saveActivityConsequence(it) }

        val inbox = RoomLocalSurfaceLedgerReader(database).inbox(
            LocalSurfaceReadLimits(inboxActivities = 2),
        )

        assertEquals(2, inbox.reviewCount)
        assertTrue(inbox.hasMore)
        assertEquals(
            listOf("extra-new", "extra-mid", "review-new", "review-old"),
            inbox.activities.map { it.activity.activityId },
        )
        assertFalse(inbox.activities.any { it.activity.activityId == "extra-applied" })
        assertFalse(inbox.activities.any { it.activity.activityId == "extra-resolved" })
        assertFalse(inbox.activities.any { it.activity.activityId == "extra-not-actionable" })
    }

    @Test
    fun inboxPaginatesPendingHealthConnectDecisionsWithoutHidingOverflow() = runBlocking {
        val activityDao = database.activityLedgerDao()
        val importDao = database.importLedgerDao()
        listOf("health-1", "health-2").forEachIndexed { index, activityId ->
            activityDao.saveActivity(activity(activityId, occurredAt = (index + 1).toLong()))
            importDao.saveHealthConnectMapping(
                HealthConnectMappingEntity(
                    mappingId = "mapping-$activityId",
                    provider = "health_connect",
                    externalRecordId = "record-$activityId",
                    activityId = activityId,
                    importedAtEpochMillis = (index + 1).toLong(),
                    lastObservedAtEpochMillis = (index + 1).toLong(),
                    correctionPending = true,
                ),
            )
        }

        val reader = RoomLocalSurfaceLedgerReader(database)
        val firstPage = reader.inbox(LocalSurfaceReadLimits(inboxActivities = 1))
        val completePage = reader.inbox(LocalSurfaceReadLimits(inboxActivities = 2))

        assertEquals(1, firstPage.pendingHealthConnect.size)
        assertTrue(firstPage.hasMore)
        assertEquals(2, completePage.pendingHealthConnect.size)
        assertFalse(completePage.hasMore)
    }

    @Test
    fun inboxKeepsUnresolvedLinkedWorkoutConsequencesVisibleUntilApplied() = runBlocking {
        database.profileSettingsDao().save(profile())
        val goal = GoalEntity(
            "linked-goal", "Goal", null, "active", 1, 1,
            "distance", "established", null, "finish_healthy",
        )
        val plan = PlanEntity("linked-plan", goal.goalId, "distance", "active", 0, 30, 1, 1)
        val week = PlanWeekEntity("linked-week", plan.planId, 1, 0, 10_000)
        val pendingWorkout = workout("linked-pending-workout", plan.planId, week.weekId, 3)
        val appliedWorkout = workout("linked-applied-workout", plan.planId, week.weekId, 4)
        val directPendingWorkout = workout("direct-pending-workout", plan.planId, week.weekId, 5)
        val directOlderPendingWorkout =
            workout("direct-older-pending-workout", plan.planId, week.weekId, 6)
        val directAppliedWorkout = workout("direct-applied-workout", plan.planId, week.weekId, 7)
        database.goalPlanDao().saveGoal(goal)
        database.goalPlanDao().createPlanGraph(
            plan,
            listOf(week),
            listOf(
                pendingWorkout,
                appliedWorkout,
                directPendingWorkout,
                directOlderPendingWorkout,
                directAppliedWorkout,
            ),
        )
        val dao = database.activityLedgerDao()
        dao.saveActivity(
            activity("linked-pending", epochMillisAtDay(3), linkedWorkoutId = pendingWorkout.workoutId),
        )
        dao.saveActivity(
            activity("linked-applied", epochMillisAtDay(4), linkedWorkoutId = appliedWorkout.workoutId),
        )
        val pendingFeedback = linkedFeedback("linked-pending-feedback", pendingWorkout.workoutId, "linked-pending")
        val appliedFeedback = linkedFeedback("linked-applied-feedback", appliedWorkout.workoutId, "linked-applied")
        val directPendingFeedback =
            directFeedback("direct-pending-feedback", directPendingWorkout.workoutId, recordedAt = 3)
        val directOlderPendingFeedback =
            directFeedback(
                "direct-older-pending-feedback",
                directOlderPendingWorkout.workoutId,
                recordedAt = 2,
            )
        val directAppliedFeedback =
            directFeedback("direct-applied-feedback", directAppliedWorkout.workoutId, recordedAt = 4)
        dao.saveWorkoutFeedback(pendingFeedback)
        dao.saveWorkoutFeedback(appliedFeedback)
        dao.saveWorkoutFeedback(directPendingFeedback)
        dao.saveWorkoutFeedback(directOlderPendingFeedback)
        dao.saveWorkoutFeedback(directAppliedFeedback)
        dao.saveWorkoutFeedbackConsequence(workoutConsequence(pendingFeedback.feedbackId))
        dao.saveWorkoutFeedbackConsequence(
            workoutConsequence(appliedFeedback.feedbackId, appliedDecision = "keep_plan"),
        )
        dao.saveWorkoutFeedbackConsequence(workoutConsequence(directPendingFeedback.feedbackId))
        dao.saveWorkoutFeedbackConsequence(workoutConsequence(directOlderPendingFeedback.feedbackId))
        dao.saveWorkoutFeedbackConsequence(
            workoutConsequence(directAppliedFeedback.feedbackId, appliedDecision = "keep_plan"),
        )

        val reader = RoomLocalSurfaceLedgerReader(database)
        val firstPage = reader.inbox(LocalSurfaceReadLimits(inboxActivities = 1))
        val inbox = reader.inbox(LocalSurfaceReadLimits(inboxActivities = 4))
        val calendar = reader.calendar(
            fromEpochDay = 0,
            throughEpochDay = 30,
            limits = LocalSurfaceReadLimits(),
        )

        assertEquals(listOf("linked-pending"), inbox.activities.map { it.activity.activityId })
        assertEquals(pendingFeedback.feedbackId, inbox.activities.single().linkedWorkoutFeedback?.feedbackId)
        assertEquals(pendingFeedback.feedbackId, inbox.activities.single().linkedWorkoutConsequence?.feedbackId)
        assertFalse(inbox.activities.any { it.activity.activityId == "linked-applied" })
        assertTrue(firstPage.hasMore)
        assertEquals(
            listOf(directPendingFeedback.feedbackId),
            firstPage.pendingWorkoutFeedback.map { it.feedback.feedbackId },
        )
        assertEquals(
            listOf(directPendingFeedback.feedbackId, directOlderPendingFeedback.feedbackId),
            inbox.pendingWorkoutFeedback.map { it.feedback.feedbackId },
        )
        assertFalse(
            inbox.pendingWorkoutFeedback.any {
                it.feedback.feedbackId == directAppliedFeedback.feedbackId
            },
        )
        assertEquals(
            3,
            calendar.pendingDecisionCount,
        )
        assertTrue(calendar.pendingDecisionCountIsExact)
    }

    @Test
    fun inboxFindsAReviewMatchInTheBoundedLatePlanWindow() = runBlocking {
        database.profileSettingsDao().save(profile())
        val goal = GoalEntity(
            "goal",
            "Goal",
            null,
            "active",
            1,
            1,
            "distance",
            "established",
            null,
            "finish_healthy",
        )
        val plan = PlanEntity("plan", goal.goalId, "distance", "active", 0, 2_000, 1, 1)
        val week = PlanWeekEntity("week", plan.planId, 1, 0, 10_000)
        val earlyWorkouts = (1..128).map { day ->
            workout("early-$day", plan.planId, week.weekId, day.toLong())
        }
        val lateWorkout = workout("late-match", plan.planId, week.weekId, 1_000)
        database.goalPlanDao().saveGoal(goal)
        database.goalPlanDao().createPlanGraph(plan, listOf(week), earlyWorkouts + lateWorkout)
        database.activityLedgerDao().saveActivity(
            activity("review-near-late", epochMillisAtDay(1_002), reviewState = "review"),
        )

        val inbox = RoomLocalSurfaceLedgerReader(database).inbox(LocalSurfaceReadLimits(inboxActivities = 2))

        assertEquals(listOf("late-match"), inbox.linkCandidates.map(WorkoutEntity::workoutId))
    }

    @Test
    fun calendarKeepsTodayAndNextAsDistinctDecisions() = runBlocking {
        val today = 1_000L
        database.profileSettingsDao().save(profile())
        val goal = GoalEntity(
            "calendar-goal",
            "Goal",
            null,
            "active",
            1,
            1,
            "distance",
            "established",
            null,
            "finish_healthy",
        )
        val plan = PlanEntity("calendar-plan", goal.goalId, "distance", "active", today, today + 14, 1, 1)
        val week = PlanWeekEntity("calendar-week", plan.planId, 1, today, 10_000)
        val todayWorkout = workout("today-run", plan.planId, week.weekId, today)
        val futureWorkout = workout("future-run", plan.planId, week.weekId, today + 2)
        database.goalPlanDao().saveGoal(goal)
        database.goalPlanDao().createPlanGraph(plan, listOf(week), listOf(todayWorkout, futureWorkout))
        val now = LocalDate.ofEpochDay(today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

        val calendar = RoomLocalSurfaceLedgerReader(database, nowEpochMillis = { now }).calendar(
            fromEpochDay = today,
            throughEpochDay = today + 30,
            limits = LocalSurfaceReadLimits(),
        )

        assertEquals("future-run", calendar.nextWorkout?.workoutId)
    }

    @Test
    fun longRunningRoutineReadsTheCurrentWindowBeforeApplyingSurfaceLimits() = runBlocking {
        val start = LocalDate.parse("2025-01-06")
        val today = LocalDate.parse("2026-08-03")
        database.profileSettingsDao().save(profile())
        val graph = RoutinePlanPersistenceMapper.map(
            goalId = "long-routine-goal",
            planId = "long-routine-plan",
            title = "Weekly running routine",
            priority = "consistency",
            startEpochDay = start.toEpochDay(),
            selectedDays = listOf(1, 3, 6),
            createdAtEpochMillis = 1,
        )
        database.goalPlanDao().saveGoal(graph.goal)
        database.goalPlanDao().createRoutineGraph(graph)
        assertTrue(
            LocalRoutineRepository(database).ensureHorizon(
                planId = graph.plan.planId,
                throughEpochDay = today.plusWeeks(8).toEpochDay(),
                updatedAtEpochMillis = 2,
            ) is LocalRoutineHorizonResult.Extended,
        )
        assertTrue(
            database.goalPlanDao().visibleWorkoutsForPlan(graph.plan.planId, limit = 1_000).size >
                LocalSurfaceReadLimits().calendarWorkouts,
        )
        val now = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val reader = RoomLocalSurfaceLedgerReader(database, nowEpochMillis = { now })

        val calendar = reader.calendar(
            fromEpochDay = today.toEpochDay(),
            throughEpochDay = today.plusDays(6).toEpochDay(),
            limits = LocalSurfaceReadLimits(),
        )
        assertEquals(
            listOf(today, today.plusDays(2), today.plusDays(5)).map(LocalDate::toEpochDay),
            calendar.plans.single().workouts.map(WorkoutEntity::currentScheduledEpochDay),
        )

        val stats = reader.stats(LocalSurfaceReadLimits(statsWeeks = 52))
        assertEquals(52, stats.plans.single().weeks.size)
        assertEquals(today.minusWeeks(51).toEpochDay(), stats.plans.single().weeks.first().startEpochDay)
        assertEquals(today.toEpochDay(), stats.plans.single().weeks.last().startEpochDay)

        val history = reader.history(LocalSurfaceReadLimits(statsWeeks = 52), 0, 0)
        assertEquals(today.toEpochDay(), history.plans.single().weeks.last().startEpochDay)
    }

    @Test
    fun profilePresenceRemainsVisibleWithoutAnActivePlan() = runBlocking {
        val reader = RoomLocalSurfaceLedgerReader(database)
        val limits = LocalSurfaceReadLimits()

        assertFalse(reader.calendar(0, 30, limits).profileExists)
        assertFalse(reader.stats(limits).profileExists)

        database.profileSettingsDao().save(profile())

        assertTrue(reader.calendar(0, 30, limits).profileExists)
        assertTrue(reader.stats(limits).profileExists)
    }

    @Test
    fun historyIncludesRemovedWorkoutsWithoutReturningThemToTheLiveCalendar() = runBlocking {
        val today = 1_000L
        database.profileSettingsDao().save(profile())
        val goal = GoalEntity(
            "audit-goal", "Goal", null, "active", 1, 1,
            "distance", "established", null, "finish_healthy",
        )
        val plan = PlanEntity("audit-plan", goal.goalId, "distance", "active", today, today + 14, 1, 1)
        val week = PlanWeekEntity("audit-week", plan.planId, 1, today, 4_000)
        val kept = workout("kept-run", plan.planId, week.weekId, today)
        val removed = workout("removed-run", plan.planId, week.weekId, today + 2).copy(
            currentStatus = "tombstoned",
            tombstonedAtEpochMillis = 5_000,
        )
        database.goalPlanDao().saveGoal(goal)
        database.goalPlanDao().createPlanGraph(plan, listOf(week), listOf(kept, removed))
        val now = LocalDate.ofEpochDay(today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val reader = RoomLocalSurfaceLedgerReader(database, nowEpochMillis = { now })

        val history = reader.history(LocalSurfaceReadLimits())
        val calendar = reader.calendar(today, today + 30, LocalSurfaceReadLimits())

        assertEquals(
            listOf("kept-run", "removed-run"),
            history.plans.single().workouts.map(WorkoutEntity::workoutId),
        )
        assertEquals(listOf("kept-run"), calendar.plans.single().workouts.map(WorkoutEntity::workoutId))
    }

    @Test
    fun inboxKeysetPagesCrossTheFormerThousandItemWindowWithoutDuplicates() = runBlocking {
        val ids = (0..1_000).map { index -> "review-${index.toString().padStart(4, '0')}" }
        ids.forEach { id ->
            database.activityLedgerDao().saveActivity(activity(id, occurredAt = 1, reviewState = "review"))
        }
        val reader = RoomLocalSurfaceLedgerReader(database)
        val limits = LocalSurfaceReadLimits(inboxActivities = 50)
        var cursor = LocalInboxPagingCursor()
        val seen = mutableListOf<String>()
        do {
            val page = reader.inboxPage(limits, cursor)
            seen += page.activities.map { it.activity.activityId }
            cursor = page.nextPage ?: break
        } while (true)

        assertEquals(ids.size, reader.calendar(0, 30, limits).pendingDecisionCount)
        assertEquals(ids.size, seen.size)
        assertEquals(ids.size, seen.distinct().size)
        assertEquals(ids.sortedDescending(), seen)
    }

    @Test
    fun statsAggregatesRemainExactBeyondTheFormerActivityWindowAndOnRerun() = runBlocking {
        repeat(513) { index ->
            database.activityLedgerDao().saveActivity(
                activity(
                    id = "accepted-${index.toString().padStart(3, '0')}",
                    occurredAt = index.toLong(),
                ),
            )
        }
        val reader = RoomLocalSurfaceLedgerReader(database)
        val limits = LocalSurfaceReadLimits(statsActivities = 1)

        val first = LocalSurfaceMappers.stats(reader.stats(limits))
        val rerun = LocalSurfaceMappers.stats(reader.stats(limits))

        assertTrue(first.isComplete)
        assertEquals(513, first.totalRuns)
        assertEquals(513 * 2_000, first.totalDistanceMeters)
        assertEquals(513 * 900, first.totalDurationSeconds)
        assertEquals(first, rerun)
    }

    @Test
    fun historyPagesPastFiftyPlansAndKeepsOldLinkedResultsIndependentOfActivityPage() =
        runBlocking {
            database.profileSettingsDao().save(profile())
            repeat(51) { index ->
                val suffix = index.toString().padStart(3, '0')
                val goal = GoalEntity(
                    goalId = "history-goal-$suffix",
                    title = "Goal $suffix",
                    targetDateEpochDay = null,
                    state = "archived",
                    createdAtEpochMillis = index.toLong(),
                    updatedAtEpochMillis = index.toLong(),
                    kind = "distance",
                    startMode = "established",
                    raceDistanceMeters = null,
                    priority = "finish_healthy",
                )
                val plan = PlanEntity(
                    planId = "history-plan-$suffix",
                    goalId = goal.goalId,
                    phaseType = "distance",
                    state = "archived",
                    startEpochDay = index * 10L,
                    endEpochDay = index * 10L + 6,
                    createdAtEpochMillis = index.toLong(),
                    updatedAtEpochMillis = index.toLong(),
                    archivedAtEpochMillis = index.toLong(),
                )
                val week = PlanWeekEntity(
                    weekId = "history-week-$suffix",
                    planId = plan.planId,
                    ordinal = 1,
                    startEpochDay = plan.startEpochDay,
                    generatedLoadMeters = 2_000,
                )
                val workout = workout(
                    id = "history-workout-$suffix",
                    planId = plan.planId,
                    weekId = week.weekId,
                    day = plan.startEpochDay,
                )
                database.goalPlanDao().saveGoal(goal)
                database.goalPlanDao().createPlanGraph(plan, listOf(week), listOf(workout))
                if (index == 0) {
                    database.activityLedgerDao().saveActivity(
                        activity(
                            id = "old-linked-result",
                            occurredAt = epochMillisAtDay(plan.startEpochDay),
                            linkedWorkoutId = workout.workoutId,
                        ),
                    )
                    database.activityLedgerDao().saveActivity(
                        activity(
                            id = "old-plan-extra",
                            occurredAt = epochMillisAtDay(plan.startEpochDay + 1),
                        ),
                    )
                }
            }
            repeat(513) { index ->
                database.activityLedgerDao().saveActivity(
                    activity(
                        id = "outside-$index",
                        occurredAt = epochMillisAtDay(10_000 + index.toLong()),
                    ),
                )
            }
            val reader = RoomLocalSurfaceLedgerReader(database)
            val limits = LocalSurfaceReadLimits(historyPlans = 50, historyActivities = 512)

            val firstPage = reader.history(limits)
            val olderPage = reader.history(
                limits = limits,
                planOffset = requireNotNull(firstPage.nextPlanOffset),
                activityOffset = requireNotNull(firstPage.nextActivityOffset),
            )
            val rerun = reader.history(
                limits = limits,
                planOffset = 50,
                activityOffset = 512,
            )
            val mapped = LocalSurfaceMappers.history(olderPage)
            val detail = LocalSurfaceMappers.history(
                requireNotNull(reader.historyPlan("history-plan-000", limits)),
            )

            assertEquals(50, firstPage.plans.size)
            assertEquals(listOf("history-plan-000"), olderPage.plans.map { it.plan.planId })
            assertEquals(
                "old-linked-result",
                olderPage.activities.single { it.activity.linkedWorkoutId != null }
                    .activity.activityId,
            )
            assertTrue(
                mapped.plans.single().weeks.single().workouts.single().result != null,
            )
            assertEquals(1, mapped.unlinkedActivities.size)
            assertTrue(
                mapped.unlinkedActivities.none { it.activityId == "old-plan-extra" },
            )
            assertEquals(
                listOf("old-plan-extra"),
                detail.plans.single().weeks.single().extraActivities
                    .map { it.activityId },
            )
            assertTrue(
                detail.plans.single().weeks.single().extraActivityContextIsComplete,
            )
            assertEquals(
                olderPage.plans.map { it.plan.planId },
                rerun.plans.map { it.plan.planId },
            )
            assertEquals(
                olderPage.activities.map { it.activity.activityId },
                rerun.activities.map { it.activity.activityId },
            )
        }

    @Test
    fun settingsExposeOnePendingGoalForAnExplicitReplacementJourney() = runBlocking {
        database.profileSettingsDao().save(profile())
        database.goalPlanDao().saveGoal(
            GoalEntity(
                "pending-goal",
                "5K later",
                LocalDate.parse("2026-11-01").toEpochDay(),
                "pending",
                1,
                1,
                "race",
                "foundation_to_goal",
                5_000,
                "finish_healthy",
            ),
        )

        val settings = RoomLocalSurfaceLedgerReader(database).settings(LocalSurfaceReadLimits())

        assertEquals("pending-goal", settings.pendingGoal?.goalId)
        assertEquals("foundation_to_goal", settings.pendingGoal?.startMode)
        assertEquals(5_000, settings.pendingGoal?.raceDistanceMeters)
    }

    private fun activity(
        id: String,
        occurredAt: Long,
        reviewState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
        linkedWorkoutId: String? = null,
    ) = ActivityEntity(
        activityId = id,
        source = "gpx",
        sourceRecordId = "digest-$id",
        reviewState = reviewState,
        occurredAtEpochMillis = occurredAt,
        durationSeconds = 900,
        distanceMeters = 2_000,
        averageHeartRateBpm = null,
        averageCadenceSpm = null,
        linkedWorkoutId = linkedWorkoutId,
        acceptedAtEpochMillis = occurredAt.takeIf { reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED },
        createdAtEpochMillis = occurredAt,
        updatedAtEpochMillis = occurredAt,
        extraPlanImpactConfirmed = reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED && linkedWorkoutId == null,
    )

    private fun consequence(
        activityId: String,
        appliedDecision: String? = null,
        resolvedAt: Long? = null,
        planChangeAvailable: Boolean = true,
    ) = ActivityConsequenceEntity(
        activityId = activityId,
        classification = "extra_activity",
        distanceDifferenceMeters = 1_000,
        durationDifferenceSeconds = null,
        actualLoadMeters = 2_000,
        assessment = "conservative",
        recommendedDecision = "keep_plan",
        resolvedAtEpochMillis = resolvedAt,
        appliedDecision = appliedDecision,
        planChangeAvailable = planChangeAvailable,
    )

    private fun linkedFeedback(feedbackId: String, workoutId: String, activityId: String) =
        WorkoutFeedbackEntity(
            feedbackId = feedbackId,
            workoutId = workoutId,
            completionState = "shortened",
            feltHard = true,
            pain = false,
            notes = null,
            recordedAtEpochMillis = 1,
            completedDistanceMeters = 1_000,
            completedDurationSeconds = 600,
            sourceActivityId = activityId,
        )

    private fun directFeedback(feedbackId: String, workoutId: String, recordedAt: Long) =
        linkedFeedback(feedbackId, workoutId, activityId = "ignored").copy(
            sourceActivityId = null,
            recordedAtEpochMillis = recordedAt,
        )

    private fun workoutConsequence(feedbackId: String, appliedDecision: String? = null) =
        WorkoutFeedbackConsequenceEntity(
            feedbackId = feedbackId,
            classification = "short",
            distanceDifferenceMeters = -1_000,
            durationDifferenceSeconds = null,
            currentWeekLoadMeters = 2_000,
            projectedWeekLoadMeters = 2_000,
            assessment = "conservative",
            recoveryConflictCount = 0,
            recommendedDecision = "keep_plan",
            nextWorkoutAction = "keep_plan",
            requiresExplicitConfirmation = false,
            appliedDecision = appliedDecision,
            planChangeAvailable = true,
        )

    private fun profile() = ProfileSettingsEntity(
        timeZone = "UTC",
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
        updatedAtEpochMillis = 1,
    )

    private fun workout(id: String, planId: String, weekId: String, day: Long) = WorkoutEntity(
        workoutId = id,
        planId = planId,
        weekId = weekId,
        position = day.toInt(),
        generatedPurpose = "easy",
        generatedDistanceMeters = 2_000,
        generatedDurationSeconds = null,
        currentPurpose = "easy",
        currentDistanceMeters = 2_000,
        currentDurationSeconds = null,
        tombstonedAtEpochMillis = null,
        updatedAtEpochMillis = day,
        generatedScheduledEpochDay = day,
        currentScheduledEpochDay = day,
        generatedWorkoutType = "easy",
        currentWorkoutType = "easy",
        generatedPrescriptionKind = "distance",
        currentPrescriptionKind = "distance",
    )

    private fun epochMillisAtDay(day: Long): Long =
        LocalDate.ofEpochDay(day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
}
