package dev.deftmartian.runway

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

sealed interface PairingApiResult {
    data class Paired(val credential: AndroidCredential) : PairingApiResult
    data object Invalid : PairingApiResult
    data object Retryable : PairingApiResult
}

sealed interface ImportApiResult {
    data class Handled(val result: String, val reason: String?) : ImportApiResult
    data object Unauthorized : ImportApiResult
    data object RequestConflict : ImportApiResult
    data object Retryable : ImportApiResult
}

sealed interface HealthConnectApiResult {
    data object Accepted : HealthConnectApiResult
    data object Unauthorized : HealthConnectApiResult
    data object Retryable : HealthConnectApiResult
    data object Rejected : HealthConnectApiResult
}

internal data class HealthConnectRequestPayload(
    val bytes: ByteArray,
    val changeCount: Int,
)

internal sealed interface HealthConnectPayloadResult {
    data class Prepared(val payload: HealthConnectRequestPayload) : HealthConnectPayloadResult
    data object Invalid : HealthConnectPayloadResult
}

/**
 * The coordinator measures this exact byte array, and the API client sends it unchanged. Keeping
 * serialization on one seam prevents a size estimate from drifting away from the request body.
 */
internal object HealthConnectPayloadSerializer {
    fun serialize(
        upserts: List<HealthConnectRun>,
        deletes: List<String>,
    ): HealthConnectPayloadResult = runCatching {
        if (upserts.any { !isValid(it) } || deletes.any { !isValidRecordId(it) }) {
            return@runCatching HealthConnectPayloadResult.Invalid
        }
        val changeCount = upserts.size + deletes.size
        if (changeCount == 0) return@runCatching HealthConnectPayloadResult.Invalid
        val json = buildString {
            append("{\"version\":").append(HEALTH_CONNECT_SCHEMA_VERSION).append(",\"changes\":[")
            var firstChange = true
            fun separateChange() {
                if (firstChange) firstChange = false else append(',')
            }
            for (run in upserts) {
                separateChange()
                append("{\"op\":\"upsert\",\"recordId\":\"").append(run.id)
                    .append("\",\"originKey\":\"").append(run.sourcePackage)
                    .append("\",\"originLabel\":\"").append(run.sourcePackage.take(100))
                    .append("\",\"startedAt\":\"")
                    .append(java.time.Instant.ofEpochMilli(run.startEpochMs))
                    .append("\",\"durationSeconds\":").append(durationSeconds(run))
                    .append(",\"distanceMeters\":").append(run.distanceMeters!!.toInt())
                run.averageHeartRateBpm?.let { append(",\"averageHeartRate\":").append(it.toInt()) }
                run.maxHeartRateBpm?.let { append(",\"maxHeartRate\":").append(it.toInt()) }
                run.averageCadenceRpm?.let { append(",\"averageCadence\":").append(it.toInt()) }
                run.elevationGainMeters?.let { append(",\"elevationGainMeters\":").append(it.toInt()) }
                run.averageSpeedMetersPerSecond?.let {
                    append(",\"averageSpeedMetersPerSecond\":").append(it)
                }
                if (run.heartRateSamples.isNotEmpty()) {
                    append(",\"heartRateSeries\":{\"version\":1,\"sourceSampleCount\":")
                        .append(run.heartRateSourceSampleCount).append(",\"points\":[")
                    run.heartRateSamples.forEachIndexed { index, sample ->
                        if (index > 0) append(',')
                        append("{\"elapsedSeconds\":").append(sample.elapsedSeconds)
                            .append(",\"bpm\":").append(sample.bpm).append('}')
                    }
                    append("]}")
                }
                if (run.routePoints.size >= 2) {
                    append(",\"routeTrace\":{\"version\":1,\"sourcePointCount\":")
                        .append(run.routeSourcePointCount).append(",\"points\":[")
                    run.routePoints.forEachIndexed { index, point ->
                        if (index > 0) append(',')
                        append("{\"latitudeE6\":").append(point.latitudeE6)
                            .append(",\"longitudeE6\":").append(point.longitudeE6)
                            .append(",\"elapsedSeconds\":").append(point.elapsedSeconds)
                            .append(",\"segmentIndex\":0,\"speedMetersPerSecond\":")
                            .append(point.speedMetersPerSecond ?: "null")
                            .append('}')
                    }
                    append("]}")
                }
                append('}')
            }
            for (id in deletes) {
                separateChange()
                append("{\"op\":\"delete\",\"recordId\":\"").append(id).append("\"}")
            }
            append("]}")
        }
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        HealthConnectPayloadResult.Prepared(HealthConnectRequestPayload(bytes, changeCount))
    }.getOrDefault(HealthConnectPayloadResult.Invalid)

