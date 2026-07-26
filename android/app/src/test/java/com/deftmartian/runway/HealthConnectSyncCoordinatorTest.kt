package com.deftmartian.runway

import androidx.health.connect.client.HealthConnectFeatures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.Instant

class HealthConnectSyncCoordinatorTest {
    @Test
    fun `initial sync captures cursor before the thirty day read`() {
        val events = mutableListOf<String>()
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override fun hasPermissions() = true
            override fun newChangesToken(): String { events += "token"; return "first" }
            override fun initialRuns(since: Instant): List<HealthConnectRun> {
                events += "initial"
                return listOf(run)
            }
            override fun changes(token: String): HealthConnectBatch {
                events += "changes:$token"
                return HealthConnectBatch(emptyList(), emptyList(), "next", false)
            }
        }
        val payloads = mutableListOf<HealthConnectRequestPayload>()
        val cursor = FakeCursorStore()
        val result = coordinator(gateway, payloads, cursor).sync(connection, credentialState)

        assertEquals(HealthSyncResult.Synced, result)
        assertEquals(listOf("token", "initial", "changes:first"), events)
        assertEquals(listOf("record"), recordIds(payloads))
        assertEquals(listOf("first", "next"), cursor.savedTokens)
    }

    @Test
    fun `more than one hundred baseline records are all delivered exactly once`() {
        val baseline = (0 until 205).map { run.copy(id = "record-$it") }
        val gateway = initialGateway(baseline)
        val payloads = mutableListOf<HealthConnectRequestPayload>()

        assertEquals(
            HealthSyncResult.Synced,
            coordinator(gateway, payloads).sync(connection, credentialState),
        )

        val delivered = recordIds(payloads)
        assertEquals(baseline.map { it.id }, delivered)
        assertEquals(delivered.size, delivered.toSet().size)
        assertTrue(payloads.size >= 3)
        assertTrue(payloads.all { it.changeCount <= 100 })
        assertTrue(payloads.all { it.bytes.size <= MAX_HEALTH_CONNECT_PAYLOAD_BYTES })
    }

    @Test
    fun `maximum heart rate and route records split using exact serialized bytes`() {
        val heartRate = (0 until 600).map { HealthConnectHeartRateSample(it, 120 + (it % 20)) }
        val route = (0 until 600).map {
            HealthConnectRoutePoint(it, 45_000_000 + it, -63_000_000 - it, 2.75)
        }
        val heavyRuns = (0 until 5).map { index ->
            run.copy(
                id = "heavy-$index",
                endEpochMs = run.startEpochMs + 600_000,
                heartRateSamples = heartRate,
                heartRateSourceSampleCount = 600,
                routePoints = route,
                routeSourcePointCount = 600,
            )
        }
        val payloads = mutableListOf<HealthConnectRequestPayload>()

        assertEquals(
            HealthSyncResult.Synced,
            coordinator(initialGateway(heavyRuns), payloads).sync(connection, credentialState),
        )

        assertEquals(heavyRuns.map { it.id }, recordIds(payloads))
        assertTrue("Expected byte-sized chunks, got ${payloads.map { it.bytes.size }}", payloads.size > 1)
        assertTrue(payloads.all { it.bytes.size <= MAX_HEALTH_CONNECT_PAYLOAD_BYTES })
    }

    @Test
    fun `single oversized or invalid record is terminal and does not advance cursor or retry`() {
        var initialReads = 0
        val oversized = run.copy(sourcePackage = "p".repeat(MAX_HEALTH_CONNECT_PAYLOAD_BYTES + 1))
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override fun hasPermissions() = true
            override fun newChangesToken() = "first"
            override fun initialRuns(since: Instant): List<HealthConnectRun> {
                initialReads++
                return listOf(oversized)
            }
            override fun changes(token: String) =
                error("A terminal baseline record must not enter the change loop")
        }
        val cursor = FakeCursorStore()
        val payloads = mutableListOf<HealthConnectRequestPayload>()

        assertEquals(
            HealthSyncResult.NeedsAttention,
            coordinator(gateway, payloads, cursor).sync(connection, credentialState),
        )
        assertEquals(1, initialReads)
        assertTrue(payloads.isEmpty())
        assertTrue(cursor.needsAttention())
        assertTrue(cursor.savedTokens.isEmpty())
    }

    @Test
    fun `cursor is not saved until every exact byte chunk is accepted`() {
        val baseline = (0 until 101).map { run.copy(id = "record-$it") }
        val cursor = FakeCursorStore()
        var attempts = 0
        val coordinator = HealthConnectSyncCoordinator(initialGateway(baseline), { _, _ ->
            attempts++
            if (attempts == 1) HealthConnectApiResult.Accepted else HealthConnectApiResult.Retryable
        }, cursor)

        assertEquals(
            HealthSyncResult.Retryable,
            coordinator.sync(connection, credentialState),
        )
        assertEquals(2, attempts)
        assertTrue(cursor.savedTokens.isEmpty())
    }

    @Test
    fun `stale connection blocks provider reads sends and cursor writes`() {
        val cursor = FakeCursorStore().apply { current = false }
        var tokenReads = 0
        var sends = 0
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override fun hasPermissions() = true
            override fun newChangesToken(): String { tokenReads++; return "first" }
            override fun initialRuns(since: Instant) = listOf(run)
            override fun changes(token: String) = HealthConnectBatch(emptyList(), emptyList(), "next", false)
        }
        val coordinator = HealthConnectSyncCoordinator(gateway, { _, _ ->
            sends++
            HealthConnectApiResult.Accepted
        }, cursor)

        assertEquals(HealthSyncResult.Retryable, coordinator.sync(connection, credentialState))
        assertEquals(0, tokenReads)
        assertEquals(0, sends)
        assertTrue(cursor.savedTokens.isEmpty())
    }

    @Test
    fun `credential becoming stale between chunks blocks the next send and cursor write`() {
        val baseline = (0 until 101).map { run.copy(id = "record-$it") }
        val cursor = FakeCursorStore()
        var sends = 0
        val coordinator = HealthConnectSyncCoordinator(initialGateway(baseline), { _, _ ->
            sends++
            cursor.current = false
            HealthConnectApiResult.Accepted
        }, cursor)

        assertEquals(HealthSyncResult.Retryable, coordinator.sync(connection, credentialState))
        assertEquals(1, sends)
        assertTrue(cursor.savedTokens.isEmpty())
    }

    @Test
    fun `expired cursor gets one bounded rebaseline instead of recursive reset`() {
        var tokens = 0
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override fun hasPermissions() = true
            override fun newChangesToken() = "token-${++tokens}"
            override fun initialRuns(since: Instant) = emptyList<HealthConnectRun>()
            override fun changes(token: String) = HealthConnectBatch(
                emptyList(), emptyList(), token, false, expired = true,
            )
        }
        val result = coordinator(gateway, mutableListOf()).sync(connection, credentialState)

        assertEquals(HealthSyncResult.Retryable, result)
        assertEquals(2, tokens)
    }

    @Test
    fun `unavailable provider and missing permission stop before reads or sends`() {
        listOf(
            HealthConnectAvailability.Unavailable to HealthSyncResult.Unavailable,
            HealthConnectAvailability.Available to HealthSyncResult.PermissionRequired,
        ).forEach { (availability, expected) ->
            var reads = 0
            var sends = 0
            val gateway = object : HealthConnectGateway {
                override fun availability() = availability
                override fun hasPermissions() = false
                override fun newChangesToken(): String { reads++; return "first" }
                override fun initialRuns(since: Instant): List<HealthConnectRun> {
                    reads++
                    return listOf(run)
                }
                override fun changes(token: String): HealthConnectBatch {
                    reads++
                    return HealthConnectBatch(emptyList(), emptyList(), "next", false)
                }
            }
            val coordinator = HealthConnectSyncCoordinator(gateway, { _, _ ->
                sends++
                HealthConnectApiResult.Accepted
            }, FakeCursorStore())

            assertEquals(expected, coordinator.sync(connection, credentialState))
            assertEquals(0, reads)
            assertEquals(0, sends)
        }
    }

    @Test
    fun `provider failures are bounded to one call and permission revocation is actionable`() {
        var providerCalls = 0
        val failedProvider = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override fun hasPermissions() = true
            override fun newChangesToken(): String {
                providerCalls++
                throw IllegalStateException("provider unavailable")
            }
            override fun initialRuns(since: Instant) = emptyList<HealthConnectRun>()
            override fun changes(token: String) = HealthConnectBatch(emptyList(), emptyList(), "next", false)
        }
        assertEquals(
            HealthSyncResult.Retryable,
            coordinator(failedProvider, mutableListOf()).sync(connection, credentialState),
        )
        assertEquals(1, providerCalls)

        var permissionCalls = 0
        val revokedPermission = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override fun hasPermissions() = true
            override fun newChangesToken(): String {
                permissionCalls++
                throw SecurityException("permission revoked")
            }
            override fun initialRuns(since: Instant) = emptyList<HealthConnectRun>()
            override fun changes(token: String) = HealthConnectBatch(emptyList(), emptyList(), "next", false)
        }
        assertEquals(
            HealthSyncResult.PermissionRequired,
            coordinator(revokedPermission, mutableListOf()).sync(connection, credentialState),
        )
        assertEquals(1, permissionCalls)
    }

    @Test
    fun `only running and treadmill sessions are eligible`() {
        assertTrue(HealthConnectRunningPolicy.accepts(56))
        assertTrue(HealthConnectRunningPolicy.accepts(57))
        assertFalse(HealthConnectRunningPolicy.accepts(8))
    }

    @Test
    fun `background work policy only permits available provider feature`() {
        assertTrue(HealthConnectBackgroundPolicy.supported(HealthConnectFeatures.FEATURE_STATUS_AVAILABLE))
        assertFalse(HealthConnectBackgroundPolicy.supported(HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE))
    }

    @Test
    fun `heart rate downsampling keeps first last and peak deterministically`() {
        val samples = (0 until 900).map { HealthConnectHeartRateSample(it, if (it == 451) 220 else 120) }
        val retained = downsampleHeartRate(samples)
        assertEquals(600, retained.size)
        assertEquals(samples.first(), retained.first())
        assertEquals(samples.last(), retained.last())
        assertTrue(retained.contains(samples[451]))
        assertEquals(retained, downsampleHeartRate(samples))
    }

    @Test
    fun `route downsampling keeps connected endpoints deterministically`() {
        val points = (0 until 900).map { HealthConnectRoutePoint(it, it, -it, null) }
        val retained = downsampleRoute(points)
        assertEquals(600, retained.size)
        assertEquals(points.first(), retained.first())
        assertEquals(points.last(), retained.last())
        assertEquals(retained, downsampleRoute(points))
    }

    @Test
    fun `metric-only change re-reads sessions before advancing token`() {
        var initialReads = 0
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override fun hasPermissions() = true
            override fun newChangesToken() = "start"
            override fun initialRuns(since: Instant): List<HealthConnectRun> {
                initialReads++
                return listOf(run)
            }
            override fun changes(token: String) = HealthConnectBatch(
                emptyList(), emptyList(), "done", false, metricChanged = initialReads == 1,
            )
        }
        val payloads = mutableListOf<HealthConnectRequestPayload>()
        assertEquals(
            HealthSyncResult.Synced,
            coordinator(gateway, payloads).sync(connection, credentialState),
        )
        assertEquals(2, initialReads)
        assertEquals(listOf("record", "record"), recordIds(payloads))
    }

    @Test
    fun `metric pagination retains second-page samples for output and source counts`() = runBlocking {
        val samples = collectHealthConnectPages<Int>(readPage = { token ->
            when (token) {
                null -> HealthConnectPage(listOf(120, 125), "second")
                "second" -> HealthConnectPage(listOf(180), null)
                else -> error("unexpected page token: $token")
            }
        })

        // This mirrors the run projection: serialized samples and source count must reflect every
        // provider page, not only the first one.
        val projected = samples.mapIndexed { elapsed, bpm -> HealthConnectHeartRateSample(elapsed, bpm) }
        val output = run.copy(
            heartRateSamples = projected,
            heartRateSourceSampleCount = samples.size,
        )
        assertEquals(listOf(120, 125, 180), projected.map { it.bpm })
        assertEquals(3, output.heartRateSourceSampleCount)
    }

    @Test
    fun `metric pagination rejects an unbounded provider result`() = runBlocking {
        val error = runCatching {
            collectHealthConnectPages<Int>(readPage = { token ->
                if (token == null) HealthConnectPage(listOf(1, 2), "second")
                else HealthConnectPage(listOf(3), null)
            }, maximumRecords = 2)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `status unavailable blocks sync without touching cursor`() {
        val cursor = FakeCursorStore()
        val repository = FakeCredentialRepository(credentialState)
        val result = refreshHealthCredential(repository, credentialState, cursor) {
            DeviceStatusApiResult.Retryable
        }
        assertEquals(HealthCredentialRefresh.Retryable, result)
        assertEquals(0, cursor.clears)
    }

    @Test
    fun `unauthorized status clears the current credential and cursor`() {
        val cursor = FakeCursorStore()
        val repository = FakeCredentialRepository(credentialState)
        val result = refreshHealthCredential(repository, credentialState, cursor) {
            DeviceStatusApiResult.Unauthorized
        }

        assertEquals(HealthCredentialRefresh.PairingRequired, result)
        assertEquals(null, repository.state.credential)
        assertEquals(1, cursor.clears)
    }

    @Test
    fun `changed server generation replaces credential and clears cursor`() {
        val cursor = FakeCursorStore()
        val repository = FakeCredentialRepository(credentialState)
        val result = refreshHealthCredential(repository, credentialState, cursor) {
            DeviceStatusApiResult.Connected(9)
        }
        assertTrue(result is HealthCredentialRefresh.Ready)
        assertEquals(9L, repository.state.credential?.importGeneration)
        assertEquals(1, cursor.clears)
    }

    @Test
    fun `unchanged server generation preserves cursor`() {
        val cursor = FakeCursorStore()
        val repository = FakeCredentialRepository(credentialState)
        val result = refreshHealthCredential(repository, credentialState, cursor) {
            DeviceStatusApiResult.Connected(credentialState.credential!!.importGeneration)
        }
        assertTrue(result is HealthCredentialRefresh.Ready)
        assertEquals(0, cursor.clears)
    }

    @Test
    fun `clearing advanced cursor re-upserts consented route session`() {
        val routeRun = run.copy(
            routePoints = listOf(
                HealthConnectRoutePoint(0, 1, 2, null),
                HealthConnectRoutePoint(1, 3, 4, 2.0),
            ),
            routeSourcePointCount = 2,
        )
        val cursor = FakeCursorStore().apply {
            cursor = HealthConnectCursor(connection.origin, "device", 3, 0, "advanced")
            clear()
        }
        val payloads = mutableListOf<HealthConnectRequestPayload>()
        val gateway = initialGateway(listOf(routeRun))

        assertEquals(
            HealthSyncResult.Synced,
            coordinator(gateway, payloads, cursor).sync(connection, credentialState),
        )
        assertTrue(String(payloads.first().bytes, StandardCharsets.UTF_8).contains("\"routeTrace\""))
    }

    private fun initialGateway(initial: List<HealthConnectRun>) = object : HealthConnectGateway {
        override fun availability() = HealthConnectAvailability.Available
        override fun hasPermissions() = true
        override fun newChangesToken() = "first"
        override fun initialRuns(since: Instant) = initial
        override fun changes(token: String) =
            HealthConnectBatch(emptyList(), emptyList(), "next", false)
    }

    private fun coordinator(
        gateway: HealthConnectGateway,
        sent: MutableList<HealthConnectRequestPayload>,
        cursor: FakeCursorStore = FakeCursorStore(),
    ) = HealthConnectSyncCoordinator(gateway, { _, payload ->
        sent += payload
        HealthConnectApiResult.Accepted
    }, cursor)

    private fun recordIds(payloads: List<HealthConnectRequestPayload>): List<String> =
        payloads.flatMap { payload ->
            UPSERT_RECORD_ID.findAll(String(payload.bytes, StandardCharsets.UTF_8))
                .map { match -> match.groupValues[1] }
                .toList()
        }

    private class FakeCursorStore : HealthConnectCursorRepository {
        var cursor: HealthConnectCursor? = null
        var current = true
        var attention = false
        var clears = 0
        val savedTokens = mutableListOf<String>()

        override fun load() = cursor
        override fun isCurrent(
            connection: ServerConnection,
            credentialState: AndroidCredentialState,
        ) = current
        override fun saveIfCurrent(
            cursor: HealthConnectCursor,
            connection: ServerConnection,
            credentialState: AndroidCredentialState,
        ): Boolean {
            if (!current) return false
            this.cursor = cursor
            savedTokens += cursor.token
            return true
        }
        override fun clear() {
            cursor = null
            clears++
        }
        override fun clearIfCurrent(
            connection: ServerConnection,
            credentialState: AndroidCredentialState,
        ): Boolean {
            if (!current) return false
            cursor = null
            clears++
            return true
        }
        override fun needsAttention() = attention
        override fun markNeedsAttentionIfCurrent(
            connection: ServerConnection,
            credentialState: AndroidCredentialState,
        ): Boolean {
            if (!current) return false
            attention = true
            return true
        }
        override fun clearNeedsAttentionIfCurrent(
            connection: ServerConnection,
            credentialState: AndroidCredentialState,
        ): Boolean {
            if (!current) return false
            attention = false
            return true
        }
    }

    private class FakeCredentialRepository(initial: AndroidCredentialState) : HealthCredentialRepository {
        var state = initial
        override fun currentIf(expected: AndroidCredentialState): AndroidCredential? =
            state.takeIf { it == expected }?.credential
        override fun replace(expected: AndroidCredentialState, credential: AndroidCredential): Boolean {
            if (state != expected) return false
            state = AndroidCredentialState(credential, state.generation + 1)
            return true
        }
        override fun clear(expected: AndroidCredentialState): Boolean {
            if (state != expected) return false
            state = AndroidCredentialState(null, state.generation + 1)
            return true
        }
        override fun snapshot() = state
    }

    private companion object {
        val connection = ServerConnection("https://runway.example", 7)
        val credentialState = AndroidCredentialState(
            AndroidCredential("https://runway.example", "device", "rwy1_token", Long.MAX_VALUE), 3,
        )
        val run = HealthConnectRun(
            id = "record",
            startEpochMs = 1_000,
            endEpochMs = 61_000,
            sourcePackage = "app",
            distanceMeters = 1_000.0,
        )
        val UPSERT_RECORD_ID = Regex("\"op\":\"upsert\",\"recordId\":\"([A-Za-z0-9._:-]+)\"")
    }
}
