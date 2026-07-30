package dev.deftmartian.runway

import dev.deftmartian.runway.data.healthconnect.HealthConnectObservation
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectOutcome
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectPersistenceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HealthConnectSyncCoordinatorTest {
    @Test
    fun `bootstrap captures token before bounded import and persists observations locally`() = runBlocking {
        val events = mutableListOf<String>()
        val observations = mutableListOf<HealthConnectObservation>()
        val cursor = FakeCursorStore()
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override suspend fun hasPermissions() = true
            override suspend fun newChangesToken(): String { events += "token"; return "initial" }
            override suspend fun initialRuns(since: Instant): List<HealthConnectRun> {
                events += "initial"
                return listOf(run)
            }
            override suspend fun changes(token: String): HealthConnectBatch {
                events += "changes:$token"
                return HealthConnectBatch(emptyList(), emptyList(), "next", hasMore = false)
            }
        }

        val result = HealthConnectSyncCoordinator(
            gateway = gateway,
            cursor = cursor,
            reconcile = { _, observation ->
                observations += observation
                LocalHealthConnectPersistenceResult.Applied(
                    LocalHealthConnectOutcome.Unchanged(observation.recordId),
                )
            },
        ).sync()

        assertEquals(HealthSyncResult.Synced, result)
        assertEquals(listOf("token", "initial", "changes:initial"), events)
        assertEquals(listOf("record"), observations.map { it.recordId })
        assertEquals("next", cursor.load()?.token)
        assertFalse(cursor.needsAttention())
    }

    @Test
    fun `failed local persistence keeps cursor at prior token for a safe rerun`() = runBlocking {
        val cursor = FakeCursorStore(HealthConnectCursor("prior"))
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override suspend fun hasPermissions() = true
            override suspend fun newChangesToken() = error("Existing cursor must be reused")
            override suspend fun initialRuns(since: Instant) = error("Existing cursor must be reused")
            override suspend fun changes(token: String) =
                HealthConnectBatch(listOf(run), emptyList(), "next", hasMore = false)
        }

        val result = HealthConnectSyncCoordinator(
            gateway = gateway,
            cursor = cursor,
            reconcile = { _, _ -> LocalHealthConnectPersistenceResult.ProfileNotConfigured },
        ).sync()

        assertEquals(HealthSyncResult.NeedsAttention, result)
        assertEquals("prior", cursor.load()?.token)
        assertTrue(cursor.needsAttention())
    }

    @Test
    fun `provider cancellation is not converted into a retry result`() = runBlocking {
        val cursor = FakeCursorStore(HealthConnectCursor("prior"))
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override suspend fun hasPermissions() = true
            override suspend fun newChangesToken() = error("Existing cursor must be reused")
            override suspend fun initialRuns(since: Instant) = error("Existing cursor must be reused")
            override suspend fun changes(token: String): HealthConnectBatch =
                throw CancellationException("stopped")
        }

        val failure = runCatching {
            HealthConnectSyncCoordinator(
                gateway = gateway,
                cursor = cursor,
                reconcile = { _, _ -> error("No observation should be reconciled") },
            ).sync()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals("prior", cursor.load()?.token)
    }

    @Test
    fun `persistence cancellation is not converted into needs attention`() = runBlocking {
        val cursor = FakeCursorStore(HealthConnectCursor("prior"))
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override suspend fun hasPermissions() = true
            override suspend fun newChangesToken() = error("Existing cursor must be reused")
            override suspend fun initialRuns(since: Instant) = error("Existing cursor must be reused")
            override suspend fun changes(token: String) =
                HealthConnectBatch(listOf(run), emptyList(), "next", hasMore = false)
        }

        val failure = runCatching {
            HealthConnectSyncCoordinator(
                gateway = gateway,
                cursor = cursor,
                reconcile = { _, _ -> throw CancellationException("stopped") },
            ).sync()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals("prior", cursor.load()?.token)
        assertFalse(cursor.needsAttention())
    }

    @Test
    fun `multiple metric change pages refresh the bounded session window only once`() = runBlocking {
        val events = mutableListOf<String>()
        val cursor = FakeCursorStore(HealthConnectCursor("prior"))
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override suspend fun hasPermissions() = true
            override suspend fun newChangesToken() = error("Existing cursor must be reused")
            override suspend fun initialRuns(since: Instant): List<HealthConnectRun> {
                events += "initial"
                return listOf(run)
            }
            override suspend fun changes(token: String): HealthConnectBatch {
                events += "changes:$token"
                return if (token == "prior") {
                    HealthConnectBatch(
                        emptyList(),
                        emptyList(),
                        "middle",
                        hasMore = true,
                        metricChanged = true,
                    )
                } else {
                    HealthConnectBatch(
                        emptyList(),
                        emptyList(),
                        "final",
                        hasMore = false,
                        metricChanged = true,
                    )
                }
            }
        }

        val result = HealthConnectSyncCoordinator(
            gateway = gateway,
            cursor = cursor,
            reconcile = { _, observation ->
                LocalHealthConnectPersistenceResult.Applied(
                    LocalHealthConnectOutcome.Unchanged(observation.recordId),
                )
            },
        ).sync()

        assertEquals(HealthSyncResult.Synced, result)
        assertEquals(listOf("changes:prior", "initial", "changes:middle"), events)
        assertEquals("final", cursor.load()?.token)
    }

    @Test
    fun `source limit failure stops cursor progress and requests attention`() = runBlocking {
        val cursor = FakeCursorStore(HealthConnectCursor("prior"))
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override suspend fun hasPermissions() = true
            override suspend fun newChangesToken() = error("Existing cursor must be reused")
            override suspend fun initialRuns(since: Instant) = error("Existing cursor must be reused")
            override suspend fun changes(token: String): HealthConnectBatch =
                throw HealthConnectSourceLimitException("bounded")
        }

        val result = HealthConnectSyncCoordinator(
            gateway = gateway,
            cursor = cursor,
            reconcile = { _, _ -> error("No observation should be reconciled") },
        ).sync()

        assertEquals(HealthSyncResult.NeedsAttention, result)
        assertEquals("prior", cursor.load()?.token)
        assertTrue(cursor.needsAttention())
    }

    @Test
    fun `repeated change token stops before replaying a provider page`() = runBlocking {
        val cursor = FakeCursorStore(HealthConnectCursor("prior"))
        var reconciliations = 0
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override suspend fun hasPermissions() = true
            override suspend fun newChangesToken() = error("Existing cursor must be reused")
            override suspend fun initialRuns(since: Instant) = error("Existing cursor must be reused")
            override suspend fun changes(token: String) = HealthConnectBatch(
                upserts = listOf(run),
                deletes = emptyList(),
                nextToken = token,
                hasMore = true,
            )
        }

        val result = HealthConnectSyncCoordinator(
            gateway = gateway,
            cursor = cursor,
            reconcile = { _, observation ->
                reconciliations += 1
                LocalHealthConnectPersistenceResult.Applied(
                    LocalHealthConnectOutcome.Unchanged(observation.recordId),
                )
            },
        ).sync()

        assertEquals(HealthSyncResult.NeedsAttention, result)
        assertEquals(0, reconciliations)
        assertEquals("prior", cursor.load()?.token)
        assertTrue(cursor.needsAttention())
    }

    @Test
    fun `change page and record caps preserve the last committed cursor`() = runBlocking {
        val pageCursor = FakeCursorStore(HealthConnectCursor("prior"))
        val recordCursor = FakeCursorStore(HealthConnectCursor("prior"))
        val gateway = object : HealthConnectGateway {
            override fun availability() = HealthConnectAvailability.Available
            override suspend fun hasPermissions() = true
            override suspend fun newChangesToken() = error("Existing cursor must be reused")
            override suspend fun initialRuns(since: Instant) = error("Existing cursor must be reused")
            override suspend fun changes(token: String) = HealthConnectBatch(
                upserts = emptyList(),
                deletes = emptyList(),
                nextToken = if (token == "prior") "middle" else "final",
                hasMore = true,
                sourceChangeCount = 1,
            )
        }

        val pageResult = HealthConnectSyncCoordinator(
            gateway = gateway,
            cursor = pageCursor,
            reconcile = { _, _ -> error("No observation should be reconciled") },
            maximumChangePages = 1,
            maximumChanges = 10,
        ).sync()
        val recordResult = HealthConnectSyncCoordinator(
            gateway = gateway,
            cursor = recordCursor,
            reconcile = { _, _ -> error("No observation should be reconciled") },
            maximumChangePages = 10,
            maximumChanges = 1,
        ).sync()

        assertEquals(HealthSyncResult.NeedsAttention, pageResult)
        assertEquals("middle", pageCursor.load()?.token)
        assertTrue(pageCursor.needsAttention())
        assertEquals(HealthSyncResult.NeedsAttention, recordResult)
        assertEquals("middle", recordCursor.load()?.token)
        assertTrue(recordCursor.needsAttention())
    }

    @Test
    fun `page collector rejects repeated tokens and record overflow`() = runBlocking {
        var calls = 0
        val repeatedToken = runCatching {
            collectHealthConnectPages(
                maximumRecords = 10,
                readPage = {
                    calls += 1
                    HealthConnectPage(listOf(calls), "same")
                },
            )
        }.exceptionOrNull()
        val overflow = runCatching {
            collectHealthConnectPages(
                maximumRecords = 1,
                readPage = { HealthConnectPage(listOf(1, 2), null) },
            )
        }.exceptionOrNull()

        assertTrue(repeatedToken is HealthConnectSourceLimitException)
        assertEquals(2, calls)
        assertTrue(overflow is HealthConnectSourceLimitException)
    }

    private class FakeCursorStore(initial: HealthConnectCursor? = null) : HealthConnectCursorRepository {
        private var cursor = initial
        private var attention = false
        override fun load(): HealthConnectCursor? = cursor
        override fun save(cursor: HealthConnectCursor) { this.cursor = cursor }
        override fun clear() { cursor = null }
        override fun needsAttention(): Boolean = attention
        override fun markNeedsAttention() { attention = true }
        override fun clearNeedsAttention() { attention = false }
    }

    private companion object {
        val run = HealthConnectRun(
            id = "record",
            startEpochMs = 1_700_000_000_000,
            endEpochMs = 1_700_000_600_000,
            sourcePackage = "example.tracker",
            distanceMeters = 5_000.0,
        )
    }
}
