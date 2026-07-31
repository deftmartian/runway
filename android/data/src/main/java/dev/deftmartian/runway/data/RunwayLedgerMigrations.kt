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

    const val ROUTE_RETENTION_REPAIR_KEY = "privacy_retention_repair_v3_route"
    const val HEART_RATE_RETENTION_REPAIR_KEY = "privacy_retention_repair_v3_heart_rate"

    private val v2ToV3Sql = listOf(
        CREATE_PLAN_SETUP_RECEIPTS_SQL,
        CREATE_PLAN_SETUP_RECEIPTS_GOAL_INDEX_SQL,
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
}
