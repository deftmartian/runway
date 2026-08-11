package dev.deftmartian.runway.data

import androidx.room.withTransaction
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Maintains a small persisted future horizon for an ongoing routine.  It is deliberately invoked
 * at read/action boundaries rather than relying on background execution to make future runs exist.
 */
class LocalRoutineRepository(
    private val database: RunwayLedgerDatabase,
) {
    suspend fun updateWeekGoal(
        request: LocalRoutineWeekGoalRequest,
    ): LocalRoutineWeekGoalResult = database.withTransaction {
        require(request.planId.isNotBlank() && request.weekId.isNotBlank())
        require(request.operationId.isNotBlank() && request.occurredAtEpochMillis >= 0)
        val target = RoutineWeeklyTarget(request.targetDistanceMeters, request.targetDurationSeconds)
        require(target.usesReadableUnits())
        val adjustmentId = "routine-week-goal-${request.operationId}"
        val adjustments = database.adjustmentDao()
        if (adjustments.adjustmentExists(adjustmentId)) {
            return@withTransaction LocalRoutineWeekGoalResult.AlreadyApplied
        }
        val plans = database.goalPlanDao()
        val plan = plans.plan(request.planId)
            ?.takeIf(::isActiveRoutine)
            ?: return@withTransaction LocalRoutineWeekGoalResult.Rejected
        val week = plans.week(request.weekId)
            ?.takeIf { it.planId == plan.planId && it.startEpochDay > request.todayEpochDay }
            ?: return@withTransaction LocalRoutineWeekGoalResult.Rejected
        val previous = RoutineWeeklyTarget.optional(
            week.generatedLoadMeters,
            week.generatedLoadDurationSeconds,
        )
        if (previous == target) {
            return@withTransaction LocalRoutineWeekGoalResult.Unchanged
        }
        val workouts = plans.workoutsForWeek(week.weekId, limit = 20)
            .filter {
                it.currentWorkoutType != "rest" &&
                    it.currentStatus == "planned" &&
                    it.tombstonedAtEpochMillis == null
            }
        if (!target.supportsRunCount(workouts.size)) {
            return@withTransaction LocalRoutineWeekGoalResult.Rejected
        }
        val replacements = RoutinePlanPersistenceMapper.retargetWorkouts(
            workouts,
            target,
            request.occurredAtEpochMillis,
        )
        check(
            plans.updateRoutineWeekTarget(
                plan.planId,
                week.weekId,
                target.distanceMeters,
                target.durationSeconds,
            ) == 1,
        )
        replacements.forEach { plans.saveWorkout(it) }
        check(plans.touchActiveRoutine(plan.planId, request.occurredAtEpochMillis) == 1)
        persistWeekGoalChange(
            adjustments,
            plan,
            week,
            previous,
            target,
            workouts.zip(replacements),
            request,
            adjustmentId,
        )
        LocalRoutineWeekGoalResult.Updated(previous, target)
    }

    suspend fun updateWeekDistribution(
        request: LocalRoutineWeekDistributionRequest,
    ): LocalRoutineWeekDistributionResult = database.withTransaction {
        require(request.planId.isNotBlank() && request.weekId.isNotBlank())
        require(request.operationId.isNotBlank() && request.occurredAtEpochMillis >= 0)
        val adjustmentId = "routine-week-distribution-${request.operationId}"
        val adjustments = database.adjustmentDao()
        if (adjustments.adjustmentExists(adjustmentId)) {
            return@withTransaction LocalRoutineWeekDistributionResult.AlreadyApplied
        }
        val plans = database.goalPlanDao()
        val plan = plans.plan(request.planId)
            ?.takeIf(::isActiveRoutine)
            ?: return@withTransaction LocalRoutineWeekDistributionResult.Rejected
        val week = plans.week(request.weekId)
            ?.takeIf { it.planId == plan.planId && it.startEpochDay > request.todayEpochDay }
            ?: return@withTransaction LocalRoutineWeekDistributionResult.Rejected
        val target = RoutineWeeklyTarget.optional(
            week.generatedLoadMeters,
            week.generatedLoadDurationSeconds,
        ) ?: return@withTransaction LocalRoutineWeekDistributionResult.Rejected
        val workouts = plans.workoutsForWeek(week.weekId, limit = 20)
            .filter {
                it.currentWorkoutType != "rest" &&
                    it.currentStatus == "planned" &&
                    it.tombstonedAtEpochMillis == null
            }
            .sortedWith(compareBy(WorkoutEntity::currentScheduledEpochDay, WorkoutEntity::position))
        val allocations = request.allocations.associateBy(LocalRoutineRunAllocation::workoutId)
        if (
            allocations.size != request.allocations.size ||
            allocations.keys != workouts.mapTo(linkedSetOf(), WorkoutEntity::workoutId)
        ) return@withTransaction LocalRoutineWeekDistributionResult.Rejected
        val replacements = workouts.map { workout ->
            val allocation = requireNotNull(allocations[workout.workoutId])
            if (!allocation.matches(target)) {
                return@withTransaction LocalRoutineWeekDistributionResult.Rejected
            }
            if (
                workout.currentDistanceMeters == allocation.targetDistanceMeters &&
                workout.currentDurationSeconds == allocation.targetDurationSeconds
            ) {
                workout
            } else {
                workout.copy(
                    currentDistanceMeters = allocation.targetDistanceMeters,
                    currentDurationSeconds = allocation.targetDurationSeconds,
                    currentPrescriptionKind = if (target.distanceMeters != null) "distance" else "timed",
                    currentReason = "Adjusted within this week's running goal.",
                    updatedAtEpochMillis = request.occurredAtEpochMillis,
                )
            }
        }
        if (!distributionMatchesTarget(replacements, target)) {
            return@withTransaction LocalRoutineWeekDistributionResult.Rejected
        }
        val workoutChanges = workouts.zip(replacements).filter { (before, after) -> before != after }
        if (workoutChanges.isEmpty()) {
            return@withTransaction LocalRoutineWeekDistributionResult.Unchanged
        }
        workoutChanges.forEach { (_, after) -> plans.saveWorkout(after) }
        check(plans.touchActiveRoutine(plan.planId, request.occurredAtEpochMillis) == 1)
        persistWeekDistributionChange(
            adjustments = adjustments,
            plan = plan,
            week = week,
            target = target,
            workoutChanges = workoutChanges,
            request = request,
            adjustmentId = adjustmentId,
        )
        LocalRoutineWeekDistributionResult.Updated
    }

    suspend fun ensureHorizon(
        planId: String,
        throughEpochDay: Long,
        updatedAtEpochMillis: Long,
    ): LocalRoutineHorizonResult = database.withTransaction {
        if (invalidHorizonRequest(planId, updatedAtEpochMillis)) {
            return@withTransaction LocalRoutineHorizonResult.Rejected
        }
        val plans = database.goalPlanDao()
        val plan = plans.plan(planId) ?: return@withTransaction LocalRoutineHorizonResult.Rejected
        if (!isActiveRoutine(plan)) {
            return@withTransaction LocalRoutineHorizonResult.Rejected
        }
        val days = plans.routineScheduleDays(planId).map(RoutineScheduleDayEntity::dayOfWeek)
        val normalizedDays = runCatching { RoutinePlanPersistenceMapper.normalizedDays(days) }
            .getOrElse { return@withTransaction LocalRoutineHorizonResult.Rejected }
        val targetStart = LocalDate.ofEpochDay(throughEpochDay)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val last = plans.lastWeekForPlan(planId)
            ?: return@withTransaction LocalRoutineHorizonResult.Rejected
        if (last.startEpochDay > targetStart.toEpochDay()) {
            return@withTransaction LocalRoutineHorizonResult.Unchanged
        }
        val addedWeeks = extendHorizon(
            plans,
            plan,
            last,
            targetStart,
            normalizedDays,
            updatedAtEpochMillis,
        )
        if (addedWeeks == 0) LocalRoutineHorizonResult.Unchanged else LocalRoutineHorizonResult.Extended(addedWeeks)
    }

    private fun invalidHorizonRequest(planId: String, updatedAtEpochMillis: Long) =
        planId.isBlank() || updatedAtEpochMillis < 0

    private fun isActiveRoutine(plan: PlanEntity) =
        plan.state == "active" && plan.phaseType == "routine" && plan.endEpochDay == null

    private suspend fun persistWeekGoalChange(
        adjustments: AdjustmentDao,
        plan: PlanEntity,
        week: PlanWeekEntity,
        previous: RoutineWeeklyTarget?,
        target: RoutineWeeklyTarget,
        workoutChanges: List<Pair<WorkoutEntity, WorkoutEntity>>,
        request: LocalRoutineWeekGoalRequest,
        adjustmentId: String,
    ) {
        val groupId = "$adjustmentId:effect"
        adjustments.insertAdjustment(
            PlanAdjustmentEntity(
                adjustmentId = adjustmentId,
                planId = plan.planId,
                workoutId = null,
                sourceActivityId = null,
                adjustmentType = "routine_week_goal",
                state = "applied",
                measuredLoadSharePercent = null,
                projectedRampPercent = null,
                affectedWorkoutCount = workoutChanges.size,
                createdAtEpochMillis = request.occurredAtEpochMillis,
                triggerKind = "manual_routine_week_goal",
                triggerId = week.weekId,
                triggerVersion = request.operationId,
            ),
        )
        adjustments.saveEffectGroup(
            AdjustmentEffectGroupEntity(
                groupId = groupId,
                adjustmentId = adjustmentId,
                ordinal = 0,
                effectType = "routine_week_goal",
                sourceWeekLoadMeters = previous?.distanceMeters,
                destinationWeekLoadMeters = target.distanceMeters,
                sourceWeekLoadDurationSeconds = previous?.durationSeconds,
                destinationWeekLoadDurationSeconds = target.durationSeconds,
            ),
        )
        workoutChanges.forEachIndexed { ordinal, (before, after) ->
            adjustments.saveWorkoutEffect(
                routineWorkoutEffect(groupId, ordinal, before, after),
            )
        }
        adjustments.saveDecision(
            PlanDecisionEntity(
                decisionId = "$adjustmentId:decision",
                adjustmentId = adjustmentId,
                decisionType = "set_routine_week_goal",
                affectedWorkoutCount = workoutChanges.size,
                effectiveFromEpochDay = week.startEpochDay,
                decidedAtEpochMillis = request.occurredAtEpochMillis,
                affectedDistanceMeters = target.distanceMeters,
                affectedDurationSeconds = target.durationSeconds,
            ),
        )
    }

    private suspend fun persistWeekDistributionChange(
        adjustments: AdjustmentDao,
        plan: PlanEntity,
        week: PlanWeekEntity,
        target: RoutineWeeklyTarget,
        workoutChanges: List<Pair<WorkoutEntity, WorkoutEntity>>,
        request: LocalRoutineWeekDistributionRequest,
        adjustmentId: String,
    ) {
        val groupId = "$adjustmentId:effect"
        adjustments.insertAdjustment(
            PlanAdjustmentEntity(
                adjustmentId = adjustmentId,
                planId = plan.planId,
                workoutId = null,
                sourceActivityId = null,
                adjustmentType = "routine_week_distribution",
                state = "applied",
                measuredLoadSharePercent = null,
                projectedRampPercent = null,
                affectedWorkoutCount = workoutChanges.size,
                createdAtEpochMillis = request.occurredAtEpochMillis,
                triggerKind = "manual_routine_week_distribution",
                triggerId = week.weekId,
                triggerVersion = request.operationId,
            ),
        )
        adjustments.saveEffectGroup(
            AdjustmentEffectGroupEntity(
                groupId = groupId,
                adjustmentId = adjustmentId,
                ordinal = 0,
                effectType = "routine_week_distribution",
                sourceWeekLoadMeters = target.distanceMeters,
                destinationWeekLoadMeters = target.distanceMeters,
                sourceWeekLoadDurationSeconds = target.durationSeconds,
                destinationWeekLoadDurationSeconds = target.durationSeconds,
            ),
        )
        workoutChanges.forEachIndexed { ordinal, (before, after) ->
            adjustments.saveWorkoutEffect(routineWorkoutEffect(groupId, ordinal, before, after))
        }
        adjustments.saveDecision(
            PlanDecisionEntity(
                decisionId = "$adjustmentId:decision",
                adjustmentId = adjustmentId,
                decisionType = "balance_routine_week",
                affectedWorkoutCount = workoutChanges.size,
                effectiveFromEpochDay = week.startEpochDay,
                decidedAtEpochMillis = request.occurredAtEpochMillis,
                affectedDistanceMeters = target.distanceMeters,
                affectedDurationSeconds = target.durationSeconds,
            ),
        )
    }

    private suspend fun extendHorizon(
        plans: GoalPlanDao,
        plan: PlanEntity,
        last: PlanWeekEntity,
        targetStart: LocalDate,
        normalizedDays: List<Int>,
        updatedAtEpochMillis: Long,
    ): Int {
        var nextStart = LocalDate.ofEpochDay(last.startEpochDay).plusWeeks(1)
        var ordinal = last.ordinal + 1
        var addedWeeks = 0
        while (!nextStart.isAfter(targetStart)) {
            val week = RoutinePlanPersistenceMapper.week(plan, nextStart, ordinal)
            plans.appendRoutineWeek(
                week,
                RoutinePlanPersistenceMapper.prescribedWorkouts(
                    plan.planId,
                    week,
                    normalizedDays,
                    updatedAtEpochMillis,
                ),
            )
            addedWeeks += 1
            nextStart = nextStart.plusWeeks(1)
            ordinal += 1
        }
        return addedWeeks
    }
}

