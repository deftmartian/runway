package dev.deftmartian.runway

import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
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

internal data class NativeAuthCapabilities(
    val local: Boolean,
    val localSignups: Boolean,
    val oidc: Boolean,
    val passkeys: Boolean,
)

internal sealed interface NativeAuthCapabilitiesResult {
    data class Loaded(val capabilities: NativeAuthCapabilities) : NativeAuthCapabilitiesResult
    data object Incompatible : NativeAuthCapabilitiesResult
    data object Retryable : NativeAuthCapabilitiesResult
}

internal enum class NativeSecondFactor {
    Totp,
    BackupCode,
}

internal data class NativeAuthChallenge(
    val cookieHeader: String,
    val methods: Set<NativeSecondFactor>,
)

internal sealed interface NativeLocalSignInResult {
    data class Authorized(val session: MobileSession) : NativeLocalSignInResult
    data class TwoFactorRequired(val challenge: NativeAuthChallenge) : NativeLocalSignInResult
    data object InvalidCredentials : NativeLocalSignInResult
    data class RateLimited(val retryAfterSeconds: Int?) : NativeLocalSignInResult
    data object Unavailable : NativeLocalSignInResult
    data object Retryable : NativeLocalSignInResult
}

internal sealed interface NativeLocalSignUpResult {
    data class Authorized(val session: MobileSession) : NativeLocalSignUpResult
    data object Rejected : NativeLocalSignUpResult
    data class RateLimited(val retryAfterSeconds: Int?) : NativeLocalSignUpResult
    data object Unavailable : NativeLocalSignUpResult
    data object Retryable : NativeLocalSignUpResult
}

internal sealed interface NativeTwoFactorResult {
    data class Authorized(val session: MobileSession) : NativeTwoFactorResult
    data object InvalidCode : NativeTwoFactorResult
    data class RateLimited(val retryAfterSeconds: Int?) : NativeTwoFactorResult
    data object Expired : NativeTwoFactorResult
    data object Retryable : NativeTwoFactorResult
}

internal sealed interface MobileViewResult {
    data class Loaded(val payload: NativeViewPayload) : MobileViewResult
    data object Unauthorized : MobileViewResult
    data object Incompatible : MobileViewResult
    data object Retryable : MobileViewResult
}

internal sealed interface MobileActionResult {
    data class Completed(val response: NativeActionResponse) : MobileActionResult
    data object Unauthorized : MobileActionResult
    data object Incompatible : MobileActionResult
    data class Rejected(val message: String) : MobileActionResult
    data class Uncertain(val message: String) : MobileActionResult
    data object Retryable : MobileActionResult
}

internal sealed interface MobileAccountOperationResult {
    data class Completed(
        val message: String,
        val accountDeleted: Boolean = false,
        val replacementSession: MobileSession? = null,
        val totpSetup: NativeTotpSetup? = null,
        val recoveryCodes: List<String> = emptyList(),
    ) : MobileAccountOperationResult {
        override fun toString(): String =
            "Completed(" +
                "message=$message, accountDeleted=$accountDeleted, " +
                "replacementSession=<redacted>, totpSetup=<redacted>, " +
                "recoveryCodes=<redacted>)"
    }

    data object Unauthorized : MobileAccountOperationResult
    data class ReauthenticationRequired(val message: String) : MobileAccountOperationResult
    data class SessionReplacementFailed(val message: String) : MobileAccountOperationResult
    data class RateLimited(
        val message: String,
        val retryAfterSeconds: Int?,
    ) : MobileAccountOperationResult

    data class Rejected(val message: String) : MobileAccountOperationResult
    data object Retryable : MobileAccountOperationResult
}

