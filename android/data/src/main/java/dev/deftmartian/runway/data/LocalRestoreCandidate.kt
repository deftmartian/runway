package dev.deftmartian.runway.data

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream

/**
 * Validates and, when necessary, upgrades a user-selected Runway backup before installation.
 *
 * Only exact released Room identities are accepted. The same immutable migrations used when Room
 * opens an installed ledger upgrade a released backup; a second preparation is intentionally a
 * no-op.
 */
internal object LocalRestoreCandidate {
    fun prepare(candidate: File): String? {
        headerError(candidate)?.let { return it }
        return when (val inspection = inspect(candidate)) {
            is Inspection.Invalid -> inspection.reason
            is Inspection.Valid -> when {
                inspection.version == RunwayLedgerDatabase.SCHEMA_VERSION &&
                    inspection.identityHash == RunwayLedgerDatabase.SCHEMA_IDENTITY_HASH -> null
                inspection.version == 1 &&
                    inspection.identityHash == RunwayLedgerMigrations.V1_IDENTITY_HASH -> {
                    migrationError(candidate, fromVersion = 1)
                        ?: currentSchemaError(candidate)
                }
                inspection.version == 2 &&
                    inspection.identityHash == RunwayLedgerMigrations.V2_IDENTITY_HASH -> {
                    migrationError(candidate, fromVersion = 2)
                        ?: currentSchemaError(candidate)
                }
                inspection.version == 3 &&
                    inspection.identityHash == RunwayLedgerMigrations.V3_IDENTITY_HASH -> {
                    migrationError(candidate, fromVersion = 3)
                        ?: currentSchemaError(candidate)
                }
                else -> unsupportedLineageMessage(inspection)
            }
        }
    }

    fun currentSchemaError(candidate: File): String? {
        headerError(candidate)?.let { return it }
        return when (val inspection = inspect(candidate)) {
            is Inspection.Invalid -> inspection.reason
            is Inspection.Valid -> when {
                inspection.version != RunwayLedgerDatabase.SCHEMA_VERSION ->
                    "This backup uses database version ${inspection.version}, but this app requires version ${RunwayLedgerDatabase.SCHEMA_VERSION}."
                inspection.identityHash != RunwayLedgerDatabase.SCHEMA_IDENTITY_HASH ->
                    "This backup belongs to a different Runway database schema."
                else -> null
            }
        }
    }

    private fun migrateToCurrent(candidate: File, fromVersion: Int) {
        SQLiteDatabase.openDatabase(
            candidate.path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { platformDatabase ->
            platformDatabase.beginTransaction()
            try {
                if (fromVersion == 1) {
                    platformDatabase.execSQL(RunwayLedgerMigrations.V1_TO_V2_SQL)
                }
                if (fromVersion <= 2) {
                    RunwayLedgerMigrations.applyV2ToV3(platformDatabase::execSQL)
                }
                LegacyTimedConsequenceLedgerRepair.apply(platformDatabase)
                platformDatabase.execSQL(
                    "UPDATE room_master_table SET identity_hash = ? WHERE id = 42",
                    arrayOf(RunwayLedgerDatabase.SCHEMA_IDENTITY_HASH),
                )
                platformDatabase.version = RunwayLedgerDatabase.SCHEMA_VERSION
                platformDatabase.setTransactionSuccessful()
            } finally {
                platformDatabase.endTransaction()
            }
        }
    }

    private fun migrationError(candidate: File, fromVersion: Int): String? =
        runCatching {
            migrateToCurrent(candidate, fromVersion)
        }.exceptionOrNull()?.let {
            "This backup could not be upgraded safely. The original file was not installed."
        }

    private fun inspect(candidate: File): Inspection =
        try {
            SQLiteDatabase.openDatabase(
                candidate.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                val integrity = sqlite.stringQuery("PRAGMA integrity_check(1)")
                if (integrity != "ok") {
                    Inspection.Invalid("The selected backup failed its SQLite integrity check.")
                } else {
                    val identity = sqlite.stringQuery(
                        "SELECT identity_hash FROM room_master_table WHERE id = 42",
                    )
                    if (identity == null) {
                        Inspection.Invalid(
                            "This database does not contain Room schema identity information.",
                        )
                    } else {
                        Inspection.Valid(sqlite.version, identity)
                    }
                }
            }
        } catch (_: Exception) {
            Inspection.Invalid(
                "The selected file could not be opened as a valid Runway backup.",
            )
        }

    private fun headerError(candidate: File): String? {
        val header = ByteArray(LocalBackupFormat.SQLITE_HEADER_LENGTH)
        return try {
            FileInputStream(candidate).use { input ->
                if (
                    input.read(header) != header.size ||
                    !LocalBackupFormat.hasSqliteHeader(header)
                ) {
                    "The selected file is not a SQLite 3 database."
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            "The selected backup could not be read."
        }
    }

    private fun unsupportedLineageMessage(inspection: Inspection.Valid): String =
        if (inspection.version != RunwayLedgerDatabase.SCHEMA_VERSION) {
            "This backup uses unsupported database version ${inspection.version}. " +
                "This app accepts released Runway versions 1 through " +
                "${RunwayLedgerDatabase.SCHEMA_VERSION}."
        } else {
            "This backup belongs to a different Runway database schema."
        }

    private fun SQLiteDatabase.stringQuery(sql: String): String? =
        rawQuery(sql, emptyArray()).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private sealed interface Inspection {
        data class Valid(val version: Int, val identityHash: String) : Inspection
        data class Invalid(val reason: String) : Inspection
    }
}
