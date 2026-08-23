package dev.deftmartian.runway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class StoredStatsAggregateRow(
    val provenance: String,
    val runs: Long,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val longestRunMeters: Int?,
    val pairedDistanceMeters: Long,
    val pairedDurationSeconds: Long,
    val heartRateDurationSeconds: Long,
    val heartRateBeatsSeconds: Long,
)

data class StoredLoadAggregateRow(
    val runs: Long,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val longestRunMeters: Int?,
    val pairedDistanceMeters: Long,
    val pairedDurationSeconds: Long,
    val heartRateDurationSeconds: Long,
    val heartRateBeatsSeconds: Long,
    val painFlags: Long,
    val hardFlags: Long,
)

data class StoredWeekLoadAggregateRow(
    val weekId: String,
    val runs: Long,
    val distanceMeters: Long,
    val durationSeconds: Long,
    val longestRunMeters: Int?,
    val pairedDistanceMeters: Long,
    val pairedDurationSeconds: Long,
    val heartRateDurationSeconds: Long,
    val heartRateBeatsSeconds: Long,
    val painFlags: Long,
    val hardFlags: Long,
)

data class StoredUnlinkedActivityStatsRow(
    val activityId: String,
    val occurredAtEpochMillis: Long,
    val distanceMeters: Int?,
    val durationSeconds: Int?,
    val averageHeartRateBpm: Int?,
    val pain: Boolean?,
    val feltHard: Boolean?,
)

@Dao
interface ActivityLedgerDao {
    @Query("SELECT * FROM activities WHERE reviewState = :reviewState ORDER BY occurredAtEpochMillis DESC LIMIT :limit")
    fun observeActivitiesByReviewState(reviewState: String, limit: Int): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE reviewState = :reviewState ORDER BY occurredAtEpochMillis DESC LIMIT :limit")
    suspend fun activitiesByReviewState(reviewState: String, limit: Int): List<ActivityEntity>

    @Query(
        """
        SELECT review_activity.* FROM activities AS review_activity
        WHERE review_activity.reviewState = 'review'
          AND NOT EXISTS (
              SELECT 1 FROM health_connect_mappings AS duplicate_mapping
              WHERE duplicate_mapping.activityId = review_activity.activityId
                AND duplicate_mapping.duplicateCandidateActivityId IS NOT NULL
          )
          AND (:beforeOccurredAt IS NULL OR review_activity.occurredAtEpochMillis < :beforeOccurredAt
            OR (review_activity.occurredAtEpochMillis = :beforeOccurredAt
                AND review_activity.activityId < :beforeActivityId))
        ORDER BY review_activity.occurredAtEpochMillis DESC, review_activity.activityId DESC LIMIT :limit
        """,
    )
    suspend fun actionableReviewActivitiesPage(
        beforeOccurredAt: Long?, beforeActivityId: String?, limit: Int,
    ): List<ActivityEntity>

    @Query(
        """
        SELECT COUNT(*) FROM activities AS review_activity
        WHERE review_activity.reviewState = 'review'
          AND NOT EXISTS (
              SELECT 1 FROM health_connect_mappings AS duplicate_mapping
              WHERE duplicate_mapping.activityId = review_activity.activityId
                AND duplicate_mapping.duplicateCandidateActivityId IS NOT NULL
          )
        """,
    )
    suspend fun actionableReviewActivityCount(): Int

    @Query("SELECT COUNT(*) FROM activities WHERE reviewState = :reviewState")
    suspend fun activityCountByReviewState(reviewState: String): Int

