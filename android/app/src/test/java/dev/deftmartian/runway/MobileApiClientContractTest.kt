package dev.deftmartian.runway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
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

            val requests = server.awaitRequests(3)
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

    private data class Response(val status: Int, val body: String) {
        companion object {
            fun json(status: Int, body: String): Response = Response(status, body)
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
