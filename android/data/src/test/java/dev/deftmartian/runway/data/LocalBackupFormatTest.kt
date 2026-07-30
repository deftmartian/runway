package dev.deftmartian.runway.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackupFormatTest {
    @Test
    fun acceptsOnlyTheSqliteThreeHeader() {
        assertTrue(LocalBackupFormat.hasSqliteHeader("SQLite format 3\u0000more".encodeToByteArray()))
        assertFalse(LocalBackupFormat.hasSqliteHeader("SQLite format 2\u0000more".encodeToByteArray()))
        assertFalse(LocalBackupFormat.hasSqliteHeader(ByteArray(LocalBackupFormat.SQLITE_HEADER_LENGTH - 1)))
    }

    @Test
    fun documentContractAndTypedOutcomesCannotRepresentRejectedRestoreAsSuccess() {
        assertEquals("application/vnd.sqlite3", LocalBackupDocumentContract.MIME_TYPE)
        assertEquals("runway-backup.sqlite3", LocalBackupDocumentContract.DEFAULT_FILE_NAME)
        assertEquals(
            LocalDocumentUserOutcome(true, "Backup created."),
            LocalBackupResult.Created(42).toUserOutcome(),
        )
        assertEquals(
            LocalDocumentUserOutcome(false, "Not a runway backup"),
            LocalBackupResult.Rejected("Not a runway backup").toUserOutcome(),
        )
        assertEquals(
            LocalDocumentUserOutcome(false, "Schema mismatch"),
            LocalRestoreResult.Rejected("Schema mismatch").toUserOutcome(),
        )
        assertEquals(
            LocalDocumentUserOutcome(false, "Recovery required", restartRequired = true),
            LocalRestoreResult.RecoveryRequired("Recovery required").toUserOutcome(),
        )
    }
}
