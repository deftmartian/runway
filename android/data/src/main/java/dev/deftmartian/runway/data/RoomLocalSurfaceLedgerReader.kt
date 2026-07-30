package dev.deftmartian.runway.data

import androidx.room.withTransaction
import dev.deftmartian.runway.domain.ACTIVITY_WORKOUT_MATCH_WINDOW_DAYS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bounded Room reader for the standalone surfaces.
 *
 * Plan children and supporting evidence use bounded bulk DAO reads; missing goal titles use the
 * neutral surface placeholder.
 */
class RoomLocalSurfaceLedgerReader(
    private val database: RunwayLedgerDatabase,
    private val versionName: String? = null,
    private val buildRevision: String? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : LocalSurfaceLedgerReader {
    override suspend fun calendar(
        fromEpochDay: Long,
        throughEpochDay: Long,
        limits: LocalSurfaceReadLimits,
    ): LocalCalendarLedgerSlice = database.withTransaction {
        val profile = database.profileSettingsDao().get()
        val timeZone = profile?.timeZone ?: ZoneId.systemDefault().id
        val zone = ZoneId.of(timeZone)
        val todayEpochDay = todayEpochDay(timeZone)
        val availability = database.profileSettingsDao().availabilityDays(limit = MAX_AVAILABILITY_DAYS).map { it.dayOfWeek }
        val review = reviewWindow(limits.inboxActivities)
        val activePlans = database.goalPlanDao().activePlans(MAX_ACTIVE_PLANS).take(1)
        val plans = planSlices(
            activePlans,
            limits,
            limits.calendarWorkouts,
            undoTodayEpochDay = todayEpochDay,
        )
        val nextWorkout = activePlans.singleOrNull()?.let { plan ->
            // Today has its own decision row. "Next" must not repeat the same workout.
            database.goalPlanDao().nextPlannedWorkout(plan.planId, todayEpochDay + 1)
        }
        val activityWindow = database.activityLedgerDao().acceptedActivitiesInRange(
            fromInclusive = LocalDate.ofEpochDay(fromEpochDay).atStartOfDay(zone).toInstant().toEpochMilli(),
            toExclusive = LocalDate.ofEpochDay(throughEpochDay + 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            limit = limits.calendarActivities + 1,
        )
        LocalCalendarLedgerSlice(
            fromEpochDay = fromEpochDay,
            throughEpochDay = throughEpochDay,
            timeZone = timeZone,
            pendingReviewCount = review.visibleCount,
            pendingReviewCountIsExact = review.isExact,
            hasMoreActivities = activityWindow.size > limits.calendarActivities,
            plans = plans,
            activities = activitySlices(activityWindow.take(limits.calendarActivities)),
            todayEpochDay = todayEpochDay,
            phaseReview = phaseReview(plans.firstOrNull(), profile, availability, todayEpochDay),
            nextWorkout = nextWorkout,
        )
    }

    override suspend fun inbox(limits: LocalSurfaceReadLimits): LocalInboxLedgerSlice =
        database.withTransaction {
            val profile = database.profileSettingsDao().get()
            val timeZone = profile?.timeZone ?: ZoneId.systemDefault().id
            val todayEpochDay = todayEpochDay(timeZone)
            val availability = database.profileSettingsDao().availabilityDays(limit = MAX_AVAILABILITY_DAYS).map { it.dayOfWeek }
            val review = reviewWindow(limits.inboxActivities)
            val pendingExtras = database.activityLedgerDao()
                .acceptedUnlinkedExtrasWithPendingPlanChange(limits.inboxActivities + 1)
            val pendingLinkedActivities = database.activityLedgerDao()
                .acceptedLinkedActivitiesWithPendingPlanChange(limits.inboxActivities + 1)
            val activePlan = database.goalPlanDao().activePlans(MAX_ACTIVE_PLANS).take(1)
                .let { planSlices(it, limits, MAX_INBOX_PLAN_WORKOUTS).firstOrNull() }
            val reviewEpochDays = review.items.map { activity ->
                Instant.ofEpochMilli(activity.occurredAtEpochMillis)
                    .atZone(ZoneId.of(timeZone))
                    .toLocalDate()
                    .toEpochDay()
            }
            val linkCandidates = activePlan?.workouts.orEmpty()
                .asSequence()
                .filter {
                    it.currentStatus == "planned" && it.tombstonedAtEpochMillis == null &&
                        it.currentWorkoutType !in setOf("rest", "race")
                }
                .filter { workout ->
                    reviewEpochDays.any { reviewDay ->
                        workout.currentScheduledEpochDay in
                            (reviewDay - ACTIVITY_WORKOUT_MATCH_WINDOW_DAYS)..
                            (reviewDay + ACTIVITY_WORKOUT_MATCH_WINDOW_DAYS)
                    }
                }
                .sortedWith(
                    compareBy(WorkoutEntity::currentScheduledEpochDay)
                        .thenBy(WorkoutEntity::workoutId),
                )
                .take(MAX_LINK_CANDIDATES)
                .toList()
            val linkCandidateIds = linkCandidates.mapTo(mutableSetOf(), WorkoutEntity::workoutId)
            val linkCandidateBlocks = activePlan?.workoutBlocks.orEmpty()
                .filter { it.workoutId in linkCandidateIds }
            val linkCandidateBlockIds = linkCandidateBlocks.mapTo(mutableSetOf(), WorkoutBlockEntity::blockId)
            val pendingWorkoutFeedbackWindow = activePlan
                ?.let { pendingDirectWorkoutFeedback(it, limits.inboxActivities + 1) }
                .orEmpty()
            LocalInboxLedgerSlice(
                reviewCount = review.visibleCount,
                reviewCountIsExact = review.isExact,
                hasMore =
                    review.visibleCount > review.items.size ||
                        pendingExtras.size > limits.inboxActivities ||
                        pendingLinkedActivities.size > limits.inboxActivities ||
                        pendingWorkoutFeedbackWindow.size > limits.inboxActivities,
                activities = enrichLinkedInboxConsequences(
                    activitySlices(
                        (
                            review.items +
                                pendingExtras.take(limits.inboxActivities) +
                                pendingLinkedActivities.take(limits.inboxActivities)
                            )
                        .distinctBy(ActivityEntity::activityId)
                        .sortedWith(
                            compareByDescending(ActivityEntity::occurredAtEpochMillis)
                                .thenByDescending(ActivityEntity::activityId),
                        ),
                    ),
                    activePlan,
                ),
                pendingWorkoutFeedback = pendingWorkoutFeedbackWindow.take(limits.inboxActivities),
                linkCandidates = linkCandidates,
                linkCandidateBlocks = linkCandidateBlocks,
                linkCandidateSegments = activePlan?.workoutSegments.orEmpty()
                    .filter { it.blockId in linkCandidateBlockIds },
                pendingHealthConnect = pendingHealthConnect(MAX_PENDING_HEALTH_CONNECT),
                timeZone = timeZone,
                todayEpochDay = todayEpochDay,
                phaseReview = phaseReview(activePlan, profile, availability, todayEpochDay),
            )
        }

    private fun enrichLinkedInboxConsequences(
        activities: List<LocalActivityLedgerSlice>,
        activePlan: LocalPlanLedgerSlice?,
    ): List<LocalActivityLedgerSlice> {
        val feedbackByActivity = activePlan?.feedback
            ?.filter { it.sourceActivityId != null }
            ?.associateBy { requireNotNull(it.sourceActivityId) }
            .orEmpty()
        val consequencesByFeedback = activePlan?.workoutConsequences
            ?.associateBy(WorkoutFeedbackConsequenceEntity::feedbackId)
            .orEmpty()
        val optionsByFeedback = activePlan?.workoutConsequenceOptions
            ?.groupBy(WorkoutFeedbackConsequenceOptionEntity::feedbackId)
            .orEmpty()
        return activities.map { row ->
            val feedback = feedbackByActivity[row.activity.activityId] ?: return@map row
            val consequence = consequencesByFeedback[feedback.feedbackId] ?: return@map row
            row.copy(
                linkedWorkoutFeedback = feedback,
                linkedWorkoutConsequence = consequence,
                linkedWorkoutConsequenceOptions = optionsByFeedback[feedback.feedbackId].orEmpty(),
            )
        }
    }

    private fun pendingDirectWorkoutFeedback(
        activePlan: LocalPlanLedgerSlice,
        limit: Int,
    ): List<LocalPendingWorkoutFeedbackLedgerSlice> {
        val workouts = activePlan.workouts.associateBy(WorkoutEntity::workoutId)
        val consequences = activePlan.workoutConsequences
            .associateBy(WorkoutFeedbackConsequenceEntity::feedbackId)
        val options = activePlan.workoutConsequenceOptions
            .groupBy(WorkoutFeedbackConsequenceOptionEntity::feedbackId)
        return activePlan.feedback
            .asSequence()
            .filter { it.sourceActivityId == null }
            .mapNotNull { feedback ->
                val workout = workouts[feedback.workoutId] ?: return@mapNotNull null
                val consequence = consequences[feedback.feedbackId] ?: return@mapNotNull null
                if (!consequence.planChangeAvailable || consequence.appliedDecision != null) {
                    return@mapNotNull null
                }
                LocalPendingWorkoutFeedbackLedgerSlice(
                    feedback = feedback,
                    workout = workout,
                    consequence = consequence,
                    consequenceOptions = options[feedback.feedbackId].orEmpty(),
                )
            }
            .sortedWith(
                compareByDescending<LocalPendingWorkoutFeedbackLedgerSlice> {
                    it.feedback.recordedAtEpochMillis
                }.thenByDescending { it.feedback.feedbackId },
            )
            .take(limit)
            .toList()
    }

    override suspend fun stats(limits: LocalSurfaceReadLimits): LocalStatsLedgerSlice =
        database.withTransaction {
            val profile = database.profileSettingsDao().get()
            val timeZone = profile?.timeZone ?: ZoneId.systemDefault().id
            val todayEpochDay = todayEpochDay(timeZone)
            val availability = database.profileSettingsDao().availabilityDays(limit = MAX_AVAILABILITY_DAYS).map { it.dayOfWeek }
            val planWindow = surfacePlans(limits.historyPlans + 1)
            val activityWindow = database.activityLedgerDao().acceptedActivitiesInRange(
                fromInclusive = Long.MIN_VALUE,
                toExclusive = Long.MAX_VALUE,
                limit = limits.statsActivities + 1,
            )
            val plans = planSlices(
                planWindow.take(limits.historyPlans),
                limits,
                limits.statsWeeks * MAX_WORKOUTS_PER_WEEK,
            )
            LocalStatsLedgerSlice(
                plans = plans,
                activities = activitySlices(activityWindow.take(limits.statsActivities)),
                hasMorePlans = planWindow.size > limits.historyPlans,
                hasMoreActivities = activityWindow.size > limits.statsActivities,
                timeZone = timeZone,
                todayEpochDay = todayEpochDay,
                phaseReview = phaseReview(
                    plans.firstOrNull { it.plan.state == "active" },
                    profile,
                    availability,
                    todayEpochDay,
                ),
                profile = profile,
            )
        }

    override suspend fun history(limits: LocalSurfaceReadLimits): LocalHistoryLedgerSlice =
        database.withTransaction {
            val profile = database.profileSettingsDao().get()
            val timeZone = profile?.timeZone ?: ZoneId.systemDefault().id
            val todayEpochDay = todayEpochDay(timeZone)
            val availability = database.profileSettingsDao().availabilityDays(limit = MAX_AVAILABILITY_DAYS).map { it.dayOfWeek }
            val planWindow = surfacePlans(limits.historyPlans + 1)
            val activityWindow = database.activityLedgerDao().acceptedActivitiesInRange(
                fromInclusive = Long.MIN_VALUE,
                toExclusive = Long.MAX_VALUE,
                limit = limits.historyActivities + 1,
            )
            val plans = planSlices(
                planWindow.take(limits.historyPlans),
                limits,
                limits.statsWeeks * MAX_WORKOUTS_PER_WEEK,
                includeHistoryAudit = true,
            )
            LocalHistoryLedgerSlice(
                plans = plans,
                activities = activitySlices(activityWindow.take(limits.historyActivities)),
                hasMorePlans = planWindow.size > limits.historyPlans,
                hasMoreActivities = activityWindow.size > limits.historyActivities,
                timeZone = timeZone,
                todayEpochDay = todayEpochDay,
                phaseReview = phaseReview(
                    plans.firstOrNull { it.plan.state == "active" },
                    profile,
                    availability,
                    todayEpochDay,
                ),
            )
        }

    override suspend fun settings(limits: LocalSurfaceReadLimits): LocalSettingsLedgerSlice =
        database.withTransaction {
            val profile = database.profileSettingsDao().get()
            val timeZone = profile?.timeZone ?: ZoneId.systemDefault().id
            val todayEpochDay = todayEpochDay(timeZone)
            val availability = database.profileSettingsDao().availabilityDays(limit = MAX_AVAILABILITY_DAYS)
            val activePlan = database.goalPlanDao().activePlans(MAX_ACTIVE_PLANS)
                .take(1)
                .let { planSlices(it, limits, limits.calendarWorkouts).firstOrNull() }
            LocalSettingsLedgerSlice(
                profile = profile,
                availabilityDays = availability,
                activePlan = activePlan,
                versionName = versionName,
                buildRevision = buildRevision,
                phaseReview = phaseReview(
                    activePlan,
                    profile,
                    availability.map { it.dayOfWeek },
                    todayEpochDay,
                ),
                pendingHealthConnect = pendingHealthConnect(MAX_PENDING_HEALTH_CONNECT),
            )
        }

    override suspend fun activityEvidence(
        activityId: String,
        limits: LocalSurfaceReadLimits,
    ): LocalActivityLedgerSlice? = database.withTransaction {
        val activity = database.activityLedgerDao().activity(activityId) ?: return@withTransaction null
        LocalActivityLedgerSlice(
            activity = activity,
            feedback = database.activityLedgerDao().activityFeedback(activityId),
            route = database.activityLedgerDao().routeSamples(activityId, limits.evidenceSamples),
            heartRate = database.activityLedgerDao().heartRateSamples(activityId, limits.evidenceSamples),
            consequence = database.activityLedgerDao().activityConsequence(activityId),
            consequenceOptions = database.activityLedgerDao().activityConsequenceOptions(activityId, MAX_CONSEQUENCE_OPTIONS),
        )
    }

    private suspend fun planSlices(
        plans: List<PlanEntity>,
        limits: LocalSurfaceReadLimits,
        workoutLimitPerPlan: Int,
        undoTodayEpochDay: Long? = null,
        includeHistoryAudit: Boolean = false,
    ): List<LocalPlanLedgerSlice> {
        if (plans.isEmpty()) return emptyList()
        val planIds = plans.map(PlanEntity::planId)
        val dao = database.goalPlanDao()
        val goals = dao.goalsByIds(
            plans.map(PlanEntity::goalId).distinct(),
            plans.size,
        ).associateBy(GoalEntity::goalId)
        val weeks = dao.weeksForPlans(
            planIds,
            plans.size * limits.statsWeeks,
        ).groupBy(PlanWeekEntity::planId)
        val summaryWarnings = dao.planSummaryWarningsForPlans(
            planIds,
            plans.size * MAX_SUMMARY_WARNINGS_PER_PLAN,
        ).groupBy(PlanSummaryWarningEntity::planId)
        val historyAdjustments = if (includeHistoryAudit) {
            database.adjustmentDao().historyAdjustmentRowsForPlans(
                planIds,
                plans.size * MAX_HISTORY_ADJUSTMENT_ROWS_PER_PLAN,
            ).groupBy(HistoryAdjustmentRow::planId)
        } else {
            emptyMap()
        }
        val workouts = dao.visibleWorkoutsForPlans(
            planIds,
            limit = plans.size * workoutLimitPerPlan,
        ).groupBy(WorkoutEntity::planId)
        val visibleWorkoutIds = workouts.values.flatten().map(WorkoutEntity::workoutId)
        val undoableAdjustments = if (undoTodayEpochDay == null || visibleWorkoutIds.isEmpty()) {
            emptyList()
        } else {
            database.adjustmentDao().undoableWorkoutAdjustments(
                workoutIds = visibleWorkoutIds,
                todayEpochDay = undoTodayEpochDay,
                limit = visibleWorkoutIds.size * MAX_UNDO_ADJUSTMENTS_PER_WORKOUT,
            ).map {
                LocalWorkoutAdjustmentReadModel(
                    workoutId = it.workoutId,
                    adjustmentId = it.adjustmentId,
                    kind = it.kind,
                    createdAtEpochMillis = it.createdAtEpochMillis,
                )
            }
        }
        val latestUndoableAdjustmentByWorkout = undoableAdjustments
            .groupBy(LocalWorkoutAdjustmentReadModel::workoutId)
            .mapValues { (_, rows) -> rows.first() }
        val blocks = chunkedSqliteIdRead(
            ids = visibleWorkoutIds,
            limit = minOf(
                MAX_SURFACE_WORKOUT_BLOCKS,
                visibleWorkoutIds.size * MAX_BLOCKS_PER_WORKOUT_ACROSS_VERSIONS,
            ),
            read = dao::blocksForWorkouts,
        )
        val segments = chunkedSqliteIdRead(
            ids = blocks.map(WorkoutBlockEntity::blockId),
            limit = minOf(MAX_SURFACE_WORKOUT_SEGMENTS, blocks.size * MAX_SEGMENTS_PER_BLOCK),
            read = dao::segmentsForBlocks,
        )
        val blocksByWorkout = blocks.groupBy(WorkoutBlockEntity::workoutId)
        val segmentsByBlock = segments.groupBy(WorkoutSegmentEntity::blockId)
        val workoutPlan = workouts.flatMap { (planId, rows) ->
            rows.map { it.workoutId to planId }
        }.toMap()
        val feedback = database.activityLedgerDao().workoutFeedbackForPlans(
            planIds,
            plans.size * workoutLimitPerPlan,
        ).groupBy { workoutPlan[it.workoutId] }
        val feedbackIds = feedback.values.flatten().map(WorkoutFeedbackEntity::feedbackId)
        val consequences = chunkedSqliteIdRead(
            ids = feedbackIds,
            limit = feedbackIds.size,
            read = database.activityLedgerDao()::workoutFeedbackConsequencesForFeedbackIds,
        )
        val consequenceOptions = chunkedSqliteIdRead(
            ids = feedbackIds,
            limit = feedbackIds.size * MAX_CONSEQUENCE_OPTIONS,
            read = database.activityLedgerDao()::workoutFeedbackConsequenceOptionsForFeedbackIds,
        )
        val feedbackIdsByPlan = feedback.mapValues { (_, rows) -> rows.map(WorkoutFeedbackEntity::feedbackId).toSet() }
        val consequencesByPlan = consequences.groupBy { consequence ->
            feedbackIdsByPlan.entries.firstOrNull { consequence.feedbackId in it.value }?.key
        }
        val optionsByPlan = consequenceOptions.groupBy { option ->
            feedbackIdsByPlan.entries.firstOrNull { option.feedbackId in it.value }?.key
        }
        val lifecycle = dao.lifecycleEventsForPlans(
            planIds,
            plans.size * MAX_LIFECYCLE_EVENTS,
        ).groupBy(PlanLifecycleEventEntity::planId)
        return plans.map { plan ->
            val planWorkouts = workouts[plan.planId].orEmpty().take(workoutLimitPerPlan)
            val planBlocks = planWorkouts.flatMap { blocksByWorkout[it.workoutId].orEmpty() }
            LocalPlanLedgerSlice(
                goal = goals[plan.goalId],
                plan = plan,
                weeks = weeks[plan.planId].orEmpty().take(limits.statsWeeks),
                workouts = planWorkouts,
                feedback = feedback[plan.planId].orEmpty().take(workoutLimitPerPlan),
                lifecycle = lifecycle[plan.planId].orEmpty().take(MAX_LIFECYCLE_EVENTS),
                workoutConsequences = consequencesByPlan[plan.planId].orEmpty(),
                workoutConsequenceOptions = optionsByPlan[plan.planId].orEmpty(),
                summaryWarnings = summaryWarnings[plan.planId].orEmpty(),
                historyAdjustments = historyAdjustments[plan.planId].orEmpty(),
                undoableWorkoutAdjustments = planWorkouts.mapNotNull { workout ->
                    latestUndoableAdjustmentByWorkout[workout.workoutId]
                },
                workoutBlocks = planBlocks,
                workoutSegments = planBlocks.flatMap { segmentsByBlock[it.blockId].orEmpty() },
            )
        }
    }

    private suspend fun surfacePlans(limit: Int): List<PlanEntity> {
        return database.goalPlanDao().plansByStates(
            states = listOf("active", "completed", "archived"),
            limit = limit,
        )
    }

    private suspend fun reviewWindow(limit: Int): ReviewWindow {
        val dao = database.activityLedgerDao()
        val count = dao.activityCountByReviewState("review")
        val rows = dao.activitiesByReviewState("review", limit)
        return ReviewWindow(
            items = rows,
            visibleCount = count,
            isExact = true,
        )
    }

    private suspend fun activitySlices(
        activities: List<ActivityEntity>,
    ): List<LocalActivityLedgerSlice> {
        if (activities.isEmpty()) return emptyList()
        val activityIds = activities.map(ActivityEntity::activityId)
        val feedback = chunkedSqliteIdRead(
            ids = activityIds,
            limit = activities.size,
            read = database.activityLedgerDao()::activityFeedbackForActivities,
        )
            .associateBy(ActivityFeedbackEntity::activityId)
        val consequences = chunkedSqliteIdRead(
            ids = activityIds,
            limit = activityIds.size,
            read = database.activityLedgerDao()::activityConsequencesForActivityIds,
        )
            .associateBy(ActivityConsequenceEntity::activityId)
        val options = chunkedSqliteIdRead(
            ids = activityIds,
            limit = activityIds.size * MAX_CONSEQUENCE_OPTIONS,
            read = database.activityLedgerDao()::activityConsequenceOptionsForActivityIds,
        )
            .groupBy(ActivityConsequenceOptionEntity::activityId)
        return activities.map { activity ->
            LocalActivityLedgerSlice(
                activity = activity,
                feedback = feedback[activity.activityId],
                consequence = consequences[activity.activityId],
                consequenceOptions = options[activity.activityId].orEmpty(),
            )
        }
    }

    private suspend fun pendingHealthConnect(limit: Int): List<LocalHealthConnectPendingReadModel> {
        val importDao = database.importLedgerDao()
        val mappings = importDao.pendingHealthConnectMappings(limit)
        if (mappings.isEmpty()) return emptyList()
        val mappingIds = mappings.map(HealthConnectMappingEntity::mappingId)
        val proposed = importDao.pendingHealthConnectObservations(mappingIds, mappingIds.size)
            .associateBy(HealthConnectPendingObservationEntity::mappingId)
        val activityIds = mappings.flatMap { mapping ->
            listOfNotNull(mapping.activityId, mapping.duplicateCandidateActivityId)
        }.distinct()
        val current = if (activityIds.isEmpty()) emptyMap() else database.activityLedgerDao()
            .activitiesByIds(activityIds, activityIds.size)
            .associateBy(ActivityEntity::activityId)
        return mappings.map { mapping ->
            val pending = proposed[mapping.mappingId]
            val state = when {
                mapping.correctionPending -> "pending_correction"
                mapping.deletePending -> "pending_delete"
                mapping.duplicateCandidateActivityId != null -> "possible_duplicate"
                else -> error("Health Connect mapping is not pending review.")
            }
            LocalHealthConnectPendingReadModel(
                mappingId = mapping.mappingId,
                provider = mapping.provider,
                externalRecordId = mapping.externalRecordId,
                state = state,
                current = mapping.activityId?.let { current[it] }?.let(::summary),
                proposed = if (state == "possible_duplicate") {
                    mapping.duplicateCandidateActivityId
                        ?.let { current[it] }
                        ?.let(::summary)
                } else {
                    pending?.let(::summary)
                },
            )
        }
    }

    private suspend fun phaseReview(
        plan: LocalPlanLedgerSlice?,
        profile: ProfileSettingsEntity?,
        availability: List<Int>,
        todayEpochDay: Long,
    ): LocalPhaseReviewReadModel? {
        val active = plan?.takeIf { it.plan.state == "active" } ?: return null
        val end = active.plan.endEpochDay ?: return null
        val phase = when (active.plan.phaseType) {
            "foundation" -> LocalPlanPhase.FOUNDATION
            "calibration" -> LocalPlanPhase.CALIBRATION
            else -> return null
        }
        val goalKind = active.goal?.kind ?: return null
        if (end > todayEpochDay) return LocalPhaseReviewReadModel(active.plan.planId, phase, goalKind, end, ready = false)
        if (availability.isEmpty()) return LocalPhaseReviewReadModel(active.plan.planId, phase, goalKind, end, ready = false)
        val observedWeeks = minOf(2, active.weeks.size).coerceAtLeast(1)
        val activities = database.goalPlanDao().acceptedLinkedActivitiesForPlan(
            active.plan.planId,
            end - (observedWeeks * 7L - 1),
            end,
            MAX_PHASE_ACTIVITIES,
        )
        val prepared = LocalPlanLifecyclePreparation.prepareReview(
            active.plan.phaseType,
            goalKind,
            activities,
            observedWeeks,
            availability,
        )
        return LocalPhaseReviewReadModel(
            planId = active.plan.planId,
            phase = phase,
            goalKind = goalKind,
            phaseEndEpochDay = end,
            ready = true,
            activityCount = prepared.baseline.activityCount,
            totalDistanceMeters = prepared.baseline.totalDistanceMeters,
            weeklyDistanceMeters = prepared.baseline.weeklyDistanceMeters,
            totalDurationSeconds = prepared.baseline.totalDurationSeconds,
            longestActivityMeters = prepared.baseline.longestActivityMeters,
            runsPerWeek = prepared.baseline.runsPerWeek,
            recommendedTransition = prepared.transition.recommended.name.lowercase(),
            transitionOptions = prepared.transition.options.map { it.name.lowercase() },
            preferredLongRunDay = prepared.preferredLongRunDay,
            racePlan = if (profile == null) {
                null
            } else {
                LocalPlanLifecyclePreparation.prepareRacePlan(
                    phasePlan = active.plan,
                    goal = requireNotNull(active.goal),
                    profile = profile,
                    review = prepared,
                    acceptedLinkedActivities = activities,
                    availabilityDays = availability,
                    todayEpochDay = todayEpochDay,
                )?.preview
            },
        )
    }

    private fun summary(activity: ActivityEntity) = LocalActivitySummaryReadModel(
        activity.activityId,
        activity.occurredAtEpochMillis,
        LocalLoadReadModel(activity.distanceMeters, activity.durationSeconds),
    )

    private fun summary(pending: HealthConnectPendingObservationEntity) = LocalActivitySummaryReadModel(
        pending.mappingId,
        pending.occurredAtEpochMillis,
        LocalLoadReadModel(pending.distanceMeters, pending.durationSeconds),
    )

    private fun todayEpochDay(timeZone: String): Long =
        Instant.ofEpochMilli(nowEpochMillis()).atZone(ZoneId.of(timeZone)).toLocalDate().toEpochDay()

    private data class ReviewWindow(
        val items: List<ActivityEntity>,
        val visibleCount: Int,
        val isExact: Boolean,
    )

    private companion object {
        const val MAX_ACTIVE_PLANS = 2
        const val MAX_AVAILABILITY_DAYS = 7
        const val MAX_WORKOUTS_PER_WEEK = 14
        const val MAX_LIFECYCLE_EVENTS = 50
        const val MAX_SUMMARY_WARNINGS_PER_PLAN = 16
        const val MAX_HISTORY_ADJUSTMENT_ROWS_PER_PLAN = 128
        const val MAX_INBOX_PLAN_WORKOUTS = 1_024
        const val MAX_LINK_CANDIDATES = 128
        const val MAX_PENDING_HEALTH_CONNECT = 50
        const val MAX_CONSEQUENCE_OPTIONS = 8
        const val MAX_UNDO_ADJUSTMENTS_PER_WORKOUT = 8
        const val MAX_PHASE_ACTIVITIES = 512
        // These are read caps, not schema limits. They bound a large history read.
        const val MAX_BLOCKS_PER_WORKOUT_ACROSS_VERSIONS = 200
        const val MAX_SEGMENTS_PER_BLOCK = 100
        const val MAX_SURFACE_WORKOUT_BLOCKS = 4_000
        const val MAX_SURFACE_WORKOUT_SEGMENTS = 16_000
    }
}

private const val MAX_SQLITE_IN_IDS = 900

/**
 * Room expands collection parameters into one SQLite bind per ID. Split every potentially large
 * local-ledger lookup below the platform ceiling while preserving one explicit total result cap.
 */
internal suspend fun <Id, Row> chunkedSqliteIdRead(
    ids: List<Id>,
    limit: Int,
    read: suspend (List<Id>, Int) -> List<Row>,
): List<Row> {
    if (ids.isEmpty() || limit <= 0) return emptyList()
    val output = ArrayList<Row>(minOf(ids.size, limit))
    var remaining = limit
    for (chunk in ids.distinct().chunked(MAX_SQLITE_IN_IDS)) {
        if (remaining == 0) break
        val rows = read(chunk, remaining)
        output.addAll(rows.take(remaining))
        remaining -= minOf(rows.size, remaining)
    }
    return output
}
