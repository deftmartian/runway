package com.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateObservationTest {
    private val markerA = "a".repeat(43)
    private val markerB = "b".repeat(43)

    @Test
    fun `same content must be observed across the full settling interval`() {
        val first = assessCandidateObservation(null, markerA, 1_000, 30_000)
        val early = assessCandidateObservation(first.record, markerA, 30_999, 30_000)
        val settled = assessCandidateObservation(early.record, markerA, 31_000, 30_000)

        assertFalse(first.isStable)
        assertFalse(early.isStable)
        assertTrue(settled.isStable)
        assertEquals(1_000, settled.record.firstObservedAtEpochMs)
    }

    @Test
    fun `content change and clock rollback restart settling`() {
        val previous = CandidateObservationRecord(markerA, 10_000)

        val changed = assessCandidateObservation(previous, markerB, 50_000, 30_000)
        val rollback = assessCandidateObservation(previous, markerA, 9_000, 30_000)

        assertFalse(changed.isStable)
        assertEquals(50_000, changed.record.firstObservedAtEpochMs)
        assertFalse(rollback.isStable)
        assertEquals(9_000, rollback.record.firstObservedAtEpochMs)
    }

    @Test
    fun `record encoding rejects malformed or negative values`() {
        val record = CandidateObservationRecord(markerA, 123, 456)

        assertEquals(record, CandidateObservationRecord.decode(record.encode()))
        assertNull(CandidateObservationRecord.decode("bad:123:456"))
        assertNull(CandidateObservationRecord.decode("$markerA:-1:456"))
        assertNull(CandidateObservationRecord.decode("$markerA:123:-1"))
    }

    @Test
    fun `metadata revision requires size and modification time`() {
        assertEquals(
            "content://provider/file\u0000123\u0000456",
            metadataRevisionIdentity("content://provider/file", 123, 456),
        )
        assertNull(metadataRevisionIdentity("content://provider/file", null, 456))
        assertNull(metadataRevisionIdentity("content://provider/file", 123, null))
    }
}
