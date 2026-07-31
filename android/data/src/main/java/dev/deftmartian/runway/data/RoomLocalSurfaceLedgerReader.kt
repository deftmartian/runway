package dev.deftmartian.runway.data

import androidx.room.withTransaction
import dev.deftmartian.runway.domain.ACTIVITY_WORKOUT_MATCH_WINDOW_DAYS
import dev.deftmartian.runway.domain.StandalonePlanRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal const val MAX_INBOX_PLAN_WORKOUTS =
    StandalonePlanRules.MAX_PLAN_WEEKS * StandalonePlanRules.MAX_VISIBLE_WORKOUTS_PER_WEEK

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
        val inboxDecisionCount = database.activityLedgerDao().actionableInboxDecisionCount()
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
            pendingDecisionCount = inboxDecisionCount,
            pendingDecisionCountIsExact = true,
            hasMoreActivities = activityWindow.size > limits.calendarActivities,
            plans = plans,
            activities = activitySlices(activityWindow.take(limits.calendarActivities)),
            profileExists = profile != null,
            todayEpochDay = todayEpochDay,
            phaseReview = phaseReview(plans.firstOrNull(), profile, availability, todayEpochDay),
            nextWorkout = nextWorkout,
        )
    }

    override suspend fun inbox(limits: LocalSurfaceReadLimits): LocalInboxLedgerSlice =
        inboxPage(limits, LocalInboxPagingCursor())

    override suspend fun inboxPage(
        limits: LocalSurfaceReadLimits,
        cursor: LocalInboxPagingCursor,
    ): LocalInboxLedgerSlice =
        database.withTransaction {
            val profile = database.profileSettingsDao().get()
            val timeZone = profile?.timeZone ?: ZoneId.systemDefault().id
            val todayEpochDay = todayEpochDay(timeZone)
            val availability = database.profileSettingsDao().availabilityDays(limit = MAX_AVAILABILITY_DAYS).map { it.dayOfWeek }
            val pageLimit = limits.inboxActivities + 1
            val activityDao = database.activityLedgerDao()
            val reviewRows = if (cursor.review.exhausted) emptyList() else activityDao.actionableReviewActivitiesPage(
                cursor.review.occurredAtEpochMillis, cursor.review.activityId, pageLimit,
            )
            val pendingExtras = if (cursor.acceptedExtra.exhausted) emptyList() else activityDao
                .acceptedUnlinkedExtrasWithPendingPlanChangePage(
                    cursor.acceptedExtra.occurredAtEpochMillis, cursor.acceptedExtra.activityId, pageLimit,
                )
            val pendingLinkedActivities = if (cursor.acceptedLinked.exhausted) emptyList() else activityDao
                .acceptedLinkedActivitiesWithPendingPlanChangePage(
                    cursor.acceptedLinked.occurredAtEpochMillis, cursor.acceptedLinked.activityId, pageLimit,
                )
            val activePlan = database.goalPlanDao().activePlans(MAX_ACTIVE_PLANS).take(1)
                .let { planSlices(it, limits, MAX_INBOX_PLAN_WORKOUTS).firstOrNull() }
            val directFeedbackRows = if (cursor.directFeedback.exhausted) emptyList() else activityDao
                .pendingDirectWorkoutFeedbackPage(
                    cursor.directFeedback.recordedAtEpochMillis,
                    cursor.directFeedback.feedbackId,
                    pageLimit,
                )
            val healthMappings = if (cursor.healthConnect.exhausted) emptyList() else database.importLedgerDao()
                .pendingHealthConnectMappingsPage(
                    cursor.healthConnect.observedAtEpochMillis,
                    cursor.healthConnect.mappingId,
                    pageLimit,
                )
            val reviewItems = reviewRows.take(limits.inboxActivities)
            val extraItems = pendingExtras.take(limits.inboxActivities)
            val linkedItems = pendingLinkedActivities.take(limits.inboxActivities)
            val directFeedbackItems = directFeedbackRows.take(limits.inboxActivities)
            val healthItems = healthMappings.take(limits.inboxActivities)
            val nextPage = LocalInboxPagingCursor(
                review = reviewCursor(cursor.review, reviewRows, limits.inboxActivities),
                acceptedExtra = reviewCursor(cursor.acceptedExtra, pendingExtras, limits.inboxActivities),
                acceptedLinked = reviewCursor(cursor.acceptedLinked, pendingLinkedActivities, limits.inboxActivities),
                directFeedback = feedbackCursor(cursor.directFeedback, directFeedbackRows, limits.inboxActivities),
                healthConnect = healthCursor(cursor.healthConnect, healthMappings, limits.inboxActivities),
            )
            val reviewEpochDays = reviewItems.map { activity ->
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
                .toList()
            val linkCandidateIds = linkCandidates.mapTo(mutableSetOf(), WorkoutEntity::workoutId)
            val linkCandidateBlocks = activePlan?.workoutBlocks.orEmpty()
                .filter { it.workoutId in linkCandidateIds }
            val linkCandidateBlockIds = linkCandidateBlocks.mapTo(mutableSetOf(), WorkoutBlockEntity::blockId)
            val pendingWorkoutFeedback = pendingDirectWorkoutFeedback(activePlan, directFeedbackItems)
            val pendingHealthConnect = pendingHealthConnect(healthItems)
            LocalInboxLedgerSlice(
                reviewCount = activityDao.actionableReviewActivityCount(),
                reviewCountIsExact = true,
                hasMore = !nextPage.exhausted,
                activities = enrichLinkedInboxConsequences(
                    activitySlices(
                        (
                            reviewItems + extraItems + linkedItems
                            )
                        .distinctBy(ActivityEntity::activityId)
                        .sortedWith(
                            compareByDescending(ActivityEntity::occurredAtEpochMillis)
                                .thenByDescending(ActivityEntity::activityId),
                        ),
                    ),
                    activePlan,
                ),
                pendingWorkoutFeedback = pendingWorkoutFeedback,
                linkCandidates = linkCandidates,
                linkCandidateBlocks = linkCandidateBlocks,
                linkCandidateSegments = activePlan?.workoutSegments.orEmpty()
                    .filter { it.blockId in linkCandidateBlockIds },
                pendingHealthConnect = pendingHealthConnect,
                timeZone = timeZone,
                todayEpochDay = todayEpochDay,
                phaseReview = phaseReview(activePlan, profile, availability, todayEpochDay),
                nextPage = nextPage.takeUnless(LocalInboxPagingCursor::exhausted),
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
        activePlan: LocalPlanLedgerSlice?,
        feedbackRows: List<WorkoutFeedbackEntity>,
    ): List<LocalPendingWorkoutFeedbackLedgerSlice> {
        if (activePlan == null || feedbackRows.isEmpty()) return emptyList()
        val workouts = activePlan.workouts.associateBy(WorkoutEntity::workoutId)
        val consequences = activePlan.workoutConsequences
            .associateBy(WorkoutFeedbackConsequenceEntity::feedbackId)
        val options = activePlan.workoutConsequenceOptions
            .groupBy(WorkoutFeedbackConsequenceOptionEntity::feedbackId)
        return feedbackRows.mapNotNull { feedback ->
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
    }

    override suspend fun stats(limits: LocalSurfaceReadLimits): LocalStatsLedgerSlice =
        database.withTransaction {
            val profile = database.profileSettingsDao().get()
            val timeZone = profile?.timeZone ?: ZoneId.systemDefault().id
            val zone = ZoneId.of(timeZone)
            val todayEpochDay = todayEpochDay(timeZone)
            val availability = database.profileSettingsDao().availabilityDays(limit = MAX_AVAILABILITY_DAYS).map { it.dayOfWeek }
            val activePlans = database.goalPlanDao().activePlans(MAX_ACTIVE_PLANS).take(1)
            val plans = planSlices(
                activePlans,
                limits,
                limits.statsWeeks * MAX_WORKOUTS_PER_WEEK,
            )
            val linkedActivities = acceptedLinkedActivitiesForPlans(plans)
            val latestUnlinkedSignal = plans.firstOrNull()
                ?.let { latestUnlinkedSignalForPlan(it, zone, todayEpochDay) }
            val statsActivities = (
                linkedActivities +
                    listOfNotNull(latestUnlinkedSignal)
                ).distinctBy(ActivityEntity::activityId)
            LocalStatsLedgerSlice(
                plans = plans,
                activities = activitySlices(statsActivities),
                profileExists = profile != null,
                hasMorePlans = false,
                hasMoreActivities = false,
                timeZone = timeZone,
                todayEpochDay = todayEpochDay,
                phaseReview = phaseReview(
                    plans.firstOrNull { it.plan.state == "active" },
                    profile,
                    availability,
                    todayEpochDay,
                ),
                profile = profile,
                recordedAggregates = exactRecordedAggregates(),
                weekActualAggregates = exactWeekActualAggregates(plans, zone),
            )
        }

    override suspend fun history(
        limits: LocalSurfaceReadLimits,
        planOffset: Int,
        activityOffset: Int,
    ): LocalHistoryLedgerSlice =
        database.withTransaction {
            require(planOffset >= 0)
            require(activityOffset >= 0)
            val profile = database.profileSettingsDao().get()
            val timeZone = profile?.timeZone ?: ZoneId.systemDefault().id
            val zone = ZoneId.of(timeZone)
            val todayEpochDay = todayEpochDay(timeZone)
            val availability = database.profileSettingsDao().availabilityDays(limit = MAX_AVAILABILITY_DAYS).map { it.dayOfWeek }
            val activePlan = database.goalPlanDao().activePlans(MAX_ACTIVE_PLANS).take(1)
            val pastPlanWindow = database.goalPlanDao().historyPastPlansPage(
                limit = limits.historyPlans + 1,
                offset = planOffset,
            )
            val plans = planSlices(
                activePlan + pastPlanWindow.take(limits.historyPlans),
                limits,
                limits.statsWeeks * MAX_WORKOUTS_PER_WEEK,
                includeHistoryAudit = true,
            )
            val linkedActivities = acceptedLinkedActivitiesForPlans(plans)
            val unlinkedWindow = database.activityLedgerDao().acceptedUnlinkedActivitiesPage(
                limit = limits.historyActivities + 1,
                offset = activityOffset,
            )
            val unlinkedPage = unlinkedWindow.take(limits.historyActivities)
            val outsidePlanActivities = if (unlinkedPage.isEmpty()) {
                emptyList()
            } else {
                val epochDays = unlinkedPage.associateWith { activity ->
                    Instant.ofEpochMilli(activity.occurredAtEpochMillis)
                        .atZone(zone)
                        .toLocalDate()
                        .toEpochDay()
                }
                val assignmentCandidates = loadAssignmentCandidates(
                    requireNotNull(epochDays.values.minOrNull()),
                    requireNotNull(epochDays.values.maxOrNull()),
                )
                unlinkedPage.filter { activity ->
                    val assignment = bestAssignment(
                        epochDay = requireNotNull(epochDays[activity]),
                        candidates = assignmentCandidates,
                    )
                    assignment == null
                }
            }
            LocalHistoryLedgerSlice(
                plans = plans,
                activities = activitySlices(
                    (linkedActivities + outsidePlanActivities)
                        .distinctBy(ActivityEntity::activityId),
                ),
                hasMorePlans = pastPlanWindow.size > limits.historyPlans,
                hasMoreActivities = unlinkedWindow.size > limits.historyActivities,
                timeZone = timeZone,
                todayEpochDay = todayEpochDay,
                phaseReview = phaseReview(
                    plans.firstOrNull { it.plan.state == "active" },
                    profile,
                    availability,
                    todayEpochDay,
                ),
                nextPlanOffset = if (pastPlanWindow.size > limits.historyPlans) {
                    planOffset + limits.historyPlans
                } else {
                    null
                },
                nextActivityOffset = if (unlinkedWindow.size > limits.historyActivities) {
                    activityOffset + limits.historyActivities
                } else {
                    null
                },
                weekActualAggregates = exactWeekActualAggregates(plans, zone),
            )
        }

    override suspend fun historyPlan(
        planId: String,
        limits: LocalSurfaceReadLimits,
    ): LocalHistoryLedgerSlice? = database.withTransaction {
        require(planId.isNotBlank())
        val plan = database.goalPlanDao().plan(planId)
            ?.takeIf { it.state in setOf("active", "completed", "archived") }
            ?: return@withTransaction null
        val profile = database.profileSettingsDao().get()
        val timeZone = profile?.timeZone ?: ZoneId.systemDefault().id
        val zone = ZoneId.of(timeZone)
        val todayEpochDay = todayEpochDay(timeZone)
        val availability = database.profileSettingsDao()
            .availabilityDays(limit = MAX_AVAILABILITY_DAYS)
            .map { it.dayOfWeek }
        val plans = planSlices(
            plans = listOf(plan),
            limits = limits,
            workoutLimitPerPlan = limits.statsWeeks * MAX_WORKOUTS_PER_WEEK,
            includeHistoryAudit = true,
        )
        val unlinkedContext = assignedUnlinkedContextForPlan(
            plan = plans.single(),
            zone = zone,
            limit = minOf(limits.historyActivities, MAX_PLAN_CONTEXT_ITEMS),
        )
        LocalHistoryLedgerSlice(
            plans = plans,
            activities = activitySlices(
                (acceptedLinkedActivitiesForPlans(plans) + unlinkedContext)
                    .distinctBy(ActivityEntity::activityId),
            ),
            hasMorePlans = false,
            hasMoreActivities = false,
            timeZone = timeZone,
            todayEpochDay = todayEpochDay,
            phaseReview = phaseReview(
                plans.firstOrNull { it.plan.state == "active" },
                profile,
                availability,
                todayEpochDay,
            ),
            weekActualAggregates = exactWeekActualAggregates(plans, zone),
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
            val pendingGoal = database.goalPlanDao().pendingGoals(MAX_CURRENT_GOALS).singleOrNull()
            LocalSettingsLedgerSlice(
                profile = profile,
                availabilityDays = availability,
                activePlan = activePlan,
                versionName = versionName,
                buildRevision = buildRevision,
                pendingGoal = pendingGoal,
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
        val workoutLimit = plans.size * workoutLimitPerPlan
        val workouts = (
            if (includeHistoryAudit) {
                dao.historyWorkoutsForPlans(planIds, limit = workoutLimit)
            } else {
                dao.visibleWorkoutsForPlans(planIds, limit = workoutLimit)
            }
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

    private suspend fun acceptedLinkedActivitiesForPlans(
        plans: List<LocalPlanLedgerSlice>,
    ): List<ActivityEntity> {
        val workoutIds = plans.flatMap { plan ->
            plan.workouts.map(WorkoutEntity::workoutId)
        }
        return workoutIds.distinct().chunked(MAX_SQLITE_IN_IDS).flatMap { ids ->
            val count = database.activityLedgerDao()
                .acceptedActivityCountLinkedToWorkouts(ids)
            require(count <= ids.size) {
                "The local ledger contains more than one accepted activity for a workout."
            }
            database.activityLedgerDao().acceptedActivitiesLinkedToWorkouts(ids, count)
                .also { rows ->
                    check(rows.size == count) {
                        "The linked activity ledger changed during the surface read."
                    }
                }
        }
    }

    private suspend fun exactRecordedAggregates(): List<LocalRecordedAggregateLedgerRow> {
        val rows = database.activityLedgerDao().acceptedStatsAggregates() +
            database.activityLedgerDao().directFeedbackStatsAggregates()
        return rows
            .groupBy { storedProvenance(it.provenance) }
            .map { (provenance, grouped) ->
                LocalRecordedAggregateLedgerRow(
                    provenance = provenance,
                    runs = grouped.sumOf(StoredStatsAggregateRow::runs),
                    distanceMeters = grouped.sumOf(StoredStatsAggregateRow::distanceMeters),
                    durationSeconds = grouped.sumOf(StoredStatsAggregateRow::durationSeconds),
                    longestRunMeters = grouped.mapNotNull(StoredStatsAggregateRow::longestRunMeters)
                        .maxOrNull(),
                    pairedDistanceMeters =
                        grouped.sumOf(StoredStatsAggregateRow::pairedDistanceMeters),
                    pairedDurationSeconds =
                        grouped.sumOf(StoredStatsAggregateRow::pairedDurationSeconds),
                    heartRateDurationSeconds =
                        grouped.sumOf(StoredStatsAggregateRow::heartRateDurationSeconds),
                    heartRateBeatsSeconds =
                        grouped.sumOf(StoredStatsAggregateRow::heartRateBeatsSeconds),
                )
            }
            .sortedBy { it.provenance.ordinal }
    }

    private suspend fun assignedUnlinkedContextForPlan(
        plan: LocalPlanLedgerSlice,
        zone: ZoneId,
        limit: Int,
    ): List<ActivityEntity> {
        if (plan.weeks.isEmpty() || limit <= 0) return emptyList()
        val fromEpochDay = plan.weeks.minOf(PlanWeekEntity::startEpochDay)
        val throughEpochDay = plan.weeks.maxOf { it.startEpochDay + 6 }
        val assignmentCandidates = loadAssignmentCandidates(
            fromEpochDay,
            throughEpochDay,
        )
        val fromInclusive = LocalDate.ofEpochDay(fromEpochDay)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val toExclusive = LocalDate.ofEpochDay(throughEpochDay + 1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val selected = mutableListOf<ActivityEntity>()
        var beforeEpochMillis = toExclusive
        var beforeActivityId = HIGH_CURSOR_ACTIVITY_ID
        do {
            val page = database.activityLedgerDao().acceptedUnlinkedActivitiesPageInRange(
                fromInclusive = fromInclusive,
                toExclusive = toExclusive,
                beforeEpochMillis = beforeEpochMillis,
                beforeActivityId = beforeActivityId,
                limit = PLAN_CONTEXT_SCAN_PAGE_SIZE,
            )
            page.forEach { activity ->
                val epochDay = Instant.ofEpochMilli(activity.occurredAtEpochMillis)
                    .atZone(zone)
                    .toLocalDate()
                    .toEpochDay()
                if (
                    bestAssignment(epochDay, assignmentCandidates)?.planId ==
                    plan.plan.planId
                ) {
                    selected += activity
                }
            }
            page.lastOrNull()?.let {
                beforeEpochMillis = it.occurredAtEpochMillis
                beforeActivityId = it.activityId
            }
        } while (
            selected.size < limit &&
            page.size == PLAN_CONTEXT_SCAN_PAGE_SIZE
        )
        return selected.take(limit)
    }

    private suspend fun latestUnlinkedSignalForPlan(
        plan: LocalPlanLedgerSlice,
        zone: ZoneId,
        todayEpochDay: Long,
    ): ActivityEntity? {
        val recentStart = todayEpochDay - 28
        val eligibleDays = plan.weeks
            .asSequence()
            .flatMap { week -> (week.startEpochDay..week.startEpochDay + 6).asSequence() }
            .filter { it in recentStart..todayEpochDay }
            .distinct()
            .sorted()
            .toList()
        return contiguousRanges(eligibleDays)
            .mapNotNull { range ->
                database.activityLedgerDao().latestAcceptedUnlinkedExtraInRange(
                    fromInclusive = LocalDate.ofEpochDay(range.first)
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli(),
                    toExclusive = LocalDate.ofEpochDay(range.last + 1)
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli(),
                )
            }
            .maxWithOrNull(
                compareBy<ActivityEntity>(
                    ActivityEntity::occurredAtEpochMillis,
                    ActivityEntity::updatedAtEpochMillis,
                    ActivityEntity::activityId,
                ),
            )
    }

    private suspend fun exactWeekActualAggregates(
        plans: List<LocalPlanLedgerSlice>,
        zone: ZoneId,
    ): List<LocalWeekActualAggregateLedgerRow> {
        if (plans.isEmpty()) return emptyList()
        val displayedWeeks = plans.flatMap { plan ->
            plan.weeks.map { week -> week.weekId to (plan.plan.planId to week) }
        }.toMap()
        if (displayedWeeks.isEmpty()) return emptyList()
        val weekIds = displayedWeeks.keys.toList()
        val linkedByWeek = weekIds.chunked(MAX_SQLITE_IN_IDS).flatMap { ids ->
            database.activityLedgerDao().acceptedLinkedAggregatesForWeeks(ids)
        }.associate { it.weekId to it.toLoadAggregate() }
        val directByWeek = weekIds.chunked(MAX_SQLITE_IN_IDS).flatMap { ids ->
            database.activityLedgerDao().directFeedbackAggregatesForWeeks(ids)
        }.associate { it.weekId to it.toLoadAggregate() }

        val fromEpochDay = displayedWeeks.values.minOf { it.second.startEpochDay }
        val throughEpochDay = displayedWeeks.values.maxOf { it.second.startEpochDay + 6 }
        val assignmentCandidates = loadAssignmentCandidates(
            fromEpochDay,
            throughEpochDay,
        )

        val unlinkedByWeek = mutableMapOf<String, StoredLoadAggregateRow>()
        val fromInclusive = LocalDate.ofEpochDay(fromEpochDay)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val toExclusive = LocalDate.ofEpochDay(throughEpochDay + 1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        var beforeEpochMillis = toExclusive
        var beforeActivityId = HIGH_CURSOR_ACTIVITY_ID
        do {
            val page = database.activityLedgerDao().acceptedUnlinkedStatsPage(
                fromInclusive = fromInclusive,
                toExclusive = toExclusive,
                beforeEpochMillis = beforeEpochMillis,
                beforeActivityId = beforeActivityId,
                limit = UNLINKED_STATS_PAGE_SIZE,
            )
            page.forEach { activity ->
                val epochDay = Instant.ofEpochMilli(activity.occurredAtEpochMillis)
                    .atZone(zone)
                    .toLocalDate()
                    .toEpochDay()
                val assignment = bestAssignment(epochDay, assignmentCandidates)
                val weekId = assignment?.weekId
                    ?.takeIf(displayedWeeks::containsKey)
                    ?: return@forEach
                unlinkedByWeek[weekId] =
                    unlinkedByWeek.getOrDefault(weekId, emptyStoredLoadAggregate()) +
                    activity.toLoadAggregate()
            }
            page.lastOrNull()?.let {
                beforeEpochMillis = it.occurredAtEpochMillis
                beforeActivityId = it.activityId
            }
        } while (page.size == UNLINKED_STATS_PAGE_SIZE)

        return displayedWeeks.map { (weekId, owner) ->
            val linked = linkedByWeek[weekId] ?: emptyStoredLoadAggregate()
            val direct = directByWeek[weekId] ?: emptyStoredLoadAggregate()
            val unlinked = unlinkedByWeek[weekId] ?: emptyStoredLoadAggregate()
            val total = linked + unlinked + direct
            LocalWeekActualAggregateLedgerRow(
                planId = owner.first,
                weekId = weekId,
                activityRuns = linked.runs + unlinked.runs,
                unlinkedRuns = unlinked.runs,
                distanceMeters = total.distanceMeters,
                durationSeconds = total.durationSeconds,
                longestRunMeters = total.longestRunMeters,
                pairedDistanceMeters = total.pairedDistanceMeters,
                pairedDurationSeconds = total.pairedDurationSeconds,
                heartRateDurationSeconds = total.heartRateDurationSeconds,
                heartRateBeatsSeconds = total.heartRateBeatsSeconds,
                painFlags = total.painFlags,
                hardFlags = total.hardFlags,
            )
        }
    }

    private suspend fun loadAssignmentCandidates(
        fromEpochDay: Long,
        throughEpochDay: Long,
    ): List<StoredPlanWeekAssignmentCandidate> {
        val candidates = mutableListOf<StoredPlanWeekAssignmentCandidate>()
        var offset = 0
        do {
            val page = database.goalPlanDao().planWeekAssignmentCandidates(
                fromEpochDay = fromEpochDay,
                throughEpochDay = throughEpochDay,
                limit = ASSIGNMENT_CANDIDATE_PAGE_SIZE,
                offset = offset,
            )
            candidates += page
            offset += page.size
        } while (page.size == ASSIGNMENT_CANDIDATE_PAGE_SIZE)
        return candidates
    }

    private fun bestAssignment(
        epochDay: Long,
        candidates: List<StoredPlanWeekAssignmentCandidate>,
    ): StoredPlanWeekAssignmentCandidate? = candidates
        .asSequence()
        .filter {
            epochDay in it.startEpochDay..(it.startEpochDay + 6)
        }
        .maxWithOrNull(
            compareBy<StoredPlanWeekAssignmentCandidate>(
                { it.planState == "active" },
                StoredPlanWeekAssignmentCandidate::planStartEpochDay,
                StoredPlanWeekAssignmentCandidate::planId,
            ),
        )

    private fun storedProvenance(value: String): LocalPlanProvenance = when (value) {
        "active" -> LocalPlanProvenance.ACTIVE
        "completed" -> LocalPlanProvenance.COMPLETED
        "archived" -> LocalPlanProvenance.ARCHIVED
        "unlinked" -> LocalPlanProvenance.UNLINKED
        else -> LocalPlanProvenance.OTHER
    }

    private fun contiguousRanges(days: List<Long>): List<LongRange> {
        if (days.isEmpty()) return emptyList()
        val ranges = mutableListOf<LongRange>()
        var start = days.first()
        var end = start
        days.drop(1).forEach { day ->
            if (day == end + 1) {
                end = day
            } else {
                ranges += start..end
                start = day
                end = day
            }
        }
        ranges += start..end
        return ranges
    }

    private fun reviewCursor(
        previous: LocalInboxActivityCursor,
        rows: List<ActivityEntity>,
        pageSize: Int,
    ): LocalInboxActivityCursor {
        if (previous.exhausted || rows.size <= pageSize) return previous.copy(exhausted = true)
        val last = requireNotNull(rows.getOrNull(pageSize - 1))
        return LocalInboxActivityCursor(last.occurredAtEpochMillis, last.activityId)
    }

    private fun feedbackCursor(
        previous: LocalInboxFeedbackCursor,
        rows: List<WorkoutFeedbackEntity>,
        pageSize: Int,
    ): LocalInboxFeedbackCursor {
        if (previous.exhausted || rows.size <= pageSize) return previous.copy(exhausted = true)
        val last = requireNotNull(rows.getOrNull(pageSize - 1))
        return LocalInboxFeedbackCursor(last.recordedAtEpochMillis, last.feedbackId)
    }

    private fun healthCursor(
        previous: LocalInboxHealthConnectCursor,
        rows: List<HealthConnectMappingEntity>,
        pageSize: Int,
    ): LocalInboxHealthConnectCursor {
        if (previous.exhausted || rows.size <= pageSize) return previous.copy(exhausted = true)
        val last = requireNotNull(rows.getOrNull(pageSize - 1))
        return LocalInboxHealthConnectCursor(last.lastObservedAtEpochMillis, last.mappingId)
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

    private suspend fun pendingHealthConnect(limit: Int): List<LocalHealthConnectPendingReadModel> =
        pendingHealthConnect(database.importLedgerDao().pendingHealthConnectMappings(limit))

    private suspend fun pendingHealthConnect(
        mappings: List<HealthConnectMappingEntity>,
    ): List<LocalHealthConnectPendingReadModel> {
        if (mappings.isEmpty()) return emptyList()
        val importDao = database.importLedgerDao()
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

    private companion object {
        const val MAX_ACTIVE_PLANS = 2
        const val MAX_CURRENT_GOALS = 2
        const val MAX_AVAILABILITY_DAYS = 7
        const val MAX_WORKOUTS_PER_WEEK = 14
        const val MAX_LIFECYCLE_EVENTS = 50
        const val MAX_SUMMARY_WARNINGS_PER_PLAN = 16
        const val MAX_HISTORY_ADJUSTMENT_ROWS_PER_PLAN = 128
        const val MAX_PENDING_HEALTH_CONNECT = 50
        const val MAX_CONSEQUENCE_OPTIONS = 8
        const val MAX_UNDO_ADJUSTMENTS_PER_WORKOUT = 8
        const val MAX_PHASE_ACTIVITIES = 512
        const val ASSIGNMENT_CANDIDATE_PAGE_SIZE = 512
        const val UNLINKED_STATS_PAGE_SIZE = 512
        const val PLAN_CONTEXT_SCAN_PAGE_SIZE = 128
        const val MAX_PLAN_CONTEXT_ITEMS = 50
        const val HIGH_CURSOR_ACTIVITY_ID = "\uFFFF"
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

private fun emptyStoredLoadAggregate(): StoredLoadAggregateRow =
    StoredLoadAggregateRow(
        runs = 0,
        distanceMeters = 0,
        durationSeconds = 0,
        longestRunMeters = null,
        pairedDistanceMeters = 0,
        pairedDurationSeconds = 0,
        heartRateDurationSeconds = 0,
        heartRateBeatsSeconds = 0,
        painFlags = 0,
        hardFlags = 0,
    )

private operator fun StoredLoadAggregateRow.plus(
    other: StoredLoadAggregateRow,
): StoredLoadAggregateRow = StoredLoadAggregateRow(
    runs = runs + other.runs,
    distanceMeters = distanceMeters + other.distanceMeters,
    durationSeconds = durationSeconds + other.durationSeconds,
    longestRunMeters = listOfNotNull(longestRunMeters, other.longestRunMeters).maxOrNull(),
    pairedDistanceMeters = pairedDistanceMeters + other.pairedDistanceMeters,
    pairedDurationSeconds = pairedDurationSeconds + other.pairedDurationSeconds,
    heartRateDurationSeconds = heartRateDurationSeconds + other.heartRateDurationSeconds,
    heartRateBeatsSeconds = heartRateBeatsSeconds + other.heartRateBeatsSeconds,
    painFlags = painFlags + other.painFlags,
    hardFlags = hardFlags + other.hardFlags,
)

private fun StoredWeekLoadAggregateRow.toLoadAggregate(): StoredLoadAggregateRow =
    StoredLoadAggregateRow(
        runs = runs,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        longestRunMeters = longestRunMeters,
        pairedDistanceMeters = pairedDistanceMeters,
        pairedDurationSeconds = pairedDurationSeconds,
        heartRateDurationSeconds = heartRateDurationSeconds,
        heartRateBeatsSeconds = heartRateBeatsSeconds,
        painFlags = painFlags,
        hardFlags = hardFlags,
    )

private fun StoredUnlinkedActivityStatsRow.toLoadAggregate(): StoredLoadAggregateRow {
    val paired = (distanceMeters ?: 0) > 0 && (durationSeconds ?: 0) > 0
    val hasHeartRate = averageHeartRateBpm != null && (durationSeconds ?: 0) > 0
    return StoredLoadAggregateRow(
        runs = 1,
        distanceMeters = (distanceMeters ?: 0).toLong(),
        durationSeconds = (durationSeconds ?: 0).toLong(),
        longestRunMeters = distanceMeters,
        pairedDistanceMeters = if (paired) requireNotNull(distanceMeters).toLong() else 0,
        pairedDurationSeconds = if (paired) requireNotNull(durationSeconds).toLong() else 0,
        heartRateDurationSeconds =
            if (hasHeartRate) requireNotNull(durationSeconds).toLong() else 0,
        heartRateBeatsSeconds = if (hasHeartRate) {
            requireNotNull(averageHeartRateBpm).toLong() * requireNotNull(durationSeconds)
        } else {
            0
        },
        painFlags = if (pain == true) 1 else 0,
        hardFlags = if (feltHard == true) 1 else 0,
    )
}
