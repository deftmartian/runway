package dev.deftmartian.runway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MobileApiClientContractTest {
    @Test
    fun `auth capabilities are discovered before credentials are requested`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{
                      "result":"runway-instance",
                      "product":"runway",
                      "auth":{"local":true,"localSignups":false,"oidc":true,"passkeys":true}
                    }""",
                ),
            ),
        ).use { server ->
            val result = testClient(server.origin).getAuthCapabilities()

            val loaded = result as NativeAuthCapabilitiesResult.Loaded
            assertTrue(loaded.capabilities.local)
            assertTrue(loaded.capabilities.oidc)
            assertTrue(loaded.capabilities.passkeys)
            assertFalse(loaded.capabilities.localSignups)
            assertEquals("/api/android/instance", server.awaitRequests(1).single().target)
        }
    }

    @Test
    fun `local credentials produce an origin-bound native session`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{"redirect":false,"token":"${"l".repeat(32)}","user":{"id":"user-1"}}""",
                ),
                Response.json(
                    200,
                    """{"session":{"expiresAt":"2031-02-03T04:05:06.789Z","mobileClientId":"runway-android"}}""",
                ),
            ),
        ).use { server ->
            val result = testClient(server.origin).signInLocal(
                " Runner@Example.Invalid ",
                "not-a-real-password",
            )

            val authorized = result as NativeLocalSignInResult.Authorized
            assertEquals(server.origin, authorized.session.origin)
            assertEquals("l".repeat(32), authorized.session.token)
            assertEquals(1_927_857_906_789L, authorized.session.expiresAtEpochMs)
            val requests = server.awaitRequests(2)
            val request = requests.first()
            assertEquals("/api/auth/sign-in/email", request.target)
            assertEquals("application/json", request.header("content-type"))
            assertEquals("runway-android/2", request.header("x-runway-client"))
            assertEquals("runner@example.invalid", JSONObject(request.body).getString("email"))
            assertFalse(request.headers.containsKey("origin"))
            assertEquals("/api/auth/get-session", requests[1].target)
            assertEquals("Bearer ${"l".repeat(32)}", requests[1].header("authorization"))
        }
    }

    @Test
    fun `local account creation uses the reviewed native auth boundary`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{"token":"${"n".repeat(32)}","user":{"id":"user-2"}}""",
                ),
                Response.json(
                    200,
                    """{"session":{"expiresAt":"2031-02-03T04:05:06.789Z","mobileClientId":"runway-android"}}""",
                ),
            ),
        ).use { server ->
            val result = testClient(server.origin).signUpLocal(
                "Runner",
                "runner@example.invalid",
                "twelve characters minimum",
            )

            assertTrue(result is NativeLocalSignUpResult.Authorized)
            val request = server.awaitRequests(2).first()
            assertEquals("/api/auth/sign-up/email", request.target)
            val payload = JSONObject(request.body)
            assertEquals("Runner", payload.getString("name"))
            assertEquals("runner@example.invalid", payload.getString("email"))
        }
    }

    @Test
    fun `native credentials reject an unstamped server session`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(200, """{"token":"${"u".repeat(32)}"}"""),
                Response.json(
                    200,
                    """{"session":{"expiresAt":"2031-02-03T04:05:06.789Z","mobileClientId":"browser"}}""",
                ),
            ),
        ).use { server ->
            val result = testClient(server.origin).signInLocal(
                "runner@example.invalid",
                "not-a-real-password",
            )

            assertTrue(result is NativeLocalSignInResult.Retryable)
            val request = server.awaitRequests(2)[1]
            assertEquals("/api/auth/get-session", request.target)
            assertFalse(request.headers.containsKey("cookie"))
        }
    }

    @Test
    fun `two factor challenge stays opaque and completes through Better Auth`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{"twoFactorRedirect":true,"twoFactorMethods":["totp"]}""",
                    headers = listOf(
                        "Set-Cookie" to
                            "better-auth.two_factor=${"c".repeat(48)}; Path=/; HttpOnly; Secure",
                    ),
                ),
                Response.json(
                    200,
                    """{"token":"${"m".repeat(32)}","user":{"id":"user-1"}}""",
                ),
                Response.json(
                    200,
                    """{"session":{"expiresAt":"2031-02-03T04:05:06.789Z","mobileClientId":"runway-android"}}""",
                ),
            ),
        ).use { server ->
            val client = testClient(server.origin)
            val first = client.signInLocal("runner@example.invalid", "not-a-real-password")
                as NativeLocalSignInResult.TwoFactorRequired
            assertEquals(setOf(NativeSecondFactor.Totp, NativeSecondFactor.BackupCode), first.challenge.methods)
            assertFalse(first.challenge.cookieHeader.contains("HttpOnly"))

            val verified = client.verifyTwoFactor(
                first.challenge,
                "123 456",
                NativeSecondFactor.Totp,
            ) as NativeTwoFactorResult.Authorized

            assertEquals("m".repeat(32), verified.session.token)
            val requests = server.awaitRequests(3)
            assertEquals("/api/auth/two-factor/verify-totp", requests[1].target)
            assertEquals(
                "better-auth.two_factor=${"c".repeat(48)}",
                requests[1].header("cookie"),
            )
            assertEquals("123456", JSONObject(requests[1].body).getString("code"))
            assertEquals("/api/auth/get-session", requests[2].target)
        }
    }

    @Test
    fun `mobile view response is decoded before it reaches presentation state`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{
                      "schemaVersion":1,
                      "view":"bootstrap",
                      "setupComplete":true,
                      "user":{"id":"user-1","name":"Runner","email":"runner@example.invalid"},
                      "serverOrigin":"__ORIGIN__"
                    }""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )

            val result = testClient(server.origin).getView(session, "bootstrap")

            val bootstrap = (result as MobileViewResult.Loaded).payload as NativeBootstrapPayload
            assertTrue(bootstrap.setupComplete == true)
            assertEquals("Runner", bootstrap.user?.name)
            assertEquals(server.origin, bootstrap.serverOrigin)
        }
    }

    @Test
    fun `device authorization preserves the server-owned poll contract`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{
                      "device_code":"${"d".repeat(32)}",
                      "user_code":"ABCD-EFGH",
                      "verification_uri_complete":"__ORIGIN__/device?user_code=ABCD-EFGH",
                      "expires_in":600,
                      "interval":2
                    }""",
                ),
                Response.json(400, """{"error":"authorization_pending"}"""),
                Response.json(
                    200,
                    """{"token_type":"Bearer","access_token":"${"t".repeat(32)}","expires_in":3600}""",
                ),
                Response.json(
                    200,
                    """{"session":{"expiresAt":"2031-02-03T04:05:06.789Z","mobileClientId":"runway-android"}}""",
                ),
            ),
        ).use { server ->
            val client = testClient(server.origin)

            val started = client.beginAuthorization() as? MobileAuthorizationStartResult.Started
            assertNotNull(started)
            val pending = requireNotNull(started).pending
            assertEquals(server.origin, pending.origin)
            assertEquals("ABCDEFGH", pending.userCode)
            assertEquals("${server.origin}/device?user_code=ABCD-EFGH", pending.verificationUri)

            assertTrue(client.pollAuthorization(pending) is MobileAuthorizationPollResult.Pending)
            val authorized = client.pollAuthorization(pending) as? MobileAuthorizationPollResult.Authorized
            assertNotNull(authorized)
            assertEquals(server.origin, requireNotNull(authorized).session.origin)
            assertEquals("t".repeat(32), authorized.session.token)
            assertEquals(1_927_857_906_789L, authorized.session.expiresAtEpochMs)

            val requests = server.awaitRequests(4)
            assertEquals("POST", requests[0].method)
            assertEquals("/api/auth/device/code", requests[0].target)
            assertEquals("runway-android/2", requests[0].header("x-runway-client"))
            assertEquals("runway-android", JSONObject(requests[0].body).getString("client_id"))
            assertEquals("runway:mobile", JSONObject(requests[0].body).getString("scope"))
            assertEquals("/api/auth/device/token", requests[1].target)
            assertEquals(
                "urn:ietf:params:oauth:grant-type:device_code",
                JSONObject(requests[1].body).getString("grant_type"),
            )
            assertEquals("/api/auth/get-session", requests[3].target)
        }
    }

    @Test
    fun `mobile mutation sends bearer identity and a stable idempotency key`() {
        LocalHttpServer(
            responses = listOf(Response.json(200, """{"ok":true,"message":"Recorded."}""")),
        ).use { server ->
            val requestId = UUID.fromString("b9b8e4e4-9b9a-4dc6-9c55-4d00a91a9025")
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )

            val result = testClient(server.origin).runAction(
                session = session,
                command = RecordFeedbackCommand(
                    workoutId = "workout-123",
                    status = "done",
                    feltHard = false,
                    pain = false,
                    completedDistanceKm = 5.0,
                ),
                requestId = requestId,
            )

            assertTrue(result is MobileActionResult.Completed)
            val request = server.awaitRequests(1).single()
            assertEquals("POST", request.method)
            assertEquals("/api/mobile/v1/action/record_feedback", request.target)
            assertEquals("Bearer ${session.token}", request.header("authorization"))
            assertEquals(requestId.toString(), request.header("idempotency-key"))
            assertEquals("runway-android/2", request.header("x-runway-client"))
            assertEquals("workout-123", JSONObject(request.body).getString("workoutId"))
        }
    }

    @Test
    fun `uncertain mutation outcome is not presented as a safe rejection`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    409,
                    """{
                      "ok":false,
                      "error":"request_outcome_unknown",
                      "message":"Refresh before deciding whether to try again."
                    }""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )

            val result = testClient(server.origin).runAction(
                session = session,
                command = RecordFeedbackCommand(
                    workoutId = "workout-123",
                    status = "skipped",
                    feltHard = false,
                    pain = false,
                ),
                requestId = UUID.fromString("c03580bb-32a5-4e8b-ac2c-4296c53e651c"),
            )

            assertTrue(result is MobileActionResult.Uncertain)
        }
    }

    @Test
    fun `signed-in Android session creates its own short-lived import pairing code`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{
                      "ok":true,
                      "code":"ABCD-1234-EF56-7890",
                      "expiresAt":"2026-07-28T15:00:00.000Z",
                      "label":"Andrew's phone"
                    }""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )

            val result = testClient(server.origin).createImportPairing(
                session,
                "  Andrew's phone  ",
            )

            assertEquals(
                MobileImportPairingResult.Ready(
                    code = "ABCD-1234-EF56-7890",
                    label = "Andrew's phone",
                ),
                result,
            )
            val request = server.awaitRequests(1).single()
            assertEquals("POST", request.method)
            assertEquals("/api/mobile/v1/android/pairing", request.target)
            assertEquals("Bearer ${session.token}", request.header("authorization"))
            assertEquals("runway-android/2", request.header("x-runway-client"))
            assertEquals("Andrew's phone", JSONObject(request.body).getString("label"))
        }
    }

    @Test
    fun `account operations use the marked route without exposing a target bearer`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    202,
                    """{"ok":true,"message":"If available, instructions will be sent."}""",
                ),
                Response.json(200, """{"ok":true,"message":"Session ended."}"""),
                Response.json(
                    200,
                    """{"ok":true,"accountDeleted":true,"message":"Account deleted."}""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )
            assertTrue(
                testClient(server.origin).requestPasswordReset(session) is
                    MobileAccountOperationResult.Completed,
            )
            assertTrue(
                testClient(server.origin).revokeAccountSession(
                    session,
                    "opaque-session-id",
                ) is MobileAccountOperationResult.Completed,
            )
            val deleted = testClient(server.origin).deleteAccount(session, "DELETE")
                as MobileAccountOperationResult.Completed
            assertTrue(deleted.accountDeleted)

            val requests = server.awaitRequests(3)
            assertEquals(
                "/api/mobile/v1/account/request-password-reset",
                requests[0].target,
            )
            assertEquals("{}", requests[0].body)
            assertFalse(requests[0].headers.containsKey("idempotency-key"))
            assertEquals("/api/mobile/v1/account/revoke-session", requests[1].target)
            assertEquals(
                "opaque-session-id",
                JSONObject(requests[1].body).getString("sessionId"),
            )
            assertFalse(requests[1].body.contains("s".repeat(32)))
            assertEquals("/api/mobile/v1/account/delete-account", requests[2].target)
            assertEquals("DELETE", JSONObject(requests[2].body).getString("confirmation"))
        }
    }

    @Test
    fun `passkey management uses fresh-session account operations without retrying ambiguous changes`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(200, """{"ok":true,"message":"Passkey renamed."}"""),
                Response.json(
                    403,
                    """{"ok":false,"error":"fresh_session_required","message":"Sign out and sign in again."}""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )
            val client = testClient(server.origin)

            assertTrue(
                client.renamePasskey(session, "passkey-id", "  Work laptop  ") is
                    MobileAccountOperationResult.Completed,
            )
            assertTrue(
                client.deletePasskey(session, "passkey-id") is
                    MobileAccountOperationResult.ReauthenticationRequired,
            )
            assertTrue(
                client.renamePasskey(session, "passkey-id", " ") is
                    MobileAccountOperationResult.Rejected,
            )

            val requests = server.awaitRequests(2)
            assertEquals("/api/mobile/v1/account/rename-passkey", requests[0].target)
            assertEquals("passkey-id", JSONObject(requests[0].body).getString("id"))
            assertEquals("Work laptop", JSONObject(requests[0].body).getString("name"))
            assertFalse(requests[0].headers.containsKey("idempotency-key"))
            assertEquals("/api/mobile/v1/account/delete-passkey", requests[1].target)
            assertEquals("passkey-id", JSONObject(requests[1].body).getString("id"))
        }
    }

    @Test
    fun `password change revalidates and returns a marked replacement session`() {
        val replacementToken = "r".repeat(32)
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{"ok":true,"sessionToken":"$replacementToken","message":"Password changed."}""",
                ),
                Response.json(
                    200,
                    """{"session":{"expiresAt":"2031-02-03T04:05:06.789Z","mobileClientId":"runway-android"}}""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )

            val result = testClient(server.origin).changePassword(
                session,
                currentPassword = "current password",
                newPassword = "new password with enough length",
            ) as MobileAccountOperationResult.Completed

            assertEquals(replacementToken, result.replacementSession?.token)
            assertEquals(1_927_857_906_789L, result.replacementSession?.expiresAtEpochMs)
            assertFalse(result.toString().contains(replacementToken))
            val requests = server.awaitRequests(2)
            assertEquals("/api/mobile/v1/account/change-password", requests[0].target)
            assertEquals(
                "current password",
                JSONObject(requests[0].body).getString("currentPassword"),
            )
            assertEquals(
                "new password with enough length",
                JSONObject(requests[0].body).getString("newPassword"),
            )
            assertFalse(requests[0].headers.containsKey("idempotency-key"))
            assertEquals("/api/auth/get-session", requests[1].target)
            assertEquals("Bearer $replacementToken", requests[1].header("authorization"))
        }
    }

    @Test
    fun `authenticator setup validates its uri and withholds recovery codes until verification`() {
        val secret = "JBSWY3DPEHPK3PXP"
        val setupUri = "otpauth://totp/runway%3Arunner?secret=$secret&issuer=runway"
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{"ok":true,"totpUri":"$setupUri","message":"Authenticator setup started."}""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )

            val result = testClient(server.origin).enableTwoFactor(
                session,
                "current password",
            ) as MobileAccountOperationResult.Completed

            assertEquals(setupUri, result.totpSetup?.uri)
            assertEquals(secret, result.totpSetup?.manualSecret)
            assertTrue(result.recoveryCodes.isEmpty())
            assertFalse(result.toString().contains(secret))
            assertFalse(result.totpSetup.toString().contains(secret))
            val request = server.awaitRequests(1).single()
            assertEquals("/api/mobile/v1/account/enable-two-factor", request.target)
            assertEquals("current password", JSONObject(request.body).getString("password"))
        }
    }

    @Test
    fun `verified authenticator setup and disable both revalidate rotated sessions`() {
        val enabledToken = "e".repeat(32)
        val disabledToken = "d".repeat(32)
        val recoveryCodes = listOf("ABCDE-FGHIJ", "KLMNO-PQRST")
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{
                      "ok":true,
                      "sessionToken":"$enabledToken",
                      "recoveryCodes":["${recoveryCodes[0]}","${recoveryCodes[1]}"],
                      "message":"Two-factor authentication enabled."
                    }""",
                ),
                Response.json(
                    200,
                    """{"session":{"expiresAt":"2031-02-03T04:05:06.789Z","mobileClientId":"runway-android"}}""",
                ),
                Response.json(
                    200,
                    """{"ok":true,"sessionToken":"$disabledToken","message":"Two-factor authentication disabled."}""",
                ),
                Response.json(
                    200,
                    """{"session":{"expiresAt":"2031-02-03T04:05:06.789Z","mobileClientId":"runway-android"}}""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )
            val client = testClient(server.origin)

            val verified = client.verifyTwoFactorSetup(session, "123456")
                as MobileAccountOperationResult.Completed
            val disabled = client.disableTwoFactor(session, "current password")
                as MobileAccountOperationResult.Completed

            assertEquals(enabledToken, verified.replacementSession?.token)
            assertEquals(recoveryCodes, verified.recoveryCodes)
            assertFalse(verified.toString().contains(recoveryCodes.first()))
            assertEquals(disabledToken, disabled.replacementSession?.token)
            val requests = server.awaitRequests(4)
            assertEquals("/api/mobile/v1/account/verify-two-factor-setup", requests[0].target)
            assertEquals("123456", JSONObject(requests[0].body).getString("code"))
            assertEquals("Bearer $enabledToken", requests[1].header("authorization"))
            assertEquals("/api/mobile/v1/account/disable-two-factor", requests[2].target)
            assertEquals("current password", JSONObject(requests[2].body).getString("password"))
            assertEquals("Bearer $disabledToken", requests[3].header("authorization"))
        }
    }

    @Test
    fun `recovery-code replacement is password protected redacted and retry safe`() {
        val recoveryCodes = listOf("ABCDE-FGHIJ", "KLMNO-PQRST")
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    200,
                    """{
                      "ok":true,
                      "recoveryCodes":["${recoveryCodes[0]}","${recoveryCodes[1]}"],
                      "message":"Recovery codes replaced."
                    }""",
                ),
                Response.json(
                    200,
                    """{
                      "ok":true,
                      "recoveryCodes":["malformed-provider-value"],
                      "message":"Recovery codes replaced."
                    }""",
                ),
                Response.json(
                    500,
                    """{
                      "ok":false,
                      "error":"recovery_code_generation_failed",
                      "message":"Recovery codes were replaced, but the new set could not be returned safely. Replace them again."
                    }""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )
            val client = testClient(server.origin)

            val replaced = client.regenerateRecoveryCodes(session, "current password")
                as MobileAccountOperationResult.Completed
            val malformed = client.regenerateRecoveryCodes(session, "current password")
                as MobileAccountOperationResult.Rejected
            val retryRequired = client.regenerateRecoveryCodes(session, "current password")
                as MobileAccountOperationResult.Rejected

            assertEquals(recoveryCodes, replaced.recoveryCodes)
            assertEquals(null, replaced.replacementSession)
            assertFalse(replaced.toString().contains(recoveryCodes.first()))
            assertTrue(malformed.message.contains("Replace them again"))
            assertTrue(retryRequired.message.contains("Replace them again"))
            val requests = server.awaitRequests(3)
            requests.forEach { request ->
                assertEquals(
                    "/api/mobile/v1/account/regenerate-recovery-codes",
                    request.target,
                )
                assertEquals("application/json", request.header("content-type"))
                assertEquals(
                    "current password",
                    JSONObject(request.body).getString("password"),
                )
            }
        }
    }

    @Test
    fun `replacement failures and unmarked replacement sessions fail closed`() {
        val unmarkedToken = "u".repeat(32)
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    500,
                    """{
                      "ok":false,
                      "error":"session_replacement_failed",
                      "message":"Sign in again."
                    }""",
                ),
                Response.json(
                    200,
                    """{"ok":true,"sessionToken":"$unmarkedToken","message":"Password changed."}""",
                ),
                Response.json(
                    200,
                    """{"session":{"expiresAt":"2031-02-03T04:05:06.789Z"}}""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )
            val client = testClient(server.origin)

            assertTrue(
                client.disableTwoFactor(session, "current password") is
                    MobileAccountOperationResult.SessionReplacementFailed,
            )
            assertTrue(
                client.changePassword(
                    session,
                    "current password",
                    "new password with enough length",
                ) is MobileAccountOperationResult.SessionReplacementFailed,
            )
            server.awaitRequests(3)
        }
    }

    @Test
    fun `fresh session rejection remains a distinct fail closed result`() {
        LocalHttpServer(
            responses = listOf(
                Response.json(
                    403,
                    """{
                      "ok":false,
                      "error":"fresh_session_required",
                      "message":"Sign out and sign in again."
                    }""",
                ),
            ),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )

            val result = testClient(server.origin).enableTwoFactor(
                session,
                "current password",
            )

            assertTrue(result is MobileAccountOperationResult.ReauthenticationRequired)
            server.awaitRequests(1)
        }
    }

    @Test
    fun `training export streams past the normal json response limit`() {
        val export = """{"payload":"${"x".repeat(2 * 1024 * 1024)}"}"""
        LocalHttpServer(
            responses = listOf(Response.json(200, export)),
        ).use { server ->
            val session = MobileSession(
                origin = server.origin,
                token = "s".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )
            val destination = ByteArrayOutputStream()

            val result = testClient(server.origin).exportTrainingData(session, destination)

            assertTrue(result is MobileAccountOperationResult.Completed)
            assertEquals(export.length, destination.size())
            assertEquals(export, destination.toString(StandardCharsets.UTF_8.name()))
            val request = server.awaitRequests(1).single()
            assertEquals("POST", request.method)
            assertEquals("/api/mobile/v1/account/export", request.target)
            assertEquals("Bearer ${session.token}", request.header("authorization"))
            assertEquals("application/json", request.header("content-type"))
            assertEquals("{}", request.body)
            assertFalse(request.headers.containsKey("idempotency-key"))
        }
    }

    @Test
    fun `server change cannot send a prior instance session to the new origin`() {
        LocalHttpServer(responses = emptyList()).use { newServer ->
            val previousSession = MobileSession(
                origin = "http://127.0.0.1:6553",
                token = "p".repeat(32),
                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
            )

            val result = testClient(newServer.origin).getView(previousSession, "bootstrap")

            assertTrue(result is MobileViewResult.Unauthorized)
            assertFalse(newServer.receivedRequest())
        }
    }

    private data class Response(
        val status: Int,
        val body: String,
        val headers: List<Pair<String, String>> = emptyList(),
    ) {
        companion object {
            fun json(
                status: Int,
                body: String,
                headers: List<Pair<String, String>> = emptyList(),
            ): Response = Response(status, body, headers)
        }
    }

    private fun testClient(origin: String) = MobileApiClient(
        origin = origin,
        allowPrivateCleartextForTests = true,
    )

    private data class CapturedRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /** Minimal loopback server so this contract has no test-only HTTP client dependency. */
    private class LocalHttpServer(responses: List<Response>) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val pendingResponses = ArrayDeque(responses)
        private val requestLatch = CountDownLatch(responses.size)
        private val requests = CopyOnWriteArrayList<CapturedRequest>()
        @Volatile private var running = true
        private val worker = Thread(::serve, "runway-mobile-api-contract").apply { start() }

        val origin: String = "http://127.0.0.1:${socket.localPort}"

        fun awaitRequests(expected: Int): List<CapturedRequest> {
            assertTrue("Timed out waiting for $expected mobile API requests", requestLatch.await(5, TimeUnit.SECONDS))
            assertEquals(expected, requests.size)
            return requests.toList()
        }

        fun receivedRequest(): Boolean = requests.isNotEmpty()

        override fun close() {
            running = false
            socket.close()
            worker.join(2_000)
        }

        private fun serve() {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    return
                }
                client.use(::handle)
            }
        }

        private fun handle(client: Socket) {
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = input.readHttpLine() ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2) return
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = input.readHttpLine() ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bytes = ByteArray(contentLength)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) return
                offset += count
            }
            requests += CapturedRequest(
                method = parts[0],
                target = parts[1],
                headers = headers,
                body = String(bytes, StandardCharsets.UTF_8),
            )
            val response = synchronized(pendingResponses) { pendingResponses.removeFirstOrNull() }
                ?: Response.json(500, "{}").also { requestLatch.countDown() }
            client.getOutputStream().use { output ->
                val payload = response.body.replace("__ORIGIN__", origin).toByteArray(StandardCharsets.UTF_8)
                output.write(
                    "HTTP/1.1 ${response.status} Test\r\n".toByteArray(StandardCharsets.US_ASCII),
                )
                output.write("Content-Type: application/json\r\n".toByteArray(StandardCharsets.US_ASCII))
                for ((name, value) in response.headers) {
                    output.write("$name: $value\r\n".toByteArray(StandardCharsets.US_ASCII))
                }
                output.write("Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.write(payload)
            }
            requestLatch.countDown()
        }

        private fun BufferedInputStream.readHttpLine(): String? {
            val bytes = ArrayList<Byte>()
            while (true) {
                val next = read()
                if (next < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.US_ASCII)
                if (next == '\n'.code) {
                    if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                    return String(bytes.toByteArray(), StandardCharsets.US_ASCII)
                }
                bytes += next.toByte()
            }
        }
    }
}