    /**
     * Exact count of cards the Inbox can require the runner to resolve. A possible Health Connect
     * duplicate replaces its raw review row, so it is counted once rather than as two decisions.
     */
    @Query(
        """
        SELECT
            (
                SELECT COUNT(*)
                FROM activities AS review_activity
                WHERE review_activity.reviewState = 'review'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM health_connect_mappings AS duplicate_mapping
                      WHERE duplicate_mapping.activityId = review_activity.activityId
                        AND duplicate_mapping.duplicateCandidateActivityId IS NOT NULL
                  )
            )
            +
            (
                SELECT COUNT(*)
                FROM activities AS extra_activity
                INNER JOIN activity_consequences AS extra_consequence
                    ON extra_consequence.activityId = extra_activity.activityId
                WHERE extra_activity.reviewState = 'accepted'
                  AND extra_activity.linkedWorkoutId IS NULL
                  AND extra_activity.extraPlanImpactConfirmed = 1
                  AND extra_consequence.planChangeAvailable = 1
                  AND extra_consequence.appliedDecision IS NULL
                  AND extra_consequence.resolvedAtEpochMillis IS NULL
            )
            +
            (
                SELECT COUNT(DISTINCT linked_activity.activityId)
                FROM activities AS linked_activity
                INNER JOIN workout_feedback AS linked_feedback
                    ON linked_feedback.sourceActivityId = linked_activity.activityId
                INNER JOIN workout_feedback_consequences AS linked_consequence
                    ON linked_consequence.feedbackId = linked_feedback.feedbackId
                INNER JOIN workouts AS linked_workout
                    ON linked_workout.workoutId = linked_feedback.workoutId
                INNER JOIN plans AS linked_plan
                    ON linked_plan.planId = linked_workout.planId
                WHERE linked_activity.reviewState = 'accepted'
                  AND linked_activity.linkedWorkoutId IS NOT NULL
                  AND linked_plan.state = 'active'
                  AND linked_consequence.planChangeAvailable = 1
                  AND linked_consequence.appliedDecision IS NULL
            )
            +
            (
                SELECT COUNT(*)
                FROM workout_feedback AS direct_feedback
                INNER JOIN workout_feedback_consequences AS direct_consequence
                    ON direct_consequence.feedbackId = direct_feedback.feedbackId
                INNER JOIN workouts AS direct_workout
                    ON direct_workout.workoutId = direct_feedback.workoutId
                INNER JOIN plans AS direct_plan
                    ON direct_plan.planId = direct_workout.planId
                WHERE direct_feedback.sourceActivityId IS NULL
                  AND direct_plan.state = 'active'
                  AND direct_consequence.planChangeAvailable = 1
                  AND direct_consequence.appliedDecision IS NULL
            )
            +
            (
                SELECT COUNT(*)
                FROM health_connect_mappings AS health_change
                WHERE health_change.correctionPending = 1
                   OR health_change.deletePending = 1
                   OR health_change.duplicateCandidateActivityId IS NOT NULL
            )
        """,
    )
    suspend fun actionableInboxDecisionCount(): Int

    /**
     * Accepted extras keep their plan-choice card in Inbox until the runner applies a choice.
     * This deliberately excludes linked work and historical/non-actionable consequences.
     */
    @Query(
        """
        SELECT activity.*
        FROM activities AS activity
        INNER JOIN activity_consequences AS consequence
            ON consequence.activityId = activity.activityId
        WHERE activity.reviewState = 'accepted'
          AND activity.linkedWorkoutId IS NULL
          AND activity.extraPlanImpactConfirmed = 1
          AND consequence.planChangeAvailable = 1
          AND consequence.appliedDecision IS NULL
          AND consequence.resolvedAtEpochMillis IS NULL
        ORDER BY activity.occurredAtEpochMillis DESC, activity.activityId DESC
        LIMIT :limit
        """,
    )
    suspend fun acceptedUnlinkedExtrasWithPendingPlanChange(limit: Int): List<ActivityEntity>

    @Query(
        """
        SELECT activity.*
        FROM activities AS activity
        INNER JOIN activity_consequences AS consequence ON consequence.activityId = activity.activityId
        WHERE activity.reviewState = 'accepted' AND activity.linkedWorkoutId IS NULL
          AND activity.extraPlanImpactConfirmed = 1 AND consequence.planChangeAvailable = 1
          AND consequence.appliedDecision IS NULL AND consequence.resolvedAtEpochMillis IS NULL
          AND (:beforeOccurredAt IS NULL OR activity.occurredAtEpochMillis < :beforeOccurredAt
            OR (activity.occurredAtEpochMillis = :beforeOccurredAt AND activity.activityId < :beforeActivityId))
        ORDER BY activity.occurredAtEpochMillis DESC, activity.activityId DESC LIMIT :limit
        """,
    )
    suspend fun acceptedUnlinkedExtrasWithPendingPlanChangePage(
        beforeOccurredAt: Long?, beforeActivityId: String?, limit: Int,
    ): List<ActivityEntity>

