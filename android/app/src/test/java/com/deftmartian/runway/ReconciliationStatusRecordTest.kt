package com.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconciliationStatusRecordTest {
    @Test
    fun `status round trips without activity or file details`() {
        val record = ReconciliationStatusRecord("imported", 4, true, 123_456)

        assertEquals(record, ReconciliationStatusRecord.decode(record.encode()))
    }

    @Test
    fun `malformed status is ignored`() {
        assertNull(ReconciliationStatusRecord.decode("imported:-1:0:123"))
        assertNull(ReconciliationStatusRecord.decode("imported:1:maybe:123"))
        assertNull(ReconciliationStatusRecord.decode("IMPORT:1:0:123"))
        assertNull(ReconciliationStatusRecord.decode("imported:1:0:-1"))
    }
}
