package dev.deftmartian.runway

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

sealed interface MobileAuthorizationStartResult {
    data class Started(val pending: PendingMobileAuthorization) : MobileAuthorizationStartResult
    data object Rejected : MobileAuthorizationStartResult
    data object Retryable : MobileAuthorizationStartResult
}

sealed interface MobileAuthorizationPollResult {
    data class Authorized(val session: MobileSession) : MobileAuthorizationPollResult
    data object Pending : MobileAuthorizationPollResult
    data class SlowDown(val extraSeconds: Int = 5) : MobileAuthorizationPollResult
    data object Denied : MobileAuthorizationPollResult
    data object Expired : MobileAuthorizationPollResult
    data object Retryable : MobileAuthorizationPollResult
}

sealed interface MobileViewResult {
    data class Loaded(val payload: JSONObject) : MobileViewResult
    data object Unauthorized : MobileViewResult
    data object Incompatible : MobileViewResult
    data object Retryable : MobileViewResult
}

sealed interface MobileActionResult {
    data class Completed(val payload: JSONObject) : MobileActionResult
    data object Unauthorized : MobileActionResult
    data object Incompatible : MobileActionResult
    data class Rejected(val message: String) : MobileActionResult
    data class Uncertain(val message: String) : MobileActionResult
    data object Retryable : MobileActionResult
}

sealed interface MobileImportPairingResult {
    data class Ready(
        val code: String,
        val label: String,
    ) : MobileImportPairingResult

    data object Unauthorized : MobileImportPairingResult
    data class Rejected(val message: String) : MobileImportPairingResult
    data object Retryable : MobileImportPairingResult
}

class MobileApiClient(origin: String) {
    private val serverOrigin = requireNotNull(
        InstanceOriginPolicy.normalizeOrigin(origin, BuildConfig.DEBUG),
    ) { "MobileApiClient requires a valid runway origin" }

    fun beginAuthorization(): MobileAuthorizationStartResult {
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("scope", MOBILE_SCOPE)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val response = request(
            path = "/api/auth/device/code",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = body,
        ) ?: return MobileAuthorizationStartResult.Retryable
        if (response.status == 429 || response.status >= 500) {
            return MobileAuthorizationStartResult.Retryable
        }
        if (response.status != HttpURLConnection.HTTP_OK) {
            return MobileAuthorizationStartResult.Rejected
        }
        return runCatching {
            val payload = JSONObject(response.body)
            val deviceCode = payload.getString("device_code")
            val userCode = payload.getString("user_code").replace("-", "").uppercase()
            val verificationUri = payload.getString("verification_uri_complete")
            val expiresIn = payload.getInt("expires_in")
            val interval = payload.getInt("interval")
            if (
                !InstanceOriginPolicy.belongsTo(verificationUri, serverOrigin) ||
                deviceCode.length !in 20..256 ||
                !userCode.matches(Regex("[A-HJ-NP-Z2-9]{8}")) ||
                expiresIn !in 60..1_800 ||
                interval !in 1..60
            ) {
                return@runCatching MobileAuthorizationStartResult.Rejected
            }
            MobileAuthorizationStartResult.Started(
                PendingMobileAuthorization(
                    origin = serverOrigin,
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUri = verificationUri,
                    expiresAtEpochMs = System.currentTimeMillis() + expiresIn * 1_000L,
                    pollIntervalSeconds = interval,
                ),
            )
        }.getOrDefault(MobileAuthorizationStartResult.Rejected)
    }

