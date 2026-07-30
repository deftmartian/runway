package dev.deftmartian.runway.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.JsonWriter
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stable SAF suggestion for the only supported local-ledger backup format. */
object LocalBackupDocumentContract {
    const val MIME_TYPE = "application/vnd.sqlite3"
    const val DEFAULT_FILE_NAME = "runway-backup.sqlite3"
}

/** The only supported local-ledger backup format is an unencrypted SQLite database. */
sealed interface LocalBackupResult {
    data class Created(
        val bytesWritten: Long,
        val privacyWarning: String = PLAINTEXT_BACKUP_WARNING,
    ) : LocalBackupResult

    data class Rejected(val reason: String) : LocalBackupResult
}

sealed interface LocalRestoreResult {
    data class Restored(
        val bytesRestored: Long,
        val restartRequired: Boolean = true,
        val privacyWarning: String = PLAINTEXT_BACKUP_WARNING,
    ) : LocalRestoreResult

    data class Rejected(
        val reason: String,
        /** True when Room was closed while handling this failure; the caller must restart. */
        val restartRequired: Boolean = false,
    ) : LocalRestoreResult

    /** The old database could not be put back after replacement failed. Do not keep using the app. */
    data class RecoveryRequired(val reason: String) : LocalRestoreResult
}

data class LocalTrainingExportResult(
    val bytesWritten: Long,
    val truncatedTables: Set<String>,
    val privacyWarning: String = PLAINTEXT_BACKUP_WARNING,
)

/** Result of removing only data that arrived through an import source. */
data class LocalImportedActivityEraseResult(
    val activitiesErased: Int,
    val retainedImportTombstones: Int,
)

const val PLAINTEXT_BACKUP_WARNING =
    "This file is plaintext. It can contain your training history, notes, route data, and heart-rate data. Store and share it carefully."

/**
 * Destructive local-ledger operations. Device grants and Health Connect permissions are owned by
 * the app layer. Callers close acquisition before erase or restore so stale source state cannot
 * repopulate the ledger.
 *
 * Backup and restore deliberately use Android's Storage Access Framework URI supplied by the UI.
 * The selected document is local plaintext; no custom cryptography is involved. A restore closes
 * this Room instance, so callers must immediately restart the process and must first stop workers
 * and other owners of this database.
 */
