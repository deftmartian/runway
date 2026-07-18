package com.deftmartian.runway

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HandledImportStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isHandled(deviceId: String, uri: Uri): Boolean {
        return marker(deviceId, uri) in handledMarkers(deviceId)
    }

    fun markHandled(deviceId: String, uri: Uri) {
        synchronized(storeLock) {
            val markers = handledMarkers(deviceId).toMutableSet()
            if (markers.size >= MAX_MARKERS) markers.clear()
            markers += marker(deviceId, uri)
            preferences.edit {
                putStringSet(markersKey(deviceId), markers)
                remove(pendingKey(deviceId, uri))
            }
        }
    }

    fun requestIdFor(deviceId: String, uri: Uri): String {
        synchronized(storeLock) {
            val key = pendingKey(deviceId, uri)
            preferences.getString(key, null)?.let { return it }
            return UUID.randomUUID().toString().also { requestId ->
                preferences.edit { putString(key, requestId) }
            }
        }
    }

    fun clearPendingRequest(deviceId: String, uri: Uri) {
        synchronized(storeLock) {
            preferences.edit { remove(pendingKey(deviceId, uri)) }
        }
    }

    fun clearForDevice(deviceId: String) {
        synchronized(storeLock) {
            val pendingPrefix = "pending_${deviceKey(deviceId)}_"
            val pendingKeys = preferences.all.keys.filter { it.startsWith(pendingPrefix) }
            preferences.edit {
                remove(markersKey(deviceId))
                pendingKeys.forEach(::remove)
            }
        }
    }

    fun clearAll() {
        synchronized(storeLock) {
            preferences.edit { clear() }
        }
    }

    private fun handledMarkers(deviceId: String): Set<String> =
        preferences.getStringSet(markersKey(deviceId), emptySet())?.toSet() ?: emptySet()

    private fun marker(deviceId: String, uri: Uri): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(markerKey(), "HmacSHA256"))
        mac.update(deviceId.toByteArray(StandardCharsets.UTF_8))
        mac.update(0.toByte())
        return Base64.encodeToString(
            mac.doFinal(uri.toString().toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
    }

    private fun markerKey(): ByteArray {
        synchronized(storeLock) {
            preferences.getString(MARKER_SECRET_KEY, null)?.let {
                return Base64.decode(it, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
            }
            val secret = ByteArray(32).also(SecureRandom()::nextBytes)
            val encoded = Base64.encodeToString(
                secret,
                Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
            )
            preferences.edit { putString(MARKER_SECRET_KEY, encoded) }
            return secret
        }
    }

    private fun deviceKey(deviceId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun markersKey(deviceId: String): String = "handled_${deviceKey(deviceId)}"

    private fun pendingKey(deviceId: String, uri: Uri): String =
        "pending_${deviceKey(deviceId)}_${marker(deviceId, uri)}"

    private companion object {
        const val PREFERENCES_NAME = "runway_android_handled_imports"
        const val MARKER_SECRET_KEY = "marker_secret"
        const val MAX_MARKERS = 10_000
        val storeLock = Any()
    }
}
