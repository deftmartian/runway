package dev.deftmartian.runway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileSettingsDao {
    @Query("SELECT * FROM profile_settings WHERE singletonId = :singletonId")
    fun observe(singletonId: Int = ProfileSettingsEntity.SINGLETON_ID): Flow<ProfileSettingsEntity?>

    @Query("SELECT * FROM profile_settings WHERE singletonId = :singletonId")
    suspend fun get(singletonId: Int = ProfileSettingsEntity.SINGLETON_ID): ProfileSettingsEntity?

    @Upsert
    suspend fun save(profile: ProfileSettingsEntity)

    @Query("SELECT * FROM profile_availability_days WHERE singletonId = :singletonId ORDER BY dayOfWeek LIMIT :limit")
    suspend fun availabilityDays(
        singletonId: Int = ProfileSettingsEntity.SINGLETON_ID,
        limit: Int,
    ): List<ProfileAvailabilityDayEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAvailabilityDays(days: List<ProfileAvailabilityDayEntity>)

    @Query("DELETE FROM profile_availability_days WHERE singletonId = :singletonId")
    suspend fun clearAvailabilityDays(singletonId: Int = ProfileSettingsEntity.SINGLETON_ID)

    @Transaction
    suspend fun replaceOnboardingInputs(
        profile: ProfileSettingsEntity,
        availabilityDays: List<Int>,
    ) {
        require(availabilityDays.distinct().size == availabilityDays.size && availabilityDays.all { it in 0..6 }) {
            "Availability must contain distinct weekday values from 0 through 6."
        }
        save(profile)
        clearAvailabilityDays(profile.singletonId)
        if (availabilityDays.isNotEmpty()) {
            insertAvailabilityDays(availabilityDays.map { ProfileAvailabilityDayEntity(profile.singletonId, it) })
        }
    }

    @Query("DELETE FROM profile_settings")
    suspend fun clear()
}

@Dao
abstract class GoalPlanDao {
    @Upsert
    abstract suspend fun saveGoal(goal: GoalEntity)

