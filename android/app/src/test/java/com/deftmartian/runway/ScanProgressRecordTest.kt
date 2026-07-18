package com.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanProgressRecordTest {
    private val fingerprint = "a".repeat(64)

    @Test
    fun `round trips a valid continuation`() {
        val record = ScanProgressRecord(fingerprint, 4_000)

        assertEquals(record, ScanProgressRecord.decode(record.encode()))
    }

    @Test
    fun `rejects malformed or negative continuations`() {
        assertNull(ScanProgressRecord.decode(null))
        assertNull(ScanProgressRecord.decode("not-a-record"))
        assertNull(ScanProgressRecord.decode("short:2000"))
        assertNull(ScanProgressRecord.decode("$fingerprint:-1"))
    }
}
