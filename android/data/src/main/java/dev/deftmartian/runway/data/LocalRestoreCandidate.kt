package dev.deftmartian.runway.data

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream

/**
 * Validates a user-selected runway backup before installation.
 *
 * This hard-cutover build accepts only the current schema identity. Older databases are not
 * rewritten in place or presented as compatible backups.
 */
internal object LocalRestoreCandidate {
    fun prepare(candidate: File): String? = currentSchemaError(candidate)

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

    private fun SQLiteDatabase.stringQuery(sql: String): String? =
        rawQuery(sql, emptyArray()).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private sealed interface Inspection {
        data class Valid(val version: Int, val identityHash: String) : Inspection
        data class Invalid(val reason: String) : Inspection
    }
}
