package com.deftmartian.runway

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.UUID

private const val SHARE_REQUEST_KEY_PREFIX = "request_"

/**
 * A share grant is short-lived, so a failed share cannot safely be queued for later upload. We can
 * still make a deliberate resend idempotent by retaining the server receipt id for the same
 * origin, paired device, and content digest.
 */
internal class ShareImportRequestStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun requestIdFor(
        origin: String,
        deviceId: String,
        contentSha256: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String = synchronized(storeLock) {
        require(contentSha256.matches(SHA256_PATTERN))
        prune(nowEpochMs)
        val key = shareImportRequestKey(origin, deviceId, contentSha256)
        val record = ShareImportRequestRecord.reuseOrCreate(
            encoded = preferences.getString(key, null),
            nowEpochMs = nowEpochMs,
        )
        preferences.edit(commit = true) { putString(key, record.encode()) }
        record.requestId
    }

    fun clear(origin: String, deviceId: String, contentSha256: String) {
        require(contentSha256.matches(SHA256_PATTERN))
        synchronized(storeLock) {
            preferences.edit(commit = true) {
                remove(shareImportRequestKey(origin, deviceId, contentSha256))
            }
        }
    }

    private fun prune(nowEpochMs: Long) {
        val records = preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(SHARE_REQUEST_KEY_PREFIX) || value !is String) return@mapNotNull null
            val record = ShareImportRequestRecord.decode(value) ?: return@mapNotNull key to null
            key to record.takeIf { it.isCurrentAt(nowEpochMs) }
        }
        val removable = records.filter { (_, record) -> record == null }.map { (key) -> key }.toMutableSet()
        removable += records
            .mapNotNull { (key, record) -> record?.let { key to it } }
            .sortedByDescending { (_, record) -> record.createdAtEpochMs }
            .drop(MAX_RECORDS)
            .map { (key) -> key }
        if (removable.isNotEmpty()) preferences.edit(commit = true) { removable.forEach(::remove) }
    }

    private companion object {
        const val PREFERENCES_NAME = "runway_android_share_import_requests"
        const val MAX_RECORDS = 128
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val storeLock = Any()
    }
}

internal fun shareImportRequestKey(origin: String, deviceId: String, contentSha256: String): String {
    require(contentSha256.matches(Regex("[0-9a-f]{64}")))
    val material = "$origin\u0000$deviceId\u0000$contentSha256"
    val key = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "$SHARE_REQUEST_KEY_PREFIX$key"
}

internal data class ShareImportRequestRecord(
    val requestId: String,
    val createdAtEpochMs: Long,
) {
    fun isCurrentAt(nowEpochMs: Long): Boolean =
        nowEpochMs >= createdAtEpochMs && nowEpochMs - createdAtEpochMs <= MAX_AGE_MS

    fun encode(): String = "$requestId:$createdAtEpochMs"

    companion object {
        private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

        fun reuseOrCreate(
            encoded: String?,
            nowEpochMs: Long,
            newRequestId: () -> String = { UUID.randomUUID().toString() },
        ): ShareImportRequestRecord = decode(encoded)
            ?.takeIf { it.isCurrentAt(nowEpochMs) }
            ?: ShareImportRequestRecord(newRequestId(), nowEpochMs)

        fun decode(value: String?): ShareImportRequestRecord? {
            val separator = value?.lastIndexOf(':') ?: return null
            if (separator <= 0) return null
            val requestId = value.substring(0, separator)
            val createdAt = value.substring(separator + 1).toLongOrNull() ?: return null
            return requestId.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
                ?.let { ShareImportRequestRecord(it, createdAt) }
        }
    }
}