    fun pollAuthorization(
        pending: PendingMobileAuthorization,
    ): MobileAuthorizationPollResult {
        if (pending.origin != serverOrigin || pending.isExpired()) {
            return MobileAuthorizationPollResult.Expired
        }
        val body = JSONObject()
            .put("grant_type", DEVICE_GRANT)
            .put("device_code", pending.deviceCode)
            .put("client_id", CLIENT_ID)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val response = request(
            path = "/api/auth/device/token",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = body,
        ) ?: return MobileAuthorizationPollResult.Retryable
        if (response.status == HttpURLConnection.HTTP_OK) {
            return runCatching {
                val payload = JSONObject(response.body)
                if (!payload.getString("token_type").equals("Bearer", ignoreCase = true)) {
                    return@runCatching MobileAuthorizationPollResult.Retryable
                }
                val token = payload.getString("access_token")
                val expiresIn = payload.getInt("expires_in")
                if (token.length !in 20..1_024 || expiresIn !in 60..31_536_000) {
                    return@runCatching MobileAuthorizationPollResult.Retryable
                }
                MobileAuthorizationPollResult.Authorized(
                    MobileSession(
                        origin = serverOrigin,
                        token = token,
                        expiresAtEpochMs = System.currentTimeMillis() + expiresIn * 1_000L,
                    ),
                )
            }.getOrDefault(MobileAuthorizationPollResult.Retryable)
        }
        if (response.status == 429 || response.status >= 500) {
            return MobileAuthorizationPollResult.Retryable
        }
        val error = runCatching { JSONObject(response.body).optString("error") }.getOrNull()
        return when (error) {
            "authorization_pending" -> MobileAuthorizationPollResult.Pending
            "slow_down" -> MobileAuthorizationPollResult.SlowDown()
            "access_denied" -> MobileAuthorizationPollResult.Denied
            "expired_token", "invalid_grant" -> MobileAuthorizationPollResult.Expired
            else -> MobileAuthorizationPollResult.Retryable
        }
    }

