package dev.deftmartian.runway.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBackupFormatTest {
    @Test
    fun acceptsOnlyTheSqliteThreeHeader() {
        assertTrue(LocalBackupFormat.hasSqliteHeader("SQLite format 3\u0000more".encodeToByteArray()))
        assertFalse(LocalBackupFormat.hasSqliteHeader("SQLite format 2\u0000more".encodeToByteArray()))
        assertFalse(LocalBackupFormat.hasSqliteHeader(ByteArray(LocalBackupFormat.SQLITE_HEADER_LENGTH - 1)))
    }
}
