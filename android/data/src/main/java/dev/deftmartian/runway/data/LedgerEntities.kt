package dev.deftmartian.runway.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room-facing persistence records for one runner. These deliberately use only stable primitive
 * values; domain adapters own conversion to the Kotlin training model and must not leak
 * presentation-specific types into this module.
 */
@Entity(tableName = "profile_settings")
data class ProfileSettingsEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val timeZone: String,
    val routeDataMode: String,
    /** Whether imported heart-rate summaries and detailed samples may be retained. */
    @ColumnInfo(defaultValue = "'private'")
    val heartRateDataMode: String = "discard",
    val heartRateSettingsSource: String,
    val maxHeartRateBpm: Int?,
    val zone2FloorBpm: Int?,
    val zone3FloorBpm: Int?,
    val zone4FloorBpm: Int?,
    val zone5FloorBpm: Int?,
    val recentInjury: Boolean,
    val currentPain: Boolean,
    val recurringPain: Boolean,
    val medicalRestriction: Boolean,
    val privateNotes: String?,
    val updatedAtEpochMillis: Long,
    val baselineDistanceMeters: Int? = null,
    val baselineDurationSeconds: Int? = null,
    val baselineConfirmed: Boolean = false,
    val currentRunsPerWeek: Int? = null,
    val longestRecentRunMeters: Int? = null,
    val calibrationDurationSeconds: Int? = null,
    val confirmConcentratedSchedule: Boolean = false,
    val preferredLongRunDay: Int? = null,
    val experienceLevel: String = "not_specified",
    val sexForEstimates: String = "not_specified",
    val ageYears: Int? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** Normalized, queryable selected days; this is not an opaque onboarding payload. */
@Entity(
    tableName = "profile_availability_days",
    primaryKeys = ["singletonId", "dayOfWeek"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileSettingsEntity::class,
            parentColumns = ["singletonId"],
            childColumns = ["singletonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["singletonId"])],
)
data class ProfileAvailabilityDayEntity(
    val singletonId: Int = ProfileSettingsEntity.SINGLETON_ID,
    val dayOfWeek: Int,
)

@Entity(
    tableName = "goals",
    indices = [Index(value = ["state"]), Index(value = ["targetDateEpochDay"])],
)
data class GoalEntity(
    @PrimaryKey val goalId: String,
    val title: String,
    val targetDateEpochDay: Long?,
    val state: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val kind: String,
    val startMode: String,
    val raceDistanceMeters: Int?,
    val priority: String,
)

@Entity(
    tableName = "plans",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["goalId"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["goalId"]), Index(value = ["state"]), Index(value = ["startEpochDay"])],
)
data class PlanEntity(
    @PrimaryKey val planId: String,
    val goalId: String,
    val phaseType: String,
    val state: String,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val riskAssessment: String? = null,
    val completedAtEpochMillis: Long? = null,
    val archivedAtEpochMillis: Long? = null,
    val summaryBaselineMeters: Int? = null,
    val summaryPeakMeters: Int? = null,
    val summaryRequiredWeeklyIncreasePercent: Double? = null,
    val summaryDefaultWeeklyIncreasePercent: Double? = null,
    val summaryLongRunPeakMeters: Int? = null,
    val summaryProgramWeeks: Int? = null,
    val summarySessionsPerWeek: Int? = null,
    val summaryContinuousRunTargetSeconds: Int? = null,
    val summarySessionDurationSeconds: Int? = null,
)

@Entity(
    tableName = "plan_summary_warnings",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["planId"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["planId", "ordinal"], unique = true),
    ],
)
data class PlanSummaryWarningEntity(
    @PrimaryKey val warningId: String,
    val planId: String,
    val ordinal: Int,
    val message: String,
)

@Entity(
    tableName = "plan_weeks",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["planId"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["planId", "ordinal"], unique = true),
        Index(value = ["planId", "weekId"], unique = true),
    ],
)
data class PlanWeekEntity(
    @PrimaryKey val weekId: String,
    val planId: String,
    val ordinal: Int,
    val startEpochDay: Long,
    val generatedLoadMeters: Int?,
    val generatedLoadDurationSeconds: Int? = null,
    val riskAssessment: String? = null,
    val isDownWeek: Boolean = false,
    val isTaperWeek: Boolean = false,
    val eventName: String? = null,
    val eventEpochDay: Long? = null,
    val generatedLongRunDistanceMeters: Int? = null,
)

