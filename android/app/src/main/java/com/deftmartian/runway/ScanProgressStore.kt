package com.deftmartian.runway

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class ScanProgressRecord(
    val treeFingerprint: String,
    val nextOffset: Int,
) {
    fun encode(): String = "$treeFingerprint:$nextOffset"

    companion object {
        fun decode(raw: String?): ScanProgressRecord? {
            if (raw == null) return null
            val separator = raw.lastIndexOf(':')
            if (separator <= 0) return null
            val fingerprint = raw.substring(0, separator)
            if (!fingerprint.matches(Regex("[0-9a-f]{64}"))) return null
            val offset = raw.substring(separator + 1).toIntOrNull()?.takeIf { it >= 0 }
                ?: return null
            return ScanProgressRecord(fingerprint, offset)
        }
    }
}

internal class ScanProgressStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun offsetFor(deviceId: String, treeUri: Uri): Int {
        val record = ScanProgressRecord.decode(preferences.getString(key(deviceId), null))
        return if (record?.treeFingerprint == fingerprint(treeUri.toString())) {
            record.nextOffset
        } else {
            0
        }
    }

    fun advance(deviceId: String, treeUri: Uri, nextOffset: Int) {
        require(nextOffset >= 0) { "nextOffset must not be negative" }
        val record = ScanProgressRecord(fingerprint(treeUri.toString()), nextOffset)
        preferences.edit(commit = true) { putString(key(deviceId), record.encode()) }
    }

    fun reset(deviceId: String) {
        preferences.edit(commit = true) { remove(key(deviceId)) }
    }

    fun clearAll() {
        preferences.edit(commit = true) { clear() }
    }

    private fun key(deviceId: String): String = "scan_${fingerprint(deviceId)}"

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val PREFERENCES_NAME = "runway_android_scan_progress"
    }
}
