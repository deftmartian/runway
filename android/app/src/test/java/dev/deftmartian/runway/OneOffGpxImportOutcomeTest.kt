package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneOffGpxImportOutcomeTest {
    @Test
    fun `one off import reports every terminal outcome without exposing document metadata`() {
        val outcomes = OneOffGpxImportOutcome.entries.map(::oneOffGpxStatus)

        assertEquals(OneOffGpxImportOutcome.entries.size, outcomes.distinct().size)
        assertTrue(outcomes.all { it != 0 })
    }
}
