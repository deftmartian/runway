package dev.deftmartian.runway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deftmartian.runway.data.RetentionRepairNotice
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A rendered-shell regression test. The payloads are intentionally local and bounded: this proves
 * information architecture and parent Back behavior without requiring imported activity fixtures
 * or a pre-seeded local database.
 */
@RunWith(AndroidJUnit4::class)
class NativeFiveSurfaceNavigationInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fiveProductSurfacesAreReachableAndNestedBackReturnsToTheirOwners() {
        compose.setContent {
            var destination by remember { mutableStateOf(NativeDestination.Calendar) }
            RunwayTheme {
                RunwayNativeApp(
                    state = readyState(destination),
                    onDestinationSelected = { destination = it },
                    onCalendarMonthSelected = {},
                    onLoadMoreHistory = {},
                    onLoadMoreInbox = {},
                    onLoadActivityTrace = {},
                    onOpenHistoryDetail = { destination = NativeDestination.HistoryDetail },
                    onRetryOpen = {},
                    onAction = {},
                    onApplyWorkoutPreview = {}, onDismissWorkoutPreview = {},
                    onApplyPlanDecisionPreview = {}, onDismissPlanDecisionPreview = {},
                    onOpenFolder = {},
                    onImportGpx = {}, onOpenHealthConnect = {}, onCreateBackup = {}, onRestoreBackup = {},
                    onExportData = {}, onTimeZoneChanged = {}, onRoutePrivacyChanged = {},
                    onHeartRatePrivacyChanged = {},
                    onHeartRateChanged = {}, onHealthContextChanged = {}, onEraseImportedActivityData = {},
                    onEraseAllData = {},
                    onAcknowledgeRetentionRepair = {},
                )
            }
        }

        assertSurface("Calendar", "Earlier")
        assertSurface("Inbox", "Choose how each run counts, then decide whether its result changes the plan.")
        assertSurface("Stats", "Recorded runs and past plans.")
        assertSurface("History", "Current training schedule and past records.")
        assertSurface("Settings", "Update your plan, imports, reminders, and saved data.")

        selectSurface("History")
        compose.onNodeWithText("Plan options").performClick()
        compose.onAllNodesWithText("Change goal").assertCountEquals(0)
        compose.onNodeWithText("Open plan record").performClick()
        compose.onNodeWithText("Test goal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to History").performClick()
        compose.onNodeWithText("Current training schedule and past records.").assertIsDisplayed()

