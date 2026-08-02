package dev.deftmartian.runway

import dev.deftmartian.runway.domain.PrescriptionKind
import dev.deftmartian.runway.domain.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NativeRunValidationTest {
    @Test
    fun `date validation requires the displayed format`() {
        assertNull(isoDateInputError("2026-07-31"))
        assertEquals(
            "Use a complete date in YYYY-MM-DD format.",
            isoDateInputError("2026-7-31"),
        )
        assertEquals("That date does not exist.", isoDateInputError("2026-02-30"))
    }

    @Test
    fun `optional measurement accepts blank but rejects values outside its boundary`() {
        assertNull(
            boundedRunMeasurementError("", false, 0.1, 100.0, "km"),
        )
        assertNull(
            boundedRunMeasurementError("42.2", false, 0.1, 100.0, "km"),
        )
        assertEquals(
            "Use a value from 0.1 to 100 km.",
            boundedRunMeasurementError("100.1", false, 0.1, 100.0, "km"),
        )
    }

    @Test
    fun `required measurement and purpose explain missing input`() {
        assertEquals(
            "Enter a value in minutes.",
            boundedRunMeasurementError("", true, 10.0, 360.0, "minutes"),
        )
        assertEquals(
            "Enter a value greater than 0 km.",
            positiveRunMeasurementError("", "km"),
        )
        assertNull(positiveRunMeasurementError("100.1", "km"))
        assertEquals(
            "Enter a purpose of 2 to 120 characters.",
            workoutPurposeInputError(" "),
        )
        assertNull(workoutPurposeInputError("Easy run"))
    }

    @Test
    fun `future plan guidance follows the routine phase rather than its editable prescription`() {
        assertEquals(
            "Hard effort and pain are saved in your record. A pain report also updates your running limits; neither changes later routine days.",
            feedbackPlanChangeMessage("routine"),
        )
        assertEquals(
            "These reports can offer conservative next-step options. Nothing changes until you choose and apply it.",
            feedbackPlanChangeMessage("distance"),
        )
    }

    @Test
    fun `added routine run remains open and cannot rebalance the recurring schedule`() {
        val mutation = workoutAddMutation(
            scheduledDate = "2026-08-08",
            routine = true,
            type = WorkoutType.LONG,
            distanceKm = 8.0,
            purpose = "  Run with a friend  ",
            reason = "  Saturday works  ",
            rebalance = true,
        )

        assertEquals(WorkoutType.EASY, mutation.type)
        assertEquals(PrescriptionKind.OPEN, mutation.prescriptionKind)
        assertEquals(0, mutation.targetDistanceMeters)
        assertEquals(null, mutation.targetDurationSeconds)
        assertEquals("Run with a friend", mutation.purpose)
        assertEquals("Saturday works", mutation.userReason)
        assertFalse(mutation.rebalance)
    }

    @Test
    fun `added prescribed run keeps the selected distance and rebalance choice`() {
        val mutation = workoutAddMutation(
            scheduledDate = "2026-08-08",
            routine = false,
            type = WorkoutType.RECOVERY,
            distanceKm = 4.5,
            purpose = "Recovery run",
            reason = "",
            rebalance = true,
        )

        assertEquals(WorkoutType.RECOVERY, mutation.type)
        assertEquals(PrescriptionKind.DISTANCE, mutation.prescriptionKind)
        assertEquals(4_500, mutation.targetDistanceMeters)
        assertEquals(true, mutation.rebalance)
    }
}
