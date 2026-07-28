package dev.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewLoadRequestGateTest {
    @Test
    fun `late calendar response cannot replace a newer month`() {
        val gate = ViewLoadRequestGate()
        val later = gate.begin("Calendar", "?month=2026-08")
        val earlier = gate.begin("Calendar", "?month=2026-06")

        assertFalse(gate.isCurrent(later, "Calendar", "?month=2026-06"))
        assertTrue(gate.isCurrent(earlier, "Calendar", "?month=2026-06"))
    }

    @Test
    fun `navigation and mutation invalidation reject prior responses`() {
        val gate = ViewLoadRequestGate()
        val request = gate.begin("Review", "")

        gate.invalidate()

        assertFalse(gate.isCurrent(request, "Review", ""))
    }
}
