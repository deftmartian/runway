package com.deftmartian.runway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxCandidatePolicyTest {
    @Test
    fun `accepts a named gpx from common document providers`() {
        assertTrue(GpxCandidatePolicy.isCandidate("morning.GPX", "application/octet-stream", 500))
        assertTrue(GpxCandidatePolicy.isCandidate("morning.gpx", "application/xml", null))
    }

    @Test
    fun `accepts the specific gpx mime type when a provider omits a name`() {
        assertTrue(GpxCandidatePolicy.isCandidate(null, "application/gpx+xml", 500))
    }

    @Test
    fun `rejects unrelated xml and incompatible mime types`() {
        assertFalse(GpxCandidatePolicy.isCandidate("settings.xml", "application/xml", 500))
        assertFalse(GpxCandidatePolicy.isCandidate("route.gpx", "image/jpeg", 500))
    }

    @Test
    fun `rejects empty and oversized files before opening them`() {
        assertFalse(GpxCandidatePolicy.isCandidate("empty.gpx", "application/gpx+xml", 0))
        assertFalse(
            GpxCandidatePolicy.isCandidate(
                "large.gpx",
                "application/gpx+xml",
                GpxCandidatePolicy.MAX_FILE_BYTES + 1,
            ),
        )
    }
}
