package dev.deftmartian.runway

import dev.deftmartian.runway.domain.PrescriptionKind
import dev.deftmartian.runway.domain.SegmentKind
import dev.deftmartian.runway.domain.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MobileCommandMappingTest {
    @Test
    fun `stored workout enum values are parsed explicitly`() {
        assertEquals(WorkoutType.EASY, "easy".toWorkoutTypeOrNull())
        assertEquals(PrescriptionKind.TIMED, "timed".toPrescriptionKindOrNull())
        assertNull("EASY".toWorkoutTypeOrNull())
        assertNull("tempo".toWorkoutTypeOrNull())
        assertNull("laps".toPrescriptionKindOrNull())
    }

    @Test
    fun `workout command preserves its typed workout fields`() {
        val proposal =
            mutation(
                type = WorkoutType.LONG,
                prescriptionKind = PrescriptionKind.DISTANCE,
            ).toWorkoutProposal("week-1")

        assertEquals(WorkoutType.LONG, proposal.type)
        assertEquals(PrescriptionKind.DISTANCE, proposal.prescriptionKind)
    }

    @Test
    fun `timed workout maps run and walk segments without coercion`() {
        val structure =
            TimedIntervalStructureDto(
                warmupSeconds = 120,
                cooldownSeconds = 60,
                blocks =
                    listOf(
                        TimedBlockDto(
                            repetitions = 2,
                            segments =
                                listOf(
                                    TimedSegmentDto(kind = "run", durationSeconds = 180),
                                    TimedSegmentDto(kind = "walk", durationSeconds = 60),
                                ),
                        ),
                    ),
            )

        val proposal =
            mutation(
                type = WorkoutType.EASY,
                prescriptionKind = PrescriptionKind.TIMED,
                intervalStructure = structure,
            ).toWorkoutProposal("week-1")

        assertEquals(
            listOf(SegmentKind.RUN, SegmentKind.WALK),
            proposal.intervalStructure?.blocks?.single()?.segments?.map { it.kind },
        )
    }

    @Test
    fun `unknown timed segment kind is rejected instead of becoming a run`() {
        val structure =
            TimedIntervalStructureDto(
                warmupSeconds = 0,
                cooldownSeconds = 0,
                blocks =
                    listOf(
                        TimedBlockDto(
                            repetitions = 1,
                            segments =
                                listOf(
                                    TimedSegmentDto(kind = "stride", durationSeconds = 30),
                                ),
                        ),
                    ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            mutation(
                type = WorkoutType.EASY,
                prescriptionKind = PrescriptionKind.TIMED,
                intervalStructure = structure,
            ).toWorkoutProposal("week-1")
        }
    }

    private fun mutation(
        type: WorkoutType,
        prescriptionKind: PrescriptionKind,
        intervalStructure: TimedIntervalStructureDto? = null,
    ) = WorkoutMutation(
        scheduledDate = "2026-08-01",
        type = type,
        prescriptionKind = prescriptionKind,
        targetDistanceMeters = 5_000,
        targetDurationSeconds = if (prescriptionKind == PrescriptionKind.TIMED) 600 else null,
        intervalStructure = intervalStructure,
        intensity = "easy",
        purpose = "Aerobic run",
        userReason = "Schedule changed",
        rebalance = false,
    )
}
