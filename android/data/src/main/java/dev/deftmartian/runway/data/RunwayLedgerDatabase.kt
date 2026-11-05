package dev.deftmartian.runway.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        ProfileSettingsEntity::class,
        ProfileAvailabilityDayEntity::class,
        GoalEntity::class,
        PlanEntity::class,
        RoutineScheduleDayEntity::class,
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
        NotificationPreferencesEntity::class,
        NotificationDeliveryEntity::class,
    ],
    version = 7,
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
    abstract fun notificationDao(): NotificationDao
    abstract fun maintenanceDao(): LedgerMaintenanceDao

    companion object {
        const val DATABASE_NAME = "runway-ledger.db"
        const val SCHEMA_VERSION = 7
        /** Room's schema identity for [SCHEMA_VERSION], also recorded in the exported schema JSON. */
        const val SCHEMA_IDENTITY_HASH = "c3cc35c4d3eb9343c527ae41e8976927"

        /**
         * Schema 7 shipped in v0.11.0 and is the first schema whose local data runway preserves.
         * Older schemas (v0.10.x and earlier) are still recreated, as v0.11.0 announced.
         */
        const val FIRST_PRESERVED_SCHEMA_VERSION = 7

        /**
         * Identity of every released schema from [FIRST_PRESERVED_SCHEMA_VERSION] to [SCHEMA_VERSION].
         * Restore accepts these and [MIGRATIONS] bring them up to date when the database opens.
         * A released entry never changes; a schema change adds a version, an entry, and a migration.
         */
        val RELEASED_SCHEMA_IDENTITY_HASHES: Map<Int, String> = mapOf(
            7 to "c3cc35c4d3eb9343c527ae41e8976927",
        )

        /** Explicit upgrade path from each released schema. Without one, opening fails instead of erasing data. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun create(context: Context): RunwayLedgerDatabase = builder(context, DATABASE_NAME).build()

        /**
         * The production open policy. Released schemas migrate; pre-release schemas are recreated.
         * There is no destructive fallback for a missing upgrade or for a downgrade.
         */
        fun builder(context: Context, name: String): RoomDatabase.Builder<RunwayLedgerDatabase> =
            Room.databaseBuilder(
                context.applicationContext,
                RunwayLedgerDatabase::class.java,
                name,
            )
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigrationFrom(
                    true,
                    *(1 until FIRST_PRESERVED_SCHEMA_VERSION).toList().toIntArray(),
                )
    }
}
