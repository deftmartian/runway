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

internal sealed interface CandidateStability {
    data object Waiting : CandidateStability
    data object StableUnhandled : CandidateStability
    data object StableHandled : CandidateStability
}

internal class HandledImportStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val markerDatabase = HandledMarkerDatabase(context)

    /**
     * Metadata revisions are an accelerator, never the authoritative handled identity. Providers
     * without both size and modification time are periodically re-opened and compared by content.
     */
    fun filterPotentiallyUnhandled(
        deviceId: String,
        candidates: Collection<GpxTreeCandidate>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<GpxTreeCandidate> {
        synchronized(storeLock) {
            migrateLegacyMarkers(deviceId)
            val revisionMarkers = candidates.mapNotNull { candidate ->
                revisionMarker(deviceId, candidate)?.let { marker -> candidate to marker }
            }.toMap()
            val handledRevisions = markerDatabase.findHandled(
                deviceKey(deviceId),
                revisionMarkers.values,
            )
            return candidates.filter { candidate ->
                val revision = revisionMarkers[candidate]
                if (revision != null) {
                    revision !in handledRevisions
                } else {
                    val observation = observation(deviceId, candidate.uri)
                    observation == null || observation.suppressUntilEpochMs <= nowEpochMs
                }
            }
        }
    }

    fun observeContent(
        deviceId: String,
        uri: Uri,
        contentSha256: String,
        nowEpochMs: Long,
        settleDurationMs: Long,
    ): CandidateStability {
        synchronized(storeLock) {
            require(contentSha256.matches(SHA256_PATTERN))
            val contentMarker = contentMarker(deviceId, contentSha256)
            val assessment = assessCandidateObservation(
                previous = observation(deviceId, uri),
                contentMarker = contentMarker,
                observedAtEpochMs = nowEpochMs,
                settleDurationMs = settleDurationMs,
            )
            saveObservation(deviceId, uri, assessment.record)
            if (!assessment.isStable) return CandidateStability.Waiting
            return if (markerDatabase.contains(deviceKey(deviceId), contentMarker)) {
                CandidateStability.StableHandled
            } else {
                CandidateStability.StableUnhandled
            }
        }
    }

    fun markHandled(
        deviceId: String,
        candidate: GpxTreeCandidate,
        contentSha256: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(storeLock) {
            require(contentSha256.matches(SHA256_PATTERN))
            migrateLegacyMarkers(deviceId)
            val contentMarker = contentMarker(deviceId, contentSha256)
            val revisionMarker = revisionMarker(deviceId, candidate)
            markerDatabase.record(
                deviceKey(deviceId),
                listOfNotNull(contentMarker, revisionMarker),
            )
            preferences.edit(commit = true) {
                remove(pendingKey(deviceId, contentSha256))
                if (revisionMarker != null) {
                    remove(observationKey(deviceId, candidate.uri))
                } else {
                    putString(
                        observationKey(deviceId, candidate.uri),
                        CandidateObservationRecord(
                            contentMarker = contentMarker,
                            firstObservedAtEpochMs = nowEpochMs,
                            suppressUntilEpochMs = nowEpochMs + WEAK_REVISION_RECHECK_MS,
                        ).encode(),
                    )
                }
            }
        }
    }

    fun requestIdFor(deviceId: String, contentSha256: String): String {
        synchronized(storeLock) {
            require(contentSha256.matches(SHA256_PATTERN))
            val key = pendingKey(deviceId, contentSha256)
            preferences.getString(key, null)?.let { return it }
            return UUID.randomUUID().toString().also { requestId ->
                preferences.edit(commit = true) { putString(key, requestId) }
            }
        }
    }

    fun clearPendingRequest(deviceId: String, contentSha256: String) {
        synchronized(storeLock) {
            require(contentSha256.matches(SHA256_PATTERN))
            preferences.edit(commit = true) { remove(pendingKey(deviceId, contentSha256)) }
        }
    }

    fun clearForDevice(deviceId: String) {
        synchronized(storeLock) {
            val deviceKey = deviceKey(deviceId)
            val privatePrefixes = listOf("pending_${deviceKey}_", "observation_${deviceKey}_")
            val privateKeys = preferences.all.keys.filter { key ->
                privatePrefixes.any(key::startsWith)
            }
            markerDatabase.clearDevice(deviceKey)
            preferences.edit(commit = true) {
                remove(markersKey(deviceId))
                privateKeys.forEach(::remove)
            }
        }
    }

    fun clearAll() {
        synchronized(storeLock) {
            markerDatabase.clearAll()
            preferences.edit(commit = true) { clear() }
        }
    }

    private fun observation(deviceId: String, uri: Uri): CandidateObservationRecord? =
        CandidateObservationRecord.decode(
            preferences.getString(observationKey(deviceId, uri), null),
        )

    private fun saveObservation(
        deviceId: String,
        uri: Uri,
        observation: CandidateObservationRecord,
    ) {
        preferences.edit(commit = true) {
            putString(observationKey(deviceId, uri), observation.encode())
        }
    }

    private fun migrateLegacyMarkers(deviceId: String) {
        val key = markersKey(deviceId)
        val legacyMarkers = runCatching { preferences.getStringSet(key, emptySet()) }
            .getOrNull()
            ?.filterNotNull()
            .orEmpty()
        if (legacyMarkers.isEmpty()) return

        markerDatabase.migrate(deviceKey(deviceId), legacyMarkers)
        preferences.edit(commit = true) { remove(key) }
    }

    private fun contentMarker(deviceId: String, contentSha256: String): String =
        marker(deviceId, "content", contentSha256)

    private fun revisionMarker(deviceId: String, candidate: GpxTreeCandidate): String? =
        metadataRevisionIdentity(
            candidate.uri.toString(),
            candidate.sizeBytes,
            candidate.lastModifiedEpochMs,
        )?.let { marker(deviceId, "revision", it) }

    private fun sourceMarker(deviceId: String, uri: Uri): String =
        marker(deviceId, "source", uri.toString())

    private fun marker(deviceId: String, purpose: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(markerKey(), "HmacSHA256"))
        mac.update(deviceId.toByteArray(StandardCharsets.UTF_8))
        mac.update(0.toByte())
        mac.update(purpose.toByteArray(StandardCharsets.UTF_8))
        mac.update(0.toByte())
        return Base64.encodeToString(
            mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
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
            preferences.edit(commit = true) { putString(MARKER_SECRET_KEY, encoded) }
            return secret
        }
    }

    private fun deviceKey(deviceId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(deviceId.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun markersKey(deviceId: String): String = "handled_${deviceKey(deviceId)}"

    private fun pendingKey(deviceId: String, contentSha256: String): String =
        "pending_${deviceKey(deviceId)}_${contentMarker(deviceId, contentSha256)}"

    private fun observationKey(deviceId: String, uri: Uri): String =
        "observation_${deviceKey(deviceId)}_${sourceMarker(deviceId, uri)}"

    private companion object {
        const val PREFERENCES_NAME = "runway_android_handled_imports"
        const val MARKER_SECRET_KEY = "marker_secret"
        const val WEAK_REVISION_RECHECK_MS = 15L * 60L * 1000L
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val storeLock = Any()
    }
}
