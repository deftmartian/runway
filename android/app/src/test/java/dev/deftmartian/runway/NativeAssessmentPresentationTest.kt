package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeAssessmentPresentationTest {
    @Test
    fun `legacy risk storage is never rendered as a medical-sounding raw label`() {
        assertEquals("Unsupported", nativeRampAssessment("unsafe").label)
        assertEquals("Outside default", nativeLoadAssessment("unsafe").label)
        assertFalse(nativeRampAssessment("unsafe").label.contains("unsafe", ignoreCase = true))
    }

    @Test
    fun `recorded outcomes use the consequence rather than raw load arithmetic`() {
        assertEquals("Pain review", nativeConsequenceAssessment("pain_reported", "unsafe").label)
        assertEquals(
            "Recorded as planned",
            nativeConsequenceAssessment("completed_as_planned", "conservative").label,
        )
        assertEquals(
            "Repeated-skip review",
            nativeConsequenceAssessment("repeated_miss", "aggressive").label,
        )
    }

    @Test
    fun `non comparable timed activity is labelled needs review`() {
        assertEquals(
            "Needs review",
            nativeConsequenceAssessment("extra_activity", "moderate", "not_comparable").label,
        )
    }
}