        selectSurface("Settings")
        compose.onNodeWithText("Training plan").performClick()
        compose.onNodeWithContentDescription("Back to Settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("Update your plan, imports, reminders, and saved data.").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Run reminders"))
        compose.onNodeWithText("Run reminders").assertIsDisplayed().performClick()
        compose.onNodeWithText(
            "Runway will check for a planned run around this time. Android may deliver the reminder later.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Imported heart-rate details"))
        compose.onNodeWithText("Imported heart-rate details").assertIsDisplayed()
        compose.onNodeWithText("Imported heart-rate details").performClick()
        compose.onNodeWithTag("heart-rate-privacy-dialog").assertIsDisplayed()
        compose.onNodeWithText("Discard imported heart-rate values").performClick()
        compose.onNodeWithText("Discard stored heart rate").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Build revision"))
        compose.onNodeWithText("Build revision").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Stored on this device"))
        compose.onNodeWithText("Stored on this device").assertIsDisplayed()
    }

    @Test
    fun compactCalendarUsesReadableDayRowsHidesRecoveryEntriesAndAutoHidesRecordAction() {
        val easyRun = calendarWorkout(
            id = "easy",
            date = "2026-08-15",
            purpose = "Long easy run",
            distanceMeters = 6_000.0,
        )
        val rest = calendarWorkout(
            id = "rest",
            date = "2026-08-16",
            purpose = "Rest",
            type = "rest",
            distanceMeters = null,
        )
        compose.setContent {
            RunwayTheme {
                RunwayNativeApp(
                    state = RunwayUiState.Ready(
                        surface = NativeSurface.Calendar(
                            NativeCalendarPayload(
                                onboardingRequired = false,
                                hasActivePlan = true,
                                calendar = NativeCalendar(
                                    month = "2026-08",
                                    today = "2026-08-12",
                                    previousMonth = "2026-07",
                                    nextMonth = "2026-09",
                                    workouts = listOf(easyRun, rest),
                                    activities = emptyList(),
                                    feedback = emptyList(),
                                ),
                                activityCandidates = emptyList(),
                            ),
                        ),
                        loading = false,
                    ),
                    onDestinationSelected = {}, onCalendarMonthSelected = {},
                    onLoadMoreHistory = {}, onLoadMoreInbox = {}, onLoadActivityTrace = {},
                    onOpenHistoryDetail = {}, onRetryOpen = {}, onAction = {},
                    onApplyWorkoutPreview = {}, onDismissWorkoutPreview = {},
                    onApplyPlanDecisionPreview = {}, onDismissPlanDecisionPreview = {},
                    onOpenFolder = {}, onImportGpx = {}, onOpenHealthConnect = {},
                    onCreateBackup = {}, onRestoreBackup = {}, onExportData = {},
                    onTimeZoneChanged = {}, onRoutePrivacyChanged = {},
                    onHeartRatePrivacyChanged = {}, onHeartRateChanged = {},
                    onHealthContextChanged = {}, onEraseImportedActivityData = {},
                    onEraseAllData = {}, onAcknowledgeRetentionRepair = {},
                )
            }
        }

        compose.onAllNodesWithText("Your plan and completed runs by date.").assertCountEquals(0)
        compose.onAllNodesWithText("Schedule run").assertCountEquals(0)
        compose.onAllNodesWithText("Change goal").assertCountEquals(0)
        val calendarList = compose.onNode(hasScrollAction())
        calendarList.performTouchInput { swipeUp() }
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodes(hasContentDescription("Record a run"))
                .fetchSemanticsNodes().isEmpty()
        }
        calendarList.performTouchInput { swipeDown() }
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodes(hasContentDescription("Record a run"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Record a run").assertIsDisplayed().performClick()
        compose.onNodeWithText("Add a run manually").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("calendar-day-ledger"))
        compose.onNodeWithTag("calendar-day-ledger").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Long easy run · 6 km"))
        compose.onNodeWithText("Long easy run · 6 km").assertIsDisplayed()
        compose.onNode(
            hasTestTag("calendar-ledger-day-2026-08-15") and
                hasContentDescription("Long easy run · 6 km", substring = true),
        ).assertIsDisplayed()
        compose.onAllNodesWithText("Recovery day").assertCountEquals(0)
        compose.onNodeWithTag("calendar-month-grid").assertDoesNotExist()
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("calendar-quiet-week-2026-08-23"))
        compose.onNodeWithTag("calendar-quiet-week-2026-08-23").performClick()
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("calendar-ledger-day-2026-08-24"))
        compose.onNodeWithTag("calendar-ledger-day-2026-08-24").performClick()
        compose.onNodeWithText("No run is planned or recorded for this day.").assertIsDisplayed()
        compose.onNodeWithText("Add a run here").assertIsDisplayed()
    }

    @Test
    fun firstRunCanRestoreABackupBeforeCreatingLocalData() {
        var restoreRequested = false
        compose.setContent {
            RunwayTheme {
                RunwayNativeApp(
                    state = RunwayUiState.Ready(
                        surface = NativeSurface.Setup(
                            NativeOnboardingPayload(
                                initialValues = null,
                                minimumTargetDate = null,
                                minimumCalibrationTargetDate = null,
                                minimumFoundationTargetDate = null,
                                maximumTargetDate = null,
                                currentGoal = null,
                            ),
                        ),
                        loading = false,
                    ),
                    onDestinationSelected = {}, onCalendarMonthSelected = {},
                    onLoadMoreHistory = {}, onLoadMoreInbox = {}, onLoadActivityTrace = {},
                    onOpenHistoryDetail = {}, onRetryOpen = {}, onAction = {},
                    onApplyWorkoutPreview = {}, onDismissWorkoutPreview = {},
                    onApplyPlanDecisionPreview = {}, onDismissPlanDecisionPreview = {},
                    onOpenFolder = {}, onImportGpx = {}, onOpenHealthConnect = {},
                    onCreateBackup = {}, onRestoreBackup = { restoreRequested = true },
                    onExportData = {}, onTimeZoneChanged = {}, onRoutePrivacyChanged = {},
                    onHeartRatePrivacyChanged = {}, onHeartRateChanged = {},
                    onHealthContextChanged = {}, onEraseImportedActivityData = {},
                    onEraseAllData = {}, onAcknowledgeRetentionRepair = {},
                )
            }
        }

        compose.onNodeWithText("Restore a backup instead").performClick()
        compose.onNodeWithText("Replace local runway data?").assertIsDisplayed()
        compose.onNodeWithText("Choose backup").performClick()
        compose.runOnIdle { assertTrue(restoreRequested) }
    }

    @Test
    fun establishedHalfMarathonShowsTheGeneratedRampBeforeCreation() {
        var submitted: CreatePlanCommand? = null
        val payload = NativeOnboardingPayload(
            initialValues = NativePlanInitialValues(
                startMode = "established",
                raceDistance = "half",
                targetDate = "2026-11-22",
                priority = "finish_healthy",
                currentWeeklyDistanceKm = "25",
                currentRunsPerWeek = "3",
                longestRecentRunKm = "10",
                calibrationDurationMinutes = null,
                preferredLongRunDay = "6",
                timeZone = "America/Halifax",
                availability = listOf(1, 3, 6),
                recentInjury = false,
                currentPain = false,
                recurringPain = false,
                medicalRestriction = false,
                injuryNotes = null,
            ),
            minimumTargetDate = null,
            minimumCalibrationTargetDate = null,
            minimumFoundationTargetDate = null,
            maximumTargetDate = null,
            currentGoal = null,
        )
        compose.setContent {
            RunwayTheme {
                SetupScreen(
                    payload = payload,
                    actionPending = false,
                    onAction = { command -> submitted = command as CreatePlanCommand },
                    onRestoreBackup = {},
                    nowProvider = { Instant.parse("2026-08-02T12:00:00Z") },
                )
            }
        }

        repeat(3) { compose.onNodeWithText("Continue").performClick() }

        listOf("Training outline", "25 km", "Needs confirmation").forEach { text ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        compose.onNodeWithText("Create plan").assertIsNotEnabled()

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Use this schedule as shown"))
        compose.onNodeWithText("Use this schedule as shown").performClick()
        compose.onNodeWithText("Use this schedule as shown").assertIsOn()
        compose.onAllNodesWithText(
            "Confirm the schedule after reviewing its warnings, or change the plan inputs.",
        ).assertCountEquals(0)
        compose.onNodeWithText("Create plan").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(submitted?.confirmedPlanKey?.isNotBlank() == true)
            assertTrue(submitted?.raceDistance == "half")
            assertTrue(submitted?.longestRecentRunKm == "10")
        }
    }

    @Test
    fun loadingAnExistingGoalIntoSetupUsesItsSavedValuesInsteadOfFreshDefaults() {
        val targetDate = "2026-10-01"
        val existingPayload = NativeOnboardingPayload(
            initialValues = NativePlanInitialValues(
                startMode = "established",
                raceDistance = "10k",
                targetDate = targetDate,
                priority = "consistency",
                currentWeeklyDistanceKm = "24",
                currentRunsPerWeek = "3",
                longestRecentRunKm = "12",
                calibrationDurationMinutes = null,
                preferredLongRunDay = "6",
                timeZone = "America/Halifax",
                availability = listOf(1, 3, 6),
                recentInjury = false,
                currentPain = false,
                recurringPain = false,
                medicalRestriction = false,
                injuryNotes = null,
            ),
            minimumTargetDate = null,
            minimumCalibrationTargetDate = null,
            minimumFoundationTargetDate = null,
            maximumTargetDate = null,
            currentGoal = NativeGoalSummary(
                id = "existing-goal",
                title = "Existing 10K plan",
                targetDate = targetDate,
                priority = "consistency",
                state = "active",
                risk = "conservative",
            ),
        )
        var loadPayload: ((NativeOnboardingPayload?) -> Unit)? = null

        compose.setContent {
            var payload by remember { mutableStateOf<NativeOnboardingPayload?>(null) }
            loadPayload = { payload = it }
            RunwayTheme {
                RunwayNativeApp(
                    state = RunwayUiState.Ready(
                        surface = NativeSurface.Setup(payload),
                        loading = payload == null,
                    ),
                    onDestinationSelected = {}, onCalendarMonthSelected = {},
                    onLoadMoreHistory = {}, onLoadMoreInbox = {}, onLoadActivityTrace = {},
                    onOpenHistoryDetail = {}, onRetryOpen = {}, onAction = {},
                    onApplyWorkoutPreview = {}, onDismissWorkoutPreview = {},
                    onApplyPlanDecisionPreview = {}, onDismissPlanDecisionPreview = {},
                    onOpenFolder = {}, onImportGpx = {}, onOpenHealthConnect = {},
                    onCreateBackup = {}, onRestoreBackup = {}, onExportData = {},
                    onTimeZoneChanged = {}, onRoutePrivacyChanged = {},
                    onHeartRatePrivacyChanged = {}, onHeartRateChanged = {},
                    onHealthContextChanged = {}, onEraseImportedActivityData = {},
                    onEraseAllData = {}, onAcknowledgeRetentionRepair = {},
                )
            }
        }

        compose.onNodeWithText("Restore a backup instead").assertIsDisplayed()
        compose.runOnIdle { loadPayload?.invoke(existingPayload) }

        compose.onNodeWithText("Replacing Existing 10K plan keeps its history and archives the current plan after confirmation.")
            .assertIsDisplayed()
        compose.onNodeWithText("Prepare for a race").assertIsSelected()
        compose.onNodeWithText("10K").assertIsSelected()
        compose.onNodeWithText("5K").assertIsNotSelected()
        compose.onNodeWithText("Build consistency").assertIsSelected()
    }

    @Test
    fun completedSetupWithoutAnActivePlanOffersANewPlanInsteadOfSetupRecovery() {
        compose.setContent {
            var destination by remember { mutableStateOf(NativeDestination.Calendar) }
            val surface = when (destination) {
                NativeDestination.Stats -> NativeSurface.Stats(
                    NativeStatsPayload(
                        onboardingRequired = false,
                        active = null,
                        detail = NativePlanDetail(emptyList()),
                        history = NativeTrainingHistory(
                            weeklySummaries = emptyList(),
                            todayIso = "2026-07-31",
                            currentSignal = null,
                            hasAcceptedActivities = true,
                            recordedSummary = NativeRecordedHistorySummary(
                                totalRuns = 2,
                                totalDistanceMeters = 7_000.0,
                                totalDurationSeconds = 2_800.0,
                                longestRunMeters = 4_000.0,
                                currentPlanRuns = 0,
                                currentPlanDistanceMeters = 0.0,
                                archivedPlanRuns = 2,
                                archivedPlanDistanceMeters = 7_000.0,
                                unlinkedRuns = 0,
                                unlinkedDistanceMeters = 0.0,
                            ),
                            heartRateSample = null,
                        ),
                        planTrace = emptyList(),
                        planHistory = null,
                        phaseReview = null,
                    ),
                )
                else -> NativeSurface.Calendar(
                    NativeCalendarPayload(
                        onboardingRequired = false,
                        hasActivePlan = false,
                        calendar = NativeCalendar(
                            month = "2026-07",
                            today = "2026-07-31",
                            previousMonth = "2026-06",
                            nextMonth = "2026-08",
                            workouts = emptyList(),
                            activities = emptyList(),
                            feedback = emptyList(),
                        ),
                        activityCandidates = emptyList(),
                    ),
                )
            }
            RunwayTheme {
                RunwayNativeApp(
                    state = RunwayUiState.Ready(surface = surface, loading = false),
                    onDestinationSelected = { destination = it },
                    onCalendarMonthSelected = {},
                    onLoadMoreHistory = {},
                    onLoadMoreInbox = {},
                    onLoadActivityTrace = {},
                    onOpenHistoryDetail = {},
                    onRetryOpen = {},
                    onAction = {},
                    onApplyWorkoutPreview = {},
                    onDismissWorkoutPreview = {},
                    onApplyPlanDecisionPreview = {},
                    onDismissPlanDecisionPreview = {},
                    onOpenFolder = {},
                    onImportGpx = {},
                    onOpenHealthConnect = {},
                    onCreateBackup = {},
                    onRestoreBackup = {},
                    onExportData = {},
                    onTimeZoneChanged = {},
                    onRoutePrivacyChanged = {},
                    onHeartRatePrivacyChanged = {},
                    onHeartRateChanged = {},
                    onHealthContextChanged = {},
                    onEraseImportedActivityData = {},
                    onEraseAllData = {},
                    onAcknowledgeRetentionRepair = {},
                )
            }
        }

        compose.onNodeWithText("Set up running").assertIsDisplayed()
        compose.onAllNodesWithText("Continue setup").assertCountEquals(0)

        selectSurface("Stats")
        compose.onNodeWithText("Set up running").assertIsDisplayed()
        compose.onAllNodesWithText("Continue setup").assertCountEquals(0)
    }

    @Test
    fun futureDayWithoutAnActivePlanDoesNotOfferAnImpossibleWorkout() {
        compose.setContent {
            RunwayTheme {
                CalendarDayDetailSheet(
                    date = "2026-08-01",
                    today = "2026-07-31",
                    workouts = emptyList(),
                    activities = emptyList(),
                    feedbackByWorkout = emptyMap(),
                    actionPending = false,
                    onDismiss = {},
                    onRecordFeedback = {},
                    onEditWorkout = {},
                    onResetWorkout = {},
                    onUndoWorkout = {},
                    onDeleteFeedback = {},
                    onOpenActivity = {},
                    onPlanDecision = {},
                    canAddWorkout = false,
                    onAddWorkout = {},
                )
            }
        }

        compose.onNodeWithText("Set up a plan or routine before scheduling future runs.")
            .assertIsDisplayed()
        compose.onAllNodesWithText("Add a run here").assertCountEquals(0)
    }

    @Test
    fun repairedPrivacyModesRemainVisibleUntilTheRunnerDismissesTheNote() {
        var acknowledged = false
        compose.setContent {
            RunwayTheme {
                RunwayNativeApp(
                    state = RunwayUiState.Ready(
                        surface = NativeSurface.Settings(
                            NativeSettingsState(
                                retentionRepair = RetentionRepairNotice(
                                    routeModeRestored = true,
                                    heartRateModeRestored = true,
                                ),
                            ),
                        ),
                        loading = false,
                    ),
                    onDestinationSelected = {},
                    onCalendarMonthSelected = {},
                    onLoadMoreHistory = {},
                    onLoadMoreInbox = {},
                    onLoadActivityTrace = {},
                    onOpenHistoryDetail = {},
                    onRetryOpen = {},
                    onAction = {},
                    onApplyWorkoutPreview = {},
                    onDismissWorkoutPreview = {},
                    onApplyPlanDecisionPreview = {},
                    onDismissPlanDecisionPreview = {},
                    onOpenFolder = {},
                    onImportGpx = {},
                    onOpenHealthConnect = {},
                    onCreateBackup = {},
                    onRestoreBackup = {},
                    onExportData = {},
                    onTimeZoneChanged = {},
                    onRoutePrivacyChanged = {},
                    onHeartRatePrivacyChanged = {},
                    onHeartRateChanged = {},
                    onHealthContextChanged = {},
                    onEraseImportedActivityData = {},
                    onEraseAllData = {},
                    onAcknowledgeRetentionRepair = { acknowledged = true },
                )
            }
        }

        compose.onNodeWithText("Import settings restored").assertIsDisplayed()
        compose.onNodeWithText("Dismiss note").performClick()
        compose.runOnIdle { assertTrue(acknowledged) }
    }

    private fun assertSurface(label: String, marker: String) {
        selectSurface(label)
        compose.onNodeWithText(marker).assertIsDisplayed()
    }

    private fun selectSurface(label: String) {
        val destination = NativeDestination.entries.first { it.label == label }
        compose.onNodeWithTag("primary-destination-${destination.view}").performClick()
    }

    private fun readyState(destination: NativeDestination) = RunwayUiState.Ready(
        surface = surfaceFor(destination),
        loading = false,
    )

    private fun surfaceFor(destination: NativeDestination): NativeSurface = when (destination) {
        NativeDestination.Calendar -> NativeSurface.Calendar(NativeCalendarPayload(
            onboardingRequired = false,
            hasActivePlan = true,
            calendar = null,
            activityCandidates = emptyList(),
        ))
        NativeDestination.Inbox -> NativeSurface.Inbox(NativeReviewPayload(
            candidates = emptyList(),
            activities = emptyList(),
        ))
        NativeDestination.Stats -> NativeSurface.Stats(NativeStatsPayload(
            onboardingRequired = false,
            active = null,
            detail = null,
            history = null,
            planTrace = emptyList(),
            planHistory = null,
            phaseReview = null,
        ))
        NativeDestination.History -> NativeSurface.History(NativeHistoryPayload(
            onboardingRequired = false,
            history = NativePlanHistoryPage(emptyList(), nextOffset = null, today = "2026-07-29"),
            activeItem = NativePlanHistoryItem(
                plan = NativePlan(
                    id = "plan-1",
                    status = "active",
                    startDate = "2026-07-01",
                    targetDate = "2026-08-01",
                    weeks = 4,
                    risk = "conservative",
                    summaryKind = "distance",
                    completedAt = null,
                    archivedAt = null,
                    lifecycleReason = null,
                ),
                goal = NativeGoalSummary(
                    title = "Test goal",
                    targetDate = "2026-08-01",
                    state = "active",
                    risk = "conservative",
                ),
                summary = null,
            ),
            phaseReview = null,
            offset = null,
            pageSize = null,
        ))
        NativeDestination.Settings -> NativeSurface.Settings(
            NativeSettingsState(
                heartRatePrivacy = NativeHeartRatePrivacy.KeepPrivate,
                appVersion = "test",
                sourceCommit = "test",
            ),
        )
        NativeDestination.HistoryDetail -> NativeSurface.HistoryDetail(NativeHistoryDetailPayload(
            onboardingRequired = false,
            detail = NativeHistoryDetail(
                plan = null,
                goal = NativeHistoryDetailGoal(
                    title = "Test goal",
                    distance = null,
                    priority = null,
                ),
                cutoffDate = null,
                timeline = emptyList(),
                weeks = emptyList(),
            ),
        ))
        NativeDestination.Setup -> NativeSurface.Setup(null)
    }

    private fun calendarWorkout(
        id: String,
        date: String,
        purpose: String,
        type: String = "easy",
        distanceMeters: Double?,
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
    )
}