internal data class NativeTotpSetup(
    val uri: String,
    val manualSecret: String,
) {
    override fun toString(): String =
        "NativeTotpSetup(uri=<redacted>, manualSecret=<redacted>)"
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

internal class MobileApiClient(
    origin: String,
    allowPrivateCleartextForTests: Boolean = false,
) {
    private val serverOrigin = requireNotNull(
        InstanceOriginPolicy.normalizeOrigin(
            origin,
            BuildConfig.DEBUG || allowPrivateCleartextForTests,
        ),
    ) { "MobileApiClient requires a valid runway origin" }

    fun getAuthCapabilities(): NativeAuthCapabilitiesResult {
        val response = request(
            path = "/api/android/instance",
            method = "GET",
            headers = emptyMap(),
        ) ?: return NativeAuthCapabilitiesResult.Retryable
        if (response.status == 404 || response.status == 426) {
            return NativeAuthCapabilitiesResult.Incompatible
        }
        if (response.status == 429 || response.status >= 500) {
            return NativeAuthCapabilitiesResult.Retryable
        }
        if (response.status != HttpURLConnection.HTTP_OK) {
            return NativeAuthCapabilitiesResult.Incompatible
        }
        return runCatching {
            val payload = JSONObject(response.body)
            if (
                payload.getString("result") != "runway-instance" ||
                payload.getString("product") != "runway"
            ) {
                return@runCatching NativeAuthCapabilitiesResult.Incompatible
            }
            val auth = payload.getJSONObject("auth")
            NativeAuthCapabilitiesResult.Loaded(
                NativeAuthCapabilities(
                    local = auth.getBoolean("local"),
                    localSignups = auth.getBoolean("localSignups"),
                    oidc = auth.getBoolean("oidc"),
                    passkeys = auth.getBoolean("passkeys"),
                ),
            )
        }.getOrDefault(NativeAuthCapabilitiesResult.Incompatible)
    }

    fun signInLocal(email: String, password: String): NativeLocalSignInResult {
        val normalizedEmail = email.trim().lowercase()
        if (
            normalizedEmail.length !in 3..320 ||
            '@' !in normalizedEmail ||
            password.length !in 1..128
        ) {
            return NativeLocalSignInResult.InvalidCredentials
        }
        val body = JSONObject()
            .put("email", normalizedEmail)
            .put("password", password)
            .put("rememberMe", true)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val response = request(
            path = "/api/auth/sign-in/email",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = body,
        ) ?: return NativeLocalSignInResult.Retryable
        if (response.status == 429) {
            return NativeLocalSignInResult.RateLimited(response.retryAfterSeconds())
        }
        if (response.status >= 500) return NativeLocalSignInResult.Retryable
        if (response.status == 404) return NativeLocalSignInResult.Unavailable
        if (response.status !in 200..299) return NativeLocalSignInResult.InvalidCredentials
        return runCatching {
            val payload = JSONObject(response.body)
            if (payload.optBoolean("twoFactorRedirect")) {
                val challenge = response.nativeTwoFactorChallenge()
                    ?: return@runCatching NativeLocalSignInResult.Retryable
                return@runCatching NativeLocalSignInResult.TwoFactorRequired(challenge)
            }
            payload.mobileSession()
                ?.let(NativeLocalSignInResult::Authorized)
                ?: NativeLocalSignInResult.Retryable
        }.getOrDefault(NativeLocalSignInResult.Retryable)
    }

    fun signUpLocal(
        name: String,
        email: String,
        password: String,
    ): NativeLocalSignUpResult {
        val normalizedEmail = email.trim().lowercase()
        val normalizedName = name.trim().ifBlank { normalizedEmail }
        if (
            normalizedEmail.length !in 3..320 ||
            '@' !in normalizedEmail ||
            normalizedName.length !in 1..100 ||
            normalizedName.any(Char::isISOControl) ||
            password.length !in 12..128
        ) {
            return NativeLocalSignUpResult.Rejected
        }
        val body = JSONObject()
            .put("name", normalizedName)
            .put("email", normalizedEmail)
            .put("password", password)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val response = request(
            path = "/api/auth/sign-up/email",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = body,
        ) ?: return NativeLocalSignUpResult.Retryable
        if (response.status == 429) {
            return NativeLocalSignUpResult.RateLimited(response.retryAfterSeconds())
        }
        if (response.status >= 500) return NativeLocalSignUpResult.Retryable
        if (response.status == 404) return NativeLocalSignUpResult.Unavailable
        if (response.status !in 200..299) return NativeLocalSignUpResult.Rejected
        return runCatching {
            JSONObject(response.body).mobileSession()
                ?.let(NativeLocalSignUpResult::Authorized)
                ?: NativeLocalSignUpResult.Retryable
        }.getOrDefault(NativeLocalSignUpResult.Retryable)
    }

    fun verifyTwoFactor(
        challenge: NativeAuthChallenge,
        code: String,
        method: NativeSecondFactor,
    ): NativeTwoFactorResult {
        val normalizedCode = when (method) {
            NativeSecondFactor.Totp -> code.filter(Char::isDigit)
            NativeSecondFactor.BackupCode -> code.trim()
        }
        if (
            challenge.cookieHeader.length !in 20..4_096 ||
            (method == NativeSecondFactor.Totp && !normalizedCode.matches(Regex("\\d{6}"))) ||
            (method == NativeSecondFactor.BackupCode && normalizedCode.length !in 4..128)
        ) {
            return NativeTwoFactorResult.InvalidCode
        }
        val path = when (method) {
            NativeSecondFactor.Totp -> "/api/auth/two-factor/verify-totp"
            NativeSecondFactor.BackupCode -> "/api/auth/two-factor/verify-backup-code"
        }
        val body = JSONObject()
            .put("code", normalizedCode)
            .put("trustDevice", true)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val response = request(
            path = path,
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/json",
                "Cookie" to challenge.cookieHeader,
            ),
            body = body,
        ) ?: return NativeTwoFactorResult.Retryable
        if (response.status == 429) {
            return NativeTwoFactorResult.RateLimited(response.retryAfterSeconds())
        }
        if (response.status >= 500) return NativeTwoFactorResult.Retryable
        if (response.status == 401 || response.status == 403) {
            return NativeTwoFactorResult.InvalidCode
        }
        if (response.status !in 200..299) return NativeTwoFactorResult.Expired
        return runCatching {
            JSONObject(response.body).mobileSession()
                ?.let(NativeTwoFactorResult::Authorized)
                ?: NativeTwoFactorResult.Retryable
        }.getOrDefault(NativeTwoFactorResult.Retryable)
    }

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
                mobileSession(token)
                    ?.let(MobileAuthorizationPollResult::Authorized)
                    ?: MobileAuthorizationPollResult.Retryable
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
            val decoded = NativePayloadCodec.decodeView(view, payload)
                ?: return@runCatching MobileViewResult.Retryable
            MobileViewResult.Loaded(decoded)
        }.getOrDefault(MobileViewResult.Retryable)
    }

    fun runAction(
        session: MobileSession,
        command: MobileCommand,
        requestId: UUID,
    ): MobileActionResult {
        val action = command.action
        if (
            session.origin != serverOrigin ||
            session.isExpired() ||
            action !in SUPPORTED_ACTIONS
        ) {
            return MobileActionResult.Unauthorized
        }
        val body = NativePayloadCodec.encodeCommand(command).toByteArray(StandardCharsets.UTF_8)
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
            val result = NativePayloadCodec.decodeAction(response.body)
                ?: return@runCatching MobileActionResult.Retryable
            val message = result.message.orEmpty().ifBlank {
                if (response.status in 200..299) "Change saved." else "The change was not accepted."
            }
            when {
                response.status in 200..299 && result.ok == true ->
                    MobileActionResult.Completed(result)
                response.status == HttpURLConnection.HTTP_CONFLICT &&
                    result.error in setOf(
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

    fun requestPasswordReset(session: MobileSession): MobileAccountOperationResult =
        runAccountOperation(session, "request-password-reset", JSONObject())

    fun changePassword(
        session: MobileSession,
        currentPassword: String,
        newPassword: String,
    ): MobileAccountOperationResult {
        if (
            currentPassword.length !in 1..128 ||
            newPassword.length !in 12..128
        ) {
            return MobileAccountOperationResult.Rejected(
                "Enter your current password and a new password of at least 12 characters.",
            )
        }
        return runAccountOperation(
            session,
            "change-password",
            JSONObject()
                .put("currentPassword", currentPassword)
                .put("newPassword", newPassword),
        )
    }

    fun enableTwoFactor(
        session: MobileSession,
        password: String,
    ): MobileAccountOperationResult {
        if (password.length !in 1..128) {
            return MobileAccountOperationResult.Rejected("Enter your current password.")
        }
        return runAccountOperation(
            session,
            "enable-two-factor",
            JSONObject().put("password", password),
        )
    }

    fun verifyTwoFactorSetup(
        session: MobileSession,
        code: String,
    ): MobileAccountOperationResult {
        if (!code.matches(TOTP_CODE_PATTERN)) {
            return MobileAccountOperationResult.Rejected(
                "Enter the 6-digit code from your authenticator app.",
            )
        }
        return runAccountOperation(
            session,
            "verify-two-factor-setup",
            JSONObject().put("code", code),
        )
    }

    fun disableTwoFactor(
        session: MobileSession,
        password: String,
    ): MobileAccountOperationResult {
        if (password.length !in 1..128) {
            return MobileAccountOperationResult.Rejected("Enter your current password.")
        }
        return runAccountOperation(
            session,
            "disable-two-factor",
            JSONObject().put("password", password),
        )
    }

    fun regenerateRecoveryCodes(
        session: MobileSession,
        password: String,
    ): MobileAccountOperationResult {
        if (password.length !in 1..128) {
            return MobileAccountOperationResult.Rejected("Enter your current password.")
        }
        return runAccountOperation(
            session,
            "regenerate-recovery-codes",
            JSONObject().put("password", password),
        )
    }

    fun revokeAccountSession(
        session: MobileSession,
        sessionId: String,
    ): MobileAccountOperationResult {
        if (sessionId.length !in 1..128 || sessionId.any(Char::isISOControl)) {
            return MobileAccountOperationResult.Rejected("Choose a valid session.")
        }
        return runAccountOperation(
            session,
            "revoke-session",
            JSONObject().put("sessionId", sessionId),
        )
    }

    fun renamePasskey(
        session: MobileSession,
        passkeyId: String,
        name: String,
    ): MobileAccountOperationResult {
        val normalizedName = name.trim()
        if (
            !validAccountIdentifier(passkeyId) ||
            normalizedName.length !in 1..80 ||
            normalizedName.any(Char::isISOControl)
        ) {
            return MobileAccountOperationResult.Rejected("Give this passkey a short, visible name.")
        }
        return runAccountOperation(
            session,
            "rename-passkey",
            JSONObject().put("id", passkeyId).put("name", normalizedName),
        )
    }

    fun deletePasskey(
        session: MobileSession,
        passkeyId: String,
    ): MobileAccountOperationResult {
        if (!validAccountIdentifier(passkeyId)) {
            return MobileAccountOperationResult.Rejected("Choose a valid passkey.")
        }
        return runAccountOperation(
            session,
            "delete-passkey",
            JSONObject().put("id", passkeyId),
        )
    }

    fun deleteAccount(
        session: MobileSession,
        confirmation: String,
    ): MobileAccountOperationResult =
        runAccountOperation(
            session,
            "delete-account",
            JSONObject().put("confirmation", confirmation),
        )

    fun exportTrainingData(
        session: MobileSession,
        destination: OutputStream,
    ): MobileAccountOperationResult {
        if (session.origin != serverOrigin || session.isExpired()) {
            return MobileAccountOperationResult.Unauthorized
        }
        return try {
            val requestBody = "{}".toByteArray(StandardCharsets.UTF_8)
            val connection = (URL("$serverOrigin/api/mobile/v1/account/export")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = EXPORT_READ_TIMEOUT_MS
                useCaches = false
                doOutput = true
                setFixedLengthStreamingMode(requestBody.size)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${session.token}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Runway-Client", ANDROID_CLIENT)
            }
            try {
                connection.outputStream.use { it.write(requestBody) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    return accountOperationResult(
                        ApiResponse(
                            status = status,
                            body = connection.errorStream?.use {
                                String(
                                    BoundedStreamInspector.readBytes(it, MAX_RESPONSE_BYTES),
                                    StandardCharsets.UTF_8,
                                )
                            }.orEmpty(),
                            headers = connection.responseHeaders(),
                        ),
                    )
                }
                val contentType = connection.contentType.orEmpty().lowercase()
                val expectedBytes = connection.contentLengthLong
                if (
                    !contentType.startsWith("application/json") ||
                    expectedBytes !in 1..MAX_EXPORT_BYTES
                ) {
                    return MobileAccountOperationResult.Retryable
                }
                var copied = 0L
                connection.inputStream.use { input ->
                    val buffer = ByteArray(EXPORT_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > expectedBytes || copied > MAX_EXPORT_BYTES) {
                            return MobileAccountOperationResult.Retryable
                        }
                        destination.write(buffer, 0, count)
                    }
                }
                destination.flush()
                if (copied != expectedBytes) {
                    MobileAccountOperationResult.Retryable
                } else {
                    MobileAccountOperationResult.Completed("Training data exported.")
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: PayloadTooLargeException) {
            MobileAccountOperationResult.Retryable
        } catch (_: IOException) {
            MobileAccountOperationResult.Retryable
        } catch (_: RuntimeException) {
            MobileAccountOperationResult.Retryable
        }
    }

    private fun runAccountOperation(
        session: MobileSession,
        operation: String,
        payload: JSONObject,
    ): MobileAccountOperationResult {
        if (
            session.origin != serverOrigin ||
            session.isExpired() ||
            operation !in SUPPORTED_ACCOUNT_OPERATIONS
        ) {
            return MobileAccountOperationResult.Unauthorized
        }
        val body = payload.toString().toByteArray(StandardCharsets.UTF_8)
        if (body.size > MAX_ACCOUNT_OPERATION_BYTES) {
            return MobileAccountOperationResult.Rejected("That account-security request is too large.")
        }
        val response = request(
            path = "/api/mobile/v1/account/$operation",
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer ${session.token}",
                "Content-Type" to "application/json",
            ),
            body = body,
        ) ?: return if (operation in REPLACEMENT_ACCOUNT_OPERATIONS) {
            MobileAccountOperationResult.SessionReplacementFailed(
                "Android could not confirm the new session after that security request. Sign in again.",
            )
        } else {
            MobileAccountOperationResult.Retryable
        }
        return accountOperationResult(response, operation, session)
    }

    private fun accountOperationResult(
        response: ApiResponse,
        operation: String? = null,
        currentSession: MobileSession? = null,
    ): MobileAccountOperationResult {
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return MobileAccountOperationResult.Unauthorized
        }
        val payload = NativePayloadCodec.decodeAccountOperation(response.body)
        val message = payload?.message.orEmpty().ifBlank {
            if (response.status in 200..299) {
                "Account-security change completed."
            } else {
                "The account-security request was not accepted."
            }
        }
        if (response.status == 403 && payload?.error == "fresh_session_required") {
            return MobileAccountOperationResult.ReauthenticationRequired(message)
        }
        if (payload?.error == "session_replacement_failed") {
            return MobileAccountOperationResult.SessionReplacementFailed(message)
        }
        if (payload?.error == "recovery_code_generation_failed") {
            return MobileAccountOperationResult.Rejected(message)
        }
        if (response.status == 429) {
            return MobileAccountOperationResult.RateLimited(
                message = message,
                retryAfterSeconds = response.retryAfterSeconds(),
            )
        }
        if (response.status >= 500) {
            return if (
                operation != null &&
                operation in REPLACEMENT_ACCOUNT_OPERATIONS
            ) {
                MobileAccountOperationResult.SessionReplacementFailed(
                    "Android could not confirm the new session after that security request. Sign in again.",
                )
            } else {
                MobileAccountOperationResult.Retryable
            }
        }
        if (response.status !in 200..299 || payload?.ok != true) {
            if (
                response.status in 200..299 &&
                operation != null &&
                operation in REPLACEMENT_ACCOUNT_OPERATIONS
            ) {
                return MobileAccountOperationResult.SessionReplacementFailed(
                    "The security change returned an invalid session response. Sign in again.",
                )
            }
            return MobileAccountOperationResult.Rejected(message)
        }
        if (operation == null) {
            return MobileAccountOperationResult.Completed(message)
        }
        val replacementSession = when (operation) {
            "change-password",
            "verify-two-factor-setup",
            "disable-two-factor",
            -> {
                val replacement = payload.sessionToken?.let(::mobileSession)
                if (
                    currentSession == null ||
                    replacement == null ||
                    replacement.token == currentSession.token
                ) {
                    return MobileAccountOperationResult.SessionReplacementFailed(
                        "The security change completed, but Android could not verify the new session. Sign in again.",
                    )
                }
                replacement
            }
            else -> null
        }
        val totpSetup = when (operation) {
            "enable-two-factor" -> payload.totpUri?.validatedTotpSetup()
                ?: return MobileAccountOperationResult.Rejected(
                    "The server did not return a valid authenticator setup. Start setup again.",
                )
            else -> null
        }
        val recoveryCodes = when (operation) {
            "verify-two-factor-setup" -> payload.recoveryCodes
                .takeIf(::validRecoveryCodes)
                ?: return MobileAccountOperationResult.SessionReplacementFailed(
                    "Two-factor authentication was enabled, but Android could not verify the recovery codes. Sign in again.",
                )
            "regenerate-recovery-codes" -> payload.recoveryCodes
                .takeIf(::validRecoveryCodes)
                ?: return MobileAccountOperationResult.Rejected(
                    "Recovery codes were replaced, but Android could not verify the new set. Replace them again.",
                )
            else -> emptyList()
        }
        val responseMatchesOperation = when (operation) {
            "change-password",
            "disable-two-factor",
            -> payload.totpUri == null && payload.recoveryCodes.isEmpty()
            "enable-two-factor" ->
                payload.sessionToken == null && payload.recoveryCodes.isEmpty()
            "verify-two-factor-setup" -> payload.totpUri == null
            "regenerate-recovery-codes" ->
                payload.sessionToken == null &&
                    payload.totpUri == null &&
                    payload.accountDeleted == null
            "delete-account" ->
                payload.accountDeleted == true &&
                    payload.sessionToken == null &&
                    payload.totpUri == null &&
                    payload.recoveryCodes.isEmpty()
            else ->
                payload.sessionToken == null &&
                    payload.totpUri == null &&
                    payload.recoveryCodes.isEmpty()
        }
        if (!responseMatchesOperation) {
            return if (replacementSession != null) {
                MobileAccountOperationResult.SessionReplacementFailed(
                    "The security change completed, but Android could not verify its response. Sign in again.",
                )
            } else {
                MobileAccountOperationResult.Rejected(
                    "The server returned an invalid account-security response.",
                )
            }
        }
        return MobileAccountOperationResult.Completed(
            message = message,
            accountDeleted = payload.accountDeleted == true,
            replacementSession = replacementSession,
            totpSetup = totpSetup,
            recoveryCodes = recoveryCodes,
        )
    }

    private fun String.validatedTotpSetup(): NativeTotpSetup? {
        if (length !in 1..MAX_TOTP_URI_LENGTH || any(Char::isISOControl)) return null
        val parsed = runCatching { URI(this) }.getOrNull() ?: return null
        if (
            !parsed.scheme.equals("otpauth", ignoreCase = true) ||
            !parsed.host.equals("totp", ignoreCase = true) ||
            parsed.rawUserInfo != null ||
            parsed.rawFragment != null ||
            parsed.rawPath.isNullOrBlank()
        ) {
            return null
        }
        val queryParameters = parsed.rawQuery
            ?.split('&')
            ?.mapNotNull { parameter ->
                val separator = parameter.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = runCatching {
                    URLDecoder.decode(
                        parameter.substring(0, separator),
                        StandardCharsets.UTF_8.name(),
                    )
                }.getOrNull() ?: return@mapNotNull null
                val value = runCatching {
                    URLDecoder.decode(
                        parameter.substring(separator + 1),
                        StandardCharsets.UTF_8.name(),
                    )
                }.getOrNull() ?: return@mapNotNull null
                name to value
            }
            .orEmpty()
        val secrets = queryParameters
            .filter { (name, _) -> name.equals("secret", ignoreCase = true) }
            .map { (_, value) -> value.uppercase() }
        val manualSecret = secrets.singleOrNull()
            ?.takeIf { it.matches(TOTP_SECRET_PATTERN) }
            ?: return null
        return NativeTotpSetup(uri = this, manualSecret = manualSecret)
    }

    private fun validRecoveryCodes(codes: List<String>): Boolean =
        codes.size in 1..MAX_RECOVERY_CODES &&
            codes.distinct().size == codes.size &&
            codes.all { it.matches(RECOVERY_CODE_PATTERN) }

    private fun validAccountIdentifier(value: String): Boolean =
        value.length in 1..128 && value.none(Char::isISOControl)

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
                val responseHeaders = connection.headerFields.entries
                    .filter { it.key != null }
                    .groupBy(
                        keySelector = { requireNotNull(it.key).lowercase() },
                        valueTransform = { it.value.orEmpty() },
                    )
                    .mapValues { (_, values) -> values.flatten() }
                ApiResponse(status, responseBody, responseHeaders)
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

    /**
     * Better Auth's native credential endpoints return a bearer token, but not
     * the session expiry. Resolve it through its authenticated get-session
     * endpoint instead of assuming the default seven-day lifetime. The marker
     * is server-controlled and keeps an ordinary Better Auth bearer from being
     * accepted by the native client.
     */
    private fun JSONObject.mobileSession(): MobileSession? {
        return mobileSession(optString("token"))
    }

    private fun mobileSession(token: String): MobileSession? {
        if (
            token.length !in 20..1_024 ||
            token.any { it.code !in 0x21..0x7e }
        ) {
            return null
        }
        val response = request(
            path = "/api/auth/get-session",
            method = "GET",
            headers = mapOf("Authorization" to "Bearer $token"),
        ) ?: return null
        if (response.status != HttpURLConnection.HTTP_OK) return null
        val session = runCatching { JSONObject(response.body).getJSONObject("session") }.getOrNull()
            ?: return null
        if (session.optString("mobileClientId") != MOBILE_CLIENT_ID) return null
        val expiresAtEpochMs = runCatching {
            Instant.parse(session.getString("expiresAt")).toEpochMilli()
        }.getOrNull() ?: return null
        if (expiresAtEpochMs <= System.currentTimeMillis()) return null
        return MobileSession(
            origin = serverOrigin,
            token = token,
            expiresAtEpochMs = expiresAtEpochMs,
        )
    }

    private fun ApiResponse.nativeTwoFactorChallenge(): NativeAuthChallenge? {
        val cookies = headerValues("set-cookie")
            .mapNotNull(::nativeChallengeCookie)
            .distinct()
        if (cookies.none { it.substringBefore('=').endsWith("two_factor") }) return null
        val payload = runCatching { JSONObject(body) }.getOrNull()
        val methods = buildSet {
            val values = payload?.optJSONArray("twoFactorMethods")
            if (values != null) {
                repeat(values.length()) { index ->
                    when (values.optString(index)) {
                        "totp" -> add(NativeSecondFactor.Totp)
                    }
                }
            }
            add(NativeSecondFactor.BackupCode)
        }
        return NativeAuthChallenge(
            cookieHeader = cookies.joinToString("; "),
            methods = methods,
        )
    }

    private fun nativeChallengeCookie(header: String): String? {
        val pair = header.substringBefore(';').trim()
        val separator = pair.indexOf('=')
        if (separator !in 1..<pair.lastIndex) return null
        val name = pair.substring(0, separator)
        if (
            name != "two_factor" &&
            !name.endsWith(".two_factor") &&
            name != "dont_remember" &&
            !name.endsWith(".dont_remember")
        ) {
            return null
        }
        val value = pair.substring(separator + 1)
        if (value.length !in 1..2_048 || value.any { it.code !in 0x21..0x7e }) return null
        return "$name=$value"
    }

    private fun ApiResponse.headerValues(name: String): List<String> =
        headers[name.lowercase()].orEmpty()

    private fun ApiResponse.retryAfterSeconds(): Int? =
        headerValues("retry-after").firstOrNull()?.toIntOrNull()?.takeIf { it in 1..86_400 }

    private data class ApiResponse(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>>,
    )

    private fun HttpURLConnection.responseHeaders(): Map<String, List<String>> =
        headerFields.entries
            .filter { it.key != null }
            .groupBy(
                keySelector = { requireNotNull(it.key).lowercase() },
                valueTransform = { it.value.orEmpty() },
            )
            .mapValues { (_, values) -> values.flatten() }

    private companion object {
        const val ANDROID_CLIENT = "runway-android/2"
        const val CLIENT_ID = "runway-android"
        const val MOBILE_CLIENT_ID = "runway-android"
        const val MOBILE_SCOPE = "runway:mobile"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val MOBILE_SCHEMA_VERSION = 1
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val EXPORT_READ_TIMEOUT_MS = 5 * 60_000
        const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
        const val MAX_ACTION_BYTES = 64 * 1024
        const val MAX_ACCOUNT_OPERATION_BYTES = 4 * 1024
        const val MAX_TOTP_URI_LENGTH = 2_048
        const val MAX_RECOVERY_CODES = 20
        const val EXPORT_BUFFER_BYTES = 64 * 1024
        const val MAX_EXPORT_BYTES = 4L * 1024L * 1024L * 1024L
        val TOTP_CODE_PATTERN = Regex("^\\d{6}$")
        val TOTP_SECRET_PATTERN = Regex("^[A-Z2-7]{8,128}$")
        val RECOVERY_CODE_PATTERN = Regex("^[A-Za-z0-9]{5}-[A-Za-z0-9]{5}$")
        val REPLACEMENT_ACCOUNT_OPERATIONS = setOf(
            "change-password",
            "verify-two-factor-setup",
            "disable-two-factor",
        )
        val SUPPORTED_ACCOUNT_OPERATIONS = setOf(
            "request-password-reset",
            "change-password",
            "enable-two-factor",
            "verify-two-factor-setup",
            "disable-two-factor",
            "regenerate-recovery-codes",
            "revoke-session",
            "rename-passkey",
            "delete-passkey",
            "delete-account",
        )
        val SUPPORTED_VIEWS = setOf(
            "bootstrap",
            "calendar",
            "review",
            "stats",
            "history",
            "history-detail",
            "settings",
			"account-security",
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
			"resolve_health_connect_record",
			"resolve_health_connect_duplicate",
            "apply_plan_decision",
			"preview_plan_decision",
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
			"revoke_android_device",
			"delete_imported_activity_data",
        )
    }
}
