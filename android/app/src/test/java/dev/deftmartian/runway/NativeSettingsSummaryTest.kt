package dev.deftmartian.runway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSettingsSummaryTest {
    @Test
    fun `settings actions stack before narrow or enlarged text becomes cramped`() {
        assertFalse(usesStackedSettingsActionRow(328f, 1f))
        assertTrue(usesStackedSettingsActionRow(299f, 1f))
        assertTrue(usesStackedSettingsActionRow(328f, 1.3f))
    }

    @Test
    fun `training row covers routine active pending and empty states`() {
        val routine = nativeTrainingSettingsRow(
            NativeTrainingSettings.Active(
                title = "Run three times a week",
                phase = "routine",
                startedOn = "2026-07-01",
                targetDate = null,
                runsPerWeek = 3,
            ),
        )
        val active = nativeTrainingSettingsRow(
            NativeTrainingSettings.Active(
                title = "Autumn 10K",
                phase = "distance",
                startedOn = "2026-07-01",
                targetDate = "2026-10-18",
                runsPerWeek = 3,
            ),
        )
        val pending = nativeTrainingSettingsRow(
            NativeTrainingSettings.Pending("5K later", "2026-11-01"),
        )

        assertEquals("Weekly routine", routine.label)
        assertTrue(routine.summary.contains("3 runs each week"))
        assertEquals("Change", routine.action)
        assertEquals("Training plan", active.label)
        assertTrue(active.summary.contains("Autumn 10K"))
        assertEquals("Goal setup", pending.label)
        assertTrue(pending.summary.contains("No runs scheduled"))
        assertEquals("Set up", nativeTrainingSettingsRow(NativeTrainingSettings.None).action)
    }

    @Test
    fun `notification summaries distinguish off enabled and Android blocked`() {
        assertEquals("Off", runReminderSummary(NativeNotificationSettings()))
        assertTrue(
            runReminderSummary(
                NativeNotificationSettings(runReminderEnabled = true, runReminderMinuteOfDay = 480),
            ).contains("planned run days"),
        )
        assertEquals(
            "Blocked by Android",
            runReminderSummary(
                NativeNotificationSettings(runReminderEnabled = true, runReminderAllowed = false),
            ),
        )
        assertEquals(
            "New folder runs ready for Inbox review",
            importAlertSummary(NativeNotificationSettings(folderImportAlertsEnabled = true)),
        )
        assertEquals(
            "Blocked by Android",
            importAlertSummary(
                NativeNotificationSettings(
                    folderImportAlertsEnabled = true,
                    folderImportAlertsAllowed = false,
                ),
            ),
        )
    }

    @Test
    fun `running limits summary states the saved consequence`() {
        assertEquals(
            "Pain reported now · setup will not schedule runs",
            runningCheckInSummary(
                NativeHealthContext(
                    recentInjury = false,
                    currentPain = true,
                    recurringPain = false,
                    clinicianRestriction = false,
                    notes = "private detail",
                ),
            ),
        )
        assertEquals(
            "Recent injury · distance increases checked more cautiously",
            runningCheckInSummary(NativeHealthContext(recentInjury = true)),
        )
        assertEquals(
            "Private reminder · no effect on the schedule",
            runningCheckInSummary(NativeHealthContext(notes = "remember")),
        )
        assertEquals("No running limits saved", runningCheckInSummary(NativeHealthContext()))
        assertFalse(hasRunningLimits(NativeHealthContext()))
        assertTrue(hasRunningLimits(NativeHealthContext(recurringPain = true)))
        assertTrue(hasRunningLimits(NativeHealthContext(notes = "remember")))
    }

    @Test
    fun `running limit effects distinguish scheduling ramp checks and private notes`() {
        assertEquals(
            "New setup saves the goal but does not create a schedule. Existing workouts stay recorded.",
            runningCheckInEffect(NativeHealthContext(currentPain = true)),
        )
        assertEquals(
            "Distance plans and edits to runs with targets use more cautious increase checks. Foundation sessions and open routine runs stay unchanged.",
            runningCheckInEffect(NativeHealthContext(recurringPain = true)),
        )
        assertEquals(
            "The private reminder is for your reference only. It does not change workouts or scheduling.",
            runningCheckInEffect(NativeHealthContext(notes = "remember")),
        )
        assertEquals(null, runningCheckInEffect(NativeHealthContext()))
    }

    @Test
    fun `folder status keeps a permission consequence visible`() {
        assertEquals(
            "Permission needed",
            importConnectionSummary(NativeImportConnection.PermissionRequired),
        )
        assertEquals(
            "Grant access",
            folderImportActionLabel(NativeImportConnection.PermissionRequired),
        )
    }

    @Test
    fun `folder action labels distinguish setup from an existing connection`() {
        assertEquals(
            "Choose",
            folderImportActionLabel(NativeImportConnection.NotConnected),
        )
        assertEquals(
            "Manage",
            folderImportActionLabel(NativeImportConnection.Connected),
        )
        assertEquals(
            "Review",
            folderImportActionLabel(NativeImportConnection.Attention("Folder access changed")),
        )
    }

    @Test
    fun `health connect attention retains its actionable local detail`() {
        assertEquals(
            "Grant activity access in Health Connect",
            importConnectionSummary(NativeImportConnection.Attention("Grant activity access in Health Connect")),
        )
    }

    @Test
    fun `health connect action labels reflect the local connection state`() {
        assertEquals("Set up", healthConnectActionLabel(NativeImportConnection.NotConnected))
        assertEquals("Grant access", healthConnectActionLabel(NativeImportConnection.PermissionRequired))
        assertEquals("Manage", healthConnectActionLabel(NativeImportConnection.Connected))
        assertEquals("Review", healthConnectActionLabel(NativeImportConnection.Attention("Access changed")))
        assertEquals("Unavailable", healthConnectActionLabel(NativeImportConnection.Unavailable))
    }

    @Test
    fun `about uses a stable short revision and handles missing build metadata`() {
        val revision = "36899686c3bb4d8016b09bfa1def9c9584f8053c"

        assertEquals("36899686c3bb", shortBuildRevision(revision))
        assertEquals("Not available", normalizedBuildRevision("  "))
        assertEquals("Not available", shortBuildRevision("  "))
    }

    @Test
    fun `route discard is destructive only when changing away from retained routes`() {
        assertEquals(
            true,
            routePrivacyChangeDeletesStoredRoutes(
                NativeRoutePrivacy.KeepPrivate,
                NativeRoutePrivacy.Discard,
            ),
        )
        assertEquals(
            false,
            routePrivacyChangeDeletesStoredRoutes(
                NativeRoutePrivacy.Discard,
                NativeRoutePrivacy.Discard,
            ),
        )
        assertEquals(
            false,
            routePrivacySelectionChanged(
                NativeRoutePrivacy.Discard,
                NativeRoutePrivacy.Discard,
            ),
        )
        assertEquals(
            true,
            routePrivacySelectionChanged(
                NativeRoutePrivacy.Discard,
                NativeRoutePrivacy.KeepPrivate,
            ),
        )
    }

    @Test
    fun `heart rate discard is destructive only when changing away from retained values`() {
        assertEquals(
            true,
            heartRatePrivacyChangeDeletesStoredData(
                NativeHeartRatePrivacy.KeepPrivate,
                NativeHeartRatePrivacy.Discard,
            ),
        )
        assertEquals(
            false,
            heartRatePrivacyChangeDeletesStoredData(
                NativeHeartRatePrivacy.Discard,
                NativeHeartRatePrivacy.Discard,
            ),
        )
        assertEquals(
            false,
            heartRatePrivacySelectionChanged(
                NativeHeartRatePrivacy.Discard,
                NativeHeartRatePrivacy.Discard,
            ),
        )
        assertEquals(
            true,
            heartRatePrivacySelectionChanged(
                NativeHeartRatePrivacy.Discard,
                NativeHeartRatePrivacy.KeepPrivate,
            ),
        )
    }

    @Test
    fun `age estimate uses the documented formulas and zone percentages`() {
        assertEquals(
            NativeHeartRateProfile(
                source = NativeHeartRateSource.Estimated,
                sexForEstimates = NativeSexForEstimate.Female,
                ageYears = 40,
                maxHeartRateBpm = 171,
                zone2FloorBpm = 103,
                zone3FloorBpm = 120,
                zone4FloorBpm = 137,
                zone5FloorBpm = 154,
            ),
            estimatedHeartRateProfile(40, NativeSexForEstimate.Female),
        )
        assertEquals(
            180,
            estimatedHeartRateProfile(
                40,
                NativeSexForEstimate.NotSpecified,
            )?.maxHeartRateBpm,
        )
        assertEquals(
            null,
            estimatedHeartRateProfile(17, NativeSexForEstimate.Male),
        )
    }

    @Test
    fun `custom heart rate form uses the same bounds as local persistence`() {
        assertEquals(
            true,
            customHeartRateValuesAreValid(
                maxHeartRateBpm = 190,
                zone2FloorBpm = 114,
                zone3FloorBpm = 133,
                zone4FloorBpm = 152,
                zone5FloorBpm = 171,
            ),
        )
        assertEquals(
            false,
            customHeartRateValuesAreValid(
                maxHeartRateBpm = 230,
                zone2FloorBpm = 225,
                zone3FloorBpm = 226,
                zone4FloorBpm = 227,
                zone5FloorBpm = 228,
            ),
        )
        assertEquals(
            false,
            customHeartRateValuesAreValid(
                maxHeartRateBpm = 190,
                zone2FloorBpm = 114,
                zone3FloorBpm = 133,
                zone4FloorBpm = 171,
                zone5FloorBpm = 171,
            ),
        )
    }
}
