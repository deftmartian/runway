package dev.deftmartian.runway.data

import androidx.room.withTransaction
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bounded Room reader for the standalone surfaces.
 *
 * Bulk joins are intentionally not emulated with unbounded reads. Until the dedicated DAO methods
 * exist, plan children are loaded with a strictly capped fan-out and missing goal titles use the
 * neutral surface placeholder.
 */
class RoomLocalSurfaceLedgerReader(
    private val database: RunwayLedgerDatabase,
    private val versionName: String? = null,
    private val buildRevision: String? = null,
) : LocalSurfaceLedgerReader {
    override suspend fun calendar(
        fromEpochDay: Long,
        throughEpochDay: Long,
        limits: LocalSurfaceReadLimits,
    ): LocalCalendarLedgerSlice = database.withTransaction {
        val timeZone = database.profileSettingsDao().get()?.timeZone ?: ZoneId.systemDefault().id
        val zone = ZoneId.of(timeZone)
        val review = reviewWindow(limits.inboxActivities)
        val plans = database.goalPlanDao().activePlans(MAX_ACTIVE_PLANS)
            .take(1)
            .let { planSlices(it, limits, limits.calendarWorkouts) }
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
        )
    }

    override suspend fun inbox(limits: LocalSurfaceReadLimits): LocalInboxLedgerSlice =
        database.withTransaction {
            val review = reviewWindow(limits.inboxActivities)
            LocalInboxLedgerSlice(
                reviewCount = review.visibleCount,
                reviewCountIsExact = review.isExact,
                hasMore = !review.isExact,
                activities = activitySlices(review.items),
            )
        }

    override suspend fun stats(limits: LocalSurfaceReadLimits): LocalStatsLedgerSlice =
        database.withTransaction {
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
            )
        }

    override suspend fun history(limits: LocalSurfaceReadLimits): LocalHistoryLedgerSlice =
        database.withTransaction {
            val planWindow = surfacePlans(limits.historyPlans + 1)
            val activityWindow = database.activityLedgerDao().acceptedActivitiesInRange(
                fromInclusive = Long.MIN_VALUE,
                toExclusive = Long.MAX_VALUE,
                limit = limits.historyActivities + 1,
            )
            LocalHistoryLedgerSlice(
                plans = planSlices(
                    planWindow.take(limits.historyPlans),
                    limits,
                    limits.statsWeeks * MAX_WORKOUTS_PER_WEEK,
                ),
                activities = activitySlices(activityWindow.take(limits.historyActivities)),
                hasMorePlans = planWindow.size > limits.historyPlans,
                hasMoreActivities = activityWindow.size > limits.historyActivities,
            )
        }

    override suspend fun settings(limits: LocalSurfaceReadLimits): LocalSettingsLedgerSlice =
        database.withTransaction {
            val profile = database.profileSettingsDao().get()
            val activePlan = database.goalPlanDao().activePlans(MAX_ACTIVE_PLANS)
                .take(1)
                .let { planSlices(it, limits, limits.calendarWorkouts).firstOrNull() }
            LocalSettingsLedgerSlice(
                profile = profile,
                availabilityDays = database.profileSettingsDao().availabilityDays(limit = MAX_AVAILABILITY_DAYS),
                activePlan = activePlan,
                versionName = versionName,
                buildRevision = buildRevision,
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
        )
    }

    private suspend fun planSlices(
        plans: List<PlanEntity>,
        limits: LocalSurfaceReadLimits,
        workoutLimitPerPlan: Int,
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
        val workouts = dao.visibleWorkoutsForPlans(
            planIds,
            limit = plans.size * workoutLimitPerPlan,
        ).groupBy(WorkoutEntity::planId)
        val workoutPlan = workouts.flatMap { (planId, rows) ->
            rows.map { it.workoutId to planId }
        }.toMap()
        val feedback = database.activityLedgerDao().workoutFeedbackForPlans(
            planIds,
            plans.size * workoutLimitPerPlan,
        ).groupBy { workoutPlan[it.workoutId] }
        val lifecycle = dao.lifecycleEventsForPlans(
            planIds,
            plans.size * MAX_LIFECYCLE_EVENTS,
        ).groupBy(PlanLifecycleEventEntity::planId)
        return plans.map { plan ->
            LocalPlanLedgerSlice(
                goal = goals[plan.goalId],
                plan = plan,
                weeks = weeks[plan.planId].orEmpty().take(limits.statsWeeks),
                workouts = workouts[plan.planId].orEmpty().take(workoutLimitPerPlan),
                feedback = feedback[plan.planId].orEmpty().take(workoutLimitPerPlan),
                lifecycle = lifecycle[plan.planId].orEmpty().take(MAX_LIFECYCLE_EVENTS),
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
        val feedback = database.activityLedgerDao()
            .activityFeedbackForActivities(
                activityIds = activities.map(ActivityEntity::activityId),
                limit = activities.size,
            )
            .associateBy(ActivityFeedbackEntity::activityId)
        return activities.map { activity ->
            LocalActivityLedgerSlice(
                activity = activity,
                feedback = feedback[activity.activityId],
            )
        }
    }

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
    }
}
