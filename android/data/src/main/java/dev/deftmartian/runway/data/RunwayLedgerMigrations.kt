package dev.deftmartian.runway.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Immutable local-ledger lineage. Existing v1 records kept detailed heart-rate samples, so their
 * newly explicit setting remains private rather than falsely claiming that those samples vanished.
 */
object RunwayLedgerMigrations {
    const val V1_IDENTITY_HASH = "154538ddd4b50c6c924697299e447e9a"
    const val V2_IDENTITY_HASH = "f91a86620eddb116d9e3fdea5af998bc"
    const val V3_IDENTITY_HASH = "e07bbca67f5da673e81167f32b14d51a"
    /** v4 was a repair-only lineage boundary and retained v3's Room schema identity. */
    const val V4_IDENTITY_HASH = "e07bbca67f5da673e81167f32b14d51a"
    const val V1_TO_V2_SQL =
        "ALTER TABLE profile_settings ADD COLUMN heartRateDataMode TEXT NOT NULL DEFAULT 'private'"
    const val CREATE_PLAN_SETUP_RECEIPTS_SQL =
        """
        CREATE TABLE plan_setup_receipts (
            operationId TEXT NOT NULL,
            operationFingerprint TEXT NOT NULL,
            goalId TEXT NOT NULL,
            planId TEXT,
            committedAtEpochMillis INTEGER NOT NULL,
            PRIMARY KEY(operationId),
            FOREIGN KEY(goalId) REFERENCES goals(goalId) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """
    const val CREATE_PLAN_SETUP_RECEIPTS_GOAL_INDEX_SQL =
        "CREATE INDEX index_plan_setup_receipts_goalId ON plan_setup_receipts (goalId)"
    private const val CREATE_PLAN_SOURCE_REFERENCES_V3_SQL =
        """
        CREATE TABLE plan_source_references_v3 (
            referenceId TEXT NOT NULL,
            planId TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            sourceName TEXT NOT NULL,
            sourceLocator TEXT,
            PRIMARY KEY(referenceId),
            FOREIGN KEY(planId) REFERENCES plans(planId) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """
    private const val CREATE_WORKOUT_SOURCE_REFERENCES_V3_SQL =
        """
        CREATE TABLE workout_source_references_v3 (
            referenceId TEXT NOT NULL,
            workoutId TEXT NOT NULL,
            prescriptionVersion TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            sourceName TEXT NOT NULL,
            sourceLocator TEXT,
            PRIMARY KEY(referenceId),
            FOREIGN KEY(workoutId) REFERENCES workouts(workoutId) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """
    private const val CREATE_ADJUSTMENT_EFFECT_SOURCE_REFERENCE_SNAPSHOTS_V3_SQL =
        """
        CREATE TABLE adjustment_effect_source_reference_snapshots_v3 (
            sourceReferenceSnapshotId TEXT NOT NULL,
            effectId TEXT NOT NULL,
            snapshotState TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            sourceName TEXT NOT NULL,
            sourceLocator TEXT,
            PRIMARY KEY(sourceReferenceSnapshotId),
            FOREIGN KEY(effectId) REFERENCES adjustment_workout_effects(effectId) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """

    const val ROUTE_RETENTION_REPAIR_KEY = "privacy_retention_repair_v3_route"
    const val HEART_RATE_RETENTION_REPAIR_KEY = "privacy_retention_repair_v3_heart_rate"
    const val CREATE_ROUTINE_SCHEDULE_DAYS_SQL =
        """
        CREATE TABLE routine_schedule_days (
            planId TEXT NOT NULL,
            dayOfWeek INTEGER NOT NULL,
            PRIMARY KEY(planId, dayOfWeek),
            FOREIGN KEY(planId) REFERENCES plans(planId) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """
    const val CREATE_ROUTINE_SCHEDULE_DAYS_PLAN_INDEX_SQL =
        "CREATE INDEX index_routine_schedule_days_planId ON routine_schedule_days (planId)"

