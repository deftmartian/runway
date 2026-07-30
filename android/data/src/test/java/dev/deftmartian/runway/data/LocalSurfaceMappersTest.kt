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
    fun `calendar exposes the next planned run even when it falls outside the visible month`() {
        val julyEnd = LocalDate.parse("2026-07-31").toEpochDay()
        val nextRun = workout(
            id = "next-run",
            day = LocalDate.parse("2026-08-03").toEpochDay(),
        )

        val mapped = LocalSurfaceMappers.calendar(
            LocalCalendarLedgerSlice(
                fromEpochDay = LocalDate.parse("2026-07-01").toEpochDay(),
                throughEpochDay = julyEnd,
                timeZone = "UTC",
                pendingReviewCount = 0,
                plans = listOf(planSlice(workouts = listOf(nextRun))),
                activities = emptyList(),
                todayEpochDay = LocalDate.parse("2026-07-30").toEpochDay(),
                nextWorkout = nextRun,
            ),
        )

        assertTrue(mapped.days.isEmpty())
        assertEquals(nextRun.workoutId, mapped.nextWorkout?.workoutId)
        assertEquals(nextRun.currentScheduledEpochDay, mapped.nextWorkout?.scheduledEpochDay)
    }

    @Test
    fun `calendar keeps generated and current timed structures distinct`() {
        val day = LocalDate.parse("2026-08-01").toEpochDay()
        val timed = workout("timed", day).copy(
            generatedDistanceMeters = null,
            generatedDurationSeconds = 1_140,
            generatedPrescriptionKind = "timed",
            generatedWarmupSeconds = 120,
            generatedCooldownSeconds = 60,
            currentDistanceMeters = null,
            currentDurationSeconds = 900,
            currentPrescriptionKind = "timed",
            currentWarmupSeconds = 60,
            currentCooldownSeconds = 60,
        )
        val generatedBlock = WorkoutBlockEntity("generated-block", timed.workoutId, "generated", 0, "run_walk", 4)
        val currentBlock = WorkoutBlockEntity("current-block", timed.workoutId, "current", 0, "run_walk", 3)
        val mapped = LocalSurfaceMappers.calendar(
            LocalCalendarLedgerSlice(
                fromEpochDay = day,
                throughEpochDay = day,
                timeZone = "UTC",
                pendingReviewCount = 0,
                plans = listOf(
                    planSlice(workouts = listOf(timed)).copy(
                        workoutBlocks = listOf(generatedBlock, currentBlock),
                        workoutSegments = listOf(
                            WorkoutSegmentEntity("generated-run", generatedBlock.blockId, 0, "run", null, 120),
                            WorkoutSegmentEntity("generated-walk", generatedBlock.blockId, 1, "walk", null, 60),
                            WorkoutSegmentEntity("current-run", currentBlock.blockId, 0, "run", null, 90),
                            WorkoutSegmentEntity("current-walk", currentBlock.blockId, 1, "walk", null, 60),
                        ),
                    ),
                ),
                activities = emptyList(),
            ),
        ).days.single().workouts.single()

        assertEquals(120, mapped.generated.intervalStructure?.warmupSeconds)
        assertEquals(4, mapped.generated.intervalStructure?.blocks?.single()?.repetitions)
        assertEquals(120, mapped.generated.intervalStructure?.blocks?.single()?.segments?.first()?.durationSeconds)
        assertEquals(60, mapped.current.intervalStructure?.warmupSeconds)
        assertEquals(3, mapped.current.intervalStructure?.blocks?.single()?.repetitions)
        assertEquals(90, mapped.current.intervalStructure?.blocks?.single()?.segments?.first()?.durationSeconds)
    }

    @Test
    fun `calendar exposes only the persisted latest undoable adjustment for its workout`() {
        val day = LocalDate.parse("2026-08-02").toEpochDay()
        val run = workout("adjusted", day)
        val mapped = LocalSurfaceMappers.calendar(
            LocalCalendarLedgerSlice(
                fromEpochDay = day,
                throughEpochDay = day,
                timeZone = "UTC",
                pendingReviewCount = 0,
                plans = listOf(
                    planSlice(workouts = listOf(run)).copy(
                        undoableWorkoutAdjustments = listOf(
                            LocalWorkoutAdjustmentReadModel(
                                workoutId = run.workoutId,
                                adjustmentId = "latest-adjustment",
                                kind = "edit",
                                createdAtEpochMillis = 20,
                            ),
                        ),
                    ),
                ),
                activities = emptyList(),
            ),
        ).days.single().workouts.single()

        assertEquals("latest-adjustment", mapped.adjustment?.adjustmentId)
        assertEquals("edit", mapped.adjustment?.kind)
    }

    @Test
    fun `stats and history count only overdue unrecorded runs as missed`() {
        val today = LocalDate.parse("2026-07-30").toEpochDay()
        val completed = workout("completed", today - 3).copy(currentStatus = "done")
        val overdue = workout("overdue", today - 1)
        val skipped = workout("skipped", today - 2).copy(currentStatus = "skipped")
        val future = workout("future", today + 2)
        val plan = planSlice(workouts = listOf(completed, overdue, skipped, future))

        val stats = LocalSurfaceMappers.stats(
            LocalStatsLedgerSlice(
                plans = listOf(plan),
                activities = emptyList(),
                todayEpochDay = today,
            ),
        ).weeks.single()
        val history = LocalSurfaceMappers.history(
            LocalHistoryLedgerSlice(
                plans = listOf(plan),
                activities = emptyList(),
                hasMorePlans = false,
                hasMoreActivities = false,
                timeZone = "UTC",
                todayEpochDay = today,
            ),
        ).plans.single()

        assertEquals(1, stats.completedRuns)
        assertEquals(1, stats.missedRuns)
        assertEquals(1, stats.skippedRuns)
        assertEquals(1, history.completedRuns)
        assertEquals(1, history.missedRuns)
        assertEquals(1, history.skippedRuns)
    }

    @Test
    fun `stats use accepted paired evidence and retain active archived and unlinked provenance`() {
        val activeWorkout = workout("active-run", 10, generatedDistance = 6_000, currentDistance = 5_000)
        val archivedWorkout = workout("archived-run", 3, planId = "archived-plan", weekId = "archived-week", generatedDistance = 10_000, currentDistance = 10_000)
        val linkedFeedback = WorkoutFeedbackEntity(
            feedbackId = "linked-feedback",
            workoutId = activeWorkout.workoutId,
            completionState = "done",
            feltHard = false,
            pain = true,
            notes = null,
            recordedAtEpochMillis = 3,
            completedDistanceMeters = 1_000,
            completedDurationSeconds = 300,
            sourceActivityId = "a",
        )
        val active = planSlice(workouts = listOf(activeWorkout)).copy(feedback = listOf(linkedFeedback))
        val archived = planSlice(
            planId = "archived-plan",
            weekId = "archived-week",
            state = "archived",
            workouts = listOf(archivedWorkout),
        )
        val activities = listOf(
            activity(
                "a",
                "accepted",
                "active-run",
                5_000,
                1_800,
                "2026-07-10T12:00:00Z",
                averageHeartRate = 150,
                feltHard = true,
                pain = true,
            ),
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
        assertEquals(1, mapped.weeks.first { it.planId == "plan" }.painFlags)
        assertEquals(1, mapped.weeks.first { it.planId == "plan" }.hardFlags)
        assertEquals(360.0, mapped.weeks.first { it.planId == "plan" }.weightedPaceSecondsPerKilometre!!, 0.001)
        assertEquals(150, mapped.weeks.first { it.planId == "plan" }.durationWeightedHeartRateBpm)
    }

    @Test
    fun `stats aggregate direct completed feedback once and retain plan provenance`() {
        val activeDirect = workout("active-direct", 10)
        val activeLinked = workout("active-linked", 11)
        val activeSkipped = workout("active-skipped", 12)
        val archivedDirect = workout(
            id = "archived-direct",
            day = 13,
            planId = "archived-plan",
            weekId = "archived-week",
        )
        val active = planSlice(workouts = listOf(activeDirect, activeLinked, activeSkipped)).copy(
            feedback = listOf(
                WorkoutFeedbackEntity(
                    feedbackId = "direct-done",
                    workoutId = activeDirect.workoutId,
                    completionState = "done",
                    feltHard = false,
                    pain = false,
                    notes = null,
                    recordedAtEpochMillis = 1,
                    completedDistanceMeters = 5_000,
                    completedDurationSeconds = 1_500,
                ),
                WorkoutFeedbackEntity(
                    feedbackId = "linked-result",
                    workoutId = activeLinked.workoutId,
                    completionState = "done",
                    feltHard = false,
                    pain = false,
                    notes = null,
                    recordedAtEpochMillis = 2,
                    completedDistanceMeters = 4_000,
                    completedDurationSeconds = 1_200,
                    sourceActivityId = "linked-activity",
                ),
                WorkoutFeedbackEntity(
                    feedbackId = "skipped-result",
                    workoutId = activeSkipped.workoutId,
                    completionState = "skipped",
                    feltHard = false,
                    pain = false,
                    notes = null,
                    recordedAtEpochMillis = 3,
                    completedDistanceMeters = 9_000,
                    completedDurationSeconds = 2_700,
                ),
            ),
        )
        val archived = planSlice(
            planId = "archived-plan",
            weekId = "archived-week",
            state = "archived",
            workouts = listOf(archivedDirect),
        ).copy(
            feedback = listOf(
                WorkoutFeedbackEntity(
                    feedbackId = "archived-direct-result",
                    workoutId = archivedDirect.workoutId,
                    completionState = "shortened",
                    feltHard = false,
                    pain = false,
                    notes = null,
                    recordedAtEpochMillis = 4,
                    completedDistanceMeters = 3_000,
                    completedDurationSeconds = 900,
                ),
            ),
        )
        val linkedActivity = activity(
            "linked-activity",
            "accepted",
            activeLinked.workoutId,
            4_000,
            1_200,
            "2026-07-11T12:00:00Z",
        )

        val mapped = LocalSurfaceMappers.stats(
            LocalStatsLedgerSlice(listOf(active, archived), listOf(linkedActivity)),
        )

        assertEquals(3, mapped.totalRuns)
        assertEquals(12_000, mapped.totalDistanceMeters)
        assertEquals(3_600, mapped.totalDurationSeconds)
        assertEquals(5_000, mapped.longestRunMeters)
        assertEquals(300.0, mapped.weightedPaceSecondsPerKilometre!!, 0.001)
        val provenance = mapped.recordedTotals.associateBy(LocalRecordedTotalsReadModel::provenance)
        assertEquals(2, provenance[LocalPlanProvenance.ACTIVE]?.runs)
        assertEquals(9_000, provenance[LocalPlanProvenance.ACTIVE]?.distanceMeters)
        assertEquals(1, provenance[LocalPlanProvenance.ARCHIVED]?.runs)
        assertEquals(3_000, provenance[LocalPlanProvenance.ARCHIVED]?.distanceMeters)
    }

    @Test
    fun `stats assign accepted extra work to its plan week and expose stored signal context`() {
        val weekStart = LocalDate.parse("2026-07-06").toEpochDay()
        val run = workout("planned", weekStart + 1, generatedDistance = 5_000, currentDistance = 5_000)
        val base = planSlice(workouts = listOf(run))
        val plan = base.copy(
            plan = base.plan.copy(
                startEpochDay = weekStart,
                endEpochDay = weekStart + 27,
                riskAssessment = "moderate",
            ),
            weeks = listOf(
                PlanWeekEntity("week", "plan", 1, weekStart, 5_000),
            ),
            summaryWarnings = listOf(
                PlanSummaryWarningEntity(
                    warningId = "warning",
                    planId = "plan",
                    ordinal = 0,
                    message = "Leave more recovery time between planned runs.",
                ),
            ),
        )
        val extra = activity(
            "extra",
            "accepted",
            null,
            4_000,
            1_500,
            "2026-07-08T12:00:00Z",
            averageHeartRate = 152,
            pain = true,
        ).let { row ->
            row.copy(
                activity = row.activity.copy(extraPlanImpactConfirmed = true),
                consequence = ActivityConsequenceEntity(
                    activityId = "extra",
                    classification = "pain_reported",
                    distanceDifferenceMeters = null,
                    durationDifferenceSeconds = null,
                    actualLoadMeters = 4_000,
                    assessment = "unsafe",
                    recommendedDecision = "next_rest",
                    resolvedAtEpochMillis = null,
                    risk = "unsafe",
                    actualLoadDurationSeconds = 1_500,
                ),
            )
        }

        val mapped = LocalSurfaceMappers.stats(
            LocalStatsLedgerSlice(
                plans = listOf(plan),
                activities = listOf(extra),
                timeZone = "UTC",
                todayEpochDay = LocalDate.parse("2026-07-10").toEpochDay(),
                profile = profile().copy(currentPain = true),
            ),
        )

        val week = mapped.weeks.single()
        assertEquals(4_000, week.actual.distanceMeters)
        assertEquals(1, week.completedRuns)
        assertEquals(1, week.painFlags)
        assertEquals(375.0, week.weightedPaceSecondsPerKilometre!!, 0.001)
        assertEquals("unsafe", mapped.currentSignal?.risk)
        assertEquals("activity", mapped.currentSignal?.source)
        assertEquals("Pain is present now", mapped.currentSignal?.healthNotice?.heading)
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
    fun `history retains adjustment effects and places accepted extra work in its plan week`() {
        val weekStart = LocalDate.parse("2026-07-06").toEpochDay()
        val run = workout("run", weekStart + 1)
        val base = planSlice(workouts = listOf(run))
        val plan = base.copy(
            plan = base.plan.copy(startEpochDay = weekStart, endEpochDay = weekStart + 6),
            weeks = listOf(PlanWeekEntity("week", "plan", 1, weekStart, 5_000)),
            historyAdjustments = listOf(
                HistoryAdjustmentRow(
                    planId = "plan",
                    adjustmentId = "adjustment",
                    adjustmentType = "edit",
                    adjustmentState = "applied",
                    triggerKind = null,
                    createdAtEpochMillis = 50,
                    decisionId = "decision",
                    decisionType = "edit",
                    reversalReason = "Changed my mind",
                    reversedAtEpochMillis = 70,
                    effectId = "effect",
                    newScheduledEpochDay = weekStart + 2,
                    newWorkoutType = "easy",
                    newPrescriptionKind = "distance",
                    newStatus = "planned",
                    newDistanceMeters = 4_500,
                    newDurationSeconds = null,
                    newTombstonedAtEpochMillis = null,
                    newReason = "Moved around work",
                ),
            ),
        )
        val extra = activity(
            "extra-in-week",
            "accepted",
            null,
            2_000,
            800,
            "2026-07-09T12:00:00Z",
        ).let { it.copy(activity = it.activity.copy(extraPlanImpactConfirmed = true)) }

        val mapped = LocalSurfaceMappers.history(
            LocalHistoryLedgerSlice(
                plans = listOf(plan),
                activities = listOf(extra),
                hasMorePlans = false,
                hasMoreActivities = false,
                timeZone = "UTC",
            ),
        )

        val historyPlan = mapped.plans.single()
        assertEquals("effect", historyPlan.adjustments.single().id)
        assertEquals(70L, historyPlan.adjustments.single().reversedAtEpochMillis)
        assertEquals(2_000, historyPlan.weeks.single().actual.distanceMeters)
        assertEquals("extra-in-week", historyPlan.weeks.single().extraActivities.single().activityId)
        assertTrue(mapped.unlinkedActivities.isEmpty())
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

    @Test
    fun `calendar exposes stored workout consequence with source version options and phase timing`() {
        val day = LocalDate.parse("2026-07-30").toEpochDay()
        val run = workout("run", day)
        val feedback = WorkoutFeedbackEntity(
            feedbackId = "feedback",
            workoutId = run.workoutId,
            completionState = "shortened",
            feltHard = true,
            pain = false,
            notes = null,
            recordedAtEpochMillis = 123,
            completedDistanceMeters = 4_000,
            completedDurationSeconds = 1_500,
        )
        val plan = planSlice(workouts = listOf(run)).copy(
            feedback = listOf(feedback),
            workoutConsequences = listOf(
                WorkoutFeedbackConsequenceEntity(
                    feedbackId = feedback.feedbackId,
                    classification = "under",
                    distanceDifferenceMeters = -1_000,
                    durationDifferenceSeconds = null,
                    currentWeekLoadMeters = 10_000,
                    projectedWeekLoadMeters = 9_000,
                    assessment = "low",
                    recoveryConflictCount = 0,
                    recommendedDecision = "keep",
                    nextWorkoutAction = "keep",
                    requiresExplicitConfirmation = false,
                    appliedDecision = "keep",
                    comparisonStatus = "ready",
                ),
            ),
            workoutConsequenceOptions = listOf(
                WorkoutFeedbackConsequenceOptionEntity(feedback.feedbackId, "keep"),
                WorkoutFeedbackConsequenceOptionEntity(feedback.feedbackId, "reduce"),
            ),
        )
        val phase = LocalPhaseReviewReadModel(
            planId = "plan",
            phase = LocalPlanPhase.FOUNDATION,
            goalKind = "race",
            phaseEndEpochDay = day,
            ready = true,
            recommendedTransition = "confirm_race_baseline",
        )

        val mapped = LocalSurfaceMappers.calendar(
            LocalCalendarLedgerSlice(
                fromEpochDay = day,
                throughEpochDay = day,
                timeZone = "America/Halifax",
                pendingReviewCount = 0,
                plans = listOf(plan),
                activities = emptyList(),
                todayEpochDay = day,
                phaseReview = phase,
            ),
        )

        val consequence = mapped.days.single().workouts.single().consequence
        assertEquals("WorkoutFeedback", consequence?.sourceKind)
        assertEquals("feedback", consequence?.sourceId)
        assertEquals("123", consequence?.sourceVersion)
        assertEquals(listOf("keep", "reduce"), consequence?.options)
        assertEquals("keep", consequence?.recommendedDecision)
        assertEquals("keep", consequence?.appliedDecision)
        assertEquals("America/Halifax", mapped.timeZone)
        assertEquals(day, mapped.todayEpochDay)
        assertEquals(phase, mapped.phaseReview)
    }

    @Test
    fun `inbox preserves review boundary while exposing link and correction choices`() {
        val review = activity("review", "review", null, 5_000, 1_800, "2026-07-30T12:00:00Z").copy(
            consequence = ActivityConsequenceEntity(
                activityId = "review",
                classification = "extra",
                distanceDifferenceMeters = 1_000,
                durationDifferenceSeconds = null,
                actualLoadMeters = 5_000,
                assessment = "low",
                recommendedDecision = "keep",
                resolvedAtEpochMillis = null,
            ),
            consequenceOptions = listOf(ActivityConsequenceOptionEntity("review", "keep")),
        )
        val candidate = workout("candidate", LocalDate.parse("2026-07-31").toEpochDay())
        val pending = LocalHealthConnectPendingReadModel(
            mappingId = "mapping",
            provider = "health_connect",
            externalRecordId = "record",
            state = "pending_correction",
            current = LocalActivitySummaryReadModel("review", 1, LocalLoadReadModel(5_000, 1_800)),
            proposed = LocalActivitySummaryReadModel("mapping", 2, LocalLoadReadModel(5_100, 1_810)),
        )

        val mapped = LocalSurfaceMappers.inbox(
            LocalInboxLedgerSlice(
                reviewCount = 1,
                hasMore = false,
                activities = listOf(review),
                linkCandidates = listOf(candidate),
                pendingHealthConnect = listOf(pending),
                timeZone = "UTC",
                todayEpochDay = 20,
            ),
        )

        assertNull(mapped.activities.single().consequence)
        assertEquals("candidate", mapped.linkCandidates.single().workoutId)
        assertEquals("mapping", mapped.pendingHealthConnect.single().mappingId)
        assertEquals("record", mapped.pendingHealthConnect.single().externalRecordId)

        val accepted = review.copy(
            activity = review.activity.copy(reviewState = "accepted", acceptedAtEpochMillis = 3),
            feedback = ActivityFeedbackEntity("feedback", "review", true, false, null, 44),
        )
        val acceptedConsequence = LocalSurfaceMappers.activityEvidence(accepted).consequence
        assertEquals("Activity", acceptedConsequence?.sourceKind)
        assertEquals("review", acceptedConsequence?.sourceId)
        assertEquals("44", acceptedConsequence?.sourceVersion)
        assertEquals(5_000, acceptedConsequence?.actualLoad?.distanceMeters)
    }

    @Test
    fun `inbox holds a possible Health Connect duplicate at the comparison boundary`() {
        val healthConnectReview = activity(
            "health-connect-review",
            "review",
            null,
            5_000,
            1_800,
            "2026-07-30T12:00:00Z",
        )
        val duplicate = LocalHealthConnectPendingReadModel(
            mappingId = "mapping",
            provider = "health_connect",
            externalRecordId = "record",
            state = "possible_duplicate",
            current = LocalActivitySummaryReadModel(
                "health-connect-review",
                1,
                LocalLoadReadModel(5_000, 1_800),
            ),
            proposed = LocalActivitySummaryReadModel(
                "existing-run",
                2,
                LocalLoadReadModel(5_020, 1_810),
            ),
        )

        val mapped = LocalSurfaceMappers.inbox(
            LocalInboxLedgerSlice(
                reviewCount = 1,
                hasMore = false,
                activities = listOf(healthConnectReview),
                pendingHealthConnect = listOf(duplicate),
            ),
        )

        assertTrue(mapped.activities.isEmpty())
        assertEquals("possible_duplicate", mapped.pendingHealthConnect.single().state)
        assertEquals("existing-run", mapped.pendingHealthConnect.single().proposed?.activityId)
    }

    @Test
    fun `inbox retains only actionable accepted extras alongside review activity`() {
        fun acceptedExtra(
            id: String,
            occurredAt: String,
            appliedDecision: String? = null,
            resolvedAtEpochMillis: Long? = null,
        ): LocalActivityLedgerSlice {
            val base = activity(id, "accepted", null, 2_000, 900, occurredAt)
            return base.copy(
                activity = base.activity.copy(extraPlanImpactConfirmed = true),
                consequence = ActivityConsequenceEntity(
                    activityId = id,
                    classification = "extra_activity",
                    distanceDifferenceMeters = 1_000,
                    durationDifferenceSeconds = null,
                    actualLoadMeters = 2_000,
                    assessment = "conservative",
                    recommendedDecision = "keep_plan",
                    resolvedAtEpochMillis = resolvedAtEpochMillis,
                    appliedDecision = appliedDecision,
                    planChangeAvailable = true,
                ),
            )
        }

        val mapped = LocalSurfaceMappers.inbox(
            LocalInboxLedgerSlice(
                reviewCount = 1,
                hasMore = false,
                activities = listOf(
                    activity("review", "review", null, 2_000, 900, "2026-07-30T09:00:00Z"),
                    acceptedExtra("pending-extra", "2026-07-30T11:00:00Z"),
                    acceptedExtra("applied-extra", "2026-07-30T12:00:00Z", appliedDecision = "keep_plan"),
                    acceptedExtra("resolved-extra", "2026-07-30T13:00:00Z", resolvedAtEpochMillis = 1),
                ),
            ),
        )

        assertEquals(1, mapped.reviewCount)
        assertEquals(listOf("pending-extra", "review"), mapped.activities.map { it.activityId })
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
        feltHard: Boolean = false,
        pain: Boolean = false,
    ): LocalActivityLedgerSlice {
        val activity = ActivityEntity(
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
        )
        return LocalActivityLedgerSlice(
            activity = activity,
            feedback = if (feltHard || pain) {
                ActivityFeedbackEntity(
                    feedbackId = "feedback-$id",
                    activityId = id,
                    feltHard = feltHard,
                    pain = pain,
                    notes = null,
                    recordedAtEpochMillis = 2,
                )
            } else {
                null
            },
        )
    }

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
