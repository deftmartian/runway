package dev.deftmartian.runway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePayloadCodecTest {
    @Test
    fun `manual timed run omits an unknown distance instead of inventing zero`() {
        val encoded = JSONObject(
            NativePayloadCodec.encodeCommand(
                RecordManualRunCommand(
                    occurredDate = "2026-07-28",
                    distanceKm = null,
                    durationMinutes = 25.0,
                    feltHard = false,
                    pain = false,
                ),
            ),
        )

        assertFalse(encoded.has("distanceKm"))
        assertEquals(25.0, encoded.getDouble("durationMinutes"), 0.0)
    }

    @Test
    fun `pace formatter preserves an exact per kilometre readout`() {
        assertEquals("5:00 /km", formatPace(300.0))
        assertEquals("—", formatPace(Double.NaN))
    }

    @Test
    fun `review decoder keeps coordinate-free activity detail summaries`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeView("review", """
            {"activities":[{"id":"activity-1","source":"gpx","reviewState":"accepted",
            "occurredDate":"2026-07-28T09:00:00.000Z","distanceMeters":5000,"durationSeconds":1500,
            "averagePaceSecondsPerKm":300,"averageHeartRate":142,"maxHeartRate":166,
            "heartRateSummary":{"highSeconds":180,"highShare":0.12,
            "secondsByZone":{"z1":60,"z2":900,"z3":360,"z4":120,"z5":60},"settingsSource":"custom"},
            "routeSummary":{"pointCount":42,"startEndRedacted":false,"hasElevation":true,"traceRetained":true}}]}
        """)) as NativeReviewPayload

        val activity = requireNotNull(decoded.activities.single())
        assertEquals(300.0, activity.averagePaceSecondsPerKm)
        assertEquals(180, activity.heartRateSummary?.highSeconds)
        assertEquals(900, activity.heartRateSummary?.secondsByZone?.z2)
        assertEquals(true, activity.routeSummary?.traceRetained)
        assertEquals(42, activity.routeSummary?.pointCount)
    }

    @Test
    fun `lazy activity evidence decoder keeps samples cadence and disclosure out of list payloads`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeView("activity-trace", """
            {"activityId":"activity-1","averageCadence":168,
             "routeTrace":{"sourcePointCount":2,"points":[{"latitudeE6":1,"longitudeE6":2,"elapsedSeconds":3,"segmentIndex":0,"speedMetersPerSecond":2.5}]},
             "heartRateSeries":{"sourceSampleCount":2,"points":[{"elapsedSeconds":3,"bpm":142}]},
             "disclosure":{"routeTraceRetained":true,"routePointCount":2,"startEndRedacted":true,"hasElevation":true,"heartRateSeriesRetained":true,"heartRateSampleCount":2}}
        """)) as NativeActivityEvidencePayload

        val evidence = requireNotNull(decoded.evidence)
        assertEquals(168, evidence.averageCadence)
        assertEquals(2, evidence.routeTrace?.sourcePointCount)
        assertEquals(2.5, evidence.routeTrace?.points?.single()?.speedMetersPerSecond)
        assertEquals(142, evidence.heartRateSeries?.points?.single()?.bpm)
        assertTrue(evidence.disclosure?.startEndRedacted == true)
        assertTrue(evidence.disclosure?.heartRateSeriesRetained == true)
    }

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
    fun `stats decoder keeps recommendation current plan and accepted work separate`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeView("stats", """
            {"onboardingRequired":false,
             "detail":{"weeks":[{"id":"week-1","weekNumber":1,"startDate":"2026-07-27",
             "targetDistanceMeters":6000,"risk":"conservative"}]},
             "planTrace":[{"id":"week-1","weekNumber":1,"startDate":"2026-07-27",
             "recommendedDistanceMeters":5000,"currentDistanceMeters":6000,
             "recommendedDurationSeconds":1500,"currentDurationSeconds":1800}],
             "history":{"weeklySummaries":[{"weekNumber":1,"startDate":"2026-07-27",
             "targetDistanceMeters":6000,"completedDistanceMeters":4500,"completedDurationSeconds":1440,
             "averagePaceSecondsPerKm":320,"averageHeartRate":142,"plannedRuns":2,"completedRuns":1}]}}
        """)) as NativeStatsPayload

        assertEquals(6_000.0, decoded.detail?.weeks?.single()?.targetDistanceMeters)
        assertNull(decoded.detail?.weeks?.single()?.completedDistanceMeters)
        assertEquals(
            4_500.0,
            decoded.history?.weeklySummaries?.single()?.completedDistanceMeters,
        )
        assertEquals(5_000.0, decoded.planTrace.single().recommendedDistanceMeters)
        assertEquals(6_000.0, decoded.planTrace.single().currentDistanceMeters)
        assertEquals(1_440.0, decoded.history?.weeklySummaries?.single()?.completedDurationSeconds)
        assertEquals(320.0, decoded.history?.weeklySummaries?.single()?.averagePaceSecondsPerKm)
        assertEquals(142, decoded.history?.weeklySummaries?.single()?.averageHeartRate)
    }

    @Test
    fun `stats decoder keeps plan-free recorded history and accepted heart-rate context`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeView("stats", """
            {"onboardingRequired":false,"active":null,"detail":null,"planTrace":[],
             "history":{"weeklySummaries":[],"hasAcceptedActivities":true,
               "recordedSummary":{"totalRuns":7,"totalDistanceMeters":32100,
                 "totalDurationSeconds":10800,"longestRunMeters":8100,
                 "archivedPlanRuns":5,"archivedPlanDistanceMeters":25100,
                 "unlinkedRuns":2,"unlinkedDistanceMeters":7000},
               "heartRateSample":{"windowDays":90,"windowStart":"2026-04-30",
                 "windowEnd":"2026-07-28","sampleCount":4,"averageHeartRate":141,
                 "highZoneSeconds":600,
                 "latest":{"activityDate":"2026-07-27","averageHeartRate":144,"maxHeartRate":168},
                 "oldest":{"activityDate":"2026-05-02","averageHeartRate":138}}}}
        """)) as NativeStatsPayload

        assertNull(decoded.active)
        assertEquals(7, decoded.history?.recordedSummary?.totalRuns)
        assertEquals(32_100.0, decoded.history?.recordedSummary?.totalDistanceMeters)
        assertEquals(4, decoded.history?.heartRateSample?.sampleCount)
        assertEquals(168, decoded.history?.heartRateSample?.latest?.maxHeartRate)
    }

    @Test
    fun `history detail decoder preserves changes reversals and workout consequences`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeView("history-detail", """
            {"onboardingRequired":false,"detail":{
              "plan":{"id":"plan-1","status":"archived","phase":"distance","weeks":8,
                "summary":{"kind":"distance","requiredWeeklyIncreasePercent":8,"defaultWeeklyIncreasePercent":10}},
              "goal":{"title":"Autumn 10K","distance":"10k"},"cutoffDate":"2026-07-28",
              "timeline":[{"id":"change-1","triggerType":"manual_edit","createdAt":"2026-07-20T12:00:00Z",
                "reversedAt":"2026-07-21T12:00:00Z","reversalReason":"Restored","reason":"Move long run",
                "newState":{"scheduledDate":"2026-07-22","type":"long","prescriptionKind":"distance","targetDistanceMeters":8000}}],
              "weeks":[{"id":"week-1","weekNumber":1,"startDate":"2026-07-20","targetDistanceMeters":12000,
                "workouts":[{"id":"workout-1","scheduledDate":"2026-07-22","type":"long","status":"done",
                  "prescriptionKind":"distance","targetDistanceMeters":8000,"purpose":"Long easy run",
                  "result":{"source":"manual","completedDistanceMeters":7500,"feltHard":true,"pain":false,
                    "consequence":{"deviation":"short","appliedDecision":"keep_plan","options":["keep_plan"]}}}]}]}}
        """)) as NativeHistoryDetailPayload

        val detail = requireNotNull(decoded.detail)
        assertEquals("Autumn 10K", detail.goal?.title)
        assertEquals(8.0, detail.plan?.summary?.requiredWeeklyIncreasePercent)
        assertEquals("Restored", detail.timeline.single().reversalReason)
        val result = detail.weeks.single().workouts.single().result
        assertEquals(7_500.0, result?.completedDistanceMeters)
        assertEquals("keep_plan", result?.consequence?.appliedDecision)
        assertTrue(result?.feltHard == true)
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

    @Test
    fun `account security decoder exposes summaries without credential or session material`() {
        val decoded = requireNotNull(NativePayloadCodec.decodeView("account-security", """
            {"authentication":{"localPassword":true,"oidc":true,"twoFactor":true,"passkeyCount":2},
             "sessions":{"activeCount":3,"currentIsNative":true,"requiresFreshSession":false,
               "items":[
                 {"id":"session-current","client":"Android app","current":true,
                  "createdAt":"2026-07-28T09:00:00Z","expiresAt":"2026-08-04T09:00:00Z"},
                 {"id":"session-other","client":"Web browser","current":false}
               ]},
             "importDevices":[{"id":"device-1","label":"Pixel","lastSeenAt":"2026-07-28T10:00:00Z"}]}
        """)) as NativeAccountSecurityPayload

        assertTrue(decoded.authentication?.localPassword == true)
        assertEquals(2, decoded.authentication?.passkeyCount)
        assertEquals(3, decoded.sessions?.activeCount)
        assertEquals(false, decoded.sessions?.requiresFreshSession)
        assertEquals("session-current", decoded.sessions?.items?.first()?.id)
        assertEquals("Web browser", decoded.sessions?.items?.last()?.client)
        assertEquals("Pixel", decoded.importDevices.single().label)
    }

    @Test
    fun `account operation decoder keeps security response fields typed and redacted`() {
        val token = "replacement-token-material"
        val secret = "JBSWY3DPEHPK3PXP"
        val code = "ABCDE-FGHIJ"
        val decoded = requireNotNull(
            NativePayloadCodec.decodeAccountOperation(
                """{
                  "ok":true,
                  "message":"Two-factor authentication enabled.",
                  "sessionToken":"$token",
                  "totpUri":"otpauth://totp/runway?secret=$secret",
                  "recoveryCodes":["$code"]
                }""",
            ),
        )

        assertEquals(token, decoded.sessionToken)
        assertEquals("otpauth://totp/runway?secret=$secret", decoded.totpUri)
        assertEquals(listOf(code), decoded.recoveryCodes)
        assertFalse(decoded.toString().contains(token))
        assertFalse(decoded.toString().contains(secret))
        assertFalse(decoded.toString().contains(code))
    }

    @Test
    fun `imported data deletion command carries the exact server confirmation`() {
        val payload = JSONObject(NativePayloadCodec.encodeCommand(DeleteImportedActivityDataCommand))
        assertEquals("DELETE IMPORTED ACTIVITY DATA", payload.getString("confirmation"))
    }

    @Test
    fun `health connect resolution commands keep their narrow typed decision wire values`() {
        val record = JSONObject(
            NativePayloadCodec.encodeCommand(
                ResolveHealthConnectRecordCommand(
                    mappingId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                    decision = HealthConnectRecordDecision.AcceptCorrection,
                ),
            ),
        )
        val duplicate = JSONObject(
            NativePayloadCodec.encodeCommand(
                ResolveHealthConnectDuplicateCommand(
                    mappingId = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
                    decision = HealthConnectDuplicateDecision.UseExisting,
                ),
            ),
        )

        assertEquals("f47ac10b-58cc-4372-a567-0e02b2c3d479", record.getString("mappingId"))
        assertEquals("accept_correction", record.getString("decision"))
        assertEquals("use_existing", duplicate.getString("decision"))
    }
}
