package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeUiModelHelpersTest {
    @Test
    fun `interval resizing preserves structure and exact requested duration`() {
        val source = TimedIntervalStructureDto(
            warmupSeconds = 300,
            cooldownSeconds = 300,
            blocks = listOf(
                TimedBlockDto(
                    repetitions = 2,
                    segments = listOf(
                        TimedSegmentDto("run", 180),
                        TimedSegmentDto("walk", 60),
                    ),
                ),
            ),
        )

        val resized = resizeIntervalStructure(source, 1_800)

        assertEquals(listOf("run", "walk"), resized.blocks.single().segments.map { it.kind })
        assertEquals(1_800, totalSeconds(resized))
    }

    @Test
    fun `missing interval structure becomes one bounded run segment`() {
        val resized = resizeIntervalStructure(null, 900)

        assertEquals(0, resized.warmupSeconds)
        assertEquals(0, resized.cooldownSeconds)
        assertEquals(1, resized.blocks.single().repetitions)
        assertEquals("run", resized.blocks.single().segments.single().kind)
        assertEquals(900, resized.blocks.single().segments.single().durationSeconds)
        assertEquals(900, totalSeconds(resized))
    }

    @Test
    fun `calendar places an unplanned run beside its rest prescription without hiding either`() {
        val rest = workout(id = "rest-1", date = "2026-07-29", type = "rest")
        val run = activity(id = "activity-1", date = "2026-07-29")

        val placement = placeCalendarActivities(listOf(rest), listOf(run))

        assertEquals(listOf("activity-1"), placement.byWorkoutId["rest-1"]?.map { it.id })
        assertEquals(emptyList<NativeActivity>(), placement.unplaced)
    }

    @Test
    fun `calendar honors an explicit workout link before same-day placement`() {
        val rest = workout(id = "rest-1", date = "2026-07-29", type = "rest")
        val planned = workout(id = "run-1", date = "2026-07-29", type = "easy")
        val run = activity(id = "activity-1", date = "2026-07-29", workoutId = "run-1")

        val placement = placeCalendarActivities(listOf(rest, planned), listOf(run))

        assertEquals(listOf("activity-1"), placement.byWorkoutId["run-1"]?.map { it.id })
        assertEquals(null, placement.byWorkoutId["rest-1"])
    }

    private fun workout(id: String, date: String, type: String) = NativeWorkout(
        id = id,
        weekId = null,
        weekNumber = null,
        scheduledDate = date,
        type = type,
        status = "planned",
        targetDistanceMeters = null,
        targetDurationSeconds = null,
        prescriptionKind = if (type == "rest") "rest" else "distance",
        intervalStructure = null,
        intensity = if (type == "rest") "rest" else "easy",
        purpose = null,
        reason = null,
        isRemoved = false,
        isEdited = false,
        adjustment = null,
    )

    private fun activity(
        id: String,
        date: String,
        workoutId: String? = null,
    ) = NativeActivity(
        id = id,
        workoutId = workoutId,
        source = "manual",
        reviewState = "accepted",
        occurredDate = date,
        activityDate = date,
        distanceMeters = 3_000.0,
        durationSeconds = 1_800.0,
        averagePaceSecondsPerKm = null,
        averageHeartRate = null,
        maxHeartRate = null,
        feltHard = false,
        pain = false,
        extraPlanImpactConfirmed = true,
        consequence = null,
        matchedWorkoutPurpose = null,
        matchedWorkoutDate = null,
        healthConnect = null,
    )

    private fun totalSeconds(structure: TimedIntervalStructureDto): Int =
        (structure.warmupSeconds ?: 0) +
            (structure.cooldownSeconds ?: 0) +
            structure.blocks.sumOf { block ->
                (block.repetitions ?: 1) *
                    block.segments.sumOf { segment -> segment.durationSeconds ?: 0 }
            }
}
