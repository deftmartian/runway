package dev.deftmartian.runway.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileSettingsEntity::class,
        ProfileAvailabilityDayEntity::class,
        GoalEntity::class,
        PlanEntity::class,
        PlanSummaryWarningEntity::class,
        PlanWeekEntity::class,
        PlanSourceReferenceEntity::class,
        PlanLifecycleEventEntity::class,
        WorkoutEntity::class,
        WorkoutBlockEntity::class,
        WorkoutSegmentEntity::class,
        WorkoutSourceReferenceEntity::class,
        ActivityEntity::class,
        ActivityFeedbackEntity::class,
        WorkoutFeedbackEntity::class,
        WorkoutFeedbackConsequenceEntity::class,
        WorkoutFeedbackConsequenceOptionEntity::class,
        RouteSampleEntity::class,
        HeartRateSampleEntity::class,
        ActivityConsequenceEntity::class,
        ActivityConsequenceOptionEntity::class,
        PlanAdjustmentEntity::class,
        AdjustmentEffectGroupEntity::class,
        AdjustmentWorkoutEffectEntity::class,
        AdjustmentEffectBlockSnapshotEntity::class,
        AdjustmentEffectSegmentSnapshotEntity::class,
        AdjustmentEffectSourceReferenceSnapshotEntity::class,
        AdjustmentConsequenceEntity::class,
        PlanDecisionEntity::class,
        DecisionConsequenceEntity::class,
        PlanReversalEntity::class,
        HealthConnectMappingEntity::class,
        HealthConnectPendingObservationEntity::class,
        HealthConnectPendingRouteSampleEntity::class,
        HealthConnectPendingHeartRateSampleEntity::class,
        ImportDigestEntity::class,
        AppMetadataEntity::class,
        PlanSetupReceiptEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class RunwayLedgerDatabase : RoomDatabase() {
    abstract fun profileSettingsDao(): ProfileSettingsDao
    abstract fun goalPlanDao(): GoalPlanDao
    abstract fun activityLedgerDao(): ActivityLedgerDao
    abstract fun adjustmentDao(): AdjustmentDao
    abstract fun importLedgerDao(): ImportLedgerDao
    abstract fun appMetadataDao(): AppMetadataDao
    abstract fun planSetupReceiptDao(): PlanSetupReceiptDao
    abstract fun maintenanceDao(): LedgerMaintenanceDao

    companion object {
        const val DATABASE_NAME = "runway-ledger.db"
        const val SCHEMA_VERSION = 3
        /** Room's schema identity for [SCHEMA_VERSION], also recorded in the exported schema JSON. */
        const val SCHEMA_IDENTITY_HASH = "e07bbca67f5da673e81167f32b14d51a"

        fun create(context: Context): RunwayLedgerDatabase = Room.databaseBuilder(
            context.applicationContext,
            RunwayLedgerDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(
            RunwayLedgerMigrations.V1_TO_V2,
            RunwayLedgerMigrations.V2_TO_V3,
        ).build()
    }
}