@Entity(
    tableName = "plan_source_references",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["planId"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["planId", "ordinal"], unique = true)],
)
data class PlanSourceReferenceEntity(
    @PrimaryKey val referenceId: String,
    val planId: String,
    val ordinal: Int,
    val sourceName: String,
    val sourceUrl: String?,
    val sourceLocator: String?,
)

@Entity(
    tableName = "plan_lifecycle_events",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["planId"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["planId", "occurredAtEpochMillis"])],
)
data class PlanLifecycleEventEntity(
    @PrimaryKey val eventId: String,
    val planId: String,
    val eventType: String,
    val occurredAtEpochMillis: Long,
    val completedWorkoutCount: Int?,
    val completedActivityCount: Int?,
    val note: String?,
)

/** Generated prescription is immutable; current prescription and tombstone state are runner edits. */
@Entity(
    tableName = "workouts",
    foreignKeys = [
        ForeignKey(
            entity = PlanWeekEntity::class,
            parentColumns = ["planId", "weekId"],
            childColumns = ["planId", "weekId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["planId", "currentScheduledEpochDay", "position"], unique = true),
        Index(value = ["planId", "weekId"]),
        Index(value = ["weekId"]),
        Index(value = ["currentScheduledEpochDay"]),
        Index(value = ["currentStatus"]),
    ],
)
data class WorkoutEntity(
    @PrimaryKey val workoutId: String,
    val planId: String,
    val weekId: String,
    val position: Int,
    val generatedPurpose: String?,
    val generatedDistanceMeters: Int?,
    val generatedDurationSeconds: Int?,
    val currentPurpose: String?,
    val currentDistanceMeters: Int?,
    val currentDurationSeconds: Int?,
    val tombstonedAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long,
    val generatedScheduledEpochDay: Long,
    val currentScheduledEpochDay: Long,
    val generatedWorkoutType: String,
    val currentWorkoutType: String,
    val generatedPrescriptionKind: String,
    val currentPrescriptionKind: String,
    val generatedIntensity: String? = null,
    val currentIntensity: String? = null,
    val generatedReason: String? = null,
    val currentReason: String? = null,
    val currentStatus: String = "planned",
    val generatedWarmupSeconds: Int? = null,
    val generatedCooldownSeconds: Int? = null,
    val currentWarmupSeconds: Int? = null,
    val currentCooldownSeconds: Int? = null,
    /** Set once for runner-added workouts; generated-plan ownership is otherwise immutable. */
    val addedByAdjustmentId: String? = null,
)

@Entity(
    tableName = "workout_blocks",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["workoutId"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["workoutId", "prescriptionVersion", "ordinal"], unique = true)],
)
data class WorkoutBlockEntity(
    @PrimaryKey val blockId: String,
    val workoutId: String,
    val prescriptionVersion: String,
    val ordinal: Int,
    val blockType: String,
    val repetitions: Int,
)

@Entity(
    tableName = "workout_segments",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutBlockEntity::class,
            parentColumns = ["blockId"],
            childColumns = ["blockId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["blockId", "ordinal"], unique = true)],
)
data class WorkoutSegmentEntity(
    @PrimaryKey val segmentId: String,
    val blockId: String,
    val ordinal: Int,
    val segmentType: String,
    val targetDistanceMeters: Int?,
    val targetDurationSeconds: Int?,
)

@Entity(
    tableName = "workout_source_references",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["workoutId"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["workoutId", "prescriptionVersion", "ordinal"], unique = true)],
)
data class WorkoutSourceReferenceEntity(
    @PrimaryKey val referenceId: String,
    val workoutId: String,
    val prescriptionVersion: String,
    val ordinal: Int,
    val sourceName: String,
    val sourceUrl: String?,
    val sourceLocator: String?,
)

@Entity(
    tableName = "activities",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["workoutId"],
            childColumns = ["linkedWorkoutId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["occurredAtEpochMillis"]),
        Index(value = ["reviewState"]),
        Index(value = ["linkedWorkoutId"]),
        Index(value = ["source", "sourceRecordId"], unique = true),
    ],
)
data class ActivityEntity(
    @PrimaryKey val activityId: String,
    val source: String,
    val sourceRecordId: String?,
    val reviewState: String,
    val occurredAtEpochMillis: Long,
    val durationSeconds: Int?,
    val distanceMeters: Int?,
    val averageHeartRateBpm: Int?,
    val averageCadenceSpm: Int?,
    val linkedWorkoutId: String?,
    val acceptedAtEpochMillis: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val maxHeartRateBpm: Int? = null,
    val maxSpeedMetersPerSecond: Double? = null,
    val elevationGainMeters: Double? = null,
    val elevationLossMeters: Double? = null,
    val routePointCount: Int = 0,
    val heartRatePointCount: Int = 0,
    val elevationPointCount: Int = 0,
    val heartRateSourceSampleCount: Int = 0,
    val routeTraceRetained: Boolean = false,
    val routeStartEndRedacted: Boolean = false,
    val heartRateSeriesRetained: Boolean = false,
    val extraPlanImpactConfirmed: Boolean = false,
)

@Entity(
    tableName = "activity_feedback",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["activityId"], unique = true), Index(value = ["recordedAtEpochMillis"])],
)
data class ActivityFeedbackEntity(
    @PrimaryKey val feedbackId: String,
    val activityId: String,
    val feltHard: Boolean,
    val pain: Boolean,
    val notes: String?,
    val recordedAtEpochMillis: Long,
)

