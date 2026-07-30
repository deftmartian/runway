package dev.deftmartian.runway.data

/**
 * Small, platform-independent checks for the first bytes of a SQLite database.
 * They intentionally do not claim that a file is safe to restore; [LocalDataManagementRepository]
 * also opens it read-only and verifies Room's schema and SQLite integrity.
 */
internal object LocalBackupFormat {
    const val SQLITE_HEADER_LENGTH = 16
    private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()

    fun hasSqliteHeader(bytes: ByteArray, length: Int = bytes.size): Boolean =
        length >= SQLITE_HEADER_LENGTH && bytes.copyOfRange(0, SQLITE_HEADER_LENGTH).contentEquals(SQLITE_HEADER)
}
