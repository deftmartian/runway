package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeCalendarDecisionTest {
    @Test
    fun `open routine run uses one durable calendar label`() {
        val next = workout(
            "routine-next",
            "2026-07-31",
            "easy",
            purpose = "Open run",
            planPhase = "routine",
        ).copy(
            prescriptionKind = "open",
            targetDistanceMeters = null,
        )

        assertEquals("Open run", calendarWorkoutPlanSummary(next))
    }

    @Test
    fun `compact calendar rows keep the full workout and explain empty and rest days`() {
        val planned = calendarLedgerDayPresentation(
            workouts = listOf(
                workout(
                    id = "planned",
                    date = "2026-07-29",
                    type = "easy",
                    purpose = "Long easy run",
                    distanceMeters = 6_000.0,
                ),
            ),
            activities = emptyList(),
            feedbackByWorkout = emptyMap(),
        )
        val recovery = calendarLedgerDayPresentation(
            workouts = listOf(workout("rest", "2026-07-29", "rest")),
            activities = emptyList(),
            feedbackByWorkout = emptyMap(),
        )
        val unscheduled = calendarLedgerDayPresentation(
            workouts = emptyList(),
            activities = emptyList(),
            feedbackByWorkout = emptyMap(),
        )

        assertEquals("Long easy run · 6 km", planned.title)
        assertEquals("Planned", planned.detail)
        assertEquals("No run planned", recovery.title)
        assertEquals(null, recovery.detail)
        assertEquals("No run planned", unscheduled.title)
        assertEquals(null, unscheduled.detail)
    }

    @Test
    fun `compact calendar summarizes every result on a multi run day`() {
        val first = workout("first", "2026-07-29", "easy", purpose = "Easy run")
        val second = workout("second", "2026-07-29", "easy", purpose = "Evening run")
        fun presentation(secondState: String) = calendarLedgerDayPresentation(
            workouts = listOf(first, second),
            activities = emptyList(),
            feedbackByWorkout = mapOf(
                "first" to feedback("first", completionState = "done"),
                "second" to feedback("second", completionState = secondState),
            ),
        )

        assertEquals("2 run results", presentation("skipped").title)
        assertEquals("1 completed · 1 skipped", presentation("skipped").detail)
        assertEquals("1 completed · 1 shortened", presentation("shortened").detail)
    }

    @Test
    fun `compact calendar describes the exact activity awaiting review`() {
        val accepted = activity("accepted", "2026-07-29").copy(
            matchedWorkoutPurpose = "Morning run",
        )
        val review = activity("review", "2026-07-29", reviewState = "review").copy(
            distanceMeters = 5_000.0,
            matchedWorkoutPurpose = "Evening run",
        )
        val presentation = calendarLedgerDayPresentation(
            workouts = emptyList(),
            activities = listOf(accepted, review),
            feedbackByWorkout = emptyMap(),
        )

        assertEquals("Evening run", presentation.title)
        assertEquals("Needs review · 5 km · 30 min", presentation.detail)
    }

    @Test
    fun `activity link candidates are limited to the persisted three day window and sorted nearby first`() {
        val candidates = activityLinkCandidates(
            activity("import", "2026-07-29", reviewState = "review"),
            listOf(
                workout("after-window", "2026-08-02", "easy"),
                workout("three-days-before", "2026-07-26", "easy"),
                workout("same-date-b", "2026-07-28", "easy"),
                workout("same-date-a", "2026-07-28", "easy"),
                workout("one-day-before", "2026-07-28", "easy"),
                workout("three-days-after", "2026-08-01", "easy"),
            ),
        )

        assertEquals(
            listOf(
                "one-day-before",
                "same-date-a",
                "same-date-b",
                "three-days-before",
                "three-days-after",
            ),
            candidates.map(NativeWorkout::id),
        )
    }

    @Test
    fun `accepted activity pain does not keep a resolved day in review`() {
        val presentation = calendarDayPresentation(
            workouts = emptyList(),
            activities = listOf(activity("accepted", "2026-07-29").copy(pain = true)),
            feedbackByWorkout = emptyMap(),
        )

        assertEquals(CalendarCellEmphasis.Actual, presentation.emphasis)
        assertEquals("recorded", presentation.stateDescription)
    }

    @Test
    fun `calendar feedback states remain distinct`() {
        val workout = workout("workout", "2026-07-29", "easy")
        fun presentation(state: String) = calendarDayPresentation(
            workouts = listOf(workout),
            activities = emptyList(),
            feedbackByWorkout = mapOf("workout" to feedback("workout", completionState = state)),
        )

        assertEquals("✓ Done", presentation("done").label)
        assertEquals("↘ Short", presentation("shortened").label)
        assertEquals("— Skipped", presentation("skipped").label)
    }

    @Test
    fun `past routine run is factual and not review debt even after an individual edit`() {
        val open = workout("routine", "2026-07-28", "easy").copy(
            prescriptionKind = "distance",
            targetDistanceMeters = 5_000.0,
            targetDurationSeconds = null,
            isEdited = true,
            planPhase = "routine",
        )
        val presentation = calendarDayPresentation(
            workouts = listOf(open),
            activities = emptyList(),
            feedbackByWorkout = emptyMap(),
            routineDateIsPast = true,
        )
        assertEquals("— Not recorded", presentation.label)
        assertEquals(CalendarCellEmphasis.Neutral, presentation.emphasis)
    }

    @Test
    fun `unapplied consequence keeps a resolved activity actionable`() {
        val presentation = calendarDayPresentation(
            workouts = emptyList(),
            activities = listOf(
                activity("accepted", "2026-07-29").copy(
                    consequence = NativeConsequence(
                        kind = null,
                        appliedDecision = null,
                        recommendedDecision = "reduce_next",
                        deviation = "short",
                        risk = null,
                        planChangeAvailable = true,
                        options = emptyList(),
                        comparisonStatus = null,
                    ),
                ),
            ),
            feedbackByWorkout = emptyMap(),
        )

        assertEquals(CalendarCellEmphasis.Review, presentation.emphasis)
    }

    private fun workout(
        id: String,
        date: String,
        type: String,
        purpose: String? = null,
        distanceMeters: Double? = null,
        planPhase: String? = null,
    ) = NativeWorkout(
        id = id,
        weekId = null,
        weekNumber = null,
        scheduledDate = date,
        type = type,
        status = "planned",
        targetDistanceMeters = distanceMeters,
        targetDurationSeconds = null,
        prescriptionKind = if (type == "rest") "rest" else "distance",
        intervalStructure = null,
        intensity = if (type == "rest") "rest" else "easy",
        purpose = purpose,
        reason = null,
        isRemoved = false,
        isEdited = false,
        adjustment = null,
        planPhase = planPhase,
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
        completionState: String = "done",
    ) = NativeWorkoutFeedback(
        id = "feedback-$workoutId",
        workoutId = workoutId,
        completedDistanceMeters = completedDistanceMeters,
        completedDurationSeconds = null,
        feltHard = false,
        pain = false,
        consequence = null,
        canDelete = true,
        completionState = completionState,
    )
}
