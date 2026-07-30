package dev.deftmartian.runway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
        compose.onNodeWithText("Heart-rate privacy").performClick()
        compose.onNodeWithText("Discard imported heart-rate values").performClick()
        compose.onNodeWithText("Discard stored heart rate").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Build revision"))
        compose.onNodeWithText("Build revision").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("This phone only"))
        compose.onNodeWithText("This phone only").assertIsDisplayed()
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
