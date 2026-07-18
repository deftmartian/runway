package com.deftmartian.runway

internal data class CandidateObservationRecord(
    val contentMarker: String,
    val firstObservedAtEpochMs: Long,
    val suppressUntilEpochMs: Long = 0,
) {
    fun encode(): String = "$contentMarker:$firstObservedAtEpochMs:$suppressUntilEpochMs"

    companion object {
        private val markerPattern = Regex("[A-Za-z0-9_-]{43}")

        fun decode(raw: String?): CandidateObservationRecord? {
            if (raw == null) return null
            val parts = raw.split(':')
            if (parts.size != 3 || !parts[0].matches(markerPattern)) return null
            val firstObservedAt = parts[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val suppressUntil = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            return CandidateObservationRecord(parts[0], firstObservedAt, suppressUntil)
        }
    }
}

internal data class CandidateObservationAssessment(
    val record: CandidateObservationRecord,
    val isStable: Boolean,
)

internal fun assessCandidateObservation(
    previous: CandidateObservationRecord?,
    contentMarker: String,
    observedAtEpochMs: Long,
    settleDurationMs: Long,
): CandidateObservationAssessment {
    require(observedAtEpochMs >= 0)
    require(settleDurationMs > 0)

    val firstObservedAt = if (
        previous?.contentMarker == contentMarker &&
        previous.firstObservedAtEpochMs <= observedAtEpochMs
    ) {
        previous.firstObservedAtEpochMs
    } else {
        observedAtEpochMs
    }
    return CandidateObservationAssessment(
        record = CandidateObservationRecord(contentMarker, firstObservedAt),
        isStable = observedAtEpochMs - firstObservedAt >= settleDurationMs,
    )
}

internal fun metadataRevisionIdentity(
    uri: String,
    sizeBytes: Long?,
    lastModifiedEpochMs: Long?,
): String? {
    if (sizeBytes == null || lastModifiedEpochMs == null) return null
    return "$uri\u0000$sizeBytes\u0000$lastModifiedEpochMs"
}
