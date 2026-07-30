package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCalendarDecisionTest {
    @Test
    fun `current decision keeps a completed run visible on a planned rest day`() {
        val summary = nativeCalendarDecisionSummary(
            calendar = calendar(
                workouts = listOf(workout("rest", "2026-07-29", "rest")),
                activities = listOf(activity("extra", "2026-07-29")),
            ),
            nextWorkout = workout("next", "2026-07-31", "easy", purpose = "Easy run"),
        )

        assertEquals("Recorded on a rest day", summary.todayStatus)
        assertEquals("Easy run", summary.nextStatus)
        assertEquals("2026-07-31", summary.nextDate)
    }

    @Test
    fun `current decision points review to the first genuinely missed run`() {
        val summary = nativeCalendarDecisionSummary(
            calendar = calendar(
                workouts = listOf(
                    workout("missed", "2026-07-27", "easy"),
                    workout("recorded", "2026-07-28", "easy"),
                    workout("today", "2026-07-29", "rest"),
                    workout("removed", "2026-07-26", "easy", removed = true),
                ),
                activities = listOf(
                    activity("linked", "2026-07-28", workoutId = "recorded"),
                    activity("extra", "2026-07-29"),
                ),
            ),
            nextWorkout = null,
        )

        assertEquals("Recorded on a rest day", summary.todayStatus)
        assertEquals("No planned run", summary.nextStatus)
        assertEquals(1, summary.reviewCount)
        assertEquals("2026-07-27", summary.reviewDate)
    }

    @Test
    fun `current decision preserves skipped and shortened workout outcomes`() {
        val skipped = nativeCalendarDecisionSummary(
            calendar = calendar(
                workouts = listOf(
                    workout("today", "2026-07-29", "easy", status = "skipped"),
                ),
                feedback = listOf(feedback("today")),
            ),
            nextWorkout = null,
        )
        val shortened = nativeCalendarDecisionSummary(
            calendar = calendar(
                workouts = listOf(
                    workout("today", "2026-07-29", "easy", status = "shortened"),
                ),
                feedback = listOf(feedback("today", completedDistanceMeters = 2_000.0)),
            ),
            nextWorkout = null,
        )

        assertEquals("Skipped — review the next run", skipped.todayStatus)
        assertEquals("Shortened to 2 km", shortened.todayStatus)
    }

    private fun calendar(
        workouts: List<NativeWorkout>,
        activities: List<NativeActivity> = emptyList(),
        feedback: List<NativeWorkoutFeedback> = emptyList(),
    ) = NativeCalendar(
        month = "2026-07",
        today = "2026-07-29",
        previousMonth = "2026-06",
        nextMonth = "2026-08",
        workouts = workouts,
        activities = activities,
        feedback = feedback,
    )

    private fun workout(
        id: String,
        date: String,
        type: String,
        purpose: String? = null,
        removed: Boolean = false,
        status: String = "planned",
    ) = NativeWorkout(
        id = id,
        weekId = null,
        weekNumber = null,
        scheduledDate = date,
        type = type,
        status = status,
        targetDistanceMeters = null,
        targetDurationSeconds = null,
        prescriptionKind = if (type == "rest") "rest" else "distance",
        intervalStructure = null,
        intensity = if (type == "rest") "rest" else "easy",
        purpose = purpose,
        reason = null,
        isRemoved = removed,
        isEdited = false,
        adjustment = null,
    )

    private fun activity(
        id: String,
        date: String,
        workoutId: String? = null,
        reviewState: String = "accepted",
    ) = NativeActivity(
        id = id,
        workoutId = workoutId,
        source = "manual",
        reviewState = reviewState,
        occurredDate = date,
        activityDate = date,
        distanceMeters = 3_000.0,
        durationSeconds = 1_800.0,
        averagePaceSecondsPerKm = null,
        averageHeartRate = null,
        maxHeartRate = null,
        heartRateSummary = null,
        feltHard = false,
        pain = false,
        extraPlanImpactConfirmed = true,
        consequence = null,
        routeSummary = null,
        matchedWorkoutPurpose = null,
        matchedWorkoutDate = null,
    )

    private fun feedback(
        workoutId: String,
        completedDistanceMeters: Double? = null,
    ) = NativeWorkoutFeedback(
        id = "feedback-$workoutId",
        workoutId = workoutId,
        completedDistanceMeters = completedDistanceMeters,
        completedDurationSeconds = null,
        feltHard = false,
        pain = false,
        consequence = null,
        canDelete = true,
    )
}
