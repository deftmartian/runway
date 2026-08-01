package dev.deftmartian.runway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deftmartian.runway.data.RetentionRepairNotice
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

        assertSurface("Calendar", "Your plan and completed runs by date.")
        assertSurface("Inbox", "Choose how each run counts, then decide whether its result changes the plan.")
        assertSurface("Stats", "Recorded runs and past plans.")
        assertSurface("History", "Current and past training plans.")
        assertSurface("Settings", "Private training preferences and local data.")

        selectSurface("History")
        compose.onNodeWithText("Plan options").performClick()
        compose.onNodeWithText("Open plan record").performClick()
        compose.onNodeWithText("Test goal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to History").performClick()
        compose.onNodeWithText("Current and past training plans.").assertIsDisplayed()

        selectSurface("Settings")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Privacy"))
        compose.onNodeWithText("Privacy").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Heart-rate privacy"))
        compose.onNodeWithText("Heart-rate privacy").assertIsDisplayed()
        compose.onNodeWithText("Heart-rate privacy").performClick()
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
                        nextWorkout = null,
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

        compose.onNodeWithText("Build plan").assertIsDisplayed()
        compose.onAllNodesWithText("Continue setup").assertCountEquals(0)

        selectSurface("Stats")
        compose.onNodeWithText("Build plan").assertIsDisplayed()
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

        compose.onNodeWithText("Build a plan before scheduling future runs.")
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

        compose.onNodeWithText("Privacy settings restored").assertIsDisplayed()
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
            nextWorkout = null,
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
}