    /** Linked activity outcomes use workout-feedback consequences rather than activity consequences. */
    @Query(
        """
        SELECT activity.*
        FROM activities AS activity
        INNER JOIN workout_feedback AS feedback
            ON feedback.sourceActivityId = activity.activityId
        INNER JOIN workout_feedback_consequences AS consequence
            ON consequence.feedbackId = feedback.feedbackId
        INNER JOIN workouts AS workout
            ON workout.workoutId = feedback.workoutId
        INNER JOIN plans AS active_plan
            ON active_plan.planId = workout.planId
        WHERE activity.reviewState = 'accepted'
          AND activity.linkedWorkoutId IS NOT NULL
          AND active_plan.state = 'active'
          AND consequence.planChangeAvailable = 1
          AND consequence.appliedDecision IS NULL
        ORDER BY activity.occurredAtEpochMillis DESC, activity.activityId DESC
        LIMIT :limit
        """,
    )
    suspend fun acceptedLinkedActivitiesWithPendingPlanChange(limit: Int): List<ActivityEntity>

    @Query(
        """
        SELECT activity.*
        FROM activities AS activity
        INNER JOIN workout_feedback AS feedback ON feedback.sourceActivityId = activity.activityId
        INNER JOIN workout_feedback_consequences AS consequence ON consequence.feedbackId = feedback.feedbackId
        INNER JOIN workouts AS workout ON workout.workoutId = feedback.workoutId
        INNER JOIN plans AS active_plan ON active_plan.planId = workout.planId
        WHERE activity.reviewState = 'accepted' AND activity.linkedWorkoutId IS NOT NULL
          AND active_plan.state = 'active' AND consequence.planChangeAvailable = 1
          AND consequence.appliedDecision IS NULL
          AND (:beforeOccurredAt IS NULL OR activity.occurredAtEpochMillis < :beforeOccurredAt
            OR (activity.occurredAtEpochMillis = :beforeOccurredAt AND activity.activityId < :beforeActivityId))
        ORDER BY activity.occurredAtEpochMillis DESC, activity.activityId DESC LIMIT :limit
        """,
    )
    suspend fun acceptedLinkedActivitiesWithPendingPlanChangePage(
        beforeOccurredAt: Long?, beforeActivityId: String?, limit: Int,
    ): List<ActivityEntity>

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
        WHERE reviewState = :acceptedState
          AND linkedWorkoutId IS NULL
        ORDER BY occurredAtEpochMillis DESC, activityId DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun acceptedUnlinkedActivitiesPage(
        limit: Int,
        offset: Int,
        acceptedState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    ): List<ActivityEntity>

    @Query(
        """
        SELECT * FROM activities
        WHERE reviewState = :acceptedState
          AND linkedWorkoutId IS NULL
          AND occurredAtEpochMillis >= :fromInclusive
          AND occurredAtEpochMillis < :toExclusive
          AND (
              occurredAtEpochMillis < :beforeEpochMillis
              OR (
                  occurredAtEpochMillis = :beforeEpochMillis
                  AND activityId < :beforeActivityId
              )
          )
        ORDER BY occurredAtEpochMillis DESC, activityId DESC
        LIMIT :limit
        """,
    )
    suspend fun acceptedUnlinkedActivitiesPageInRange(
        fromInclusive: Long,
        toExclusive: Long,
        beforeEpochMillis: Long,
        beforeActivityId: String,
        limit: Int,
        acceptedState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    ): List<ActivityEntity>

