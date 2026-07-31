package dev.deftmartian.runway

import org.junit.Assert.assertEquals
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
}
