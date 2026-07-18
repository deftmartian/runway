package com.deftmartian.runway

import android.content.Context
import androidx.core.content.edit

internal data class ReconciliationStatusRecord(
    val state: String,
    val backlog: Int,
    val scanTruncated: Boolean,
    val recordedAtEpochMs: Long,
) {
    fun encode(): String = "$state:$backlog:${if (scanTruncated) 1 else 0}:$recordedAtEpochMs"

    companion object {
        private val statePattern = Regex("[a-z_]{2,40}")

        fun decode(raw: String?): ReconciliationStatusRecord? {
            if (raw == null) return null
            val parts = raw.split(':')
            if (parts.size != 4 || !parts[0].matches(statePattern)) return null
            val backlog = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val truncated = when (parts[2]) {
                "0" -> false
                "1" -> true
                else -> return null
            }
            val recordedAt = parts[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return ReconciliationStatusRecord(parts[0], backlog, truncated, recordedAt)
        }
    }
}

internal class ReconciliationStatusStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): ReconciliationStatusRecord? =
        ReconciliationStatusRecord.decode(preferences.getString(STATUS_KEY, null))

    fun record(
        state: String,
        backlog: Int = 0,
        scanTruncated: Boolean = false,
        recordedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        val status = ReconciliationStatusRecord(
            state = state,
            backlog = backlog.coerceAtLeast(0),
            scanTruncated = scanTruncated,
            recordedAtEpochMs = recordedAtEpochMs,
        )
        require(ReconciliationStatusRecord.decode(status.encode()) == status)
        preferences.edit(commit = true) { putString(STATUS_KEY, status.encode()) }
    }

    private companion object {
        const val PREFERENCES_NAME = "runway_android_reconciliation_status"
        const val STATUS_KEY = "last_status"
    }
}