    private fun isValid(run: HealthConnectRun): Boolean {
        val duration = durationSeconds(run)
        val distance = run.distanceMeters?.takeIf(Double::isFinite)?.toInt()
        if (
            !isValidRecordId(run.id) || !isValidRecordId(run.sourcePackage) ||
            duration !in 1..86_400 || distance == null || distance !in 1..500_000 ||
            run.endEpochMs > System.currentTimeMillis() + FUTURE_HEADROOM_MS ||
            !optionalIntIn(run.averageHeartRateBpm, 30..240) ||
            !optionalIntIn(run.maxHeartRateBpm, 30..260) ||
            !optionalIntIn(run.averageCadenceRpm, 1..300) ||
            !optionalIntIn(run.elevationGainMeters, 0..20_000) ||
            !optionalDoubleIn(run.averageSpeedMetersPerSecond, 0.0..30.0)
        ) return false
        if (
            run.heartRateSamples.size > 600 ||
            (run.heartRateSamples.isNotEmpty() && run.heartRateSourceSampleCount !in 1..100_000)
        ) return false
        var previousElapsed = -1
        for (sample in run.heartRateSamples) {
            if (
                sample.elapsedSeconds !in 0..duration ||
                sample.elapsedSeconds <= previousElapsed ||
                sample.bpm !in 30..260
            ) return false
            previousElapsed = sample.elapsedSeconds
        }
        if (
            run.routePoints.size > 600 ||
            (run.routePoints.size >= 2 && run.routeSourcePointCount !in 2..100_000)
        ) return false
        for (point in run.routePoints) {
            if (
                point.elapsedSeconds !in 0..duration ||
                point.latitudeE6 !in -90_000_000..90_000_000 ||
                point.longitudeE6 !in -180_000_000..180_000_000 ||
                !optionalDoubleIn(point.speedMetersPerSecond, 0.0..30.0)
            ) return false
        }
        return true
    }

    private fun durationSeconds(run: HealthConnectRun): Int {
        val durationMs = run.endEpochMs - run.startEpochMs
        return if (durationMs <= 0 || durationMs > Int.MAX_VALUE.toLong() * 1_000) {
            -1
        } else {
            (durationMs / 1_000).toInt()
        }
    }

    private fun optionalIntIn(value: Double?, range: IntRange): Boolean =
        value == null || (value.isFinite() && value.toInt() in range)

    private fun optionalDoubleIn(value: Double?, range: ClosedFloatingPointRange<Double>): Boolean =
        value == null || (value.isFinite() && value in range)

    private fun isValidRecordId(value: String): Boolean =
        value.length in 1..256 && value.all { character ->
            character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
                character in "._:-"
        }

    private const val FUTURE_HEADROOM_MS = 5 * 60 * 1_000L
}

sealed interface DeviceStatusApiResult {
    data class Connected(val importGeneration: Long) : DeviceStatusApiResult
    data object Unauthorized : DeviceStatusApiResult
    data object Retryable : DeviceStatusApiResult
}

sealed interface DeviceDisconnectApiResult {
    data object Disconnected : DeviceDisconnectApiResult
    data object Unauthorized : DeviceDisconnectApiResult
    data object Retryable : DeviceDisconnectApiResult
}

sealed interface InstanceProbeResult {
    data object Compatible : InstanceProbeResult
    data object NotRunway : InstanceProbeResult
    data object UpgradeRequired : InstanceProbeResult
    data object Unreachable : InstanceProbeResult
}

