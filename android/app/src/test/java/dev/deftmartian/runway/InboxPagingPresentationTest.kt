package dev.deftmartian.runway

import dev.deftmartian.runway.data.LocalInboxHealthConnectCursor
import dev.deftmartian.runway.data.LocalInboxPagingCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxPagingPresentationTest {
    @Test
    fun `appending more than the former inbox window preserves stable unique identities`() {
        var surface: NativeSurface = NativeSurface.Inbox(emptyPayload())

        repeat(1_001) { index ->
            val hasMore = index < 1_000
            surface = appendInboxSurface(
                surface,
                NativeSurface.Inbox(
                    emptyPayload(
                        changes = listOf(change(index)),
                        hasMore = hasMore,
                        nextPage = if (hasMore) cursor(index) else null,
                    ),
                ),
            )
        }

        val payload = (surface as NativeSurface.Inbox).payload!!
        assertEquals((0..1_000).map(::id), payload.healthConnectChanges.map(NativeHealthConnectChange::mappingId))
        assertFalse(payload.hasMore)
        assertEquals(1_001, payload.healthConnectChanges.distinctBy(NativeHealthConnectChange::mappingId).size)
    }

    @Test
    fun `refresh replaces a paged inbox and duplicate page rows do not reappear`() {
        val first = NativeSurface.Inbox(emptyPayload(changes = listOf(change(1)), hasMore = true, nextPage = cursor(1)))
        val duplicate = NativeSurface.Inbox(emptyPayload(changes = listOf(change(1), change(2)), hasMore = false))
        val appended = appendInboxSurface(first, duplicate) as NativeSurface.Inbox
        val refreshed = appendInboxSurface(null, NativeSurface.Inbox(emptyPayload(changes = listOf(change(9))))) as NativeSurface.Inbox

        val appendedPayload = requireNotNull(appended.payload)
        val refreshedPayload = requireNotNull(refreshed.payload)
        assertEquals(listOf(id(1), id(2)), appendedPayload.healthConnectChanges.map(NativeHealthConnectChange::mappingId))
        assertEquals(listOf(id(9)), refreshedPayload.healthConnectChanges.map(NativeHealthConnectChange::mappingId))
        assertTrue(refreshedPayload.nextPage == null)
    }

    @Test
    fun `later inbox pages retain their distinct workout link candidates`() {
        val first = NativeSurface.Inbox(
            emptyPayload(candidates = listOf(workout("workout-1"))),
        )
        val second = NativeSurface.Inbox(
            emptyPayload(candidates = listOf(workout("workout-1"), workout("workout-2"))),
        )

        val appended = appendInboxSurface(first, second) as NativeSurface.Inbox

        assertEquals(
            listOf("workout-1", "workout-2"),
            requireNotNull(appended.payload).candidates.map(NativeWorkout::id),
        )
    }

    private fun emptyPayload(
        candidates: List<NativeWorkout> = emptyList(),
        changes: List<NativeHealthConnectChange> = emptyList(),
        hasMore: Boolean = false,
        nextPage: LocalInboxPagingCursor? = null,
    ) = NativeReviewPayload(candidates, emptyList(), healthConnectChanges = changes, hasMore = hasMore, nextPage = nextPage)

    private fun cursor(index: Int) = LocalInboxPagingCursor(
        healthConnect = LocalInboxHealthConnectCursor(index.toLong(), id(index)),
    )

    private fun change(index: Int) = NativeHealthConnectChange(id(index), "test", id(index), "pending_correction", null, null)
    private fun id(index: Int) = "mapping-${index.toString().padStart(4, '0')}"

    private fun workout(id: String) = NativeWorkout(
        id = id,
        weekId = "week-1",
        weekNumber = 1,
        scheduledDate = "2026-08-12",
        type = "easy",
        status = "planned",
        targetDistanceMeters = 4_000.0,
        targetDurationSeconds = null,
        prescriptionKind = "distance",
        intervalStructure = null,
        intensity = "easy",
        purpose = "Easy run",
        reason = null,
        isRemoved = false,
        isEdited = false,
        adjustment = null,
    )
}
