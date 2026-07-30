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
 * information architecture and parent Back behavior without requiring a server, credentials, or
 * imported activity data.
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
                    onRefresh = {},
                    onAction = {},
                    onApplyWorkoutPreview = {}, onDismissWorkoutPreview = {},
                    onOpenFolder = {},
                    onImportGpx = {}, onOpenHealthConnect = {}, onCreateBackup = {}, onRestoreBackup = {},
                    onExportData = {}, onTimeZoneChanged = {}, onRoutePrivacyChanged = {},
                    onHeartRateChanged = {}, onHealthContextChanged = {}, onEraseAllData = {},
                )
            }
        }

        assertSurface("Calendar", "Your plan and completed runs by date.")
        assertSurface("Inbox", "Link each imported run, count it as extra training, or delete it.")
        assertSurface("Stats", "Recorded runs and past plans.")
        assertSurface("History", "Current and past training plans.")
        assertSurface("Settings", "Private training preferences and local data.")

        selectSurface("History")
        compose.onNodeWithText("Open plan record").performClick()
        compose.onNodeWithText("Test goal").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to History").performClick()
        compose.onNodeWithText("Current and past training plans.").assertIsDisplayed()

        selectSurface("Settings")
        compose.onNodeWithText("Stored locally on this phone").assertIsDisplayed()
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
        bootstrap = NativeBootstrapPayload(
            user = null,
            setupComplete = true,
            timeZone = "America/Halifax",
            release = "test",
            commit = "test",
            serverOrigin = "https://runway.test",
            androidApi = 2,
            features = null,
        ),
        destination = destination,
        payload = payloadFor(destination),
        loading = false,
    )

    private fun payloadFor(destination: NativeDestination): Any? = when (destination) {
        NativeDestination.Calendar -> NativeCalendarPayload(
            onboardingRequired = false,
            hasActivePlan = true,
            calendar = null,
            nextWorkout = null,
            activityCandidates = emptyList(),
        )
        NativeDestination.Inbox -> NativeReviewPayload(
            candidates = emptyList(),
            activities = emptyList(),
            activityPage = null,
            sources = emptyList(),
            androidDevices = emptyList(),
            routeDataMode = null,
        )
        NativeDestination.Stats -> NativeStatsPayload(
            onboardingRequired = false,
            active = null,
            detail = null,
            history = null,
            planTrace = emptyList(),
            planHistory = null,
            phaseReview = null,
        )
        NativeDestination.History -> NativeHistoryPayload(
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
        )
        NativeDestination.Settings -> NativeSettingsState(appVersion = "test", sourceCommit = "test")
        NativeDestination.HistoryDetail -> NativeHistoryDetailPayload(
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
        )
        NativeDestination.Setup -> null
    }
}