    @Query("SELECT goalId FROM goals WHERE state = 'active' ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    abstract suspend fun activeGoalIds(limit: Int): List<String>

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

    @Query("UPDATE goals SET state = 'archived', updatedAtEpochMillis = :archivedAtEpochMillis WHERE state = 'active'")
    abstract suspend fun archiveActiveGoals(archivedAtEpochMillis: Long): Int

    @Query("UPDATE plans SET state = 'archived', archivedAtEpochMillis = :archivedAtEpochMillis, updatedAtEpochMillis = :archivedAtEpochMillis WHERE state = 'active'")
    abstract suspend fun archiveActivePlans(archivedAtEpochMillis: Long): Int

    @Query("SELECT * FROM goals WHERE state = :state ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    abstract fun observeGoalsByState(state: String, limit: Int): Flow<List<GoalEntity>>

    @Query("SELECT * FROM plans WHERE state = :state ORDER BY startEpochDay DESC LIMIT :limit")
    abstract fun observePlansByState(state: String, limit: Int): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE state IN (:states) ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    abstract suspend fun plansByStates(states: List<String>, limit: Int): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE state = 'active' ORDER BY updatedAtEpochMillis DESC LIMIT :limit")
    abstract suspend fun activePlans(limit: Int): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE planId = :planId")
    abstract suspend fun plan(planId: String): PlanEntity?

    @Query("SELECT * FROM workouts WHERE workoutId = :workoutId")
    abstract suspend fun workout(workoutId: String): WorkoutEntity?

    @Query("SELECT * FROM plan_weeks WHERE planId = :planId ORDER BY ordinal LIMIT :limit")
    abstract suspend fun weeksForPlan(planId: String, limit: Int): List<PlanWeekEntity>

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

    @Query("SELECT * FROM workouts WHERE planId = :planId ORDER BY currentScheduledEpochDay, position LIMIT :limit")
    abstract suspend fun allWorkoutsForPlan(planId: String, limit: Int): List<WorkoutEntity>

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
}

@Dao
interface ActivityLedgerDao {
    @Query("SELECT * FROM activities WHERE reviewState = :reviewState ORDER BY occurredAtEpochMillis DESC LIMIT :limit")
    fun observeActivitiesByReviewState(reviewState: String, limit: Int): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE reviewState = :reviewState ORDER BY occurredAtEpochMillis DESC LIMIT :limit")
    suspend fun activitiesByReviewState(reviewState: String, limit: Int): List<ActivityEntity>

    @Query("SELECT COUNT(*) FROM activities WHERE reviewState = :reviewState")
    suspend fun activityCountByReviewState(reviewState: String): Int

    @Query("SELECT * FROM activities WHERE occurredAtEpochMillis >= :fromInclusive AND occurredAtEpochMillis < :toExclusive ORDER BY occurredAtEpochMillis DESC LIMIT :limit")
    suspend fun activitiesInRange(
        fromInclusive: Long,
        toExclusive: Long,
        limit: Int,
    ): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE reviewState = :acceptedState AND occurredAtEpochMillis >= :fromInclusive AND occurredAtEpochMillis < :toExclusive ORDER BY occurredAtEpochMillis DESC LIMIT :limit")
    suspend fun acceptedActivitiesInRange(
        fromInclusive: Long,
        toExclusive: Long,
        limit: Int,
        acceptedState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    ): List<ActivityEntity>

    @Query(
        """
        SELECT * FROM activities
        WHERE occurredAtEpochMillis BETWEEN :fromInclusive AND :throughInclusive
          AND source != :excludedSource
        ORDER BY ABS(occurredAtEpochMillis - :targetEpochMillis), activityId
        LIMIT :limit
        """,
    )
    suspend fun potentialDuplicateActivities(
        targetEpochMillis: Long,
        fromInclusive: Long,
        throughInclusive: Long,
        excludedSource: String,
        limit: Int,
    ): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE activityId = :activityId")
    suspend fun activity(activityId: String): ActivityEntity?

    @Query("SELECT * FROM activities WHERE activityId IN (:activityIds) LIMIT :limit")
    suspend fun activitiesByIds(activityIds: List<String>, limit: Int): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE linkedWorkoutId = :workoutId LIMIT 2")
    suspend fun activitiesLinkedToWorkout(workoutId: String): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE linkedWorkoutId IN (:workoutIds) ORDER BY linkedWorkoutId, occurredAtEpochMillis DESC LIMIT :limit")
    suspend fun activitiesLinkedToWorkouts(
        workoutIds: List<String>,
        limit: Int,
    ): List<ActivityEntity>

    @Query("SELECT * FROM activity_feedback WHERE activityId = :activityId")
    suspend fun activityFeedback(activityId: String): ActivityFeedbackEntity?

    @Query("SELECT * FROM activity_feedback WHERE activityId IN (:activityIds) LIMIT :limit")
    suspend fun activityFeedbackForActivities(
        activityIds: List<String>,
        limit: Int,
    ): List<ActivityFeedbackEntity>

    @Query("SELECT * FROM workout_feedback WHERE workoutId = :workoutId")
    suspend fun workoutFeedback(workoutId: String): WorkoutFeedbackEntity?

    @Query("SELECT * FROM workout_feedback WHERE feedbackId = :feedbackId")
    suspend fun workoutFeedbackById(feedbackId: String): WorkoutFeedbackEntity?

    @Query("SELECT * FROM workout_feedback WHERE workoutId IN (:workoutIds) LIMIT :limit")
    suspend fun workoutFeedbackForWorkouts(
        workoutIds: List<String>,
        limit: Int,
    ): List<WorkoutFeedbackEntity>

    @Query("SELECT * FROM workout_feedback WHERE sourceActivityId = :activityId")
    suspend fun workoutFeedbackForActivity(activityId: String): WorkoutFeedbackEntity?

    @Query("SELECT * FROM workout_feedback_consequences WHERE feedbackId = :feedbackId")
    suspend fun workoutFeedbackConsequence(feedbackId: String): WorkoutFeedbackConsequenceEntity?

    @Query("SELECT * FROM workout_feedback_consequences WHERE feedbackId IN (:feedbackIds) LIMIT :limit")
    suspend fun workoutFeedbackConsequencesForFeedbackIds(
        feedbackIds: List<String>,
        limit: Int,
    ): List<WorkoutFeedbackConsequenceEntity>

    @Query("SELECT * FROM activity_consequences WHERE activityId = :activityId")
    suspend fun activityConsequence(activityId: String): ActivityConsequenceEntity?

    @Query("SELECT * FROM activity_consequences WHERE activityId IN (:activityIds) LIMIT :limit")
    suspend fun activityConsequencesForActivityIds(
        activityIds: List<String>,
        limit: Int,
    ): List<ActivityConsequenceEntity>

    @Query(
        """
        SELECT feedback.*
        FROM workout_feedback AS feedback
        INNER JOIN workouts AS workout ON workout.workoutId = feedback.workoutId
        WHERE workout.planId = :planId
          AND workout.currentScheduledEpochDay >= :fromEpochDay
          AND workout.currentScheduledEpochDay < :beforeEpochDay
        ORDER BY workout.currentScheduledEpochDay DESC
        LIMIT :limit
        """,
    )
    suspend fun workoutFeedbackInPlanDateRange(
        planId: String,
        fromEpochDay: Long,
        beforeEpochDay: Long,
        limit: Int,
    ): List<WorkoutFeedbackEntity>

    @Query(
        """
        SELECT feedback.*
        FROM workout_feedback AS feedback
        INNER JOIN workouts AS workout ON workout.workoutId = feedback.workoutId
        WHERE workout.planId IN (:planIds)
        ORDER BY workout.planId, feedback.recordedAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun workoutFeedbackForPlans(
        planIds: List<String>,
        limit: Int,
    ): List<WorkoutFeedbackEntity>

    @Query("SELECT * FROM route_samples WHERE activityId = :activityId ORDER BY ordinal LIMIT :limit")
    suspend fun routeSamples(activityId: String, limit: Int): List<RouteSampleEntity>

    @Query("SELECT * FROM heart_rate_samples WHERE activityId = :activityId ORDER BY ordinal LIMIT :limit")
    suspend fun heartRateSamples(activityId: String, limit: Int): List<HeartRateSampleEntity>

    @Upsert
    suspend fun saveActivity(activity: ActivityEntity)

    @Upsert
    suspend fun saveFeedback(feedback: ActivityFeedbackEntity)

    @Upsert
    suspend fun saveWorkoutFeedback(feedback: WorkoutFeedbackEntity)

    @Upsert
    suspend fun saveWorkoutFeedbackConsequence(consequence: WorkoutFeedbackConsequenceEntity)

    @Upsert
    suspend fun saveWorkoutFeedbackConsequenceOption(option: WorkoutFeedbackConsequenceOptionEntity)

    @Query("SELECT * FROM workout_feedback_consequence_options WHERE feedbackId = :feedbackId ORDER BY decision LIMIT :limit")
    suspend fun workoutFeedbackConsequenceOptions(feedbackId: String, limit: Int): List<WorkoutFeedbackConsequenceOptionEntity>

    @Query("SELECT * FROM workout_feedback_consequence_options WHERE feedbackId IN (:feedbackIds) ORDER BY feedbackId, decision LIMIT :limit")
    suspend fun workoutFeedbackConsequenceOptionsForFeedbackIds(
        feedbackIds: List<String>,
        limit: Int,
    ): List<WorkoutFeedbackConsequenceOptionEntity>

    @Query("DELETE FROM workout_feedback_consequence_options WHERE feedbackId = :feedbackId")
    suspend fun clearWorkoutFeedbackConsequenceOptions(feedbackId: String)

    /** Deleting direct feedback cascades its consequence and offered-decision rows. */
    @Query("DELETE FROM workout_feedback WHERE feedbackId = :feedbackId AND sourceActivityId IS NULL")
    suspend fun deleteDirectWorkoutFeedback(feedbackId: String): Int

    @Upsert
    suspend fun saveActivityConsequence(consequence: ActivityConsequenceEntity)

    @Upsert
    suspend fun saveActivityConsequenceOption(option: ActivityConsequenceOptionEntity)

    @Query("UPDATE workout_feedback_consequences SET appliedDecision = :decision WHERE feedbackId = :feedbackId AND appliedDecision IS NULL AND planChangeAvailable = 1")
    suspend fun claimWorkoutFeedbackConsequence(feedbackId: String, decision: String): Int

    @Query("UPDATE activity_consequences SET appliedDecision = :decision, resolvedAtEpochMillis = :resolvedAtEpochMillis WHERE activityId = :activityId AND appliedDecision IS NULL AND planChangeAvailable = 1")
    suspend fun claimActivityConsequence(activityId: String, decision: String, resolvedAtEpochMillis: Long): Int

    @Query("SELECT * FROM activity_consequence_options WHERE activityId = :activityId ORDER BY decision LIMIT :limit")
    suspend fun activityConsequenceOptions(activityId: String, limit: Int): List<ActivityConsequenceOptionEntity>

    @Query("SELECT * FROM activity_consequence_options WHERE activityId IN (:activityIds) ORDER BY activityId, decision LIMIT :limit")
    suspend fun activityConsequenceOptionsForActivityIds(
        activityIds: List<String>,
        limit: Int,
    ): List<ActivityConsequenceOptionEntity>

    @Query("DELETE FROM activity_consequence_options WHERE activityId = :activityId")
    suspend fun clearActivityConsequenceOptions(activityId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRouteSamples(samples: List<RouteSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHeartRateSamples(samples: List<HeartRateSampleEntity>)

    @Query("DELETE FROM route_samples WHERE activityId = :activityId")
    suspend fun clearRouteSamplesForActivity(activityId: String)

    @Query("DELETE FROM route_samples")
    suspend fun clearAllRouteSamples()

    @Query(
        """
        UPDATE activities
        SET routePointCount = 0,
            routeTraceRetained = 0,
            routeStartEndRedacted =
                CASE
                    WHEN routePointCount > 0 OR routeTraceRetained = 1 OR routeStartEndRedacted = 1
                    THEN 1
                    ELSE 0
                END
        """,
    )
    suspend fun markAllRouteTracesDiscarded()

    @Query("DELETE FROM heart_rate_samples WHERE activityId = :activityId")
    suspend fun clearHeartRateSamplesForActivity(activityId: String)

    @Query("DELETE FROM workout_feedback WHERE sourceActivityId = :activityId")
    suspend fun deleteWorkoutFeedbackForActivity(activityId: String): Int

    @Query("DELETE FROM activity_feedback WHERE activityId = :activityId")
    suspend fun deleteActivityFeedback(activityId: String): Int

    @Query("DELETE FROM activities WHERE activityId = :activityId")
    suspend fun deleteActivity(activityId: String): Int

    @Transaction
    suspend fun replaceRouteSamplesBounded(
        activityId: String,
        samples: List<RouteSampleEntity>,
        maximumSamples: Int,
    ) {
        require(maximumSamples > 1) { "Route samples must retain at least the start and end." }
        clearRouteSamplesForActivity(activityId)
        val bounded = samples.boundRouteSamples(maximumSamples).mapIndexed { ordinal, sample ->
            sample.copy(sampleId = 0, activityId = activityId, ordinal = ordinal)
        }
        if (bounded.isNotEmpty()) insertRouteSamples(bounded)
    }

    @Transaction
    suspend fun replaceHeartRateSamplesBounded(
        activityId: String,
        samples: List<HeartRateSampleEntity>,
        maximumSamples: Int,
    ) {
        require(maximumSamples > 0) { "Heart-rate sample limit must be positive." }
        clearHeartRateSamplesForActivity(activityId)
        val bounded = samples.boundEvenly(maximumSamples).mapIndexed { ordinal, sample ->
            sample.copy(sampleId = 0, activityId = activityId, ordinal = ordinal)
        }
        if (bounded.isNotEmpty()) insertHeartRateSamples(bounded)
    }
}

@Dao
interface AdjustmentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAdjustment(adjustment: PlanAdjustmentEntity)

    @Upsert
    suspend fun saveAdjustment(adjustment: PlanAdjustmentEntity)

    @Upsert
    suspend fun saveAdjustmentConsequence(consequence: AdjustmentConsequenceEntity)

    @Upsert
    suspend fun saveEffectGroup(group: AdjustmentEffectGroupEntity)

    @Upsert
    suspend fun saveWorkoutEffect(effect: AdjustmentWorkoutEffectEntity)

    @Upsert
    suspend fun saveEffectBlockSnapshot(snapshot: AdjustmentEffectBlockSnapshotEntity)

    @Upsert
    suspend fun saveEffectSegmentSnapshot(snapshot: AdjustmentEffectSegmentSnapshotEntity)

    @Upsert
    suspend fun saveEffectSourceReferenceSnapshot(snapshot: AdjustmentEffectSourceReferenceSnapshotEntity)

    @Upsert
    suspend fun saveDecision(decision: PlanDecisionEntity)

    @Upsert
    suspend fun saveDecisionConsequence(consequence: DecisionConsequenceEntity)

    @Upsert
    suspend fun saveReversal(reversal: PlanReversalEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReversalIfAbsent(reversal: PlanReversalEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM plan_adjustments WHERE adjustmentId = :adjustmentId)")
    suspend fun adjustmentExists(adjustmentId: String): Boolean

    @Query("SELECT * FROM plan_adjustments WHERE adjustmentId = :adjustmentId")
    suspend fun adjustment(adjustmentId: String): PlanAdjustmentEntity?

    @Query("SELECT * FROM plan_decisions WHERE adjustmentId = :adjustmentId LIMIT 1")
    suspend fun decisionForAdjustment(adjustmentId: String): PlanDecisionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM plan_reversals WHERE decisionId = :decisionId)")
    suspend fun reversalExistsForDecision(decisionId: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM plan_adjustments WHERE triggerKind = :kind AND triggerId = :id AND triggerVersion = :version)")
    suspend fun adjustmentExistsForTrigger(kind: String, id: String, version: String): Boolean

    @Query("SELECT * FROM plan_adjustments WHERE planId = :planId ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    suspend fun adjustmentsForPlan(planId: String, limit: Int): List<PlanAdjustmentEntity>

    /**
     * One bounded read for the history timeline. A row represents either one stored workout effect
     * or an adjustment without a workout effect (for example, a keep-plan decision).
     */
    @Query(
        """
        SELECT adjustment.planId AS planId,
               adjustment.adjustmentId AS adjustmentId,
               adjustment.adjustmentType AS adjustmentType,
               adjustment.state AS adjustmentState,
               adjustment.triggerKind AS triggerKind,
               adjustment.createdAtEpochMillis AS createdAtEpochMillis,
               decision.decisionId AS decisionId,
               decision.decisionType AS decisionType,
               reversal.reason AS reversalReason,
               reversal.reversedAtEpochMillis AS reversedAtEpochMillis,
               effect.effectId AS effectId,
               effect.newScheduledEpochDay AS newScheduledEpochDay,
               effect.newWorkoutType AS newWorkoutType,
               effect.newPrescriptionKind AS newPrescriptionKind,
               effect.newStatus AS newStatus,
               effect.newDistanceMeters AS newDistanceMeters,
               effect.newDurationSeconds AS newDurationSeconds,
               effect.newTombstonedAtEpochMillis AS newTombstonedAtEpochMillis,
               effect.newReason AS newReason
        FROM plan_adjustments AS adjustment
        LEFT JOIN plan_decisions AS decision
            ON decision.adjustmentId = adjustment.adjustmentId
        LEFT JOIN plan_reversals AS reversal
            ON reversal.decisionId = decision.decisionId
        LEFT JOIN adjustment_effect_groups AS effect_group
            ON effect_group.adjustmentId = adjustment.adjustmentId
        LEFT JOIN adjustment_workout_effects AS effect
            ON effect.groupId = effect_group.groupId
        WHERE adjustment.planId IN (:planIds)
        ORDER BY adjustment.createdAtEpochMillis DESC,
                 adjustment.adjustmentId,
                 effect_group.ordinal,
                 effect.ordinal
        LIMIT :limit
        """,
    )
    suspend fun historyAdjustmentRowsForPlans(
        planIds: List<String>,
        limit: Int,
    ): List<HistoryAdjustmentRow>

    /**
     * One bounded query for changes whose persisted scalar after-state still matches the current,
     * future plan. Manual edits and applied consequence decisions both persist the complete
     * before/after structure required by the shared guarded undo boundary. The mutation command
     * remains the final guard for interval/source-reference snapshot equality.
     */
    @Query(
        """
        SELECT effect.workoutId AS workoutId,
               adjustment.adjustmentId AS adjustmentId,
               adjustment.adjustmentType AS kind,
               adjustment.createdAtEpochMillis AS createdAtEpochMillis
        FROM plan_adjustments AS adjustment
        INNER JOIN adjustment_effect_groups AS effect_group
            ON effect_group.adjustmentId = adjustment.adjustmentId
        INNER JOIN adjustment_workout_effects AS effect
            ON effect.groupId = effect_group.groupId
        INNER JOIN plan_decisions AS decision
            ON decision.adjustmentId = adjustment.adjustmentId
        INNER JOIN plans AS plan_row
            ON plan_row.planId = adjustment.planId
        LEFT JOIN plan_reversals AS reversal
            ON reversal.decisionId = decision.decisionId
        INNER JOIN workouts AS current_workout
            ON current_workout.workoutId = effect.workoutId
        WHERE effect.workoutId IN (:workoutIds)
          AND adjustment.state = 'applied'
          AND plan_row.state = 'active'
          AND effect_group.effectType IN ('workout_change', 'consequence_decision')
          AND reversal.reversalId IS NULL
          AND current_workout.currentStatus = 'planned'
          AND current_workout.currentScheduledEpochDay >= :todayEpochDay
          AND NOT EXISTS (
              SELECT 1
              FROM adjustment_effect_groups AS own_group
              INNER JOIN adjustment_workout_effects AS own_effect
                  ON own_effect.groupId = own_group.groupId
              LEFT JOIN workouts AS own_current
                  ON own_current.workoutId = own_effect.workoutId
              WHERE own_group.adjustmentId = adjustment.adjustmentId
                AND (
                    own_effect.workoutId IS NULL
                    OR own_effect.previousWeekId IS NULL
                    OR own_effect.newWeekId IS NULL
                    OR own_current.workoutId IS NULL
                    OR own_current.currentStatus IS NOT own_effect.newStatus
                    OR own_current.currentScheduledEpochDay IS NOT own_effect.newScheduledEpochDay
                    OR own_current.currentWorkoutType IS NOT own_effect.newWorkoutType
                    OR own_current.currentPrescriptionKind IS NOT own_effect.newPrescriptionKind
                    OR own_current.currentDistanceMeters IS NOT own_effect.newDistanceMeters
                    OR own_current.currentDurationSeconds IS NOT own_effect.newDurationSeconds
                    OR own_current.currentIntensity IS NOT own_effect.newIntensity
                    OR own_current.currentPurpose IS NOT own_effect.newPurpose
                    OR own_current.currentReason IS NOT own_effect.newReason
                    OR own_current.currentWarmupSeconds IS NOT own_effect.newWarmupSeconds
                    OR own_current.currentCooldownSeconds IS NOT own_effect.newCooldownSeconds
                    OR own_current.tombstonedAtEpochMillis IS NOT own_effect.newTombstonedAtEpochMillis
                    OR own_current.weekId IS NOT own_effect.newWeekId
                    OR own_current.currentScheduledEpochDay < :todayEpochDay
                )
          )
        ORDER BY effect.workoutId, adjustment.createdAtEpochMillis DESC, adjustment.adjustmentId DESC
        LIMIT :limit
        """,
    )
    suspend fun undoableWorkoutAdjustments(
        workoutIds: List<String>,
        todayEpochDay: Long,
        limit: Int,
    ): List<UndoableWorkoutAdjustmentRow>

    @Query("SELECT * FROM plan_decisions WHERE adjustmentId = :adjustmentId ORDER BY decidedAtEpochMillis DESC LIMIT :limit")
    suspend fun decisionsForAdjustment(adjustmentId: String, limit: Int): List<PlanDecisionEntity>

    @Query("SELECT COUNT(*) FROM plan_adjustments WHERE sourceActivityId = :activityId AND state = 'applied'")
    suspend fun appliedAdjustmentCountForActivity(activityId: String): Int

    @Query("SELECT * FROM adjustment_effect_groups WHERE adjustmentId = :adjustmentId ORDER BY ordinal LIMIT :limit")
    suspend fun effectGroups(adjustmentId: String, limit: Int): List<AdjustmentEffectGroupEntity>

    @Query("SELECT * FROM adjustment_workout_effects WHERE groupId = :groupId ORDER BY ordinal LIMIT :limit")
    suspend fun workoutEffects(groupId: String, limit: Int): List<AdjustmentWorkoutEffectEntity>

    @Query("SELECT * FROM adjustment_effect_block_snapshots WHERE effectId = :effectId AND snapshotState = :snapshotState ORDER BY ordinal LIMIT :limit")
    suspend fun effectBlockSnapshots(effectId: String, snapshotState: String, limit: Int): List<AdjustmentEffectBlockSnapshotEntity>

    @Query("SELECT * FROM adjustment_effect_segment_snapshots WHERE blockSnapshotId IN (SELECT blockSnapshotId FROM adjustment_effect_block_snapshots WHERE effectId = :effectId AND snapshotState = :snapshotState) ORDER BY blockSnapshotId, ordinal LIMIT :limit")
    suspend fun effectSegmentSnapshots(effectId: String, snapshotState: String, limit: Int): List<AdjustmentEffectSegmentSnapshotEntity>

    @Query("SELECT * FROM adjustment_effect_source_reference_snapshots WHERE effectId = :effectId AND snapshotState = :snapshotState ORDER BY ordinal LIMIT :limit")
    suspend fun effectSourceReferenceSnapshots(effectId: String, snapshotState: String, limit: Int): List<AdjustmentEffectSourceReferenceSnapshotEntity>
}

data class UndoableWorkoutAdjustmentRow(
    val workoutId: String,
    val adjustmentId: String,
    val kind: String,
    val createdAtEpochMillis: Long,
)

data class HistoryAdjustmentRow(
    val planId: String,
    val adjustmentId: String,
    val adjustmentType: String,
    val adjustmentState: String,
    val triggerKind: String?,
    val createdAtEpochMillis: Long,
    val decisionId: String?,
    val decisionType: String?,
    val reversalReason: String?,
    val reversedAtEpochMillis: Long?,
    val effectId: String?,
    val newScheduledEpochDay: Long?,
    val newWorkoutType: String?,
    val newPrescriptionKind: String?,
    val newStatus: String?,
    val newDistanceMeters: Int?,
    val newDurationSeconds: Int?,
    val newTombstonedAtEpochMillis: Long?,
    val newReason: String?,
)

@Dao
abstract class ImportLedgerDao {
    @Query("SELECT * FROM import_digests WHERE source = :source AND digest = :digest")
    abstract suspend fun digest(source: String, digest: String): ImportDigestEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDigest(digest: ImportDigestEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertImportedActivity(activity: ActivityEntity)

    @Upsert
    abstract suspend fun saveDigest(digest: ImportDigestEntity)

    @Query("UPDATE import_digests SET activityId = :activityId WHERE source = :source AND digest = :digest")
    abstract suspend fun linkDigest(source: String, digest: String, activityId: String)

    @Query("UPDATE import_digests SET activityId = NULL, tombstonedAtEpochMillis = :tombstonedAtEpochMillis WHERE source = :source AND digest = :digest")
    abstract suspend fun tombstoneDigest(source: String, digest: String, tombstonedAtEpochMillis: Long)

    @Upsert
    abstract suspend fun saveHealthConnectMapping(mapping: HealthConnectMappingEntity)

    @Upsert
    abstract suspend fun savePendingHealthConnectObservation(observation: HealthConnectPendingObservationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPendingHealthConnectRouteSamples(samples: List<HealthConnectPendingRouteSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPendingHealthConnectHeartRateSamples(samples: List<HealthConnectPendingHeartRateSampleEntity>)

    @Query("SELECT * FROM health_connect_pending_observations WHERE mappingId = :mappingId")
    abstract suspend fun pendingHealthConnectObservation(mappingId: String): HealthConnectPendingObservationEntity?

    @Query("SELECT * FROM health_connect_pending_route_samples WHERE mappingId = :mappingId ORDER BY ordinal LIMIT :limit")
    abstract suspend fun pendingHealthConnectRouteSamples(mappingId: String, limit: Int): List<HealthConnectPendingRouteSampleEntity>

    @Query("SELECT * FROM health_connect_pending_heart_rate_samples WHERE mappingId = :mappingId ORDER BY ordinal LIMIT :limit")
    abstract suspend fun pendingHealthConnectHeartRateSamples(mappingId: String, limit: Int): List<HealthConnectPendingHeartRateSampleEntity>

    @Query("DELETE FROM health_connect_pending_observations WHERE mappingId = :mappingId")
    abstract suspend fun clearPendingHealthConnectObservation(mappingId: String)

    @Query("DELETE FROM health_connect_pending_route_samples WHERE mappingId = :mappingId")
    protected abstract suspend fun clearPendingHealthConnectRouteSamples(mappingId: String)

    @Query("DELETE FROM health_connect_pending_route_samples")
    abstract suspend fun clearAllPendingHealthConnectRouteSamples()

    @Query("DELETE FROM health_connect_pending_heart_rate_samples WHERE mappingId = :mappingId")
    protected abstract suspend fun clearPendingHealthConnectHeartRateSamples(mappingId: String)

    @Transaction
    open suspend fun replacePendingHealthConnectRouteSamplesBounded(
        mappingId: String,
        samples: List<HealthConnectPendingRouteSampleEntity>,
        maximumSamples: Int,
    ) {
        require(maximumSamples > 1)
        clearPendingHealthConnectRouteSamples(mappingId)
        insertPendingHealthConnectRouteSamples(samples.boundEvenly(maximumSamples).mapIndexed { ordinal, sample -> sample.copy(sampleId = 0, mappingId = mappingId, ordinal = ordinal) })
    }

    @Transaction
    open suspend fun replacePendingHealthConnectHeartRateSamplesBounded(
        mappingId: String,
        samples: List<HealthConnectPendingHeartRateSampleEntity>,
        maximumSamples: Int,
    ) {
        require(maximumSamples > 0)
        clearPendingHealthConnectHeartRateSamples(mappingId)
        insertPendingHealthConnectHeartRateSamples(samples.boundEvenly(maximumSamples).mapIndexed { ordinal, sample -> sample.copy(sampleId = 0, mappingId = mappingId, ordinal = ordinal) })
    }

    @Query("SELECT * FROM health_connect_mappings WHERE provider = :provider AND externalRecordId = :externalRecordId")
    abstract suspend fun healthConnectMapping(provider: String, externalRecordId: String): HealthConnectMappingEntity?

    @Query(
        """
        SELECT * FROM health_connect_mappings
        WHERE correctionPending = 1
           OR deletePending = 1
           OR duplicateCandidateActivityId IS NOT NULL
        ORDER BY lastObservedAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun pendingHealthConnectMappings(limit: Int): List<HealthConnectMappingEntity>

    @Query("SELECT * FROM health_connect_pending_observations WHERE mappingId IN (:mappingIds) LIMIT :limit")
    abstract suspend fun pendingHealthConnectObservations(
        mappingIds: List<String>,
        limit: Int,
    ): List<HealthConnectPendingObservationEntity>

    @Query("UPDATE health_connect_mappings SET activityId = NULL, lifecycleState = :tombstoneState, correctionPending = 0, deletePending = 0, duplicateCandidateActivityId = NULL, tombstonedAtEpochMillis = :deletedAtEpochMillis, deletedAtEpochMillis = :deletedAtEpochMillis WHERE activityId = :activityId")
    protected abstract suspend fun tombstoneHealthConnectMappingsForActivity(
        activityId: String,
        deletedAtEpochMillis: Long,
        tombstoneState: String = HEALTH_CONNECT_MAPPING_STATE_TOMBSTONED,
    )

    @Query(
        """
        DELETE FROM health_connect_pending_observations
        WHERE mappingId IN (
            SELECT mappingId FROM health_connect_mappings WHERE activityId = :activityId
        )
        """,
    )
    protected abstract suspend fun clearPendingHealthConnectObservationsForActivity(
        activityId: String,
    )

    @Query("DELETE FROM import_digests")
    abstract suspend fun clearDigests()

    @Transaction
    open suspend fun recordImportedActivity(
        activity: ActivityEntity,
        source: String,
        digest: String,
        firstSeenAtEpochMillis: Long,
    ): Boolean {
        require(activity.source == source) { "Import source must match the activity source." }
        val inserted = insertDigest(
            ImportDigestEntity(
                source = source,
                digest = digest,
                activityId = null,
                firstSeenAtEpochMillis = firstSeenAtEpochMillis,
                tombstonedAtEpochMillis = null,
            ),
        )
        if (inserted == -1L) return false
        insertImportedActivity(activity)
        linkDigest(source, digest, activity.activityId)
        return true
    }

    @Transaction
    open suspend fun deleteImportedActivityToTombstone(
        activityId: String,
        source: String,
        digest: String,
        tombstonedAtEpochMillis: Long,
    ) {
        tombstoneDigest(source, digest, tombstonedAtEpochMillis)
        clearPendingHealthConnectObservationsForActivity(activityId)
        tombstoneHealthConnectMappingsForActivity(activityId, tombstonedAtEpochMillis)
        deleteImportedActivity(activityId)
    }

    @Query("DELETE FROM activities WHERE activityId = :activityId")
    protected abstract suspend fun deleteImportedActivity(activityId: String)
}

@Dao
interface AppMetadataDao {
    @Upsert
    suspend fun save(metadata: AppMetadataEntity)

    @Query("SELECT * FROM app_metadata WHERE key = :key")
    suspend fun value(key: String): AppMetadataEntity?

    @Query("DELETE FROM app_metadata")
    suspend fun clear()
}

@Dao
abstract class LedgerMaintenanceDao {
    @Query("DELETE FROM goals")
    protected abstract suspend fun clearGoals()

    @Query("DELETE FROM activities")
    protected abstract suspend fun clearActivities()

    @Query("DELETE FROM import_digests")
    protected abstract suspend fun clearDigests()

    @Query("DELETE FROM health_connect_mappings")
    protected abstract suspend fun clearHealthConnectMappings()

    @Query("DELETE FROM profile_settings")
    protected abstract suspend fun clearProfile()

    @Query("DELETE FROM app_metadata")
    protected abstract suspend fun clearMetadata()

    @Query(
        """
        UPDATE import_digests
        SET activityId = NULL,
            tombstonedAtEpochMillis = COALESCE(tombstonedAtEpochMillis, :erasedAtEpochMillis)
        WHERE activityId IS NOT NULL
        """,
    )
    protected abstract suspend fun tombstoneImportedDigests(erasedAtEpochMillis: Long): Int

    @Query(
        """
        UPDATE health_connect_mappings
        SET activityId = NULL,
            lifecycleState = :tombstoneState,
            correctionPending = 0,
            deletePending = 0,
            duplicateCandidateActivityId = NULL,
            tombstonedAtEpochMillis = COALESCE(tombstonedAtEpochMillis, :erasedAtEpochMillis),
            deletedAtEpochMillis = COALESCE(deletedAtEpochMillis, :erasedAtEpochMillis)
        WHERE lifecycleState != :tombstoneState
           OR activityId IS NOT NULL
           OR correctionPending = 1
           OR deletePending = 1
           OR duplicateCandidateActivityId IS NOT NULL
        """,
    )
    protected abstract suspend fun tombstoneImportedHealthConnectMappings(
        erasedAtEpochMillis: Long,
        tombstoneState: String = HEALTH_CONNECT_MAPPING_STATE_TOMBSTONED,
    ): Int

    @Query("DELETE FROM health_connect_pending_observations")
    protected abstract suspend fun clearPendingHealthConnectImportData(): Int

    @Query(
        """
        DELETE FROM activities
        WHERE source != :manualSource
        """,
    )
    protected abstract suspend fun deleteImportedActivities(
        manualSource: String = "manual",
    ): Int

    /** Deletes runner-owned ledger records in one transaction, including retained import tombstones. */
    @Transaction
    open suspend fun clearAll() {
        clearGoals()
        clearActivities()
        clearDigests()
        clearHealthConnectMappings()
        clearProfile()
        clearMetadata()
    }

    /**
     * Keeps manual activities and all plan/audit rows. The mapping update precedes the activity
     * delete so retained provider identities remain terminal even if the app is reconnected.
     */
    @Transaction
    open suspend fun clearImportedActivityData(
        erasedAtEpochMillis: Long,
    ): LocalImportedActivityEraseResult {
        require(erasedAtEpochMillis > 0) { "Imported-data erase time must be positive." }
        tombstoneImportedHealthConnectMappings(erasedAtEpochMillis)
        clearPendingHealthConnectImportData()
        val tombstones = tombstoneImportedDigests(erasedAtEpochMillis)
        val erased = deleteImportedActivities()
        return LocalImportedActivityEraseResult(
            activitiesErased = erased,
            retainedImportTombstones = tombstones,
        )
    }
}

const val WORKOUT_STATE_TOMBSTONED = "tombstoned"
const val ACTIVITY_REVIEW_STATE_ACCEPTED = "accepted"
const val HEALTH_CONNECT_MAPPING_STATE_TOMBSTONED = "tombstoned"

private fun List<RouteSampleEntity>.boundRouteSamples(limit: Int): List<RouteSampleEntity> {
    if (size <= limit) return this
    val lastIndex = lastIndex
    return buildList(limit) {
        repeat(limit) { outputIndex ->
            add(this@boundRouteSamples[(outputIndex * lastIndex) / (limit - 1)])
        }
    }
}

private fun <T> List<T>.boundEvenly(limit: Int): List<T> {
    require(limit > 0) { "Sample limit must be positive." }
    if (size <= limit) return this
    if (limit == 1) return listOf(first())
    return buildList(limit) {
        repeat(limit) { outputIndex ->
            add(this@boundEvenly[(outputIndex * lastIndex) / (limit - 1)])
        }
    }
}
