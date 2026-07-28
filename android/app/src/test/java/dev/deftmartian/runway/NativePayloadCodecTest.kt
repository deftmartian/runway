package dev.deftmartian.runway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePayloadCodecTest {
    @Test
    fun `calendar decoder preserves nullable values and ignores malformed array entries`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeView("calendar", """
            {"onboardingRequired":false,"calendar":{"month":"2026-07","today":"2026-07-28",
            "workouts":[{"id":"w-1","isRemoved":false,"targetDistanceMeters":5000},17],
            "feedback":[{"workoutId":"w-1","pain":false,"consequence":{"options":["keep_plan",null]}}]}}
        """)) as NativeCalendarPayload

        assertEquals(false, decoded.onboardingRequired)
        assertEquals("2026-07", decoded.calendar?.month)
        assertEquals(1, decoded.calendar?.workouts?.size)
        assertEquals(false, decoded.calendar?.workouts?.single()?.isRemoved)
        assertEquals(5_000.0, decoded.calendar?.workouts?.single()?.targetDistanceMeters)
        assertNull(decoded.calendar?.workouts?.single()?.purpose)
        assertEquals(listOf("keep_plan"), decoded.calendar?.feedback?.single()?.consequence?.options)
    }

    @Test
    fun `onboarding defaults remain distinguishable from omitted server fields`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeView("onboarding", """
            {"initialValues":{"raceDistance":"","availability":[1,"2",8,6],"recentInjury":false}}
        """)) as NativeOnboardingPayload

        val values = requireNotNull(decoded.initialValues)
        assertEquals("", values.raceDistance)
        assertNull(values.startMode)
        assertEquals(listOf(1, 6), values.availability)
        assertEquals(false, values.recentInjury)
        assertNull(values.currentPain)
    }

    @Test
    fun `action preview exposes consequence risk fields without raw json`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeAction("""
            {"ok":true,"message":"Preview ready.","setupComplete":true,
             "preview":{"risk":"moderate","weeklyLoadChangePercent":12.5,
             "spacingConflicts":[{"workoutId":"w-2","scheduledDate":"2026-07-30","purpose":"Easy run"}]}}
        """))

        assertTrue(decoded.ok == true)
        assertEquals("Preview ready.", decoded.message)
        assertTrue(decoded.setupComplete == true)
        assertEquals("moderate", decoded.preview?.risk)
        assertEquals(12.5, decoded.preview?.weeklyLoadChangePercent)
        assertEquals("w-2", decoded.preview?.spacingConflicts?.single()?.workoutId)
        assertFalse(decoded.preview?.spacingConflicts.orEmpty().isEmpty())
    }

    @Test
    fun `stats decoder keeps completed work separate from generated week targets`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeView("stats", """
            {"onboardingRequired":false,
             "detail":{"weeks":[{"id":"week-1","weekNumber":1,"startDate":"2026-07-27",
             "targetDistanceMeters":6000,"risk":"conservative"}]},
             "history":{"weeklySummaries":[{"weekNumber":1,"startDate":"2026-07-27",
             "targetDistanceMeters":6000,"completedDistanceMeters":4500,
             "plannedRuns":2,"completedRuns":1}]}}
        """)) as NativeStatsPayload

        assertEquals(6_000.0, decoded.detail?.weeks?.single()?.targetDistanceMeters)
        assertNull(decoded.detail?.weeks?.single()?.completedDistanceMeters)
        assertEquals(
            4_500.0,
            decoded.history?.weeklySummaries?.single()?.completedDistanceMeters,
        )
    }

    @Test
    fun `phase completion decoder preserves the baseline and supported choices`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeView("stats", """
            {"onboardingRequired":false,
             "phaseReview":{"planId":"plan-1","phase":"foundation","goalKind":"race",
             "goalTitle":"Autumn 10K",
             "baseline":{"activityCount":4,"totalDurationSeconds":7200,
             "totalDistanceMeters":12000,"longestActivityMeters":4000,
             "weeklyDistanceMeters":6000,"runsPerWeek":2},
             "recommended":"confirm_race_baseline",
             "options":["confirm_race_baseline","another_foundation_week","later_date"],
             "preferredLongRunDay":6,
             "racePlan":{"risk":"conservative","weeks":10,"startDate":"2026-08-03",
             "targetDate":"2026-10-11",
             "summary":{"baselineMeters":6000,"peakMeters":10000,
             "requiredWeeklyIncreasePercent":6.1,"defaultWeeklyIncreasePercent":10,
             "longRunPeakMeters":7000,"warnings":[]},"warnings":[]}}}
        """)) as NativeStatsPayload

        val review = requireNotNull(decoded.phaseReview)
        assertEquals(4, review.baseline?.activityCount)
        assertEquals(2.0, review.baseline?.runsPerWeek)
        assertEquals("confirm_race_baseline", review.recommended)
        assertTrue("confirm_race_baseline" in review.options)
        assertEquals(10, review.racePlan?.weeks)
        assertEquals(6.1, review.racePlan?.summary?.requiredWeeklyIncreasePercent)
    }

    @Test
    fun `unknown view is rejected at typed boundary`() {
        assertNull(NativePayloadCodec.decodeView("unknown", "{}"))
    }

    @Test
    fun `distance mutation encodes required nullable fields and confirmation explicitly`() {
        val preview = PreviewWorkoutEditCommand(
            workoutId = "workout-1",
            mutation = WorkoutMutation(
                scheduledDate = "2026-08-01",
                type = "easy",
                prescriptionKind = "distance",
                targetDistanceMeters = 5_000,
                targetDurationSeconds = null,
                intervalStructure = null,
                intensity = "easy",
                purpose = "Easy aerobic run",
                userReason = "",
                rebalance = false,
            ),
        )

        val previewJson = JSONObject(NativePayloadCodec.encodeCommand(preview))
        assertTrue(previewJson.has("targetDurationSeconds"))
        assertTrue(previewJson.isNull("targetDurationSeconds"))
        assertTrue(previewJson.has("intervalStructure"))
        assertTrue(previewJson.isNull("intervalStructure"))
        assertFalse(previewJson.getBoolean("confirmRisk"))

        val confirmed = preview.confirmed() as ApplyWorkoutEditCommand
        assertTrue(
            JSONObject(NativePayloadCodec.encodeCommand(confirmed))
                .getBoolean("confirmRisk"),
        )
    }
}