class RunwayApiClient(origin: String) {
    private val serverOrigin = requireNotNull(
        InstanceOriginPolicy.normalizeOrigin(origin, BuildConfig.DEBUG),
    ) { "RunwayApiClient requires a valid runway origin" }

    fun probe(): InstanceProbeResult {
        val response = request(
            path = "/api/android/instance",
            method = "GET",
            headers = emptyMap(),
        ) ?: return InstanceProbeResult.Unreachable
        if (response.status == 429 || response.status >= 500) return InstanceProbeResult.Unreachable
        if (response.status != HttpURLConnection.HTTP_OK) return InstanceProbeResult.NotRunway
        return runCatching {
            val payload = JSONObject(response.body)
            if (
                payload.getString("result") != "runway-instance" ||
                payload.getString("product") != "runway"
            ) return@runCatching InstanceProbeResult.NotRunway
            val minimum = payload.getInt("minimumAndroidApi")
            val maximum = payload.getInt("maximumAndroidApi")
            if (ANDROID_API_VERSION !in minimum..maximum) {
                InstanceProbeResult.UpgradeRequired
            } else {
                InstanceProbeResult.Compatible
            }
        }.getOrDefault(InstanceProbeResult.NotRunway)
    }

    fun pair(code: String, label: String): PairingApiResult {
        val body = JSONObject()
            .put("code", code)
            .put("label", label)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val response = request(
            path = "/api/android/pair",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = body,
        ) ?: return PairingApiResult.Retryable
        if (response.status == HttpURLConnection.HTTP_BAD_REQUEST) return PairingApiResult.Invalid
        if (response.status == 429 || response.status >= 500) return PairingApiResult.Retryable
        if (response.status != HttpURLConnection.HTTP_CREATED) return PairingApiResult.Invalid

        return runCatching {
            val payload = JSONObject(response.body)
            if (payload.getString("result") != "paired") return@runCatching PairingApiResult.Invalid
            PairingApiResult.Paired(
                AndroidCredential(
                    origin = serverOrigin,
                    deviceId = payload.getString("deviceId"),
                    token = payload.getString("token"),
                    expiresAtEpochMs = payload.getLong("expiresAtEpochMs"),
                    importGeneration = payload.optLong("importGeneration", 0),
                ),
            )
        }.getOrDefault(PairingApiResult.Invalid)
    }