    @Query(
        """
        SELECT * FROM activities
        WHERE reviewState = :acceptedState
          AND linkedWorkoutId IS NULL
          AND extraPlanImpactConfirmed = 1
          AND occurredAtEpochMillis >= :fromInclusive
          AND occurredAtEpochMillis < :toExclusive
        ORDER BY occurredAtEpochMillis DESC, updatedAtEpochMillis DESC, activityId DESC
        LIMIT 1
        """,
    )
    suspend fun latestAcceptedUnlinkedExtraInRange(
        fromInclusive: Long,
        toExclusive: Long,
        acceptedState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    ): ActivityEntity?

    @Query(
        """
        SELECT * FROM activities
        WHERE reviewState = :acceptedState
          AND linkedWorkoutId IN (:workoutIds)
        ORDER BY linkedWorkoutId, occurredAtEpochMillis DESC, activityId DESC
        LIMIT :limit
        """,
    )
    suspend fun acceptedActivitiesLinkedToWorkouts(
        workoutIds: List<String>,
        limit: Int,
        acceptedState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    ): List<ActivityEntity>

    @Query(
        """
        SELECT COUNT(*) FROM activities
        WHERE reviewState = :acceptedState
          AND linkedWorkoutId IN (:workoutIds)
        """,
    )
    suspend fun acceptedActivityCountLinkedToWorkouts(
        workoutIds: List<String>,
        acceptedState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    ): Int

    @Query(
        """
        SELECT
            CASE
                WHEN activity.linkedWorkoutId IS NULL THEN 'unlinked'
                ELSE COALESCE(stored_plan.state, 'other')
            END AS provenance,
            COUNT(*) AS runs,
            COALESCE(SUM(COALESCE(activity.distanceMeters, 0)), 0) AS distanceMeters,
            COALESCE(SUM(COALESCE(activity.durationSeconds, 0)), 0) AS durationSeconds,
            MAX(activity.distanceMeters) AS longestRunMeters,
            COALESCE(SUM(CASE
                WHEN activity.distanceMeters > 0 AND activity.durationSeconds > 0
                THEN activity.distanceMeters ELSE 0 END), 0) AS pairedDistanceMeters,
            COALESCE(SUM(CASE
                WHEN activity.distanceMeters > 0 AND activity.durationSeconds > 0
                THEN activity.durationSeconds ELSE 0 END), 0) AS pairedDurationSeconds,
            COALESCE(SUM(CASE
                WHEN activity.averageHeartRateBpm IS NOT NULL AND activity.durationSeconds > 0
                THEN activity.durationSeconds ELSE 0 END), 0) AS heartRateDurationSeconds,
            COALESCE(SUM(CASE
                WHEN activity.averageHeartRateBpm IS NOT NULL AND activity.durationSeconds > 0
                THEN CAST(activity.averageHeartRateBpm AS INTEGER) * activity.durationSeconds
                ELSE 0 END), 0) AS heartRateBeatsSeconds
        FROM activities AS activity
        LEFT JOIN workouts AS workout ON workout.workoutId = activity.linkedWorkoutId
        LEFT JOIN plans AS stored_plan ON stored_plan.planId = workout.planId
        WHERE activity.reviewState = :acceptedState
        GROUP BY provenance
        ORDER BY provenance
        """,
    )
    suspend fun acceptedStatsAggregates(
        acceptedState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    ): List<StoredStatsAggregateRow>

