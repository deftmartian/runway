package dev.deftmartian.runway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class StoredPlanWeekAssignmentCandidate(
    val planId: String,
    val weekId: String,
    val startEpochDay: Long,
    val planState: String,
    val planStartEpochDay: Long,
)

@Dao
abstract class GoalPlanDao {
    @Upsert
    abstract suspend fun saveGoal(goal: GoalEntity)

    /** Include legacy pending goals when deciding whether setup needs replacement confirmation. */
    @Query("SELECT goalId FROM goals WHERE state IN ('active', 'pending') ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    abstract suspend fun currentGoalIds(limit: Int): List<String>

    @Query("SELECT * FROM goals WHERE state = 'pending' ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    abstract suspend fun pendingGoals(limit: Int): List<GoalEntity>

    @Query("SELECT planId FROM plans WHERE state = 'active' ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    abstract suspend fun activePlanIds(limit: Int): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM goals WHERE goalId = :goalId)")
    abstract suspend fun goalExists(goalId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM plans WHERE planId = :planId)")
    abstract suspend fun planExists(planId: String): Boolean

    @Query("SELECT * FROM goals WHERE goalId = :goalId")
    abstract suspend fun goal(goalId: String): GoalEntity?

    @Query("SELECT * FROM goals WHERE goalId IN (:goalIds) LIMIT :limit")
    abstract suspend fun goalsByIds(goalIds: List<String>, limit: Int): List<GoalEntity>

    @Query("SELECT * FROM plan_lifecycle_events WHERE eventId = :eventId")
    abstract suspend fun lifecycleEvent(eventId: String): PlanLifecycleEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertLifecycleEvent(event: PlanLifecycleEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertWeek(week: PlanWeekEntity)

    @Query("SELECT * FROM plan_weeks WHERE weekId = :weekId")
    abstract suspend fun week(weekId: String): PlanWeekEntity?

    @Query("UPDATE plan_weeks SET generatedLoadMeters = :targetDistanceMeters, generatedLoadDurationSeconds = :targetDurationSeconds WHERE planId = :planId AND weekId = :weekId")
    abstract suspend fun updateRoutineWeekTarget(
        planId: String,
        weekId: String,
        targetDistanceMeters: Int?,
        targetDurationSeconds: Int?,
    ): Int

    @Query("UPDATE plans SET updatedAtEpochMillis = :updatedAtEpochMillis WHERE planId = :planId AND state = 'active' AND phaseType = 'routine'")
    abstract suspend fun touchActiveRoutine(planId: String, updatedAtEpochMillis: Long): Int

    @Query("UPDATE plans SET state = 'archived', completedAtEpochMillis = :at, archivedAtEpochMillis = :at, updatedAtEpochMillis = :at WHERE planId = :planId AND state = 'active'")
    abstract suspend fun completePlanIfActive(planId: String, at: Long): Int

    @Query("UPDATE plans SET state = 'archived', completedAtEpochMillis = NULL, archivedAtEpochMillis = :at, updatedAtEpochMillis = :at WHERE planId = :planId AND state = 'active'")
    abstract suspend fun archivePlanIfActive(planId: String, at: Long): Int

    @Query("UPDATE plans SET endEpochDay = :newEnd, updatedAtEpochMillis = :at WHERE planId = :planId AND state = 'active' AND endEpochDay = :expectedEnd")
    abstract suspend fun extendPlanIfCurrentEnd(planId: String, expectedEnd: Long, newEnd: Long, at: Long): Int

    @Query("UPDATE goals SET state = :state, updatedAtEpochMillis = :at WHERE goalId = :goalId AND state = 'active'")
    abstract suspend fun updateActiveGoalState(goalId: String, state: String, at: Long): Int

    @Query("UPDATE goals SET targetDateEpochDay = :targetDateEpochDay, updatedAtEpochMillis = :at WHERE goalId = :goalId AND state = 'active'")
    abstract suspend fun updateActiveGoalTargetDate(goalId: String, targetDateEpochDay: Long, at: Long): Int

    @Query("""
        SELECT activity.activityId, activity.distanceMeters, activity.durationSeconds
        FROM activities AS activity
        INNER JOIN workouts AS workout ON workout.workoutId = activity.linkedWorkoutId
        WHERE activity.reviewState = 'accepted' AND activity.linkedWorkoutId IS NOT NULL
          AND workout.planId = :planId
          AND workout.currentScheduledEpochDay BETWEEN :fromEpochDay AND :throughEpochDay
        ORDER BY workout.currentScheduledEpochDay, activity.activityId
        LIMIT :limit
    """)
    abstract suspend fun acceptedLinkedActivitiesForPlan(
        planId: String,
        fromEpochDay: Long,
        throughEpochDay: Long,
        limit: Int,
    ): List<LocalAcceptedLinkedActivity>

    @Query("UPDATE goals SET state = 'archived', updatedAtEpochMillis = :archivedAtEpochMillis WHERE state IN ('active', 'pending')")
    abstract suspend fun archiveCurrentGoals(archivedAtEpochMillis: Long): Int

    @Query("UPDATE plans SET state = 'archived', archivedAtEpochMillis = :archivedAtEpochMillis, updatedAtEpochMillis = :archivedAtEpochMillis WHERE state = 'active'")
    abstract suspend fun archiveActivePlans(archivedAtEpochMillis: Long): Int

    @Query("SELECT * FROM goals WHERE state = :state ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    abstract fun observeGoalsByState(state: String, limit: Int): Flow<List<GoalEntity>>

    @Query("SELECT * FROM plans WHERE state = :state ORDER BY startEpochDay DESC LIMIT :limit")
    abstract fun observePlansByState(state: String, limit: Int): Flow<List<PlanEntity>>

    @Query(
        """
        SELECT * FROM plans
        WHERE state IN ('completed', 'archived')
        ORDER BY
            COALESCE(completedAtEpochMillis, archivedAtEpochMillis, updatedAtEpochMillis) DESC,
            planId DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    abstract suspend fun historyPastPlansPage(limit: Int, offset: Int): List<PlanEntity>

    @Query(
        """
        SELECT
            plan_week.planId,
            plan_week.weekId,
            plan_week.startEpochDay,
            stored_plan.state AS planState,
            stored_plan.startEpochDay AS planStartEpochDay
        FROM plan_weeks AS plan_week
        INNER JOIN plans AS stored_plan ON stored_plan.planId = plan_week.planId
        WHERE stored_plan.state IN ('active', 'completed', 'archived')
          AND plan_week.startEpochDay <= :throughEpochDay
          AND (plan_week.startEpochDay + 6) >= :fromEpochDay
        ORDER BY plan_week.startEpochDay, stored_plan.planId, plan_week.ordinal
        LIMIT :limit OFFSET :offset
        """,
    )
    abstract suspend fun planWeekAssignmentCandidates(
        fromEpochDay: Long,
        throughEpochDay: Long,
        limit: Int,
        offset: Int,
    ): List<StoredPlanWeekAssignmentCandidate>

    @Query("SELECT * FROM plans WHERE state = 'active' ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    abstract suspend fun activePlans(limit: Int): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE planId = :planId")
    abstract suspend fun plan(planId: String): PlanEntity?

    @Query("SELECT * FROM routine_schedule_days WHERE planId = :planId ORDER BY dayOfWeek")
    abstract suspend fun routineScheduleDays(planId: String): List<RoutineScheduleDayEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRoutineScheduleDays(days: List<RoutineScheduleDayEntity>)

    @Query("SELECT * FROM workouts WHERE workoutId = :workoutId")
    abstract suspend fun workout(workoutId: String): WorkoutEntity?

    @Query("SELECT * FROM plan_weeks WHERE planId = :planId ORDER BY ordinal LIMIT :limit")
    abstract suspend fun weeksForPlan(planId: String, limit: Int): List<PlanWeekEntity>

    @Query("SELECT * FROM plan_weeks WHERE planId = :planId AND startEpochDay >= :fromEpochDay ORDER BY ordinal LIMIT :limit")
    abstract suspend fun weeksForPlanFrom(
        planId: String,
        fromEpochDay: Long,
        limit: Int,
    ): List<PlanWeekEntity>

    @Query(
        """
        SELECT * FROM plan_weeks
        WHERE planId = :planId
          AND startEpochDay <= :throughEpochDay
          AND startEpochDay + 6 >= :fromEpochDay
        ORDER BY ordinal
        LIMIT :limit
        """,
    )
    abstract suspend fun weeksForPlanInRange(
        planId: String,
        fromEpochDay: Long,
        throughEpochDay: Long,
        limit: Int,
    ): List<PlanWeekEntity>

    @Query("SELECT * FROM plan_weeks WHERE planId = :planId ORDER BY startEpochDay DESC LIMIT 1")
    abstract suspend fun lastWeekForPlan(planId: String): PlanWeekEntity?

    @Query("SELECT * FROM plan_weeks WHERE planId IN (:planIds) ORDER BY planId, ordinal LIMIT :limit")
    abstract suspend fun weeksForPlans(planIds: List<String>, limit: Int): List<PlanWeekEntity>

    @Query("SELECT * FROM workouts WHERE weekId = :weekId AND currentStatus != :tombstoneState ORDER BY currentScheduledEpochDay, position LIMIT :limit")
    abstract suspend fun workoutsForWeek(
        weekId: String,
        tombstoneState: String = WORKOUT_STATE_TOMBSTONED,
        limit: Int,
    ): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE planId = :planId AND currentStatus != :tombstoneState ORDER BY currentScheduledEpochDay, position LIMIT :limit")
    abstract fun observeVisibleWorkouts(
        planId: String,
        tombstoneState: String = WORKOUT_STATE_TOMBSTONED,
        limit: Int,
    ): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE planId = :planId AND currentStatus != :tombstoneState ORDER BY currentScheduledEpochDay, position LIMIT :limit")
    abstract suspend fun visibleWorkoutsForPlan(
        planId: String,
        tombstoneState: String = WORKOUT_STATE_TOMBSTONED,
        limit: Int,
    ): List<WorkoutEntity>

    @Query(
        """
        SELECT * FROM workouts
        WHERE planId = :planId
          AND currentStatus = 'planned'
          AND currentWorkoutType != 'rest'
          AND currentScheduledEpochDay >= :fromEpochDay
        ORDER BY currentScheduledEpochDay, position
        LIMIT 1
        """,
    )
    abstract suspend fun nextPlannedWorkout(
        planId: String,
        fromEpochDay: Long,
    ): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE planId IN (:planIds) AND currentStatus != :tombstoneState ORDER BY planId, currentScheduledEpochDay, position LIMIT :limit")
    abstract suspend fun visibleWorkoutsForPlans(
        planIds: List<String>,
        tombstoneState: String = WORKOUT_STATE_TOMBSTONED,
        limit: Int,
    ): List<WorkoutEntity>

    /** History keeps removed workouts so the original recommendation remains auditable. */
    @Query("SELECT * FROM workouts WHERE planId IN (:planIds) ORDER BY planId, currentScheduledEpochDay, position LIMIT :limit")
    abstract suspend fun historyWorkoutsForPlans(
        planIds: List<String>,
        limit: Int,
    ): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE planId = :planId ORDER BY currentScheduledEpochDay, position LIMIT :limit")
    abstract suspend fun allWorkoutsForPlan(planId: String, limit: Int): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE planId = :planId AND currentScheduledEpochDay >= :fromEpochDay ORDER BY currentScheduledEpochDay, position LIMIT :limit")
    abstract suspend fun allWorkoutsForPlanFrom(
        planId: String,
        fromEpochDay: Long,
        limit: Int,
    ): List<WorkoutEntity>

    @Query(
        """
        SELECT * FROM workouts
        WHERE planId = :planId
          AND currentScheduledEpochDay BETWEEN :fromEpochDay AND :throughEpochDay
          AND currentStatus != :tombstoneState
        ORDER BY currentScheduledEpochDay, position
        LIMIT :limit
        """,
    )
    abstract suspend fun visibleWorkoutsForPlanInRange(
        planId: String,
        fromEpochDay: Long,
        throughEpochDay: Long,
        tombstoneState: String = WORKOUT_STATE_TOMBSTONED,
        limit: Int,
    ): List<WorkoutEntity>

    @Query(
        """
        SELECT * FROM workouts
        WHERE planId = :planId
          AND currentScheduledEpochDay BETWEEN :fromEpochDay AND :throughEpochDay
        ORDER BY currentScheduledEpochDay, position
        LIMIT :limit
        """,
    )
    abstract suspend fun historyWorkoutsForPlanInRange(
        planId: String,
        fromEpochDay: Long,
        throughEpochDay: Long,
        limit: Int,
    ): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE currentScheduledEpochDay BETWEEN :fromEpochDay AND :toEpochDay AND currentStatus != :tombstoneState ORDER BY currentScheduledEpochDay, position LIMIT :limit")
    abstract suspend fun workoutsInRange(
        fromEpochDay: Long,
        toEpochDay: Long,
        tombstoneState: String = WORKOUT_STATE_TOMBSTONED,
        limit: Int,
    ): List<WorkoutEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPlan(plan: PlanEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertWeeks(weeks: List<PlanWeekEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertWorkouts(workouts: List<WorkoutEntity>)

    @Upsert
    protected abstract suspend fun upsertWorkout(workout: WorkoutEntity)

    @Query("SELECT planId FROM plan_weeks WHERE weekId = :weekId")
    protected abstract suspend fun planIdForWeek(weekId: String): String?

    @Transaction
    open suspend fun saveWorkout(workout: WorkoutEntity) {
        require(planIdForWeek(workout.weekId) == workout.planId) {
            "Workout planId must match its owning plan week."
        }
        upsertWorkout(workout)
    }

    @Upsert
    abstract suspend fun savePlanSourceReference(reference: PlanSourceReferenceEntity)

    @Upsert
    abstract suspend fun savePlanSummaryWarning(warning: PlanSummaryWarningEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPlanSummaryWarnings(warnings: List<PlanSummaryWarningEntity>)

    @Query("SELECT * FROM plan_summary_warnings WHERE planId = :planId ORDER BY ordinal LIMIT :limit")
    abstract suspend fun planSummaryWarnings(planId: String, limit: Int): List<PlanSummaryWarningEntity>

    @Query("SELECT * FROM plan_summary_warnings WHERE planId IN (:planIds) ORDER BY planId, ordinal LIMIT :limit")
    abstract suspend fun planSummaryWarningsForPlans(
        planIds: List<String>,
        limit: Int,
    ): List<PlanSummaryWarningEntity>

    @Query("SELECT * FROM plan_source_references WHERE planId = :planId ORDER BY ordinal LIMIT :limit")
    abstract suspend fun planSourceReferences(planId: String, limit: Int): List<PlanSourceReferenceEntity>

    @Upsert
    abstract suspend fun saveLifecycleEvent(event: PlanLifecycleEventEntity)

    @Query("SELECT * FROM plan_lifecycle_events WHERE planId = :planId ORDER BY occurredAtEpochMillis DESC LIMIT :limit")
    abstract suspend fun lifecycleEvents(planId: String, limit: Int): List<PlanLifecycleEventEntity>

    @Query("SELECT * FROM plan_lifecycle_events WHERE planId IN (:planIds) ORDER BY planId, occurredAtEpochMillis DESC LIMIT :limit")
    abstract suspend fun lifecycleEventsForPlans(
        planIds: List<String>,
        limit: Int,
    ): List<PlanLifecycleEventEntity>

    @Upsert
    abstract suspend fun saveBlock(block: WorkoutBlockEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBlocks(blocks: List<WorkoutBlockEntity>)

    @Query("SELECT * FROM workout_blocks WHERE workoutId = :workoutId AND prescriptionVersion = :prescriptionVersion ORDER BY ordinal LIMIT :limit")
    abstract suspend fun blocksForWorkout(workoutId: String, prescriptionVersion: String, limit: Int): List<WorkoutBlockEntity>

    @Query("SELECT * FROM workout_blocks WHERE workoutId IN (:workoutIds) ORDER BY workoutId, prescriptionVersion, ordinal LIMIT :limit")
    abstract suspend fun blocksForWorkouts(
        workoutIds: List<String>,
        limit: Int,
    ): List<WorkoutBlockEntity>

    @Upsert
    abstract suspend fun saveSegment(segment: WorkoutSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSegments(segments: List<WorkoutSegmentEntity>)

    @Query("SELECT * FROM workout_segments WHERE blockId = :blockId ORDER BY ordinal LIMIT :limit")
    abstract suspend fun segmentsForBlock(blockId: String, limit: Int): List<WorkoutSegmentEntity>

    @Query("SELECT * FROM workout_segments WHERE blockId IN (:blockIds) ORDER BY blockId, ordinal LIMIT :limit")
    abstract suspend fun segmentsForBlocks(
        blockIds: List<String>,
        limit: Int,
    ): List<WorkoutSegmentEntity>

    @Upsert
    abstract suspend fun saveWorkoutSourceReference(reference: WorkoutSourceReferenceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertWorkoutSourceReferences(references: List<WorkoutSourceReferenceEntity>)

    @Query("SELECT * FROM workout_source_references WHERE workoutId = :workoutId AND prescriptionVersion = :prescriptionVersion ORDER BY ordinal LIMIT :limit")
    abstract suspend fun workoutSourceReferences(
        workoutId: String,
        prescriptionVersion: String,
        limit: Int,
    ): List<WorkoutSourceReferenceEntity>

    @Query("SELECT * FROM workout_source_references WHERE workoutId IN (:workoutIds) ORDER BY workoutId, prescriptionVersion, ordinal LIMIT :limit")
    abstract suspend fun workoutSourceReferencesForWorkouts(
        workoutIds: List<String>,
        limit: Int,
    ): List<WorkoutSourceReferenceEntity>

    @Query("DELETE FROM workout_segments WHERE blockId IN (SELECT blockId FROM workout_blocks WHERE workoutId = :workoutId AND prescriptionVersion = :prescriptionVersion)")
    abstract suspend fun clearSegmentsForWorkoutVersion(workoutId: String, prescriptionVersion: String)

    @Query("DELETE FROM workout_blocks WHERE workoutId = :workoutId AND prescriptionVersion = :prescriptionVersion")
    abstract suspend fun clearBlocksForWorkoutVersion(workoutId: String, prescriptionVersion: String)

    @Query("DELETE FROM workout_source_references WHERE workoutId = :workoutId AND prescriptionVersion = :prescriptionVersion")
    abstract suspend fun clearWorkoutSourceReferencesForVersion(workoutId: String, prescriptionVersion: String)

    @Transaction
    open suspend fun createPlanGraph(
        plan: PlanEntity,
        weeks: List<PlanWeekEntity>,
        workouts: List<WorkoutEntity>,
        summaryWarnings: List<PlanSummaryWarningEntity> = emptyList(),
    ) {
        require(weeks.all { it.planId == plan.planId }) { "Every week must belong to the inserted plan." }
        val weekIds = weeks.asSequence().map { it.weekId }.toSet()
        require(workouts.all { it.planId == plan.planId && it.weekId in weekIds }) {
            "Every workout must belong to a week in the inserted plan."
        }
        require(summaryWarnings.all { it.planId == plan.planId }) { "Every summary warning must belong to the inserted plan." }
        insertPlan(plan)
        insertWeeks(weeks)
        insertWorkouts(workouts)
        if (summaryWarnings.isNotEmpty()) insertPlanSummaryWarnings(summaryWarnings)
    }

    @Transaction
    open suspend fun createRoutineGraph(graph: RoutinePlanPersistenceGraph) {
        require(graph.days.all { it.planId == graph.plan.planId })
        require(graph.weeks.all { it.planId == graph.plan.planId })
        val weekIds = graph.weeks.mapTo(mutableSetOf(), PlanWeekEntity::weekId)
        require(graph.workouts.all { it.planId == graph.plan.planId && it.weekId in weekIds })
        insertPlan(graph.plan)
        insertRoutineScheduleDays(graph.days)
        insertWeeks(graph.weeks)
        insertWorkouts(graph.workouts)
    }

    @Transaction
    open suspend fun appendRoutineWeek(week: PlanWeekEntity, workouts: List<WorkoutEntity>) {
        require(workouts.all { it.planId == week.planId && it.weekId == week.weekId })
        insertWeeks(listOf(week))
        insertWorkouts(workouts)
    }
}
