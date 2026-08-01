package dev.deftmartian.runway

import dev.deftmartian.runway.domain.EditPreview
import dev.deftmartian.runway.domain.PrescriptionKind
import dev.deftmartian.runway.domain.Risk
import dev.deftmartian.runway.domain.WeekLoad
import dev.deftmartian.runway.domain.WorkoutProposal
import dev.deftmartian.runway.domain.WorkoutType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkoutChangePresentationTest {
    @Test
    fun `local workout preview keeps exact risk and affected weekly loads`() {
        val current = WorkoutProposal("week-1", LocalDate.parse("2026-08-03"), WorkoutType.EASY, PrescriptionKind.DISTANCE, 5_000, purpose = "Easy run")
        val proposed = current.copy(targetDistanceMeters = 6_000)
        val display = EditPreview(
            operation = "edit", recommended = current, current = current, proposed = proposed,
            workoutChanges = emptyList(), weekLoads = mapOf("week-1" to (WeekLoad(15_000, 0) to WeekLoad(16_000, 0))),
            spacingConflicts = emptyList(), affectedFutureWorkoutIds = emptyList(), weeklyLoadChangePercent = 6.7,
            projectedRampPercent = 6.7, projectedRampRisk = Risk.CONSERVATIVE, prescriptionBasisChanged = false,
            risk = Risk.CONSERVATIVE, requiresConfirmation = false,
        ).toLocalWorkoutChangeDisplay()

        assertEquals("edit", display.operation)
        assertEquals(Risk.CONSERVATIVE, display.risk)
        assertEquals(15_000, display.weeks.single().distanceBefore)
        assertEquals(16_000, display.weeks.single().distanceAfter)
        assertTrue(display.recommended === current)
    }
}