class LocalDataManagementRepository(
    private val database: RunwayLedgerDatabase,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun eraseAllTrainingData() {
        database.maintenanceDao().clearAll()
    }

    /**
     * Removes imported activity evidence without changing manually entered activities, plans,
     * preferences, or audit adjustments. The import identities remain as tombstones so a source
     * cannot silently put the same private data back on the phone after it is reconnected.
     *
     * Android-owned source grants and Health Connect permissions are intentionally not touched
     * here. The caller must stop acquisition and revoke them before entering this transaction so
     * an in-flight worker cannot recreate imported data after the delete commits.
     */
    suspend fun eraseImportedActivityData(): LocalImportedActivityEraseResult =
        database.maintenanceDao().clearImportedActivityData(nowEpochMillis())

    /** Writes a self-contained, checkpointed SQLite snapshot to a user-selected SAF document. */
    suspend fun backupToDocument(context: Context, destination: Uri): LocalBackupResult = withContext(Dispatchers.IO) {
        val snapshot = temporaryFile(context, "backup")
        try {
            createConsistentSnapshot(snapshot)
            val bytes = snapshot.length()
            require(bytes in 1..MAX_BACKUP_BYTES) { "The local database is outside the backup size limit." }
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                FileInputStream(snapshot).use { input -> input.copyTo(output) }
                output.flush()
            } ?: error("The selected document could not be opened for writing.")
            LocalBackupResult.Created(bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LocalBackupResult.Rejected(
                "The backup could not be created. Choose another location and try again.",
            )
        } finally {
            snapshot.delete()
        }
    }

    /**
     * Validates the complete SAF document before changing the installed ledger, then replaces the
     * database with a private staged file. On a successful return the current process is unusable
     * by design: the caller must restart it before accessing Room again.
     */
    suspend fun restoreFromDocument(context: Context, source: Uri): LocalRestoreResult = withContext(Dispatchers.IO) {
        val candidate = temporaryFile(context, "restore-candidate")
        try {
            val bytes = copyDocumentBounded(context.contentResolver, source, candidate)
            val validationError = LocalRestoreCandidate.prepare(candidate)
            if (validationError != null) return@withContext LocalRestoreResult.Rejected(validationError)
            replaceInstalledDatabase(context, candidate, bytes)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            LocalRestoreResult.Rejected(
                "The backup is not valid or is outside the supported size limit.",
            )
        } catch (_: Exception) {
            LocalRestoreResult.Rejected(
                "The selected backup could not be read or validated. Choose a Runway backup and try again.",
            )
        } finally {
            candidate.delete()
        }
    }

    /**
     * Writes a bounded, pretty-printed JSON view of the ledger. It is an inspection/export format,
     * not an import format. Unlike the SQLite backup it intentionally omits raw route and
     * heart-rate samples, which can be unusually sensitive and very large.
     */
    suspend fun exportTrainingJson(context: Context, destination: Uri): LocalTrainingExportResult = withContext(Dispatchers.IO) {
        val snapshot = temporaryFile(context, "training-export")
        try {
            createConsistentSnapshot(snapshot)
            val truncated = linkedSetOf<String>()
            var bytesWritten = 0L
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                val countingOutput = CountingOutputStream(output)
                JsonWriter(OutputStreamWriter(countingOutput, StandardCharsets.UTF_8)).use { writer ->
                    writer.setIndent("  ")
                    writer.beginObject()
                    writer.name("format").value("runway-training-export-v1")
                    writer.name("warning").value(PLAINTEXT_BACKUP_WARNING)
                    writer.name("schemaVersion").value(RunwayLedgerDatabase.SCHEMA_VERSION.toLong())
                    writer.name("tables").beginObject()
                    SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                        EXPORT_TABLES.forEach { table -> writeTable(writer, sqlite, table, truncated) }
                    }
                    writer.endObject()
                    writer.name("truncatedTables").beginArray()
                    truncated.forEach(writer::value)
                    writer.endArray()
                    writer.endObject()
                }
                bytesWritten = countingOutput.bytesWritten
            } ?: error("The selected document could not be opened for writing.")
            LocalTrainingExportResult(bytesWritten, truncated)
        } finally {
            snapshot.delete()
        }
    }

    private fun createConsistentSnapshot(snapshot: File) {
        // VACUUM INTO requires a path that does not already exist; temporaryFile creates it.
        check(!snapshot.exists() || snapshot.delete()) { "Could not prepare private snapshot storage." }
        val sqlite = database.openHelper.writableDatabase
        sqlite.query("PRAGMA wal_checkpoint(FULL)").use { checkpoint ->
            if (checkpoint.moveToFirst() && checkpoint.getInt(0) != 0) {
                error("The local database is busy and could not be checkpointed for backup.")
            }
        }
        // VACUUM INTO is the SQLite-supported snapshot operation. It deliberately fails on a
        // device whose SQLite is too old rather than silently producing an unsafe file copy.
        // The path is generated inside private app storage and quoted defensively for SQLite.
        sqlite.execSQL("VACUUM INTO '${snapshot.absolutePath.replace("'", "''")}'")
    }

    private fun copyDocumentBounded(resolver: ContentResolver, source: Uri, destination: File): Long {
        var total = 0L
        resolver.openInputStream(source)?.use { raw ->
            BufferedInputStream(raw).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_BACKUP_BYTES) { "The backup exceeds the ${MAX_BACKUP_BYTES / 1_024 / 1_024} MB restore limit." }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
        } ?: error("The selected document could not be opened for reading.")
        require(total >= LocalBackupFormat.SQLITE_HEADER_LENGTH) { "The backup is too small to be SQLite." }
        return total
    }

    private fun replaceInstalledDatabase(context: Context, candidate: File, bytes: Long): LocalRestoreResult {
        val installed = context.getDatabasePath(RunwayLedgerDatabase.DATABASE_NAME)
        val directory = installed.parentFile ?: return LocalRestoreResult.Rejected("The app database directory is unavailable.")
        if (!directory.exists() && !directory.mkdirs()) return LocalRestoreResult.Rejected("The app database directory could not be created.")
        val staged = File(directory, "${RunwayLedgerDatabase.DATABASE_NAME}.restore-${UUID.randomUUID()}")
        val rollback = File(directory, "${RunwayLedgerDatabase.DATABASE_NAME}.rollback")
        var databaseClosed = false
        var replacementInstalled = false
        var hadInstalled = false
        return try {
            copyFile(candidate, staged)
            check(LocalRestoreCandidate.currentSchemaError(staged) == null) {
                "The staged restore database did not validate."
            }
            database.close()
            databaseClosed = true
            deleteSidecars(installed)
            if (rollback.exists() && !rollback.delete()) {
                return LocalRestoreResult.Rejected("A previous restore rollback file could not be cleared.", restartRequired = true)
            }
            hadInstalled = installed.exists()
            if (hadInstalled) copyFile(installed, rollback)
            // On Android's app-private filesystem this is the OS rename operation: it atomically
            // replaces the installed file. The independently fsynced rollback copy remains until
            // the replacement has succeeded, so a failed rename leaves the installed file intact.
            if (!staged.renameTo(installed)) {
                return LocalRestoreResult.Rejected(
                    "The restored database could not be installed; the previous database was kept.",
                    restartRequired = true,
                )
            }
            replacementInstalled = true
            deleteSidecars(rollback)
            rollback.delete()
            LocalRestoreResult.Restored(bytes)
        } catch (_: Exception) {
            if (replacementInstalled) {
                val restored = if (hadInstalled && rollback.exists()) {
                    installed.delete() && rollback.renameTo(installed)
                } else if (!hadInstalled) {
                    !installed.exists() || installed.delete()
                } else {
                    false
                }
                if (!restored) {
                    LocalRestoreResult.RecoveryRequired(
                        "Runway could not restore the backup or put the previous local database back. " +
                            "Close the app and recover from a separate backup before recording more training.",
                    )
                } else {
                    LocalRestoreResult.Rejected(
                        reason = "Restore failed after replacement. The previous local database was put back.",
                        restartRequired = true,
                    )
                }
            } else {
                LocalRestoreResult.Rejected(
                    reason = "Restore failed before replacement. The previous local database was kept.",
                    restartRequired = databaseClosed,
                )
            }
        } finally {
            staged.delete()
        }
    }

    private fun writeTable(writer: JsonWriter, sqlite: SQLiteDatabase, table: String, truncated: MutableSet<String>) {
        writer.name(table).beginObject()
        sqlite.rawQuery("SELECT * FROM $table LIMIT ${JSON_ROWS_PER_TABLE + 1}", emptyArray()).use { cursor ->
            writer.name("rows").beginArray()
            var rows = 0
            while (rows < JSON_ROWS_PER_TABLE && cursor.moveToNext()) {
                writer.beginObject()
                for (column in 0 until cursor.columnCount) writeValue(writer, cursor, column)
                writer.endObject()
                rows++
            }
            if (cursor.moveToNext()) truncated += table
            writer.endArray()
            writer.name("rowLimit").value(JSON_ROWS_PER_TABLE.toLong())
        }
        writer.endObject()
    }

    private fun writeValue(writer: JsonWriter, cursor: Cursor, column: Int) {
        writer.name(cursor.getColumnName(column))
        when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> writer.nullValue()
            Cursor.FIELD_TYPE_INTEGER -> writer.value(cursor.getLong(column))
            Cursor.FIELD_TYPE_FLOAT -> writer.value(cursor.getDouble(column))
            Cursor.FIELD_TYPE_BLOB -> writer.value("[binary data omitted]")
            else -> writer.value(cursor.getString(column).take(JSON_TEXT_VALUE_LIMIT))
        }
    }

    private fun temporaryFile(context: Context, purpose: String): File =
        File.createTempFile("runway-$purpose-", ".db", context.cacheDir)

    private fun copyFile(from: File, to: File) {
        FileInputStream(from).use { input ->
            FileOutputStream(to).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun deleteSidecars(databaseFile: File) {
        File("${databaseFile.path}-wal").delete()
        File("${databaseFile.path}-shm").delete()
    }

    private class CountingOutputStream(output: java.io.OutputStream) : FilterOutputStream(output) {
        var bytesWritten = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            bytesWritten++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            bytesWritten += length
        }
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 512L * 1_024 * 1_024
        const val COPY_BUFFER_BYTES = 32 * 1_024
        const val JSON_ROWS_PER_TABLE = 2_000
        const val JSON_TEXT_VALUE_LIMIT = 16_384
        val EXPORT_TABLES = listOf(
            "profile_settings", "profile_availability_days", "goals", "plans", "plan_weeks", "workouts",
            "activities", "activity_feedback", "workout_feedback", "plan_adjustments", "plan_decisions",
            "plan_lifecycle_events", "plan_reversals", "app_metadata",
        )
    }
}
