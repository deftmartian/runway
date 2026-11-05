package dev.deftmartian.runway.data

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream

/**
 * Validates a user-selected runway backup before installation.
 *
 * It accepts any released schema from [RunwayLedgerDatabase.FIRST_PRESERVED_SCHEMA_VERSION] up to the
 * current one, identified by its exact Room identity. An older released backup is installed as-is and
 * upgraded by [RunwayLedgerDatabase.MIGRATIONS] when runway next opens it. Pre-v0.11 backups and newer
 * or unknown schemas are rejected.
 */
internal object LocalRestoreCandidate {
    fun prepare(candidate: File): String? = compatibilityError(candidate)

    fun compatibilityError(candidate: File): String? {
        headerError(candidate)?.let { return it }
        return when (val inspection = inspect(candidate)) {
            is Inspection.Invalid -> inspection.reason
            is Inspection.Valid -> schemaError(inspection.version, inspection.identityHash)
        }
    }

    internal fun schemaError(version: Int, identityHash: String): String? {
        val expected = RunwayLedgerDatabase.RELEASED_SCHEMA_IDENTITY_HASHES[version]
        return when {
            version > RunwayLedgerDatabase.SCHEMA_VERSION ->
                "This backup was made by a newer version of runway. Update runway, then restore it."
            expected == null ->
                "This backup uses database version $version, which this version of runway cannot restore."
            identityHash != expected ->
                "This backup belongs to a different Runway database schema."
            else -> null
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