private fun LocalRoutineRunAllocation.matches(target: RoutineWeeklyTarget): Boolean = when {
    target.distanceMeters != null ->
        targetDistanceMeters != null && targetDurationSeconds == null &&
            targetDistanceMeters in 500..100_000 && targetDistanceMeters % 500 == 0
    else ->
        targetDurationSeconds != null && targetDistanceMeters == null &&
            targetDurationSeconds in 600..21_600 && targetDurationSeconds % 300 == 0
}

private fun distributionMatchesTarget(
    workouts: List<WorkoutEntity>,
    target: RoutineWeeklyTarget,
): Boolean = if (target.distanceMeters != null) {
    workouts.all { it.currentDistanceMeters != null && it.currentDurationSeconds == null } &&
        workouts.sumOf { it.currentDistanceMeters ?: 0 } == target.distanceMeters
} else {
    workouts.all { it.currentDurationSeconds != null && it.currentDistanceMeters == null } &&
        workouts.sumOf { it.currentDurationSeconds ?: 0 } == target.durationSeconds
}

private fun routineWorkoutEffect(
    groupId: String,
    ordinal: Int,
    before: WorkoutEntity,
    after: WorkoutEntity,
) = AdjustmentWorkoutEffectEntity(
    effectId = "$groupId:workout:$ordinal",
    groupId = groupId,
    workoutId = before.workoutId,
    ordinal = ordinal,
    previousScheduledEpochDay = before.currentScheduledEpochDay,
    newScheduledEpochDay = after.currentScheduledEpochDay,
    previousWorkoutType = before.currentWorkoutType,
    newWorkoutType = after.currentWorkoutType,
    previousStatus = before.currentStatus,
    newStatus = after.currentStatus,
    previousDistanceMeters = before.currentDistanceMeters,
    newDistanceMeters = after.currentDistanceMeters,
    previousDurationSeconds = before.currentDurationSeconds,
    newDurationSeconds = after.currentDurationSeconds,
    previousIntensity = before.currentIntensity,
    newIntensity = after.currentIntensity,
    previousPurpose = before.currentPurpose,
    newPurpose = after.currentPurpose,
    previousReason = before.currentReason,
    newReason = after.currentReason,
    previousTombstonedAtEpochMillis = before.tombstonedAtEpochMillis,
    newTombstonedAtEpochMillis = after.tombstonedAtEpochMillis,
    previousWarmupSeconds = before.currentWarmupSeconds,
    newWarmupSeconds = after.currentWarmupSeconds,
    previousCooldownSeconds = before.currentCooldownSeconds,
    newCooldownSeconds = after.currentCooldownSeconds,
    previousPrescriptionKind = before.currentPrescriptionKind,
    newPrescriptionKind = after.currentPrescriptionKind,
    previousWeekId = before.weekId,
    newWeekId = after.weekId,
)

