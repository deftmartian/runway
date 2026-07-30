package dev.deftmartian.runway.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Immutable local-ledger lineage. Existing v1 records kept detailed heart-rate samples, so their
 * newly explicit setting remains private rather than falsely claiming that those samples vanished.
 */
object RunwayLedgerMigrations {
    const val V1_IDENTITY_HASH = "154538ddd4b50c6c924697299e447e9a"
    const val V1_TO_V2_SQL =
        "ALTER TABLE profile_settings ADD COLUMN heartRateDataMode TEXT NOT NULL DEFAULT 'private'"

    val V1_TO_V2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(V1_TO_V2_SQL)
        }
    }
}
