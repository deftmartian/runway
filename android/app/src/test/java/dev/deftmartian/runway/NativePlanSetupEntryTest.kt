package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePlanSetupEntryTest {
    @Test
    fun `plan-free progress offers a truthful build-plan entry`() {
        val entry = planSetupEntry(null)

        assertEquals("Next plan", entry.title)
        assertEquals("Build plan", entry.actionLabel)
        assertEquals("Create a new goal and schedule when you are ready.", entry.description)
    }

    @Test
    fun `active plan progress offers a replacement-goal entry`() {
        val entry = planSetupEntry(
            NativePlanHistoryItem(
                plan = NativePlan(
                    id = "plan-1",
                    status = "active",
                    startDate = "2026-07-01",
                    targetDate = "2026-10-01",
                    weeks = 13,
                    risk = "conservative",
                    summaryKind = "distance",
                    completedAt = null,
                    archivedAt = null,
                    lifecycleReason = null,
                ),
                goal = null,
                summary = null,
            ),
        )

        assertEquals("Goal", entry.title)
        assertEquals("Change goal", entry.actionLabel)
        assertEquals(
            "Review a replacement goal. Your current plan is archived only after you confirm it.",
            entry.description,
        )
    }
}