sealed interface LocalRoutineHorizonResult {
    data class Extended(val addedWeeks: Int) : LocalRoutineHorizonResult
    data object Unchanged : LocalRoutineHorizonResult
    data object Rejected : LocalRoutineHorizonResult
}

data class LocalRoutineWeekGoalRequest(
    val planId: String,
    val weekId: String,
    val targetDistanceMeters: Int?,
    val targetDurationSeconds: Int?,
    val todayEpochDay: Long,
    val operationId: String,
    val occurredAtEpochMillis: Long,
)

sealed interface LocalRoutineWeekGoalResult {
    data class Updated(
        val previous: RoutineWeeklyTarget?,
        val target: RoutineWeeklyTarget,
    ) : LocalRoutineWeekGoalResult
    data object Unchanged : LocalRoutineWeekGoalResult
    data object AlreadyApplied : LocalRoutineWeekGoalResult
    data object Rejected : LocalRoutineWeekGoalResult
}

data class LocalRoutineRunAllocation(
    val workoutId: String,
    val targetDistanceMeters: Int?,
    val targetDurationSeconds: Int?,
)

data class LocalRoutineWeekDistributionRequest(
    val planId: String,
    val weekId: String,
    val allocations: List<LocalRoutineRunAllocation>,
    val todayEpochDay: Long,
    val operationId: String,
    val occurredAtEpochMillis: Long,
)

sealed interface LocalRoutineWeekDistributionResult {
    data object Updated : LocalRoutineWeekDistributionResult
    data object Unchanged : LocalRoutineWeekDistributionResult
    data object AlreadyApplied : LocalRoutineWeekDistributionResult
    data object Rejected : LocalRoutineWeekDistributionResult
}