@Entity(
    tableName = "workout_feedback",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["workoutId"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["sourceActivityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["workoutId"], unique = true),
        Index(value = ["sourceActivityId"], unique = true),
        Index(value = ["recordedAtEpochMillis"]),
    ],
)
data class WorkoutFeedbackEntity(
    @PrimaryKey val feedbackId: String,
    val workoutId: String,
    val completionState: String,
    val feltHard: Boolean,
    val pain: Boolean,
    val notes: String?,
    val recordedAtEpochMillis: Long,
    val completedDistanceMeters: Int? = null,
    val completedDurationSeconds: Int? = null,
    /** Null for direct workout feedback; set for an accepted activity linked to this workout. */
    val sourceActivityId: String? = null,
)

@Entity(
    tableName = "workout_feedback_consequences",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutFeedbackEntity::class,
            parentColumns = ["feedbackId"],
            childColumns = ["feedbackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["feedbackId"], unique = true)],
)
data class WorkoutFeedbackConsequenceEntity(
    @PrimaryKey val feedbackId: String,
    val classification: String?,
    val distanceDifferenceMeters: Int?,
    val durationDifferenceSeconds: Int?,
    val currentWeekLoadMeters: Int?,
    val projectedWeekLoadMeters: Int?,
    val assessment: String?,
    val recoveryConflictCount: Int,
    val recommendedDecision: String?,
    val nextWorkoutAction: String?,
    val requiresExplicitConfirmation: Boolean,
    val deviation: String? = null,
    val loadMetric: String? = null,
    val risk: String? = null,
    val appliedDecision: String? = null,
    val comparisonStatus: String? = null,
    val planChangeAvailable: Boolean = true,
    val currentWeekLoadDurationSeconds: Int? = null,
    val projectedWeekLoadDurationSeconds: Int? = null,
)

@Entity(
    tableName = "route_samples",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["activityId", "ordinal"], unique = true)],
)
data class RouteSampleEntity(
    @PrimaryKey(autoGenerate = true) val sampleId: Long = 0,
    val activityId: String,
    val ordinal: Int,
    /** Coordinates use microdegrees, matching import and UI payload contracts. */
    val latitudeE6: Int,
    val longitudeE6: Int,
    val elapsedSeconds: Int?,
    val elevationMeters: Double?,
    val segmentOrdinal: Int? = null,
    val speedMetersPerSecond: Double? = null,
)

@Entity(
    tableName = "heart_rate_samples",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["activityId", "ordinal"], unique = true)],
)
data class HeartRateSampleEntity(
    @PrimaryKey(autoGenerate = true) val sampleId: Long = 0,
    val activityId: String,
    val ordinal: Int,
    val elapsedSeconds: Int,
    val beatsPerMinute: Int,
    val sourceSampleCount: Int = 1,
)