    fun importGpx(
        credential: AndroidCredential,
        bytes: ByteArray,
        requestId: String = UUID.randomUUID().toString(),
    ): ImportApiResult {
        if (credential.isExpired() || credential.origin != serverOrigin) {
            return ImportApiResult.Unauthorized
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val response = request(
            path = "/api/android/import",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer ${credential.token}",
                "Content-Type" to "application/gpx+xml",
                "X-Runway-Content-SHA256" to digest,
                "X-Runway-Request-Id" to requestId,
                "X-Runway-Activity-Generation" to credential.importGeneration.toString(),
            ),
            body = bytes,
        ) ?: return ImportApiResult.Retryable
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) return ImportApiResult.Unauthorized
        if (response.status == 409) {
            val result = runCatching { JSONObject(response.body).optString("result") }.getOrNull()
            return if (result == "request-conflict") {
                ImportApiResult.RequestConflict
            } else {
                ImportApiResult.Retryable
            }
        }
        if (response.status == 429 || response.status >= 500) {
            return ImportApiResult.Retryable
        }
        return runCatching {
            val payload = JSONObject(response.body)
            val result = payload.getString("result")
            if (result !in handledImportResults) return@runCatching ImportApiResult.Retryable
            ImportApiResult.Handled(
                result = result,
                reason = payload.optString("reason").takeIf { it.isNotBlank() && it != "null" },
            )
        }.getOrDefault(ImportApiResult.Retryable)
    }

    /** Sends only a versioned running-session delta; Health Connect data is never written by runway. */
    internal fun syncHealthConnectChanges(
        credential: AndroidCredential,
        payload: HealthConnectRequestPayload,
        requestId: String = UUID.randomUUID().toString(),
    ): HealthConnectApiResult {
        if (credential.isExpired() || credential.origin != serverOrigin) {
            return HealthConnectApiResult.Unauthorized
        }
        if (
            payload.changeCount !in 1..MAX_HEALTH_CONNECT_CHANGES ||
            payload.bytes.size > MAX_HEALTH_CONNECT_PAYLOAD_BYTES
        ) return HealthConnectApiResult.Rejected
        val response = request(
            path = "/api/android/health-connect/changes",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer ${credential.token}",
                "Content-Type" to "application/json",
                "X-Runway-Request-Id" to requestId,
                "X-Runway-Activity-Generation" to credential.importGeneration.toString(),
            ),
            body = payload.bytes,
        ) ?: return HealthConnectApiResult.Retryable
        return when {
            response.status == HttpURLConnection.HTTP_UNAUTHORIZED -> HealthConnectApiResult.Unauthorized
            response.status == 408 || response.status == 409 ||
                response.status == 429 || response.status >= 500 -> HealthConnectApiResult.Retryable
            response.status in 200..299 -> HealthConnectApiResult.Accepted
            else -> HealthConnectApiResult.Rejected
        }
    }

    fun status(credential: AndroidCredential): DeviceStatusApiResult {
        if (credential.isExpired() || credential.origin != serverOrigin) {
            return DeviceStatusApiResult.Unauthorized
        }
        val response = request(
            path = "/api/android/status",
            method = "GET",
            headers = mapOf("Authorization" to "Bearer ${credential.token}"),
        ) ?: return DeviceStatusApiResult.Retryable
        return when {
            response.status == HttpURLConnection.HTTP_OK -> runCatching {
                DeviceStatusApiResult.Connected(
                    JSONObject(response.body).getLong("activityImportGeneration"),
                )
            }.getOrDefault(DeviceStatusApiResult.Retryable)
            response.status == HttpURLConnection.HTTP_UNAUTHORIZED -> DeviceStatusApiResult.Unauthorized
            else -> DeviceStatusApiResult.Retryable
        }
    }

    fun disconnect(credential: AndroidCredential): DeviceDisconnectApiResult {
        if (credential.isExpired() || credential.origin != serverOrigin) {
            return DeviceDisconnectApiResult.Unauthorized
        }
        val response = request(
            path = "/api/android/status",
            method = "DELETE",
            headers = mapOf("Authorization" to "Bearer ${credential.token}"),
        ) ?: return DeviceDisconnectApiResult.Retryable
        return when {
            response.status == HttpURLConnection.HTTP_OK -> DeviceDisconnectApiResult.Disconnected
            response.status == HttpURLConnection.HTTP_UNAUTHORIZED -> {
                DeviceDisconnectApiResult.Unauthorized
            }
            else -> DeviceDisconnectApiResult.Retryable
        }
    }

    private fun request(
        path: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
    ): ApiResponse? {
        return try {
            val connection = (URL("$serverOrigin$path").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = method
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Runway-Client", ANDROID_CLIENT)
                    for ((name, value) in headers) setRequestProperty(name, value)
                    if (body != null) {
                        doOutput = true
                        setFixedLengthStreamingMode(body.size)
                    }
                }
            try {
                if (body != null) connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status in 200..399) connection.inputStream else connection.errorStream
                val responseBody = stream?.use {
                    String(
                        BoundedStreamInspector.readBytes(it, MAX_RESPONSE_BYTES),
                        StandardCharsets.UTF_8,
                    )
                } ?: ""
                ApiResponse(status, responseBody)
            } finally {
                connection.disconnect()
            }
        } catch (_: PayloadTooLargeException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private data class ApiResponse(val status: Int, val body: String)

    private companion object {
        const val ANDROID_CLIENT = "runway-android/2"
        const val ANDROID_API_VERSION = 2
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_RESPONSE_BYTES = 64L * 1024L
        const val MAX_HEALTH_CONNECT_CHANGES = 100
        val handledImportResults = setOf("imported", "duplicate", "quarantined")
    }
}

/** Leaves 16 KiB below the server's exact 256 KiB request-body limit. */
internal const val MAX_HEALTH_CONNECT_PAYLOAD_BYTES = 240 * 1024
