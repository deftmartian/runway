package dev.deftmartian.runway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

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
               decision.effectiveFromEpochDay AS effectiveFromEpochDay,
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
               effect.newReason AS newReason,
               effect_group.sourceWeekLoadMeters AS sourceWeekLoadMeters,
               effect_group.destinationWeekLoadMeters AS destinationWeekLoadMeters,
               effect_group.sourceWeekLoadDurationSeconds AS sourceWeekLoadDurationSeconds,
               effect_group.destinationWeekLoadDurationSeconds AS destinationWeekLoadDurationSeconds
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
    val effectiveFromEpochDay: Long? = null,
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
    val sourceWeekLoadMeters: Int? = null,
    val destinationWeekLoadMeters: Int? = null,
    val sourceWeekLoadDurationSeconds: Int? = null,
    val destinationWeekLoadDurationSeconds: Int? = null,
)