@Entity(
    tableName = "activity_consequences",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["activityId"], unique = true)],
)
data class ActivityConsequenceEntity(
    @PrimaryKey val activityId: String,
    val classification: String?,
    val distanceDifferenceMeters: Int?,
    val durationDifferenceSeconds: Int?,
    val actualLoadMeters: Int?,
    val assessment: String?,
    val recommendedDecision: String?,
    val resolvedAtEpochMillis: Long?,
    val deviation: String? = null,
    val loadMetric: String? = null,
    val risk: String? = null,
    val appliedDecision: String? = null,
    val comparisonStatus: String? = null,
    val planChangeAvailable: Boolean = true,
    val actualLoadDurationSeconds: Int? = null,
)

@Entity(
    tableName = "workout_feedback_consequence_options",
    primaryKeys = ["feedbackId", "decision"],
    foreignKeys = [
        ForeignKey(
            entity = WorkoutFeedbackConsequenceEntity::class,
            parentColumns = ["feedbackId"],
            childColumns = ["feedbackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class WorkoutFeedbackConsequenceOptionEntity(
    val feedbackId: String,
    val decision: String,
)

@Entity(
    tableName = "activity_consequence_options",
    primaryKeys = ["activityId", "decision"],
    foreignKeys = [
        ForeignKey(
            entity = ActivityConsequenceEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ActivityConsequenceOptionEntity(
    val activityId: String,
    val decision: String,
)

@Entity(
    tableName = "plan_adjustments",
    foreignKeys = [
        ForeignKey(
            entity = PlanEntity::class,
            parentColumns = ["planId"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["workoutId"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["sourceActivityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["planId", "createdAtEpochMillis"]),
        Index(value = ["workoutId"]),
        Index(value = ["sourceActivityId"]),
        Index(value = ["triggerKind", "triggerId", "triggerVersion"], unique = true),
    ],
)
data class PlanAdjustmentEntity(
    @PrimaryKey val adjustmentId: String,
    val planId: String,
    val workoutId: String?,
    val sourceActivityId: String?,
    val adjustmentType: String,
    val state: String,
    val measuredLoadSharePercent: Double?,
    val projectedRampPercent: Double?,
    val affectedWorkoutCount: Int,
    val createdAtEpochMillis: Long,
    /** Immutable source ownership; it is never inferred from mutable consequence rows. */
    val triggerKind: String? = null,
    val triggerId: String? = null,
    val triggerVersion: String? = null,
)

@Entity(
    tableName = "adjustment_effect_groups",
    foreignKeys = [
        ForeignKey(
            entity = PlanAdjustmentEntity::class,
            parentColumns = ["adjustmentId"],
            childColumns = ["adjustmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["adjustmentId", "ordinal"], unique = true)],
)
data class AdjustmentEffectGroupEntity(
    @PrimaryKey val groupId: String,
    val adjustmentId: String,
    val ordinal: Int,
    val effectType: String,
    val sourceWeekLoadMeters: Int?,
    val destinationWeekLoadMeters: Int?,
    val sourceWeekLoadDurationSeconds: Int? = null,
    val destinationWeekLoadDurationSeconds: Int? = null,
)

/** Complete before/after effective state supports exact reset, undo, and history rendering. */
@Entity(
    tableName = "adjustment_workout_effects",
    foreignKeys = [
        ForeignKey(
            entity = AdjustmentEffectGroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["workoutId"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["groupId", "ordinal"], unique = true), Index(value = ["workoutId"])],
)
data class AdjustmentWorkoutEffectEntity(
    @PrimaryKey val effectId: String,
    val groupId: String,
    val workoutId: String?,
    val ordinal: Int,
    val previousScheduledEpochDay: Long?,
    val newScheduledEpochDay: Long?,
    val previousWorkoutType: String?,
    val newWorkoutType: String?,
    val previousStatus: String?,
    val newStatus: String?,
    val previousDistanceMeters: Int?,
    val newDistanceMeters: Int?,
    val previousDurationSeconds: Int?,
    val newDurationSeconds: Int?,
    val previousIntensity: String?,
    val newIntensity: String?,
    val previousPurpose: String?,
    val newPurpose: String?,
    val previousReason: String?,
    val newReason: String?,
    val previousTombstonedAtEpochMillis: Long?,
    val newTombstonedAtEpochMillis: Long?,
    val previousWarmupSeconds: Int? = null,
    val newWarmupSeconds: Int? = null,
    val previousCooldownSeconds: Int? = null,
    val newCooldownSeconds: Int? = null,
    val previousPrescriptionKind: String? = null,
    val newPrescriptionKind: String? = null,
    /** Week ownership is captured with the effect rather than inferred from mutable workouts. */
    val previousWeekId: String? = null,
    val newWeekId: String? = null,
)

@Entity(
    tableName = "adjustment_effect_block_snapshots",
    foreignKeys = [ForeignKey(entity = AdjustmentWorkoutEffectEntity::class, parentColumns = ["effectId"], childColumns = ["effectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["effectId", "snapshotState", "ordinal"], unique = true)],
)
data class AdjustmentEffectBlockSnapshotEntity(
    @PrimaryKey val blockSnapshotId: String,
    val effectId: String,
    val snapshotState: String,
    val ordinal: Int,
    val blockType: String,
    val repetitions: Int,
)

@Entity(
    tableName = "adjustment_effect_segment_snapshots",
    foreignKeys = [ForeignKey(entity = AdjustmentEffectBlockSnapshotEntity::class, parentColumns = ["blockSnapshotId"], childColumns = ["blockSnapshotId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["blockSnapshotId", "ordinal"], unique = true)],
)
data class AdjustmentEffectSegmentSnapshotEntity(
    @PrimaryKey val segmentSnapshotId: String,
    val blockSnapshotId: String,
    val ordinal: Int,
    val segmentType: String,
    val targetDistanceMeters: Int?,
    val targetDurationSeconds: Int?,
)

@Entity(
    tableName = "adjustment_effect_source_reference_snapshots",
    foreignKeys = [ForeignKey(entity = AdjustmentWorkoutEffectEntity::class, parentColumns = ["effectId"], childColumns = ["effectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["effectId", "snapshotState", "ordinal"], unique = true)],
)
data class AdjustmentEffectSourceReferenceSnapshotEntity(
    @PrimaryKey val sourceReferenceSnapshotId: String,
    val effectId: String,
    val snapshotState: String,
    val ordinal: Int,
    val sourceName: String,
    val sourceUrl: String?,
    val sourceLocator: String?,
)

/** Stored consequences remain queryable rather than being an opaque action-preview blob. */
@Entity(
    tableName = "adjustment_consequences",
    foreignKeys = [
        ForeignKey(
            entity = PlanAdjustmentEntity::class,
            parentColumns = ["adjustmentId"],
            childColumns = ["adjustmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["adjustmentId"], unique = true)],
)
data class AdjustmentConsequenceEntity(
    @PrimaryKey val adjustmentId: String,
    val sourceWeekLoadMeters: Int?,
    val destinationWeekLoadMeters: Int?,
    val recoveryConflictCount: Int,
    val assessment: String?,
    val nextWorkoutState: String?,
    val sourceWeekLoadDurationSeconds: Int? = null,
    val destinationWeekLoadDurationSeconds: Int? = null,
)

@Entity(
    tableName = "plan_decisions",
    foreignKeys = [
        ForeignKey(
            entity = PlanAdjustmentEntity::class,
            parentColumns = ["adjustmentId"],
            childColumns = ["adjustmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["adjustmentId"]), Index(value = ["decidedAtEpochMillis"])],
)
data class PlanDecisionEntity(
    @PrimaryKey val decisionId: String,
    val adjustmentId: String,
    val decisionType: String,
    val affectedWorkoutCount: Int,
    val effectiveFromEpochDay: Long?,
    val decidedAtEpochMillis: Long,
    val affectedDistanceMeters: Int? = null,
    val affectedDurationSeconds: Int? = null,
)

@Entity(
    tableName = "decision_consequences",
    foreignKeys = [
        ForeignKey(
            entity = PlanDecisionEntity::class,
            parentColumns = ["decisionId"],
            childColumns = ["decisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["decisionId"], unique = true)],
)
data class DecisionConsequenceEntity(
    @PrimaryKey val decisionId: String,
    val resultingWeekLoadMeters: Int?,
    val resultingAssessment: String?,
    val nextWorkoutState: String?,
    val appliedAtEpochMillis: Long,
    val resultingWeekLoadDurationSeconds: Int? = null,
)

@Entity(
    tableName = "plan_reversals",
    foreignKeys = [
        ForeignKey(
            entity = PlanDecisionEntity::class,
            parentColumns = ["decisionId"],
            childColumns = ["decisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["decisionId"], unique = true)],
)
data class PlanReversalEntity(
    @PrimaryKey val reversalId: String,
    val decisionId: String,
    val reason: String?,
    val reversedAtEpochMillis: Long,
)

@Entity(
    tableName = "health_connect_mappings",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["provider", "externalRecordId"], unique = true),
        Index(value = ["activityId"], unique = true),
    ],
)
data class HealthConnectMappingEntity(
    @PrimaryKey val mappingId: String,
    val provider: String,
    val externalRecordId: String,
    val activityId: String?,
    val importedAtEpochMillis: Long,
    val lastObservedAtEpochMillis: Long,
    val lifecycleState: String = "active",
    val correctionPending: Boolean = false,
    val deletePending: Boolean = false,
    val tombstonedAtEpochMillis: Long? = null,
    val deletedAtEpochMillis: Long? = null,
    val lastCorrectionAtEpochMillis: Long? = null,
    val fingerprint: String? = null,
    val originKey: String? = null,
    val originLabel: String? = null,
    val runningType: String? = null,
    val duplicateCandidateActivityId: String? = null,
)

/** Pending Health Connect observations are retained until an explicit correction/delete resolution. */
@Entity(
    tableName = "health_connect_pending_observations",
    foreignKeys = [ForeignKey(entity = HealthConnectMappingEntity::class, parentColumns = ["mappingId"], childColumns = ["mappingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["mappingId"], unique = true)],
)
data class HealthConnectPendingObservationEntity(
    @PrimaryKey val mappingId: String,
    val observedAtEpochMillis: Long,
    val occurredAtEpochMillis: Long,
    val durationSeconds: Int?,
    val distanceMeters: Int?,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val averageCadenceSpm: Int?,
    val elevationGainMeters: Double?,
    val heartRateSourceSampleCount: Int,
    val routeSourcePointCount: Int,
    val fingerprint: String,
    val originKey: String?,
    val originLabel: String?,
    val runningType: String?,
    val duplicateCandidateActivityId: String?,
)

@Entity(
    tableName = "health_connect_pending_route_samples",
    foreignKeys = [ForeignKey(entity = HealthConnectPendingObservationEntity::class, parentColumns = ["mappingId"], childColumns = ["mappingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["mappingId", "ordinal"], unique = true)],
)
data class HealthConnectPendingRouteSampleEntity(
    @PrimaryKey(autoGenerate = true) val sampleId: Long = 0,
    val mappingId: String,
    val ordinal: Int,
    val latitudeE6: Int,
    val longitudeE6: Int,
    val elapsedSeconds: Int?,
    val elevationMeters: Double?,
    val segmentOrdinal: Int?,
    val speedMetersPerSecond: Double?,
)

@Entity(
    tableName = "health_connect_pending_heart_rate_samples",
    foreignKeys = [ForeignKey(entity = HealthConnectPendingObservationEntity::class, parentColumns = ["mappingId"], childColumns = ["mappingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["mappingId", "ordinal"], unique = true)],
)
data class HealthConnectPendingHeartRateSampleEntity(
    @PrimaryKey(autoGenerate = true) val sampleId: Long = 0,
    val mappingId: String,
    val ordinal: Int,
    val elapsedSeconds: Int,
    val beatsPerMinute: Int,
)

/** A retained digest prevents a deleted private import from being silently re-imported. */
@Entity(
    tableName = "import_digests",
    primaryKeys = ["source", "digest"],
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["activityId"]), Index(value = ["tombstonedAtEpochMillis"])],
)
data class ImportDigestEntity(
    val source: String,
    val digest: String,
    val activityId: String?,
    val firstSeenAtEpochMillis: Long,
    val tombstonedAtEpochMillis: Long?,
)

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAtEpochMillis: Long,
)

/**
 * Durable proof that one setup operation committed. The fingerprint binds a UI operation identity
 * to the exact normalized setup form; it is not inferred from goal dates or generated row IDs.
 */
@Entity(
    tableName = "plan_setup_receipts",
    foreignKeys = [
        ForeignKey(
            entity = GoalEntity::class,
            parentColumns = ["goalId"],
            childColumns = ["goalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["goalId"])],
)
data class PlanSetupReceiptEntity(
    @PrimaryKey val operationId: String,
    val operationFingerprint: String,
    val goalId: String,
    val planId: String?,
    val committedAtEpochMillis: Long,
)