    @Query(
        """
        SELECT
            COALESCE(stored_plan.state, 'other') AS provenance,
            COUNT(*) AS runs,
            COALESCE(SUM(COALESCE(feedback.completedDistanceMeters, 0)), 0) AS distanceMeters,
            COALESCE(SUM(COALESCE(feedback.completedDurationSeconds, 0)), 0) AS durationSeconds,
            MAX(feedback.completedDistanceMeters) AS longestRunMeters,
            COALESCE(SUM(CASE
                WHEN feedback.completedDistanceMeters > 0 AND feedback.completedDurationSeconds > 0
                THEN feedback.completedDistanceMeters ELSE 0 END), 0) AS pairedDistanceMeters,
            COALESCE(SUM(CASE
                WHEN feedback.completedDistanceMeters > 0 AND feedback.completedDurationSeconds > 0
                THEN feedback.completedDurationSeconds ELSE 0 END), 0) AS pairedDurationSeconds,
            0 AS heartRateDurationSeconds,
            0 AS heartRateBeatsSeconds
        FROM workout_feedback AS feedback
        INNER JOIN workouts AS workout ON workout.workoutId = feedback.workoutId
        LEFT JOIN plans AS stored_plan ON stored_plan.planId = workout.planId
        WHERE feedback.sourceActivityId IS NULL
          AND feedback.completionState IN ('done', 'shortened', 'completed', 'overrun')
        GROUP BY provenance
        ORDER BY provenance
        """,
    )
    suspend fun directFeedbackStatsAggregates(): List<StoredStatsAggregateRow>

    @Query(
        """
        SELECT
            workout.weekId AS weekId,
            COUNT(*) AS runs,
            COALESCE(SUM(COALESCE(activity.distanceMeters, 0)), 0) AS distanceMeters,
            COALESCE(SUM(COALESCE(activity.durationSeconds, 0)), 0) AS durationSeconds,
            MAX(activity.distanceMeters) AS longestRunMeters,
            COALESCE(SUM(CASE
                WHEN activity.distanceMeters > 0 AND activity.durationSeconds > 0
                THEN activity.distanceMeters ELSE 0 END), 0) AS pairedDistanceMeters,
            COALESCE(SUM(CASE
                WHEN activity.distanceMeters > 0 AND activity.durationSeconds > 0
                THEN activity.durationSeconds ELSE 0 END), 0) AS pairedDurationSeconds,
            COALESCE(SUM(CASE
                WHEN activity.averageHeartRateBpm IS NOT NULL AND activity.durationSeconds > 0
                THEN activity.durationSeconds ELSE 0 END), 0) AS heartRateDurationSeconds,
            COALESCE(SUM(CASE
                WHEN activity.averageHeartRateBpm IS NOT NULL AND activity.durationSeconds > 0
                THEN CAST(activity.averageHeartRateBpm AS INTEGER) * activity.durationSeconds
                ELSE 0 END), 0) AS heartRateBeatsSeconds,
            COALESCE(SUM(CASE WHEN feedback.pain = 1 THEN 1 ELSE 0 END), 0) AS painFlags,
            COALESCE(SUM(CASE WHEN feedback.feltHard = 1 THEN 1 ELSE 0 END), 0) AS hardFlags
        FROM activities AS activity
        INNER JOIN workouts AS workout ON workout.workoutId = activity.linkedWorkoutId
        LEFT JOIN activity_feedback AS feedback ON feedback.activityId = activity.activityId
        WHERE activity.reviewState = :acceptedState
          AND workout.weekId IN (:weekIds)
        GROUP BY workout.weekId
        ORDER BY workout.weekId
        """,
    )
    suspend fun acceptedLinkedAggregatesForWeeks(
        weekIds: List<String>,
        acceptedState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    ): List<StoredWeekLoadAggregateRow>

    @Query(
        """
        SELECT
            workout.weekId AS weekId,
            COUNT(*) AS runs,
            COALESCE(SUM(COALESCE(feedback.completedDistanceMeters, 0)), 0) AS distanceMeters,
            COALESCE(SUM(COALESCE(feedback.completedDurationSeconds, 0)), 0) AS durationSeconds,
            MAX(feedback.completedDistanceMeters) AS longestRunMeters,
            COALESCE(SUM(CASE
                WHEN feedback.completedDistanceMeters > 0 AND feedback.completedDurationSeconds > 0
                THEN feedback.completedDistanceMeters ELSE 0 END), 0) AS pairedDistanceMeters,
            COALESCE(SUM(CASE
                WHEN feedback.completedDistanceMeters > 0 AND feedback.completedDurationSeconds > 0
                THEN feedback.completedDurationSeconds ELSE 0 END), 0) AS pairedDurationSeconds,
            0 AS heartRateDurationSeconds,
            0 AS heartRateBeatsSeconds,
            COALESCE(SUM(CASE WHEN feedback.pain = 1 THEN 1 ELSE 0 END), 0) AS painFlags,
            COALESCE(SUM(CASE WHEN feedback.feltHard = 1 THEN 1 ELSE 0 END), 0) AS hardFlags
        FROM workout_feedback AS feedback
        INNER JOIN workouts AS workout ON workout.workoutId = feedback.workoutId
        WHERE workout.weekId IN (:weekIds)
          AND feedback.sourceActivityId IS NULL
          AND feedback.completionState IN ('done', 'shortened', 'completed', 'overrun')
        GROUP BY workout.weekId
        ORDER BY workout.weekId
        """,
    )
    suspend fun directFeedbackAggregatesForWeeks(
        weekIds: List<String>,
    ): List<StoredWeekLoadAggregateRow>

