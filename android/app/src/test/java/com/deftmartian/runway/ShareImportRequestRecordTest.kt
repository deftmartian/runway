package com.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShareImportRequestRecordTest {
    private val digest = "a".repeat(64)

    @Test
    fun `resend and recreated activity reuse a durable request id`() {
        val first = ShareImportRequestRecord.reuseOrCreate(
            encoded = null,
            nowEpochMs = 1_000L,
            newRequestId = { "00000000-0000-4000-8000-000000000001" },
        )

        val resent = ShareImportRequestRecord.reuseOrCreate(
            encoded = first.encode(),
            nowEpochMs = 2_000L,
            newRequestId = { "00000000-0000-4000-8000-000000000002" },
        )

        assertEquals(first, resent)
    }

    @Test
    fun `expired or malformed retained request creates a new id`() {
        val original = ShareImportRequestRecord(
            requestId = "00000000-0000-4000-8000-000000000001",
            createdAtEpochMs = 1_000L,
        )
        val afterExpiry = ShareImportRequestRecord.reuseOrCreate(
            encoded = original.encode(),
            nowEpochMs = 8L * 24L * 60L * 60L * 1000L,
            newRequestId = { "00000000-0000-4000-8000-000000000002" },
        )
        val malformed = ShareImportRequestRecord.reuseOrCreate(
            encoded = "not-a-record",
            nowEpochMs = 2_000L,
            newRequestId = { "00000000-0000-4000-8000-000000000003" },
        )

        assertNotEquals(original.requestId, afterExpiry.requestId)
        assertEquals("00000000-0000-4000-8000-000000000003", malformed.requestId)
    }

    @Test
    fun `request identity is scoped to origin device and content`() {
        val first = shareImportRequestKey("https://one.example", "device-a", digest)

        assertNotEquals(first, shareImportRequestKey("https://two.example", "device-a", digest))
        assertNotEquals(first, shareImportRequestKey("https://one.example", "device-b", digest))
        assertNotEquals(first, shareImportRequestKey("https://one.example", "device-a", "b".repeat(64)))
    }
}
