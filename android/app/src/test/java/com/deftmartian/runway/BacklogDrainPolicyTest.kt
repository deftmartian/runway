package com.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BacklogDrainPolicyTest {
    @Test
    fun `backlog consumes one bounded continuation budget`() {
        assertEquals(7, nextBacklogDrainBudget(8, 4))
        assertEquals(1, nextBacklogDrainBudget(2, 1))
    }

    @Test
    fun `empty backlog or exhausted budget stops chaining`() {
        assertNull(nextBacklogDrainBudget(8, 0))
        assertNull(nextBacklogDrainBudget(1, 4))
        assertNull(nextBacklogDrainBudget(0, 4))
    }
}
