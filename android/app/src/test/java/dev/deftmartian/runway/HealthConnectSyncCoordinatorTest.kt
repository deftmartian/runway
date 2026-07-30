package dev.deftmartian.runway

import dev.deftmartian.runway.data.healthconnect.HealthConnectObservation
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectOutcome
import dev.deftmartian.runway.data.healthconnect.LocalHealthConnectPersistenceResult
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
            override fun hasPermissions() = true
            override fun newChangesToken(): String { events += "token"; return "initial" }
            override fun initialRuns(since: Instant): List<HealthConnectRun> {
                events += "initial"
                return listOf(run)
            }
            override fun changes(token: String): HealthConnectBatch {
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
            override fun hasPermissions() = true
            override fun newChangesToken() = error("Existing cursor must be reused")
            override fun initialRuns(since: Instant) = error("Existing cursor must be reused")
            override fun changes(token: String) = HealthConnectBatch(listOf(run), emptyList(), "next", hasMore = false)
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