    private val v2ToV3Sql = listOf(
        CREATE_PLAN_SETUP_RECEIPTS_SQL,
        CREATE_PLAN_SETUP_RECEIPTS_GOAL_INDEX_SQL,
        CREATE_PLAN_SOURCE_REFERENCES_V3_SQL,
        """
        INSERT INTO plan_source_references_v3 (
            referenceId, planId, ordinal, sourceName, sourceLocator
        )
        SELECT referenceId, planId, ordinal, sourceName, sourceLocator
        FROM plan_source_references
        """,
        "DROP TABLE plan_source_references",
        "ALTER TABLE plan_source_references_v3 RENAME TO plan_source_references",
        """
        CREATE UNIQUE INDEX index_plan_source_references_planId_ordinal
        ON plan_source_references (planId, ordinal)
        """,
        CREATE_WORKOUT_SOURCE_REFERENCES_V3_SQL,
        """
        INSERT INTO workout_source_references_v3 (
            referenceId, workoutId, prescriptionVersion, ordinal, sourceName, sourceLocator
        )
        SELECT referenceId, workoutId, prescriptionVersion, ordinal, sourceName, sourceLocator
        FROM workout_source_references
        """,
        "DROP TABLE workout_source_references",
        "ALTER TABLE workout_source_references_v3 RENAME TO workout_source_references",
        """
        CREATE UNIQUE INDEX index_workout_source_references_workoutId_prescriptionVersion_ordinal
        ON workout_source_references (workoutId, prescriptionVersion, ordinal)
        """,
        CREATE_ADJUSTMENT_EFFECT_SOURCE_REFERENCE_SNAPSHOTS_V3_SQL,
        """
        INSERT INTO adjustment_effect_source_reference_snapshots_v3 (
            sourceReferenceSnapshotId, effectId, snapshotState, ordinal, sourceName, sourceLocator
        )
        SELECT sourceReferenceSnapshotId, effectId, snapshotState, ordinal, sourceName, sourceLocator
        FROM adjustment_effect_source_reference_snapshots
        """,
        "DROP TABLE adjustment_effect_source_reference_snapshots",
        """
        ALTER TABLE adjustment_effect_source_reference_snapshots_v3
        RENAME TO adjustment_effect_source_reference_snapshots
        """,
        """
        CREATE UNIQUE INDEX index_adjustment_effect_source_reference_snapshots_effectId_snapshotState_ordinal
        ON adjustment_effect_source_reference_snapshots (effectId, snapshotState, ordinal)
        """,
        """
        INSERT OR REPLACE INTO app_metadata (key, value, updatedAtEpochMillis)
        SELECT '$ROUTE_RETENTION_REPAIR_KEY', 'restored_private', 0
        FROM profile_settings
        WHERE routeDataMode = 'discard'
          AND (
              EXISTS (SELECT 1 FROM route_samples LIMIT 1)
              OR EXISTS (
                  SELECT 1 FROM activities
                  WHERE routeTraceRetained = 1
                  LIMIT 1
              )
              OR EXISTS (SELECT 1 FROM health_connect_pending_route_samples LIMIT 1)
          )
        """,
        """
        UPDATE profile_settings
        SET routeDataMode = 'private'
        WHERE routeDataMode = 'discard'
          AND (
              EXISTS (SELECT 1 FROM route_samples LIMIT 1)
              OR EXISTS (
                  SELECT 1 FROM activities
                  WHERE routeTraceRetained = 1
                  LIMIT 1
              )
              OR EXISTS (SELECT 1 FROM health_connect_pending_route_samples LIMIT 1)
          )
        """,
        """
        INSERT OR REPLACE INTO app_metadata (key, value, updatedAtEpochMillis)
        SELECT '$HEART_RATE_RETENTION_REPAIR_KEY', 'restored_private', 0
        FROM profile_settings
        WHERE heartRateDataMode = 'discard'
          AND (
              EXISTS (SELECT 1 FROM heart_rate_samples LIMIT 1)
              OR EXISTS (
                  SELECT 1 FROM activities
                  WHERE heartRateSeriesRetained = 1
                     OR averageHeartRateBpm IS NOT NULL
                     OR maxHeartRateBpm IS NOT NULL
                  LIMIT 1
              )
              OR EXISTS (SELECT 1 FROM health_connect_pending_heart_rate_samples LIMIT 1)
              OR EXISTS (
                  SELECT 1 FROM health_connect_pending_observations
                  WHERE averageHeartRateBpm IS NOT NULL
                     OR maxHeartRateBpm IS NOT NULL
                     OR heartRateSourceSampleCount > 0
                  LIMIT 1
              )
          )
        """,
        """
        UPDATE profile_settings
        SET heartRateDataMode = 'private'
        WHERE heartRateDataMode = 'discard'
          AND (
              EXISTS (SELECT 1 FROM heart_rate_samples LIMIT 1)
              OR EXISTS (
                  SELECT 1 FROM activities
                  WHERE heartRateSeriesRetained = 1
                     OR averageHeartRateBpm IS NOT NULL
                     OR maxHeartRateBpm IS NOT NULL
                  LIMIT 1
              )
              OR EXISTS (SELECT 1 FROM health_connect_pending_heart_rate_samples LIMIT 1)
              OR EXISTS (
                  SELECT 1 FROM health_connect_pending_observations
                  WHERE averageHeartRateBpm IS NOT NULL
                     OR maxHeartRateBpm IS NOT NULL
                     OR heartRateSourceSampleCount > 0
                  LIMIT 1
              )
          )
        """,
    )

    val V1_TO_V2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(V1_TO_V2_SQL)
        }
    }

    val V2_TO_V3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            applyV2ToV3(db::execSQL)
        }
    }

    fun applyV2ToV3(execSql: (String) -> Unit) {
        v2ToV3Sql.forEach(execSql)
    }

    /**
     * v0.8.0-v0.8.8 changed timed consequence headlines without resizing their persisted
     * interval structures. Version 4 has the same Room schema shape as version 3, but crossing
     * this explicit lineage boundary repairs the released ledger before it can be used again.
     */
    val V3_TO_V4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            LegacyTimedConsequenceLedgerRepair.apply(db)
        }
    }

    /** Additive routine cadence metadata; released ledger rows are deliberately untouched. */
    val V4_TO_V5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            applyV4ToV5(db::execSQL)
        }
    }

    fun applyV4ToV5(execSql: (String) -> Unit) {
        execSql(CREATE_ROUTINE_SCHEDULE_DAYS_SQL)
        execSql(CREATE_ROUTINE_SCHEDULE_DAYS_PLAN_INDEX_SQL)
    }
}
