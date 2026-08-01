package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeHistoryDetailPresentationTest {
    @Test
    fun `past planned routine slot is not recorded rather than missed`() {
        val workout = NativeHistoryWorkout(
            id = "routine-run",
            status = "planned",
            generated = prescription(),
            current = prescription(prescriptionKind = "distance", distanceMeters = 5_000.0),
            isRemoved = false,
            result = null,
        )

        assertEquals(
            "Not recorded",
            workoutStateLabel(
                workout = workout,
                cutoffDate = "2026-08-02",
                planClosed = false,
                routine = true,
            ),
        )
        assertEquals(
            "Missed",
            workoutStateLabel(
                workout = workout,
                cutoffDate = "2026-08-02",
                planClosed = false,
                routine = false,
            ),
        )
    }

    private fun prescription(
        prescriptionKind: String = "open",
        distanceMeters: Double? = null,
    ) = NativeHistoryPrescription(
        scheduledDate = "2026-08-01",
        type = "easy",
        prescriptionKind = prescriptionKind,
        targetDistanceMeters = distanceMeters,
        targetDurationSeconds = null,
        purpose = "Open run",
    )
}