    fun getView(
        session: MobileSession,
        view: String,
        query: String = "",
    ): MobileViewResult {
        if (
            session.origin != serverOrigin ||
            session.isExpired() ||
            view !in SUPPORTED_VIEWS ||
            (query.isNotEmpty() && !query.startsWith("?"))
        ) {
            return MobileViewResult.Unauthorized
        }
        val response = request(
            path = "/api/mobile/v1/view/$view$query",
            method = "GET",
            headers = mapOf("Authorization" to "Bearer ${session.token}"),
        ) ?: return MobileViewResult.Retryable
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return MobileViewResult.Unauthorized
        }
        if (response.status == 404 || response.status == 426) {
            return MobileViewResult.Incompatible
        }
        if (response.status == 429 || response.status >= 500) {
            return MobileViewResult.Retryable
        }
        if (response.status != HttpURLConnection.HTTP_OK) {
            return MobileViewResult.Retryable
        }
        return runCatching {
            val payload = JSONObject(response.body)
            if (payload.getInt("schemaVersion") != MOBILE_SCHEMA_VERSION) {
                return@runCatching MobileViewResult.Incompatible
            }
            if (payload.getString("view") != view) {
                return@runCatching MobileViewResult.Incompatible
            }
            MobileViewResult.Loaded(payload)
        }.getOrDefault(MobileViewResult.Retryable)
    }

    fun runAction(
        session: MobileSession,
        action: String,
        payload: JSONObject,
        requestId: UUID,
    ): MobileActionResult {
        if (
            session.origin != serverOrigin ||
            session.isExpired() ||
            action !in SUPPORTED_ACTIONS
        ) {
            return MobileActionResult.Unauthorized
        }
        val body = payload.toString().toByteArray(StandardCharsets.UTF_8)
        if (body.size > MAX_ACTION_BYTES) return MobileActionResult.Rejected("That change is too large.")
        val response = request(
            path = "/api/mobile/v1/action/$action",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer ${session.token}",
                "Content-Type" to "application/json",
                "Idempotency-Key" to requestId.toString(),
            ),
            body = body,
        ) ?: return MobileActionResult.Retryable
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return MobileActionResult.Unauthorized
        }
        if (response.status == 404 || response.status == 426) {
            return MobileActionResult.Incompatible
        }
        if (response.status == 429 || response.status >= 500) {
            return MobileActionResult.Retryable
        }
        return runCatching {
            val result = JSONObject(response.body)
            val message = result.optString("message").ifBlank {
                if (response.status in 200..299) "Change saved." else "The change was not accepted."
            }
            when {
                response.status in 200..299 && result.optBoolean("ok") ->
                    MobileActionResult.Completed(result)
                response.status == HttpURLConnection.HTTP_CONFLICT &&
                    result.optString("error") in setOf(
                        "request_in_progress",
                        "request_outcome_unknown",
                    ) ->
                    MobileActionResult.Uncertain(message)
                response.status in 400..499 -> MobileActionResult.Rejected(message)
                else -> MobileActionResult.Retryable
            }
        }.getOrDefault(MobileActionResult.Retryable)
    }

    fun createImportPairing(
        session: MobileSession,
        label: String,
    ): MobileImportPairingResult {
        val normalizedLabel = label.trim()
        if (
            session.origin != serverOrigin ||
            session.isExpired() ||
            normalizedLabel.length !in 1..60 ||
            normalizedLabel.any { it.isISOControl() }
        ) {
            return MobileImportPairingResult.Rejected("Give this phone a short, visible name.")
        }
        val body = JSONObject()
            .put("label", normalizedLabel)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val response = request(
            path = "/api/mobile/v1/android/pairing",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer ${session.token}",
                "Content-Type" to "application/json",
            ),
            body = body,
        ) ?: return MobileImportPairingResult.Retryable
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return MobileImportPairingResult.Unauthorized
        }
        if (response.status == 429 || response.status >= 500) {
            return MobileImportPairingResult.Retryable
        }
        return runCatching {
            val payload = JSONObject(response.body)
            if (response.status !in 200..299 || !payload.optBoolean("ok")) {
                return@runCatching MobileImportPairingResult.Rejected(
                    payload.optString("message").ifBlank {
                        "This phone could not be connected for imports."
                    },
                )
            }
            val code = payload.getString("code")
            val returnedLabel = payload.getString("label")
            if (
                !code.matches(Regex("[0-9A-F]{4}(?:-[0-9A-F]{4}){3}")) ||
                returnedLabel != normalizedLabel
            ) {
                return@runCatching MobileImportPairingResult.Retryable
            }
            MobileImportPairingResult.Ready(code, returnedLabel)
        }.getOrDefault(MobileImportPairingResult.Retryable)
    }

    fun signOut(session: MobileSession): Boolean {
        if (session.origin != serverOrigin || session.isExpired()) return true
        val body = "{}".toByteArray(StandardCharsets.UTF_8)
        val response = request(
            path = "/api/mobile/v1/session",
            method = "DELETE",
            headers = mapOf(
                "Authorization" to "Bearer ${session.token}",
                "Content-Type" to "application/json",
            ),
            body = body,
        )
        return response != null &&
            (response.status in 200..299 || response.status == HttpURLConnection.HTTP_UNAUTHORIZED)
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
        const val CLIENT_ID = "runway-android"
        const val MOBILE_SCOPE = "runway:mobile"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val MOBILE_SCHEMA_VERSION = 1
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
        const val MAX_ACTION_BYTES = 64 * 1024
        val SUPPORTED_VIEWS = setOf(
            "bootstrap",
            "calendar",
            "review",
            "stats",
            "history",
            "settings",
            "onboarding",
        )
        val SUPPORTED_ACTIONS = setOf(
            "create_plan",
            "record_feedback",
            "delete_feedback",
            "record_manual_run",
            "link_activity",
            "unlink_activity",
            "confirm_activity_extra",
            "update_activity_feedback",
            "delete_activity",
            "apply_plan_decision",
            "preview_workout_edit",
            "apply_workout_edit",
            "preview_workout_add",
            "apply_workout_add",
            "preview_workout_removal",
            "remove_workout",
            "reset_workout",
            "undo_workout_adjustment",
            "complete_plan",
            "confirm_phase_baseline",
            "continue_beginner_phase",
            "archive_plan",
            "update_time_zone",
            "update_route_data_mode",
            "update_health_context",
            "update_training_profile",
            "connect_nextcloud",
            "test_nextcloud",
            "sync_nextcloud",
            "disconnect_nextcloud",
        )
    }
}
