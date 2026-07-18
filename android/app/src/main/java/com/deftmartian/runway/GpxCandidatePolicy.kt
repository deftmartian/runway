package com.deftmartian.runway

import java.util.Locale

object GpxCandidatePolicy {
    const val MAX_FILE_BYTES: Long = 10L * 1024L * 1024L

    private val compatibleMimeTypes = setOf(
        "application/gpx+xml",
        "application/x-gpx+xml",
        "application/xml",
        "text/xml",
        "application/octet-stream",
    )

    fun isCandidate(displayName: String?, mimeType: String?, sizeBytes: Long?): Boolean {
        if (sizeBytes != null && sizeBytes !in 1..MAX_FILE_BYTES) return false

        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        val hasGpxName = displayName
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.endsWith(".gpx") == true

        if (normalizedMime == "application/gpx+xml" || normalizedMime == "application/x-gpx+xml") {
            return true
        }
        return hasGpxName && (normalizedMime == null || normalizedMime in compatibleMimeTypes)
    }
}
