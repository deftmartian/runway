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
    fun `planned distances use half kilometre steps`() {
        assertNull(plannedDistanceInputError("0.5"))
        assertNull(plannedDistanceInputError("12.5"))
        assertEquals(
            "Use 0.5 km steps for planned distance.",
            plannedDistanceInputError("12.3"),
        )
        assertEquals(
            "Use a value from 0.5 to 100 km.",
            plannedDistanceInputError("0.1"),
        )
    }

    @Test
    fun `future plan guidance follows the routine phase rather than its editable prescription`() {
        assertEquals(
            "An unusually hard effort is saved with this result. Use Calendar to reduce or postpone the next prescribed run, or change a future week's goal.",
            feedbackPlanChangeMessage("routine"),
        )
        assertEquals(
            "An unusually hard effort can offer a smaller next run. You can also move the next run later in Calendar. Nothing changes until you choose it.",
            feedbackPlanChangeMessage("distance"),
        )
    }

    @Test
    fun `added routine run is prescribed and cannot rebalance the recurring schedule`() {
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
        assertEquals(PrescriptionKind.DISTANCE, mutation.prescriptionKind)
        assertEquals(8_000, mutation.targetDistanceMeters)
        assertEquals(null, mutation.targetDurationSeconds)
        assertEquals("Run with a friend", mutation.purpose)
        assertEquals("Saturday works", mutation.userReason)
        assertFalse(mutation.rebalance)
    }

    @Test
    fun `added routine run can use time`() {
        val mutation = workoutAddMutation(
            scheduledDate = "2026-08-08",
            routine = true,
            type = WorkoutType.EASY,
            distanceKm = null,
            durationMinutes = 45.0,
            routineTargetMetric = "time",
            purpose = "Routine run",
            reason = "",
            rebalance = false,
        )

        assertEquals(PrescriptionKind.TIMED, mutation.prescriptionKind)
        assertEquals(0, mutation.targetDistanceMeters)
        assertEquals(2_700, mutation.targetDurationSeconds)
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
