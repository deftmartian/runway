package dev.deftmartian.runway.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

object LocalSurfaceMappers {
    fun activityEvidence(row: LocalActivityLedgerSlice): LocalActivityReadModel = activity(row)

    fun calendar(slice: LocalCalendarLedgerSlice): LocalCalendarReadModel {
        require(slice.throughEpochDay >= slice.fromEpochDay)
        val accepted = slice.activities.filter { it.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
        val activityByWorkout = accepted
            .filter { it.activity.linkedWorkoutId != null }
            .associateBy { requireNotNull(it.activity.linkedWorkoutId) }
        val activePlans = slice.plans.filter { it.plan.state == "active" }
        val feedbackByWorkout = activePlans.flatMap { it.feedback }.associateBy(WorkoutFeedbackEntity::workoutId)
        val workoutConsequences = activePlans.flatMap { it.workoutConsequences }.associateBy(WorkoutFeedbackConsequenceEntity::feedbackId)
        val workoutOptions = activePlans.flatMap { it.workoutConsequenceOptions }.groupBy(WorkoutFeedbackConsequenceOptionEntity::feedbackId)
        val workouts = activePlans.flatMap { plan ->
            val weekOrdinals = plan.weeks.associate { it.weekId to it.ordinal }
            plan.workouts
                .filter { it.currentStatus != WORKOUT_STATE_TOMBSTONED }
                .filter { it.currentScheduledEpochDay in slice.fromEpochDay..slice.throughEpochDay }
                .map {
                    workout(
                        entity = it,
                        weekOrdinal = weekOrdinals[it.weekId] ?: 0,
                        actual = activityByWorkout[it.workoutId],
                        blocks = plan.workoutBlocks,
                        segments = plan.workoutSegments,
                        consequence = feedbackByWorkout[it.workoutId]?.let { feedback ->
                            workoutConsequences[feedback.feedbackId]?.let { stored ->
                                workoutConsequence(feedback, stored, workoutOptions[feedback.feedbackId].orEmpty())
                            }
                        },
                        adjustment = plan.undoableWorkoutAdjustments.firstOrNull { adjustment ->
                            adjustment.workoutId == it.workoutId
                        },
                        planPhase = planPhase(plan.plan.phaseType),
                    )
                }
        }
        val visibleWorkoutIds = workouts.mapTo(mutableSetOf(), LocalWorkoutReadModel::workoutId)
        val feedback = activePlans
            .flatMap { it.feedback }
            .filter { it.workoutId in visibleWorkoutIds }
            .map {
                LocalWorkoutFeedbackReadModel(
                    feedbackId = it.feedbackId,
                    workoutId = it.workoutId,
                    completionState = it.completionState,
                    completedDistanceMeters = it.completedDistanceMeters,
                    completedDurationSeconds = it.completedDurationSeconds,
                    feltHard = it.feltHard,
                    pain = it.pain,
                    recordedAtEpochMillis = it.recordedAtEpochMillis,
                    sourceActivityId = it.sourceActivityId,
                )
            }
        val unlinkedByDay = accepted
            .filter { it.activity.linkedWorkoutId == null }
            .groupBy { localEpochDay(it.activity.occurredAtEpochMillis, slice.timeZone) }
        val workoutsByDay = workouts.groupBy(LocalWorkoutReadModel::scheduledEpochDay)
        val days = (slice.fromEpochDay..slice.throughEpochDay).mapNotNull { epochDay ->
            val dayWorkouts = workoutsByDay[epochDay].orEmpty()
            val dayActivities = unlinkedByDay[epochDay].orEmpty().map(::activity)
            if (dayWorkouts.isEmpty() && dayActivities.isEmpty()) null
            else LocalCalendarDayReadModel(epochDay, dayWorkouts, dayActivities)
        }
        val nextWorkout = slice.nextWorkout?.let { entity ->
            val plan = activePlans.firstOrNull { it.plan.planId == entity.planId }
            val weekOrdinal = plan?.weeks
                ?.firstOrNull { it.weekId == entity.weekId }
                ?.ordinal
                ?: 0
            workout(
                entity = entity,
                weekOrdinal = weekOrdinal,
                actual = activityByWorkout[entity.workoutId],
                blocks = plan?.workoutBlocks.orEmpty(),
                segments = plan?.workoutSegments.orEmpty(),
                consequence = feedbackByWorkout[entity.workoutId]?.let { feedback ->
                    workoutConsequences[feedback.feedbackId]?.let { stored ->
                        workoutConsequence(feedback, stored, workoutOptions[feedback.feedbackId].orEmpty())
                    }
                },
                adjustment = plan?.undoableWorkoutAdjustments?.firstOrNull { adjustment ->
                    adjustment.workoutId == entity.workoutId
                },
                planPhase = plan?.plan?.phaseType?.let(::planPhase) ?: LocalPlanPhase.OTHER,
            )
        }
        val routineWeekGoals = routineWeekGoals(activePlans, slice)
        return LocalCalendarReadModel(
            fromEpochDay = slice.fromEpochDay,
            throughEpochDay = slice.throughEpochDay,
            activePlanId = activePlans.singleOrNull()?.plan?.planId,
            profileExists = slice.profileExists,
            pendingDecisionCount = slice.pendingDecisionCount,
            pendingDecisionCountIsExact = slice.pendingDecisionCountIsExact,
            hasMoreActivities = slice.hasMoreActivities,
            days = days,
            feedback = feedback,
            timeZone = slice.timeZone,
            todayEpochDay = slice.todayEpochDay,
            phaseReview = slice.phaseReview,
            nextWorkout = nextWorkout,
            activePlanPhase = activePlans.singleOrNull()?.plan?.phaseType?.let(::planPhase),
            routineWeekGoals = routineWeekGoals,
        )
    }

    private fun routineWeekGoals(
        activePlans: List<LocalPlanLedgerSlice>,
        slice: LocalCalendarLedgerSlice,
    ) = activePlans
        .filter { it.plan.phaseType == "routine" }
        .flatMap { plan ->
            plan.weeks
                .filter { week ->
                    week.startEpochDay <= slice.throughEpochDay &&
                        week.startEpochDay + 6 >= slice.fromEpochDay
                }
                .map { week -> routineWeekGoal(plan, week) }
        }

    private fun routineWeekGoal(
        plan: LocalPlanLedgerSlice,
        week: PlanWeekEntity,
    ) = LocalRoutineWeekGoalReadModel(
        weekId = week.weekId,
        startEpochDay = week.startEpochDay,
        targetDistanceMeters = week.generatedLoadMeters,
        targetDurationSeconds = week.generatedLoadDurationSeconds,
        scheduledRuns = plan.workouts.count { workout ->
            workout.weekId == week.weekId &&
                workout.currentWorkoutType != "rest" &&
                workout.tombstonedAtEpochMillis == null
        },
        runs = plan.workouts
            .filter { workout ->
                workout.weekId == week.weekId &&
                    workout.currentWorkoutType != "rest" &&
                    workout.tombstonedAtEpochMillis == null
            }
            .sortedWith(compareBy(WorkoutEntity::currentScheduledEpochDay, WorkoutEntity::position))
            .map { workout ->
                LocalRoutineRunTargetReadModel(
                    workoutId = workout.workoutId,
                    scheduledEpochDay = workout.currentScheduledEpochDay,
                    targetDistanceMeters = workout.currentDistanceMeters,
                    targetDurationSeconds = workout.currentDurationSeconds,
                )
            },
    )

    fun inbox(slice: LocalInboxLedgerSlice): LocalInboxReadModel = LocalInboxReadModel(
        reviewCount = slice.reviewCount,
        reviewCountIsExact = slice.reviewCountIsExact,
        hasMore = slice.hasMore,
        activities = slice.activities
            .filter { row ->
                val reviewActivity =
                    row.activity.reviewState == "review" &&
                        slice.pendingHealthConnect.none { pending ->
                            pending.state == "possible_duplicate" &&
                                pending.current?.activityId == row.activity.activityId
                        }
                val actionableAcceptedExtra =
                    row.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED &&
                        row.activity.linkedWorkoutId == null &&
                        row.activity.extraPlanImpactConfirmed &&
                        row.consequence?.let { consequence ->
                            consequence.planChangeAvailable &&
                                consequence.appliedDecision == null &&
                                consequence.resolvedAtEpochMillis == null
                        } == true
                val actionableLinkedActivity =
                    row.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED &&
                        row.activity.linkedWorkoutId != null &&
                        row.linkedWorkoutConsequence?.let { consequence ->
                            consequence.planChangeAvailable && consequence.appliedDecision == null
                        } == true
                reviewActivity || actionableAcceptedExtra || actionableLinkedActivity
            }
            .sortedByDescending { it.activity.occurredAtEpochMillis }
            .map(::activity),
        pendingWorkoutFeedback = slice.pendingWorkoutFeedback.map { row ->
            LocalPendingWorkoutFeedbackReadModel(
                feedbackId = row.feedback.feedbackId,
                workoutId = row.feedback.workoutId,
                scheduledEpochDay = row.workout.currentScheduledEpochDay,
                workoutPurpose = row.workout.currentPurpose,
                recordedAtEpochMillis = row.feedback.recordedAtEpochMillis,
                completedDistanceMeters = row.feedback.completedDistanceMeters,
                completedDurationSeconds = row.feedback.completedDurationSeconds,
                feltHard = row.feedback.feltHard,
                pain = row.feedback.pain,
                completionState = row.feedback.completionState,
                consequence = workoutConsequence(
                    row.feedback,
                    row.consequence,
                    row.consequenceOptions,
                ),
            )
        },
        linkCandidates = slice.linkCandidates.map {
            LocalWorkoutLinkCandidateReadModel(
                it.planId,
                it.workoutId,
                it.currentScheduledEpochDay,
                prescription(it, generated = false, slice.linkCandidateBlocks, slice.linkCandidateSegments),
            )
        },
        pendingHealthConnect = slice.pendingHealthConnect,
        timeZone = slice.timeZone,
        todayEpochDay = slice.todayEpochDay,
        phaseReview = slice.phaseReview,
        nextPage = slice.nextPage,
    )

    fun stats(slice: LocalStatsLedgerSlice): LocalStatsReadModel {
        assertCompleteWeekActualContext(
            plans = slice.plans,
            aggregates = slice.weekActualAggregates,
            activitiesContainCompleteWeekContext = slice.activitiesContainCompleteWeekContext,
        )
        val accepted = slice.activities.filter { it.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
        val acceptedByWorkout = accepted
            .filter { it.activity.linkedWorkoutId != null }
            .groupBy { requireNotNull(it.activity.linkedWorkoutId) }
        val zone = runCatching { ZoneId.of(slice.timeZone) }.getOrDefault(ZoneId.systemDefault())
        val unlinkedAssignments = assignUnlinkedToPlanWeeks(accepted, slice.plans, zone)
        val unlinkedByPlanWeek = unlinkedAssignments
            .groupBy(
                keySelector = { it.plan.plan.planId to it.week.weekId },
                valueTransform = AcceptedWeekAssignment::activity,
            )
        val exactWeekActuals = slice.weekActualAggregates.associateBy {
            it.planId to it.weekId
        }
        val weeks = slice.plans.flatMap { plan ->
            val feedbackByWorkout = plan.feedback.associateBy(WorkoutFeedbackEntity::workoutId)
            plan.weeks.map { week ->
                val workouts = plan.workouts.filter {
                    it.weekId == week.weekId && it.currentStatus != WORKOUT_STATE_TOMBSTONED
                }
                val acceptedRows = workouts.flatMap { workout ->
                    acceptedByWorkout[workout.workoutId].orEmpty()
                } + unlinkedByPlanWeek[plan.plan.planId to week.weekId].orEmpty()
                val unlinkedRows = unlinkedByPlanWeek[plan.plan.planId to week.weekId].orEmpty()
                val directFeedback = workouts.mapNotNull { feedbackByWorkout[it.workoutId] }
                    .filter { it.sourceActivityId == null }
                val actualLoads =
                    acceptedRows.map { it.activity.distanceMeters to it.activity.durationSeconds } +
                        directFeedback.map { it.completedDistanceMeters to it.completedDurationSeconds }
                val paceLoads = actualLoads.filter { (distance, duration) ->
                    (distance ?: 0) > 0 && (duration ?: 0) > 0
                }
                val heartRateRows = acceptedRows.map(LocalActivityLedgerSlice::activity)
                    .filter { it.averageHeartRateBpm != null && (it.durationSeconds ?: 0) > 0 }
                val acceptedFeedback = acceptedRows.mapNotNull(LocalActivityLedgerSlice::feedback)
                val heartRateDurationSeconds = heartRateRows.sumOf { requireNotNull(it.durationSeconds) }
                val exactActual = exactWeekActuals[plan.plan.planId to week.weekId]
                val completedWorkoutIds = completedWorkoutIds(
                    workouts,
                    acceptedByWorkout,
                    feedbackByWorkout,
                )
                val plannedRunsRecorded = workouts.count {
                    it.currentWorkoutType != "rest" && it.workoutId in completedWorkoutIds
                }
                val extraRuns = exactActual?.unlinkedRuns?.toSurfaceInt("unlinked week run count")
                    ?: unlinkedRows.size
                LocalWeekStatsReadModel(
                    planId = plan.plan.planId,
                    planState = planState(plan.plan.state),
                    phase = planPhase(plan.plan.phaseType),
                    weekOrdinal = week.ordinal,
                    startEpochDay = week.startEpochDay,
                    generated = storedWeekLoad(plan.plan, week, workouts, generated = true),
                    current = storedWeekLoad(plan.plan, week, workouts, generated = false),
                    actual = weekActualLoad(exactActual, actualLoads),
                    plannedRuns = workouts.count { it.currentWorkoutType != "rest" },
                    completedRuns = plannedRunsRecorded + extraRuns,
                    missedRuns = missedRuns(workouts, completedWorkoutIds, slice.todayEpochDay),
                    painFlags = weekPainFlags(exactActual, acceptedFeedback, directFeedback),
                    hardFlags = weekHardFlags(exactActual, acceptedFeedback, directFeedback),
                    weightedPaceSecondsPerKilometre = weightedPace(exactActual, paceLoads),
                    durationWeightedHeartRateBpm = weightedHeartRate(
                        exactActual,
                        heartRateRows,
                        heartRateDurationSeconds,
                    ),
                    skippedRuns = skippedRuns(workouts, completedWorkoutIds),
                    plannedRunsRecorded = plannedRunsRecorded,
                    extraRuns = extraRuns,
                )
            }
        }
        val planByWorkout = slice.plans.flatMap { plan ->
            plan.workouts.map { it.workoutId to planState(plan.plan.state) }
        }.toMap()
        // A direct feedback result has no ActivityEntity, while linked activity feedback points
        // back to its ActivityEntity. Combine the former here and exclude the latter so Stats
        // reflects every completed fact exactly once.
        val acceptedEvidence = accepted.map { row ->
            RecordedStatsEvidence(
                provenance = row.activity.linkedWorkoutId
                    ?.let { workoutId -> planByWorkout[workoutId]?.let(::provenance) }
                    ?: if (row.activity.linkedWorkoutId == null) {
                        LocalPlanProvenance.UNLINKED
                    } else {
                        LocalPlanProvenance.OTHER
                    },
                distanceMeters = row.activity.distanceMeters,
                durationSeconds = row.activity.durationSeconds,
            )
        }
        val directFeedbackEvidence = slice.plans.flatMap { plan ->
            plan.feedback
                .asSequence()
                .filter { it.sourceActivityId == null && it.completionState in COMPLETED_FEEDBACK_STATES }
                .map { feedback ->
                    RecordedStatsEvidence(
                        provenance = provenance(planState(plan.plan.state)),
                        distanceMeters = feedback.completedDistanceMeters,
                        durationSeconds = feedback.completedDurationSeconds,
                    )
                }
                .toList()
        }
        val recordedEvidence = acceptedEvidence + directFeedbackEvidence
        val totals = if (slice.recordedAggregates.isNotEmpty()) {
            slice.recordedAggregates.map { row ->
                LocalRecordedTotalsReadModel(
                    provenance = row.provenance,
                    runs = row.runs.toSurfaceInt("recorded run count"),
                    distanceMeters = row.distanceMeters.toSurfaceInt("recorded distance"),
                    durationSeconds = row.durationSeconds.toSurfaceInt("recorded duration"),
                )
            }
        } else {
            recordedEvidence
                .groupBy(RecordedStatsEvidence::provenance)
                .map { (origin, rows) ->
                    LocalRecordedTotalsReadModel(
                        provenance = origin,
                        runs = rows.size,
                        distanceMeters = rows.sumOf { it.distanceMeters ?: 0 },
                        durationSeconds = rows.sumOf { it.durationSeconds ?: 0 },
                    )
                }
        }.sortedBy { it.provenance.ordinal }
        val paired = recordedEvidence
            .filter { (it.distanceMeters ?: 0) > 0 && (it.durationSeconds ?: 0) > 0 }
        val heartRate = accepted.map { it.activity }
            .filter { it.averageHeartRateBpm != null && (it.durationSeconds ?: 0) > 0 }
        val exactRuns = slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::runs)
        val exactDistance = slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::distanceMeters)
        val exactDuration = slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::durationSeconds)
        val exactPairedDistance =
            slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::pairedDistanceMeters)
        val exactPairedDuration =
            slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::pairedDurationSeconds)
        val exactHeartRateDuration =
            slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::heartRateDurationSeconds)
        val exactHeartRateBeats =
            slice.recordedAggregates.sumOf(LocalRecordedAggregateLedgerRow::heartRateBeatsSeconds)
        return LocalStatsReadModel(
            weeks = weeks.sortedWith(compareBy(LocalWeekStatsReadModel::startEpochDay, LocalWeekStatsReadModel::weekOrdinal)),
            profileExists = slice.profileExists,
            recordedTotals = totals,
            totalRuns = if (slice.recordedAggregates.isNotEmpty()) {
                exactRuns.toSurfaceInt("total run count")
            } else {
                recordedEvidence.size
            },
            totalDistanceMeters = if (slice.recordedAggregates.isNotEmpty()) {
                exactDistance.toSurfaceInt("total distance")
            } else {
                recordedEvidence.sumOf { it.distanceMeters ?: 0 }
            },
            totalDurationSeconds = if (slice.recordedAggregates.isNotEmpty()) {
                exactDuration.toSurfaceInt("total duration")
            } else {
                recordedEvidence.sumOf { it.durationSeconds ?: 0 }
            },
            longestRunMeters = if (slice.recordedAggregates.isNotEmpty()) {
                slice.recordedAggregates.mapNotNull(LocalRecordedAggregateLedgerRow::longestRunMeters)
                    .maxOrNull()
            } else {
                recordedEvidence.mapNotNull(RecordedStatsEvidence::distanceMeters).maxOrNull()
            },
            weightedPaceSecondsPerKilometre = if (slice.recordedAggregates.isNotEmpty()) {
                if (exactPairedDistance > 0 && exactPairedDuration > 0) {
                    exactPairedDuration.toDouble() * 1_000 / exactPairedDistance
                } else {
                    null
                }
            } else {
                paired.takeIf { it.isNotEmpty() }?.let { rows ->
                    rows.sumOf { requireNotNull(it.durationSeconds) }.toDouble() * 1_000 /
                        rows.sumOf { requireNotNull(it.distanceMeters) }
                }
            },
            durationWeightedHeartRateBpm = if (slice.recordedAggregates.isNotEmpty()) {
                if (exactHeartRateDuration > 0) {
                    (exactHeartRateBeats.toDouble() / exactHeartRateDuration).roundToInt()
                } else {
                    null
                }
            } else {
                heartRate.takeIf { it.isNotEmpty() }?.let { rows ->
                    val seconds = rows.sumOf { requireNotNull(it.durationSeconds) }
                    (
                        rows.sumOf {
                            requireNotNull(it.averageHeartRateBpm).toLong() *
                                requireNotNull(it.durationSeconds)
                        }.toDouble() / seconds
                        ).roundToInt()
                }
            },
            isComplete = slice.recordedAggregates.isNotEmpty() ||
                (!slice.hasMorePlans && !slice.hasMoreActivities),
            currentSignal = slice.plans
                .firstOrNull { it.plan.state == "active" }
                ?.let {
                    currentSignal(
                        plan = it,
                        accepted = accepted,
                        profile = slice.profile,
                        zone = zone,
                        todayEpochDay = slice.todayEpochDay,
                    )
                },
            timeZone = slice.timeZone,
            todayEpochDay = slice.todayEpochDay,
            phaseReview = slice.phaseReview,
            activeSessionsPerWeek = slice.plans
                .firstOrNull { it.plan.state == "active" }
                ?.plan
                ?.summarySessionsPerWeek,
        )
    }

    private fun completedWorkoutIds(
        workouts: List<WorkoutEntity>,
        acceptedByWorkout: Map<String, List<LocalActivityLedgerSlice>>,
        feedbackByWorkout: Map<String, WorkoutFeedbackEntity>,
    ): Set<String> = workouts.filter { workout ->
        workout.currentStatus in COMPLETED_FEEDBACK_STATES ||
            acceptedByWorkout.containsKey(workout.workoutId) ||
            feedbackByWorkout[workout.workoutId]?.completionState in COMPLETED_FEEDBACK_STATES
    }.mapTo(mutableSetOf(), WorkoutEntity::workoutId)

    private fun weekActualLoad(
        exact: LocalWeekActualAggregateLedgerRow?,
        fallback: List<Pair<Int?, Int?>>,
    ) = exact?.let {
        LocalLoadReadModel(
            it.distanceMeters.toSurfaceInt("week distance"),
            it.durationSeconds.toSurfaceInt("week duration"),
        )
    } ?: loads(fallback)

    private fun missedRuns(
        workouts: List<WorkoutEntity>,
        completedWorkoutIds: Set<String>,
        todayEpochDay: Long,
    ) = workouts.count {
        it.currentWorkoutType != "rest" &&
            it.workoutId !in completedWorkoutIds &&
            (it.currentStatus == "missed" ||
                (it.currentStatus == "planned" && it.currentScheduledEpochDay < todayEpochDay))
    }

    private fun skippedRuns(workouts: List<WorkoutEntity>, completedWorkoutIds: Set<String>) =
        workouts.count {
            it.currentWorkoutType != "rest" &&
                it.workoutId !in completedWorkoutIds &&
                it.currentStatus == "skipped"
        }

    private fun weekPainFlags(
        exact: LocalWeekActualAggregateLedgerRow?,
        accepted: List<ActivityFeedbackEntity>,
        direct: List<WorkoutFeedbackEntity>,
    ) = exact?.painFlags?.toSurfaceInt("week pain flag count")
        ?: accepted.count(ActivityFeedbackEntity::pain) + direct.count(WorkoutFeedbackEntity::pain)

    private fun weekHardFlags(
        exact: LocalWeekActualAggregateLedgerRow?,
        accepted: List<ActivityFeedbackEntity>,
        direct: List<WorkoutFeedbackEntity>,
    ) = exact?.hardFlags?.toSurfaceInt("week hard flag count")
        ?: accepted.count(ActivityFeedbackEntity::feltHard) + direct.count(WorkoutFeedbackEntity::feltHard)

    private fun weightedPace(
        exact: LocalWeekActualAggregateLedgerRow?,
        fallback: List<Pair<Int?, Int?>>,
    ): Double? {
        if (exact != null) {
            return if (exact.pairedDistanceMeters > 0 && exact.pairedDurationSeconds > 0) {
                exact.pairedDurationSeconds.toDouble() * 1_000 / exact.pairedDistanceMeters
            } else null
        }
        if (fallback.isEmpty()) return null
        return fallback.sumOf { requireNotNull(it.second) }.toDouble() * 1_000 /
            fallback.sumOf { requireNotNull(it.first) }
    }

    private fun weightedHeartRate(
        exact: LocalWeekActualAggregateLedgerRow?,
        fallback: List<ActivityEntity>,
        fallbackDurationSeconds: Int,
    ): Int? {
        if (exact != null) {
            return exact.heartRateBeatsSeconds
                .takeIf { exact.heartRateDurationSeconds > 0 }
                ?.let { (it.toDouble() / exact.heartRateDurationSeconds).roundToInt() }
        }
        if (fallback.isEmpty() || fallbackDurationSeconds <= 0) return null
        val beatsSeconds = fallback.sumOf {
            requireNotNull(it.averageHeartRateBpm).toLong() * requireNotNull(it.durationSeconds)
        }
        return (beatsSeconds.toDouble() / fallbackDurationSeconds).roundToInt()
    }

    private data class RecordedStatsEvidence(
        val provenance: LocalPlanProvenance,
        val distanceMeters: Int?,
        val durationSeconds: Int?,
    )

    fun history(slice: LocalHistoryLedgerSlice): LocalHistoryReadModel {
        assertCompleteWeekActualContext(
            plans = slice.plans,
            aggregates = slice.weekActualAggregates,
            activitiesContainCompleteWeekContext = slice.activitiesContainCompleteWeekContext,
        )
        val accepted = slice.activities.filter { it.activity.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
        val zone = runCatching { ZoneId.of(slice.timeZone) }.getOrDefault(ZoneId.systemDefault())
        val unlinkedAssignments = assignUnlinkedToPlanWeeks(accepted, slice.plans, zone)
        val unlinkedByPlanWeek = unlinkedAssignments.groupBy(
            keySelector = { it.plan.plan.planId to it.week.weekId },
            valueTransform = AcceptedWeekAssignment::activity,
        )
        val assignedUnlinkedActivityIds =
            unlinkedAssignments.mapTo(mutableSetOf()) { it.activity.activity.activityId }
        val exactWeekActuals = slice.weekActualAggregates.associateBy {
            it.planId to it.weekId
        }
        val plans = slice.plans.map { plan ->
            val workoutIds = plan.workouts.mapTo(mutableSetOf(), WorkoutEntity::workoutId)
            val linked = accepted.filter { it.activity.linkedWorkoutId in workoutIds }
            val direct = plan.feedback.filter { it.sourceActivityId == null }
            val feedbackByWorkout = plan.feedback.associateBy(WorkoutFeedbackEntity::workoutId)
            val consequencesByFeedback = plan.workoutConsequences.associateBy(WorkoutFeedbackConsequenceEntity::feedbackId)
            val optionsByFeedback = plan.workoutConsequenceOptions.groupBy(WorkoutFeedbackConsequenceOptionEntity::feedbackId)
            val linkedByWorkout = linked
                .groupBy { requireNotNull(it.activity.linkedWorkoutId) }
            val historyWeeks = plan.weeks.map { week ->
                val weekWorkouts = plan.workouts
                    .filter { it.weekId == week.weekId }
                    .sortedBy { it.position }
                val currentWeekWorkouts = weekWorkouts.filter {
                    it.currentStatus != WORKOUT_STATE_TOMBSTONED
                }
                val extraActivities =
                    unlinkedByPlanWeek[plan.plan.planId to week.weekId].orEmpty()
                val exactActual = exactWeekActuals[plan.plan.planId to week.weekId]
                val workoutRows = weekWorkouts.map { workout ->
                    val linkedResult = linkedByWorkout[workout.workoutId]?.singleOrNull()
                    val directResult = feedbackByWorkout[workout.workoutId]
                        ?.takeIf { it.sourceActivityId == null }
                    LocalHistoryWorkoutReadModel(
                        workoutId = workout.workoutId,
                        status = workout.currentStatus,
                        generatedScheduledEpochDay = workout.generatedScheduledEpochDay,
                        currentScheduledEpochDay = workout.currentScheduledEpochDay,
                        generated = prescription(
                            workout,
                            generated = true,
                            blocks = plan.workoutBlocks,
                            segments = plan.workoutSegments,
                        ),
                        current = prescription(
                            workout,
                            generated = false,
                            blocks = plan.workoutBlocks,
                            segments = plan.workoutSegments,
                        ),
                        isRemoved = workout.currentStatus == WORKOUT_STATE_TOMBSTONED,
                        result = linkedResult?.let {
                            LocalRecordedResultReadModel(
                                source = it.activity.source,
                                distanceMeters = it.activity.distanceMeters,
                                durationSeconds = it.activity.durationSeconds,
                                feltHard = it.feedback?.feltHard,
                                pain = it.feedback?.pain,
                            )
                        } ?: directResult?.let {
                            LocalRecordedResultReadModel(
                                source = "manual_feedback",
                                distanceMeters = it.completedDistanceMeters,
                                durationSeconds = it.completedDurationSeconds,
                                feltHard = it.feltHard,
                                pain = it.pain,
                            )
                        },
                        consequence = feedbackByWorkout[workout.workoutId]?.let { feedback ->
                            consequencesByFeedback[feedback.feedbackId]?.let { stored ->
                                workoutConsequence(feedback, stored, optionsByFeedback[feedback.feedbackId].orEmpty())
                            }
                        },
                    )
                }
                LocalHistoryWeekReadModel(
                    weekId = week.weekId,
                    ordinal = week.ordinal,
                    startEpochDay = week.startEpochDay,
                    generated = LocalLoadReadModel(
                        week.generatedLoadMeters,
                        week.generatedLoadDurationSeconds,
                    ),
                    current = storedWeekLoad(
                        plan.plan,
                        week,
                        currentWeekWorkouts,
                        generated = false,
                    ),
                    actual = exactActual?.let {
                        LocalLoadReadModel(
                            it.distanceMeters.toSurfaceInt("history week distance"),
                            it.durationSeconds.toSurfaceInt("history week duration"),
                        )
                    } ?: loads(
                        workoutRows.mapNotNull { workout ->
                            workout.result?.let {
                                it.distanceMeters to it.durationSeconds
                            }
                        } + extraActivities.map {
                            it.activity.distanceMeters to it.activity.durationSeconds
                        },
                    ),
                    riskAssessment = week.riskAssessment,
                    isDownWeek = week.isDownWeek,
                    isTaperWeek = week.isTaperWeek,
                    workouts = workoutRows,
                    extraActivities = extraActivities.map(::activity),
                    extraActivityContextIsComplete =
                        exactActual == null ||
                            exactActual.unlinkedRuns == extraActivities.size.toLong(),
                )
            }
            val planExtraActivities = unlinkedAssignments
                .filter { it.plan.plan.planId == plan.plan.planId }
                .map(AcceptedWeekAssignment::activity)
            val exactPlanActuals = plan.weeks.mapNotNull {
                exactWeekActuals[plan.plan.planId to it.weekId]
            }
            val completedWorkoutIds = historyCompletedWorkoutIds(plan.workouts, linked, direct)
            val historyCutoffEpochDay = historyCutoffEpochDay(plan.plan, slice)
            val plannedRunsRecorded = plan.workouts.count {
                it.currentWorkoutType != "rest" && it.workoutId in completedWorkoutIds
            }
            val extraRuns = exactPlanActuals
                .sumOf(LocalWeekActualAggregateLedgerRow::unlinkedRuns)
                .takeIf { exactPlanActuals.isNotEmpty() }
                ?.toSurfaceInt("history plan unlinked run count")
                ?: planExtraActivities.size
            LocalPlanHistoryReadModel(
                planId = plan.plan.planId,
                goalId = plan.goal?.goalId ?: plan.plan.goalId,
                goalTitle = plan.goal?.title ?: "Training goal",
                state = planState(plan.plan.state),
                phase = planPhase(plan.plan.phaseType),
                startEpochDay = plan.plan.startEpochDay,
                endEpochDay = plan.plan.endEpochDay,
                completedAtEpochMillis = plan.plan.completedAtEpochMillis,
                archivedAtEpochMillis = plan.plan.archivedAtEpochMillis,
                plannedRuns = plan.workouts.count {
                    it.currentWorkoutType != "rest" && it.currentStatus != WORKOUT_STATE_TOMBSTONED
                },
                completedRuns = plannedRunsRecorded + extraRuns,
                actual = historyPlanActual(exactPlanActuals, linked, direct, planExtraActivities),
                lifecycle = plan.lifecycle.sortedByDescending { it.occurredAtEpochMillis }.map(::lifecycle),
                adjustments = historyAdjustments(plan.historyAdjustments),
                weeks = historyWeeks,
                missedRuns = historyMissedRuns(
                    plan.workouts, completedWorkoutIds, historyCutoffEpochDay,
                ),
                skippedRuns = historySkippedRuns(plan.workouts, completedWorkoutIds),
                painFlags = historyPainFlags(exactPlanActuals, linked, direct, planExtraActivities),
                sessionsPerWeek = plan.plan.summarySessionsPerWeek,
                plannedRunsRecorded = plannedRunsRecorded,
                extraRuns = extraRuns,
            )
        }
        return LocalHistoryReadModel(
            plans = plans.sortedByDescending {
                it.completedAtEpochMillis ?: it.archivedAtEpochMillis ?: it.startEpochDay
            },
            unlinkedActivities = accepted
                .filter {
                    it.activity.linkedWorkoutId == null &&
                        it.activity.activityId !in assignedUnlinkedActivityIds
                }
                .map(::activity),
            hasMorePlans = slice.hasMorePlans,
            hasMoreActivities = slice.hasMoreActivities,
            nextPlanOffset = slice.nextPlanOffset,
            nextActivityOffset = slice.nextActivityOffset,
            timeZone = slice.timeZone,
            todayEpochDay = slice.todayEpochDay,
            phaseReview = slice.phaseReview,
        )
    }

    private fun historyCompletedWorkoutIds(
        workouts: List<WorkoutEntity>,
        linked: List<LocalActivityLedgerSlice>,
        direct: List<WorkoutFeedbackEntity>,
    ): Set<String> = workouts.filter { workout ->
        workout.currentStatus in COMPLETED_FEEDBACK_STATES ||
            linked.any { it.activity.linkedWorkoutId == workout.workoutId } ||
            direct.any {
                it.workoutId == workout.workoutId && it.completionState in COMPLETED_FEEDBACK_STATES
            }
    }.mapTo(mutableSetOf(), WorkoutEntity::workoutId)

    private fun historyCutoffEpochDay(plan: PlanEntity, slice: LocalHistoryLedgerSlice): Long {
        if (plan.state == "active") return slice.todayEpochDay
        val lifecycleEnd = listOfNotNull(plan.completedAtEpochMillis, plan.archivedAtEpochMillis)
            .minOrNull() ?: return slice.todayEpochDay
        return localEpochDay(lifecycleEnd, slice.timeZone)
    }

    private fun historyPlanActual(
        exact: List<LocalWeekActualAggregateLedgerRow>,
        linked: List<LocalActivityLedgerSlice>,
        direct: List<WorkoutFeedbackEntity>,
        extras: List<LocalActivityLedgerSlice>,
    ): LocalLoadReadModel {
        if (exact.isNotEmpty()) {
            return LocalLoadReadModel(
                exact.sumOf(LocalWeekActualAggregateLedgerRow::distanceMeters)
                    .toSurfaceInt("history plan distance"),
                exact.sumOf(LocalWeekActualAggregateLedgerRow::durationSeconds)
                    .toSurfaceInt("history plan duration"),
            )
        }
        return loads(
            linked.map { it.activity.distanceMeters to it.activity.durationSeconds } +
                direct.map { it.completedDistanceMeters to it.completedDurationSeconds } +
                extras.map { it.activity.distanceMeters to it.activity.durationSeconds },
        )
    }

    private fun historyAdjustments(
        rows: List<HistoryAdjustmentRow>,
    ): List<LocalHistoryAdjustmentReadModel> = rows
        .map(::historyAdjustment)
        .distinctBy(LocalHistoryAdjustmentReadModel::id)
        .sortedBy(LocalHistoryAdjustmentReadModel::createdAtEpochMillis)

    private fun historyAdjustment(row: HistoryAdjustmentRow): LocalHistoryAdjustmentReadModel {
        val routineGoal = row.adjustmentType == "routine_week_goal"
        return LocalHistoryAdjustmentReadModel(
            id = if (routineGoal) row.adjustmentId else row.effectId ?: row.adjustmentId,
            triggerType = row.adjustmentType,
            createdAtEpochMillis = row.createdAtEpochMillis,
            reversedAtEpochMillis = row.reversedAtEpochMillis,
            reversalReason = row.reversalReason,
            reason = row.newReason ?: historyAdjustmentReason(row),
            scheduledEpochDay = if (routineGoal) row.effectiveFromEpochDay else row.newScheduledEpochDay,
            workoutType = row.newWorkoutType.takeUnless { routineGoal },
            prescriptionKind = row.newPrescriptionKind.takeUnless { routineGoal },
            distanceMeters = if (routineGoal) row.destinationWeekLoadMeters else row.newDistanceMeters,
            durationSeconds = if (routineGoal) row.destinationWeekLoadDurationSeconds else row.newDurationSeconds,
            removed = row.effectId?.takeUnless { routineGoal }?.let {
                row.newTombstonedAtEpochMillis != null || row.newStatus == WORKOUT_STATE_TOMBSTONED
            },
        )
    }

    private fun historyAdjustmentReason(row: HistoryAdjustmentRow) = when {
        row.adjustmentType == "routine_week_goal" -> "Weekly running goal changed."
        else -> historyTriggerReason(row.triggerKind)
    }

    private fun historyTriggerReason(triggerKind: String?) = when (triggerKind) {
        LocalDecisionSourceKind.WorkoutFeedback.name -> "Recorded after workout feedback."
        LocalDecisionSourceKind.Activity.name -> "Recorded after an accepted activity."
        else -> null
    }

    private fun historyMissedRuns(
        workouts: List<WorkoutEntity>,
        completedWorkoutIds: Set<String>,
        cutoffEpochDay: Long,
    ) = workouts.count {
        it.currentWorkoutType != "rest" &&
            it.currentStatus != WORKOUT_STATE_TOMBSTONED &&
            it.workoutId !in completedWorkoutIds &&
            (it.currentStatus == "missed" ||
                (it.currentStatus == "planned" && it.currentScheduledEpochDay < cutoffEpochDay))
    }

    private fun historySkippedRuns(
        workouts: List<WorkoutEntity>,
        completedWorkoutIds: Set<String>,
    ) = workouts.count {
        it.currentWorkoutType != "rest" &&
            it.currentStatus != WORKOUT_STATE_TOMBSTONED &&
            it.workoutId !in completedWorkoutIds &&
            it.currentStatus == "skipped"
    }

    private fun historyPainFlags(
        exact: List<LocalWeekActualAggregateLedgerRow>,
        linked: List<LocalActivityLedgerSlice>,
        direct: List<WorkoutFeedbackEntity>,
        extras: List<LocalActivityLedgerSlice>,
    ): Int {
        if (exact.isNotEmpty()) {
            return exact.sumOf(LocalWeekActualAggregateLedgerRow::painFlags)
                .toSurfaceInt("history plan pain flag count")
        }
        return linked.mapNotNull(LocalActivityLedgerSlice::feedback).count(ActivityFeedbackEntity::pain) +
            direct.count(WorkoutFeedbackEntity::pain) +
            extras.mapNotNull(LocalActivityLedgerSlice::feedback).count(ActivityFeedbackEntity::pain)
    }

    private fun assertCompleteWeekActualContext(
        plans: List<LocalPlanLedgerSlice>,
        aggregates: List<LocalWeekActualAggregateLedgerRow>,
        activitiesContainCompleteWeekContext: Boolean,
    ) {
        if (activitiesContainCompleteWeekContext) return
        val expected = plans.flatMap { plan ->
            plan.weeks.map { week -> plan.plan.planId to week.weekId }
        }.toSet()
        val actual = aggregates.map { it.planId to it.weekId }
        require(actual.size == expected.size && actual.toSet() == expected) {
            "Bounded activity context requires one exact actual aggregate for every displayed plan week."
        }
    }

    fun settings(slice: LocalSettingsLedgerSlice): LocalSettingsReadModel {
        val profile = slice.profile?.let {
            LocalProfileReadModel(
                timeZone = it.timeZone,
                routeDataMode = it.routeDataMode,
                heartRateDataMode = it.heartRateDataMode,
                availabilityDays = slice.availabilityDays.map(ProfileAvailabilityDayEntity::dayOfWeek).sorted(),
                heartRateSettingsSource = it.heartRateSettingsSource,
                sexForEstimates = it.sexForEstimates,
                ageYears = it.ageYears,
                maxHeartRateBpm = it.maxHeartRateBpm,
                zone2FloorBpm = it.zone2FloorBpm,
                zone3FloorBpm = it.zone3FloorBpm,
                zone4FloorBpm = it.zone4FloorBpm,
                zone5FloorBpm = it.zone5FloorBpm,
                baselineDistanceMeters = it.baselineDistanceMeters,
                baselineDurationSeconds = it.baselineDurationSeconds,
                currentRunsPerWeek = it.currentRunsPerWeek,
                longestRecentRunMeters = it.longestRecentRunMeters,
                preferredLongRunDay = it.preferredLongRunDay,
            )
        }
        val activePlan = slice.activePlan?.let {
            LocalActivePlanReadModel(
                planId = it.plan.planId,
                goalId = it.goal?.goalId,
                goalTitle = it.goal?.title ?: "Training goal",
                goalKind = it.goal?.kind,
                startMode = it.goal?.startMode,
                raceDistanceMeters = it.goal?.raceDistanceMeters,
                goalTargetEpochDay = it.goal?.targetDateEpochDay,
                goalPriority = it.goal?.priority,
                phase = planPhase(it.plan.phaseType),
                state = planState(it.plan.state),
                startEpochDay = it.plan.startEpochDay,
                endEpochDay = it.plan.endEpochDay,
                riskAssessment = it.plan.riskAssessment,
                latestLifecycleEvent = it.lifecycle.maxByOrNull(PlanLifecycleEventEntity::occurredAtEpochMillis)?.let(::lifecycle),
                sessionsPerWeek = it.plan.summarySessionsPerWeek,
                routineWeeklyTargetMeters = it.plan.routineTargetDistanceMeters
                    .takeIf { _ -> it.plan.phaseType == "routine" },
                routineWeeklyTargetDurationSeconds = it.plan.routineTargetDurationSeconds
                    .takeIf { _ -> it.plan.phaseType == "routine" },
                routineWeeklyChangeMeters = it.plan.routineWeeklyChangeDistanceMeters
                    .takeIf { _ -> it.plan.phaseType == "routine" },
                routineWeeklyChangeDurationSeconds = it.plan.routineWeeklyChangeDurationSeconds
                    .takeIf { _ -> it.plan.phaseType == "routine" },
            )
        }
        val pendingGoal = slice.pendingGoal?.let {
            LocalPendingGoalReadModel(
                goalId = it.goalId,
                title = it.title,
                goalKind = it.kind,
                startMode = it.startMode,
                raceDistanceMeters = it.raceDistanceMeters,
                targetEpochDay = it.targetDateEpochDay,
                priority = it.priority,
            )
        }
        return LocalSettingsReadModel(
            profile = profile,
            activePlan = activePlan,
            about = LocalAboutReadModel(
                versionName = slice.versionName,
                buildRevision = slice.buildRevision,
            ),
            pendingGoal = pendingGoal,
            phaseReview = slice.phaseReview,
            pendingHealthConnect = slice.pendingHealthConnect,
            notificationPreferences = slice.notificationPreferences?.let {
                LocalNotificationPreferences(
                    runReminderEnabled = it.runReminderEnabled,
                    runReminderMinuteOfDay =
                        normalizedReminderMinuteOfDay(it.runReminderMinuteOfDay),
                    folderImportAlertsEnabled = it.folderImportAlertsEnabled,
                )
            } ?: LocalNotificationPreferences(),
        )
    }

    private fun workout(
        entity: WorkoutEntity,
        weekOrdinal: Int,
        actual: LocalActivityLedgerSlice?,
        blocks: List<WorkoutBlockEntity> = emptyList(),
        segments: List<WorkoutSegmentEntity> = emptyList(),
        consequence: LocalConsequenceReadModel? = null,
        adjustment: LocalWorkoutAdjustmentReadModel? = null,
        planPhase: LocalPlanPhase = LocalPlanPhase.OTHER,
    ): LocalWorkoutReadModel {
        val generated = prescription(entity, generated = true, blocks = blocks, segments = segments)
        val current = prescription(entity, generated = false, blocks = blocks, segments = segments)
        return LocalWorkoutReadModel(
            workoutId = entity.workoutId,
            planId = entity.planId,
            weekOrdinal = weekOrdinal,
            scheduledEpochDay = entity.currentScheduledEpochDay,
            status = entity.currentStatus,
            isRest = entity.currentWorkoutType == "rest",
            isEdited = entity.generatedScheduledEpochDay != entity.currentScheduledEpochDay ||
                entity.generatedWorkoutType != entity.currentWorkoutType ||
                entity.generatedPrescriptionKind != entity.currentPrescriptionKind ||
                entity.generatedDistanceMeters != entity.currentDistanceMeters ||
                entity.generatedDurationSeconds != entity.currentDurationSeconds ||
                entity.generatedIntensity != entity.currentIntensity ||
                entity.generatedPurpose != entity.currentPurpose ||
                entity.generatedReason != entity.currentReason ||
                generated.intervalStructure != current.intervalStructure,
            generated = generated,
            current = current,
            actual = actual?.let(::activity),
            consequence = consequence,
            adjustment = adjustment,
            planPhase = planPhase,
        )
    }

    private fun prescription(
        entity: WorkoutEntity,
        generated: Boolean,
        blocks: List<WorkoutBlockEntity> = emptyList(),
        segments: List<WorkoutSegmentEntity> = emptyList(),
    ): LocalPrescriptionReadModel = if (generated) {
        LocalPrescriptionReadModel(
            entity.generatedWorkoutType,
            entity.generatedPrescriptionKind,
            LocalLoadReadModel(entity.generatedDistanceMeters, entity.generatedDurationSeconds),
            entity.generatedIntensity,
            entity.generatedPurpose,
            entity.generatedReason,
            entity.generatedWarmupSeconds,
            entity.generatedCooldownSeconds,
            timedStructure(entity, "generated", entity.generatedWarmupSeconds, entity.generatedCooldownSeconds, blocks, segments),
        )
    } else {
        LocalPrescriptionReadModel(
            entity.currentWorkoutType,
            entity.currentPrescriptionKind,
            LocalLoadReadModel(entity.currentDistanceMeters, entity.currentDurationSeconds),
            entity.currentIntensity,
            entity.currentPurpose,
            entity.currentReason,
            entity.currentWarmupSeconds,
            entity.currentCooldownSeconds,
            timedStructure(entity, "current", entity.currentWarmupSeconds, entity.currentCooldownSeconds, blocks, segments),
        )
    }

    private fun timedStructure(
        workout: WorkoutEntity,
        version: String,
        warmupSeconds: Int?,
        cooldownSeconds: Int?,
        blocks: List<WorkoutBlockEntity>,
        segments: List<WorkoutSegmentEntity>,
    ): LocalTimedIntervalStructureReadModel? {
        val timedBlocks = blocks
            .asSequence()
            .filter { it.workoutId == workout.workoutId && it.prescriptionVersion == version }
            .sortedBy(WorkoutBlockEntity::ordinal)
            .map { block ->
                LocalTimedBlockReadModel(
                    repetitions = block.repetitions,
                    segments = segments
                        .asSequence()
                        .filter { it.blockId == block.blockId }
                        .sortedBy(WorkoutSegmentEntity::ordinal)
                        .map { segment -> LocalTimedSegmentReadModel(segment.segmentType, segment.targetDurationSeconds) }
                        .toList(),
                )
            }
            .toList()
        return if (timedBlocks.isEmpty() && warmupSeconds == null && cooldownSeconds == null) {
            null
        } else {
            LocalTimedIntervalStructureReadModel(warmupSeconds, cooldownSeconds, timedBlocks)
        }
    }

    private fun activity(row: LocalActivityLedgerSlice): LocalActivityReadModel {
        val entity = row.activity
        return LocalActivityReadModel(
            activityId = entity.activityId,
            source = entity.source,
            occurredAtEpochMillis = entity.occurredAtEpochMillis,
            linkedWorkoutId = entity.linkedWorkoutId,
            load = LocalLoadReadModel(entity.distanceMeters, entity.durationSeconds),
            feltHard = row.feedback?.feltHard,
            pain = row.feedback?.pain,
            reviewState = entity.reviewState,
            extraPlanImpactConfirmed = entity.extraPlanImpactConfirmed,
            consequence = entity.takeIf { it.reviewState == ACTIVITY_REVIEW_STATE_ACCEPTED }
                ?.let { accepted ->
                    row.consequence?.let { stored ->
                        activityConsequence(accepted, row.feedback, stored, row.consequenceOptions)
                    } ?: row.linkedWorkoutFeedback?.let { feedback ->
                        row.linkedWorkoutConsequence?.let { stored ->
                            workoutConsequence(
                                feedback,
                                stored,
                                row.linkedWorkoutConsequenceOptions,
                            )
                        }
                    }
                },
            evidence = LocalActivityEvidenceReadModel(
                routeRetained = entity.routeTraceRetained,
                routeStartEndRedacted = entity.routeStartEndRedacted,
                routeSourcePointCount = entity.routePointCount,
                route = row.route.sortedBy(RouteSampleEntity::ordinal).map {
                    LocalRoutePointReadModel(
                        it.latitudeE6,
                        it.longitudeE6,
                        it.elapsedSeconds,
                        it.elevationMeters,
                        it.segmentOrdinal,
                        it.speedMetersPerSecond,
                    )
                },
                heartRateSeriesRetained = entity.heartRateSeriesRetained,
                heartRateSourceSampleCount = entity.heartRateSourceSampleCount,
                heartRate = row.heartRate.sortedBy(HeartRateSampleEntity::ordinal).map {
                    LocalHeartRatePointReadModel(it.elapsedSeconds, it.beatsPerMinute, it.sourceSampleCount)
                },
                averageHeartRateBpm = entity.averageHeartRateBpm,
                maxHeartRateBpm = entity.maxHeartRateBpm,
                averageCadenceSpm = entity.averageCadenceSpm,
            ),
        )
    }

    private fun workoutConsequence(
        feedback: WorkoutFeedbackEntity,
        stored: WorkoutFeedbackConsequenceEntity,
        options: List<WorkoutFeedbackConsequenceOptionEntity>,
    ) = LocalConsequenceReadModel(
        sourceKind = LocalDecisionSourceKind.WorkoutFeedback.name,
        sourceId = feedback.feedbackId,
        sourceVersion = feedback.recordedAtEpochMillis.toString(),
        classification = stored.classification,
        deviation = stored.deviation,
        loadMetric = stored.loadMetric,
        actualDifference = stored.distanceDifferenceMeters ?: stored.durationDifferenceSeconds,
        actualLoad = LocalLoadReadModel(feedback.completedDistanceMeters, feedback.completedDurationSeconds),
        risk = stored.risk ?: stored.assessment,
        recommendedDecision = stored.recommendedDecision,
        appliedDecision = stored.appliedDecision,
        options = options.map(WorkoutFeedbackConsequenceOptionEntity::decision),
        comparisonStatus = stored.comparisonStatus,
        planChangeAvailable = stored.planChangeAvailable,
    )

    private fun activityConsequence(
        activity: ActivityEntity,
        feedback: ActivityFeedbackEntity?,
        stored: ActivityConsequenceEntity,
        options: List<ActivityConsequenceOptionEntity>,
    ) = LocalConsequenceReadModel(
        sourceKind = LocalDecisionSourceKind.Activity.name,
        sourceId = activity.activityId,
        sourceVersion = (feedback?.recordedAtEpochMillis ?: activity.updatedAtEpochMillis).toString(),
        classification = stored.classification,
        deviation = stored.deviation,
        loadMetric = stored.loadMetric,
        actualDifference = stored.distanceDifferenceMeters ?: stored.durationDifferenceSeconds,
        actualLoad = LocalLoadReadModel(stored.actualLoadMeters, stored.actualLoadDurationSeconds),
        risk = stored.risk ?: stored.assessment,
        recommendedDecision = stored.recommendedDecision,
        appliedDecision = stored.appliedDecision,
        options = options.map(ActivityConsequenceOptionEntity::decision),
        comparisonStatus = stored.comparisonStatus,
        planChangeAvailable = stored.planChangeAvailable,
    )

    private fun currentSignal(
        plan: LocalPlanLedgerSlice,
        accepted: List<LocalActivityLedgerSlice>,
        profile: ProfileSettingsEntity?,
        zone: ZoneId,
        todayEpochDay: Long,
    ): LocalCurrentSignalReadModel {
        val planRisk = plan.plan.riskAssessment?.takeIf(::knownRisk) ?: "conservative"
        val recentStart = todayEpochDay - 28
        val feedbackEvidence = feedbackSignalEvidence(plan, planRisk, recentStart, todayEpochDay)
        val activityEvidence = activitySignalEvidence(
            plan, accepted, planRisk, zone, recentStart, todayEpochDay,
        )
        val latest = (feedbackEvidence + activityEvidence).maxWithOrNull(
            compareBy<SignalEvidence>(
                SignalEvidence::evidenceEpochDay,
                SignalEvidence::recordedAtEpochMillis,
            ),
        )
        val selected = latest?.takeUnless(SignalEvidence::resolved)
            ?.takeIf { riskRank(it.risk) >= riskRank(planRisk) }
        val planReasons = currentPlanReasons(plan, planRisk)
        return LocalCurrentSignalReadModel(
            risk = selected?.risk ?: planRisk,
            reasons = selected?.reasons.orEmpty().ifEmpty { if (selected == null) planReasons else emptyList() },
            source = selected?.source ?: "plan",
        )
    }

    private fun feedbackSignalEvidence(
        plan: LocalPlanLedgerSlice,
        planRisk: String,
        recentStart: Long,
        todayEpochDay: Long,
    ): List<SignalEvidence> {
        val workoutById = plan.workouts.associateBy(WorkoutEntity::workoutId)
        val consequenceByFeedback =
            plan.workoutConsequences.associateBy(WorkoutFeedbackConsequenceEntity::feedbackId)
        return plan.feedback.mapNotNull { feedback ->
            val workout = workoutById[feedback.workoutId] ?: return@mapNotNull null
            val stored = consequenceByFeedback[feedback.feedbackId] ?: return@mapNotNull null
            if (workout.currentScheduledEpochDay !in recentStart..todayEpochDay) return@mapNotNull null
            SignalEvidence(
                risk = (stored.risk ?: stored.assessment)?.takeIf(::knownRisk) ?: planRisk,
                resolved = stored.appliedDecision != null,
                evidenceEpochDay = workout.currentScheduledEpochDay,
                recordedAtEpochMillis = feedback.recordedAtEpochMillis,
                source = if (feedback.sourceActivityId == null) "feedback" else "activity",
                reasons = signalReasons(
                    stored.classification,
                    stored.deviation,
                    feedback.feltHard,
                    stored.recommendedDecision,
                ),
            )
        }
    }

    private fun activitySignalEvidence(
        plan: LocalPlanLedgerSlice,
        accepted: List<LocalActivityLedgerSlice>,
        planRisk: String,
        zone: ZoneId,
        recentStart: Long,
        todayEpochDay: Long,
    ): List<SignalEvidence> = accepted.mapNotNull { row ->
        activitySignalEvidenceRow(plan, row, planRisk, zone, recentStart, todayEpochDay)
    }

    private fun activitySignalEvidenceRow(
        plan: LocalPlanLedgerSlice,
        row: LocalActivityLedgerSlice,
        planRisk: String,
        zone: ZoneId,
        recentStart: Long,
        todayEpochDay: Long,
    ): SignalEvidence? {
        if (row.activity.linkedWorkoutId != null || !row.activity.extraPlanImpactConfirmed) {
            return null
        }
        val stored = row.consequence ?: return null
        val epochDay = Instant.ofEpochMilli(row.activity.occurredAtEpochMillis)
            .atZone(zone).toLocalDate().toEpochDay()
        val inPlan = plan.weeks.any { epochDay in it.startEpochDay..(it.startEpochDay + 6) }
        if (epochDay !in recentStart..todayEpochDay || !inPlan) return null
        return SignalEvidence(
            risk = (stored.risk ?: stored.assessment)?.takeIf(::knownRisk) ?: planRisk,
            resolved = stored.appliedDecision != null,
            evidenceEpochDay = epochDay,
            recordedAtEpochMillis = row.feedback?.recordedAtEpochMillis ?: row.activity.updatedAtEpochMillis,
            source = "activity",
            reasons = signalReasons(
                stored.classification,
                stored.deviation,
                row.feedback?.feltHard == true,
                stored.recommendedDecision,
            ),
        )
    }

    private fun currentPlanReasons(plan: LocalPlanLedgerSlice, planRisk: String): List<String> {
        val hasDistance = plan.workouts.any { (it.currentDistanceMeters ?: 0) > 0 }
        val hasDuration = plan.workouts.any { (it.currentDurationSeconds ?: 0) > 0 }
        val hasMixedLoad = hasDistance && hasDuration
        val result = plan.summaryWarnings
            .map(PlanSummaryWarningEntity::message)
            .filterNot(::isStoredHealthContextWarning)
            .filterNot { hasMixedLoad && isStoredNumericRampWarning(it) }
            .distinct()
            .take(3)
            .toMutableList()
        if (result.isEmpty() && planRisk != "conservative" && !hasMixedLoad) {
            result += "The saved plan is above runway's default ramp."
        }
        return result
    }

    private fun assignUnlinkedToPlanWeeks(
        accepted: List<LocalActivityLedgerSlice>,
        plans: List<LocalPlanLedgerSlice>,
        zone: ZoneId,
    ): List<AcceptedWeekAssignment> = accepted
        .asSequence()
        .filter { it.activity.linkedWorkoutId == null }
        .mapNotNull { row ->
            val activityEpochDay = Instant.ofEpochMilli(row.activity.occurredAtEpochMillis)
                .atZone(zone)
                .toLocalDate()
                .toEpochDay()
            plans
                .asSequence()
                .flatMap { plan ->
                    plan.weeks
                        .asSequence()
                        .filter { activityEpochDay in it.startEpochDay..(it.startEpochDay + 6) }
                        .map { week -> AcceptedWeekAssignment(plan, week, row) }
                }
                .sortedWith(
                    compareByDescending<AcceptedWeekAssignment> {
                        it.plan.plan.state == "active"
                    }.thenByDescending { it.plan.plan.startEpochDay },
                )
                .firstOrNull()
        }
        .toList()

    private fun signalReasons(
        classification: String?,
        deviation: String?,
        feltHard: Boolean,
        recommendedDecision: String?,
    ): List<String> = buildList {
        when {
            feltHard -> add("The latest accepted run was marked hard.")
            deviation == "short" -> add("The latest accepted run was shorter than its planned amount.")
            deviation in setOf("over", "long") -> add("The latest accepted run exceeded its planned amount.")
            classification == "completed_as_planned" ->
                add("The latest accepted run was recorded at the planned amount.")
            classification != null ->
                add("The latest accepted run has a stored training consequence.")
        }
        recommendedDecision
            ?.takeUnless { it == "keep_plan" }
            ?.let { add("Recommended choice: ${decisionLabel(it)}.") }
    }.distinct().take(3)

    private fun decisionLabel(value: String): String = when (value) {
        "reduce_next" -> "reduce the next run"
        "next_rest" -> "make the next workout a recovery day"
        "repeat_prescription" -> "repeat the prescription"
        "rebalance_week" -> "rebalance the week"
        else -> value.replace('_', ' ')
    }

    private fun Long.toSurfaceInt(label: String): Int {
        require(this in Int.MIN_VALUE..Int.MAX_VALUE) {
            "$label exceeds the current local surface range."
        }
        return toInt()
    }

    private fun knownRisk(value: String): Boolean =
        value in setOf("conservative", "moderate", "aggressive", "unsafe")

    private fun riskRank(value: String): Int = when (value) {
        "moderate" -> 1
        "aggressive" -> 2
        "unsafe" -> 3
        else -> 0
    }

    private fun isStoredHealthContextWarning(value: String): Boolean =
        value.startsWith("Injury recovery or recurring pain is included") ||
            value.startsWith("Recent injury or recurring pain is noted")

    private fun isStoredNumericRampWarning(value: String): Boolean =
        value.startsWith("The required weekly increase is above") ||
            value.startsWith("Weekly distance growth above 10%")

    private fun storedWeekLoad(
        plan: PlanEntity,
        week: PlanWeekEntity,
        workouts: List<WorkoutEntity>,
        generated: Boolean,
    ): LocalLoadReadModel = if (plan.phaseType == "routine") {
        LocalLoadReadModel(week.generatedLoadMeters, week.generatedLoadDurationSeconds)
    } else if (generated) {
        loads(workouts.map { it.generatedDistanceMeters to it.generatedDurationSeconds })
    } else {
        loads(workouts.map { it.currentDistanceMeters to it.currentDurationSeconds })
    }

    private fun loads(values: List<Pair<Int?, Int?>>): LocalLoadReadModel {
        val distances = values.mapNotNull { it.first }
        val durations = values.mapNotNull { it.second }
        return LocalLoadReadModel(
            distanceMeters = distances.takeIf { it.isNotEmpty() }?.sum(),
            durationSeconds = durations.takeIf { it.isNotEmpty() }?.sum(),
        )
    }

    private fun lifecycle(entity: PlanLifecycleEventEntity) = LocalLifecycleReadModel(
        entity.eventType,
        entity.occurredAtEpochMillis,
        entity.completedWorkoutCount,
        entity.completedActivityCount,
        entity.note,
    )

    private fun planState(value: String) = when (value) {
        "active" -> LocalPlanState.ACTIVE
        "completed" -> LocalPlanState.COMPLETED
        "archived" -> LocalPlanState.ARCHIVED
        else -> LocalPlanState.OTHER
    }

    private fun planPhase(value: String) = when (value) {
        "calibration" -> LocalPlanPhase.CALIBRATION
        "distance" -> LocalPlanPhase.DISTANCE
        "routine" -> LocalPlanPhase.ROUTINE
        else -> LocalPlanPhase.OTHER
    }

    private fun provenance(state: LocalPlanState) = when (state) {
        LocalPlanState.ACTIVE -> LocalPlanProvenance.ACTIVE
        LocalPlanState.COMPLETED -> LocalPlanProvenance.COMPLETED
        LocalPlanState.ARCHIVED -> LocalPlanProvenance.ARCHIVED
        LocalPlanState.OTHER -> LocalPlanProvenance.OTHER
    }

    private val COMPLETED_FEEDBACK_STATES = setOf("done", "shortened", "completed", "overrun")

    private fun localEpochDay(epochMillis: Long, timeZone: String): Long =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(timeZone)).toLocalDate().toEpochDay()

    private data class AcceptedWeekAssignment(
        val plan: LocalPlanLedgerSlice,
        val week: PlanWeekEntity,
        val activity: LocalActivityLedgerSlice,
    )

    private data class SignalEvidence(
        val risk: String,
        val resolved: Boolean,
        val evidenceEpochDay: Long,
        val recordedAtEpochMillis: Long,
        val source: String,
        val reasons: List<String>,
    )
}
