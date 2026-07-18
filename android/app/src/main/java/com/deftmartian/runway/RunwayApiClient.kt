package com.deftmartian.runway

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

sealed interface DeviceStatusApiResult {
    data object Connected : DeviceStatusApiResult
    data object Unauthorized : DeviceStatusApiResult
    data object Retryable : DeviceStatusApiResult
}

class RunwayApiClient {
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
                    deviceId = payload.getString("deviceId"),
                    token = payload.getString("token"),
                    expiresAtEpochMs = payload.getLong("expiresAtEpochMs"),
                ),
            )
        }.getOrDefault(PairingApiResult.Invalid)
    }

    fun importGpx(
        credential: AndroidCredential,
        bytes: ByteArray,
        requestId: String = UUID.randomUUID().toString(),
    ): ImportApiResult {
        if (credential.isExpired()) return ImportApiResult.Unauthorized
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

    fun status(credential: AndroidCredential): DeviceStatusApiResult {
        if (credential.isExpired()) return DeviceStatusApiResult.Unauthorized
        val response = request(
            path = "/api/android/status",
            method = "GET",
            headers = mapOf("Authorization" to "Bearer ${credential.token}"),
        ) ?: return DeviceStatusApiResult.Retryable
        return when {
            response.status == HttpURLConnection.HTTP_OK -> DeviceStatusApiResult.Connected
            response.status == HttpURLConnection.HTTP_UNAUTHORIZED -> DeviceStatusApiResult.Unauthorized
            else -> DeviceStatusApiResult.Retryable
        }
    }

    private fun request(
        path: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
    ): ApiResponse? {
        return try {
            val connection = (URL("${BuildConfig.RUNWAY_ORIGIN}$path").openConnection() as HttpURLConnection)
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
        const val ANDROID_CLIENT = "runway-android/1"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_RESPONSE_BYTES = 64L * 1024L
        val handledImportResults = setOf("imported", "duplicate", "quarantined")
    }
}
