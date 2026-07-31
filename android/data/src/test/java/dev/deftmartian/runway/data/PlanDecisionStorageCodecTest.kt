package dev.deftmartian.runway.data

import dev.deftmartian.runway.domain.PlanDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanDecisionStorageCodecTest {
    @Test
    fun `released decision values remain stable and round trip`() {
        val expected =
            mapOf(
                PlanDecision.KEEP_PLAN to "keep_plan",
                PlanDecision.REDUCE_NEXT to "reduce_next",
                PlanDecision.NEXT_REST to "next_rest",
                PlanDecision.REPEAT_PRESCRIPTION to "repeat_prescription",
                PlanDecision.REBALANCE_WEEK to "rebalance_week",
            )

        assertEquals(expected, PlanDecision.entries.associateWith { it.toStorageValue() })
        expected.forEach { (decision, stored) ->
            assertEquals(decision, stored.toStoredPlanDecision())
        }
    }

    @Test
    fun `unknown or differently cased decision values are rejected`() {
        assertNull("KEEP_PLAN".toStoredPlanDecision())
        assertNull("unknown".toStoredPlanDecision())
    }
}