    @Query(
        """
        SELECT
            activity.activityId,
            activity.occurredAtEpochMillis,
            activity.distanceMeters,
            activity.durationSeconds,
            activity.averageHeartRateBpm,
            feedback.pain,
            feedback.feltHard
        FROM activities AS activity
        LEFT JOIN activity_feedback AS feedback ON feedback.activityId = activity.activityId
        WHERE activity.reviewState = :acceptedState
          AND activity.linkedWorkoutId IS NULL
          AND activity.occurredAtEpochMillis >= :fromInclusive
          AND activity.occurredAtEpochMillis < :toExclusive
          AND (
              activity.occurredAtEpochMillis < :beforeEpochMillis
              OR (
                  activity.occurredAtEpochMillis = :beforeEpochMillis
                  AND activity.activityId < :beforeActivityId
              )
          )
        ORDER BY activity.occurredAtEpochMillis DESC, activity.activityId DESC
        LIMIT :limit
        """,
    )
    suspend fun acceptedUnlinkedStatsPage(
        fromInclusive: Long,
        toExclusive: Long,
        beforeEpochMillis: Long,
        beforeActivityId: String,
        limit: Int,
        acceptedState: String = ACTIVITY_REVIEW_STATE_ACCEPTED,
    ): List<StoredUnlinkedActivityStatsRow>

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

    @Query(
        """
        SELECT feedback.* FROM workout_feedback AS feedback
        INNER JOIN workout_feedback_consequences AS consequence ON consequence.feedbackId = feedback.feedbackId
        INNER JOIN workouts AS workout ON workout.workoutId = feedback.workoutId
        INNER JOIN plans AS stored_plan ON stored_plan.planId = workout.planId
        WHERE feedback.sourceActivityId IS NULL AND stored_plan.state = 'active'
          AND consequence.planChangeAvailable = 1 AND consequence.appliedDecision IS NULL
          AND (:beforeRecordedAt IS NULL OR feedback.recordedAtEpochMillis < :beforeRecordedAt
            OR (feedback.recordedAtEpochMillis = :beforeRecordedAt AND feedback.feedbackId < :beforeFeedbackId))
        ORDER BY feedback.recordedAtEpochMillis DESC, feedback.feedbackId DESC LIMIT :limit
        """,
    )
    suspend fun pendingDirectWorkoutFeedbackPage(
        beforeRecordedAt: Long?, beforeFeedbackId: String?, limit: Int,
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

    /** Only used while returning an unapplied extra activity to the explicit Review boundary. */
    @Query("DELETE FROM activity_consequences WHERE activityId = :activityId AND appliedDecision IS NULL")
    suspend fun clearUnappliedActivityConsequence(activityId: String): Int

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

    @Query("DELETE FROM heart_rate_samples")
    suspend fun clearAllHeartRateSamples()

    @Query(
        """
        UPDATE activities
        SET averageHeartRateBpm = NULL,
            maxHeartRateBpm = NULL,
            heartRatePointCount = 0,
            heartRateSourceSampleCount = 0,
            heartRateSeriesRetained = 0
        """,
    )
    suspend fun markAllHeartRateDataDiscarded()

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
