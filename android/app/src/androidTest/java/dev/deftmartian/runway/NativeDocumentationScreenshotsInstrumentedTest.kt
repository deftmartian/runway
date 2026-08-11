package dev.deftmartian.runway

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sanitized, deterministic screenshots for CI artifacts. They render the production shell and
 * surfaces with fallback Material colours; no database, import provider, or user data is used.
 */
@RunWith(AndroidJUnit4::class)
class NativeDocumentationScreenshotsInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun capturesCalendarInDarkTheme() =
        capture(
            name = "calendar-dark",
            darkTheme = true,
            state = calendarState(),
            densityScale = 0.85f,
        ) {
            compose.onNodeWithTag("calendar-day-ledger").assertExists()
            compose.onNodeWithText("Long easy run · 6 km").assertExists()
        }

    @Test
    fun capturesCalendarAtLargeFontScale() =
        capture(
            name = "calendar-large-text",
            darkTheme = false,
            state = calendarState(),
            fontScale = 1.3f,
        ) {
            NativeDestination.entries
                .filter(NativeDestination::primaryNavigation)
                .forEach { destination ->
                    compose.onNodeWithTag("primary-destination-${destination.view}")
                        .assertIsDisplayed()
                }
        }

    @Test
    fun capturesCalendarWithExpandedNavigation() =
        capture(
            name = "calendar-expanded-light",
            darkTheme = false,
            state = calendarState(),
            densityScale = 0.34f,
            viewportHeightDp = 1_350f,
        ) {
            compose.onNodeWithTag("primary-navigation-rail").assertIsDisplayed()
            compose.onNodeWithTag("primary-navigation-bar").assertDoesNotExist()
            compose.onNodeWithTag("calendar-month-grid").assertExists()
            compose.onNodeWithTag("calendar-day-ledger").assertDoesNotExist()
            compose.onNodeWithText("Long easy run · 6 km").assertExists()
        }

    @Test
    fun capturesInboxInLightTheme() =
        capture("inbox-light", darkTheme = false, inboxState()) {
            compose.onNodeWithText("Runs to review").assertIsDisplayed()
            compose.onNodeWithText("Plan decisions").assertIsDisplayed()
        }

    @Test
    fun capturesHistoryInLightTheme() =
        capture("history-light", darkTheme = false, historyState())

    @Test
    fun capturesStatsInLightTheme() =
        capture("stats-light", darkTheme = false, statsState())

    @Test
    fun capturesSettingsInDarkTheme() =
        capture("settings-dark", darkTheme = true, settingsState())

    @Test
    fun capturesSettingsAboutInDarkTheme() =
        capture("settings-about-dark", darkTheme = true, settingsState()) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Build revision"))
            compose.onNodeWithText("Build revision").assertIsDisplayed()
        }

    @Test
    fun capturesRoutineProgressionAtLargeFontScale() =
        captureContent(
            name = "routine-progression-large-text",
            darkTheme = false,
            fontScale = 1.3f,
            content = {
                SetupScreen(
                    payload = NativeOnboardingPayload(
                        initialValues = null,
                        minimumTargetDate = null,
                        minimumCalibrationTargetDate = null,
                        maximumTargetDate = null,
                        currentGoal = null,
                    ),
                    actionPending = false,
                    onAction = {},
                    onRestoreBackup = {},
                    nowProvider = { java.time.Instant.parse("2026-08-11T12:00:00Z") },
                )
            },
        ) {
            compose.onNodeWithText("Run consistently each week").performClick()
            compose.onNodeWithText("Weekly distance goal (km)").performTextReplacement("15")
            compose.onNodeWithText("Continue").performClick()
            compose.onNodeWithText("Repeatable weekly distance (km)").performTextReplacement("12")
            compose.onNodeWithText("Current runs per week").performTextReplacement("3")
            compose.onNodeWithText("Change each week (km)").performTextReplacement("1")
            compose.onNodeWithText("Continue").performClick()
            compose.onNodeWithText("Continue").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Final goal"))
            compose.onNodeWithText("Weekly progression").assertIsDisplayed()
            compose.onNodeWithText("Final goal").assertIsDisplayed()
        }

    @Test
    fun capturesRoutineWeekBalanceInDarkTheme() =
        captureContent(
            name = "routine-week-balance-dark",
            darkTheme = true,
            captureTag = "routine-week-balance-dialog",
            content = {
                RoutineWeekBalanceDialog(
                    goal = NativeRoutineWeekGoal(
                        weekId = "routine-week",
                        startDate = "2026-08-17",
                        targetDistanceMeters = 15_000.0,
                        scheduledRuns = 3,
                        runs = listOf(
                            NativeRoutineRunTarget("monday", "2026-08-17", 5_000, null),
                            NativeRoutineRunTarget("wednesday", "2026-08-19", 5_000, null),
                            NativeRoutineRunTarget("saturday", "2026-08-22", 5_000, null),
                        ),
                    ),
                    actionPending = false,
                    errorMessage = null,
                    onDismiss = {},
                    onSubmit = {},
                )
            },
        ) {
            compose.onAllNodesWithText("+ 0.5 km")[0].performClick()
            compose.onNodeWithText("5.5 km").assertIsDisplayed()
            compose.onNodeWithText("4.5 km").assertIsDisplayed()
        }

    private fun capture(
        name: String,
        darkTheme: Boolean,
        state: RunwayUiState.Ready,
        fontScale: Float = 1f,
        densityScale: Float = 1f,
        viewportHeightDp: Float? = null,
        prepareCapture: (() -> Unit)? = null,
    ) = captureContent(
        name = name,
        darkTheme = darkTheme,
        fontScale = fontScale,
        densityScale = densityScale,
        viewportHeightDp = viewportHeightDp,
        content = {
            RunwayNativeApp(
                state = state,
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
                onEraseImportedActivityData = {},
                onEraseAllData = {},
                onAcknowledgeRetentionRepair = {},
            )
        },
        prepareCapture = prepareCapture,
    )

    private fun captureContent(
        name: String,
        darkTheme: Boolean,
        fontScale: Float = 1f,
        densityScale: Float = 1f,
        viewportHeightDp: Float? = null,
        captureTag: String? = null,
        content: @Composable () -> Unit,
        prepareCapture: (() -> Unit)? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density * densityScale, fontScale),
            ) {
                val captureModifier = if (viewportHeightDp == null) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().height(viewportHeightDp.dp)
                }
                Box(captureModifier.testTag(SCREENSHOT_ROOT_TAG)) {
                    RunwayTheme(darkTheme = darkTheme, dynamicColor = false) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
        prepareCapture?.invoke()
        compose.waitForIdle()

        val directory = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            SCREENSHOT_DIRECTORY,
        ).apply { mkdirs() }
        val output = File(directory, "$name.png")
        val bitmap = captureScreenshotBitmap(name, captureTag)
        FileOutputStream(output).use { stream ->
            assertTrue(
                "Could not write $output",
                bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    stream,
                ),
            )
        }
        assertTrue("Screenshot is empty: $output", output.length() > 0)
    }

    private fun captureScreenshotBitmap(name: String, captureTag: String? = null): Bitmap {
        var lastPixelCopyFailure: AssertionError? = null
        repeat(3) { attempt ->
            compose.waitForIdle()
            try {
                return compose.onNodeWithTag(captureTag ?: SCREENSHOT_ROOT_TAG)
                    .captureToImage()
                    .asAndroidBitmap()
            } catch (failure: AssertionError) {
                if (failure.message?.contains("PixelCopy") != true) {
                    throw failure
                }
                lastPixelCopyFailure = failure
                if (attempt < 2) {
                    SystemClock.sleep(500L)
                }
            }
        }
        throw AssertionError("PixelCopy failed three times while capturing $name").apply {
            initCause(lastPixelCopyFailure)
        }
    }

    private fun calendarState() = RunwayUiState.Ready(
        surface = NativeSurface.Calendar(
            NativeCalendarPayload(
                onboardingRequired = false,
                hasActivePlan = true,
                calendar = NativeCalendar(
                    month = "2026-08",
                    today = "2026-08-12",
                    previousMonth = "2026-07",
                    nextMonth = "2026-09",
                    workouts = listOf(
                        workout("w-1", "2026-08-12", "Easy run", 4_000.0),
                        workout("w-2", "2026-08-15", "Long easy run", 6_000.0),
                        workout("w-3", "2026-08-16", "Rest", null, type = "rest"),
                    ),
                    activities = emptyList(),
                    feedback = emptyList(),
                ),
                activityCandidates = emptyList(),
            ),
        ),
        loading = false,
    )

    private fun inboxState() = RunwayUiState.Ready(
        surface = NativeSurface.Inbox(
            NativeReviewPayload(
                candidates = listOf(workout("w-2", "2026-08-15", "Long easy run", 6_000.0)),
                activities = listOf(
                    NativeActivity(
                        id = "activity-review",
                        workoutId = null,
                        source = "GPX import",
                        reviewState = "review",
                        occurredDate = "2026-08-12",
                        activityDate = "2026-08-12",
                        distanceMeters = 4_200.0,
                        durationSeconds = 1_680.0,
                        averagePaceSecondsPerKm = 400.0,
                        averageHeartRate = null,
                        maxHeartRate = null,
                        heartRateSummary = null,
                        feltHard = false,
                        extraPlanImpactConfirmed = false,
                        consequence = null,
                        routeSummary = null,
                        matchedWorkoutPurpose = null,
                        matchedWorkoutDate = null,
                    ),
                    NativeActivity(
                        id = "activity-extra",
                        workoutId = null,
                        source = "GPX import",
                        reviewState = "accepted",
                        occurredDate = "2026-08-11",
                        activityDate = "2026-08-11",
                        distanceMeters = 5_400.0,
                        durationSeconds = 2_100.0,
                        averagePaceSecondsPerKm = 389.0,
                        averageHeartRate = null,
                        maxHeartRate = null,
                        heartRateSummary = null,
                        feltHard = false,
                        extraPlanImpactConfirmed = true,
                        consequence = NativeConsequence(
                            kind = "extra_activity",
                            appliedDecision = null,
                            recommendedDecision = "keep_plan",
                            deviation = "unplanned",
                            risk = "conservative",
                            planChangeAvailable = true,
                            options = listOf("keep_plan", "reduce_next"),
                            comparisonStatus = "ready",
                            sourceKind = "Activity",
                            sourceId = "activity-extra",
                        ),
                        routeSummary = null,
                        matchedWorkoutPurpose = null,
                        matchedWorkoutDate = null,
                    ),
                ),
            ),
        ),
        loading = false,
    )

    private fun historyState(): RunwayUiState.Ready {
        val active = NativePlanHistoryItem(
            plan = NativePlan(
                id = "plan-current",
                status = "active",
                startDate = "2026-08-03",
                targetDate = "2026-10-18",
                weeks = 11,
                risk = "conservative",
                summaryKind = "distance",
                completedAt = null,
                archivedAt = null,
                lifecycleReason = null,
            ),
            goal = NativeGoalSummary(
                title = "10K",
                targetDate = "2026-10-18",
                state = "active",
                risk = "conservative",
            ),
            summary = NativePlanSummary(
                plannedRuns = 3,
                completedRuns = 2,
                missedRuns = 1,
                skippedRuns = 0,
                completedDistanceMeters = 9_200.0,
            ),
        )
        val prior = NativePlanHistoryItem(
            plan = NativePlan(
                id = "plan-spring",
                status = "completed",
                startDate = "2026-03-02",
                targetDate = "2026-05-10",
                weeks = 10,
                risk = "conservative",
                summaryKind = "distance",
                completedAt = "2026-05-10",
                archivedAt = null,
                lifecycleReason = "goal_complete",
            ),
            goal = NativeGoalSummary(
                title = "Spring 5K",
                targetDate = "2026-05-10",
                state = "completed",
                risk = "conservative",
            ),
            summary = NativePlanSummary(
                plannedRuns = 24,
                completedRuns = 23,
                missedRuns = 1,
                skippedRuns = 0,
                completedDistanceMeters = 68_400.0,
            ),
        )
        return RunwayUiState.Ready(
            surface = NativeSurface.History(
                NativeHistoryPayload(
                    onboardingRequired = false,
                    history = NativePlanHistoryPage(
                        items = listOf(active, prior),
                        nextOffset = null,
                        today = "2026-08-12",
                    ),
                    activeItem = active,
                    phaseReview = null,
                    offset = null,
                    pageSize = null,
                ),
            ),
            loading = false,
        )
    }

    private fun statsState(): RunwayUiState.Ready {
        val plan = NativePlan(
            id = "plan-1",
            status = "active",
            startDate = "2026-08-03",
            targetDate = "2026-10-18",
            weeks = 11,
            risk = "conservative",
            summaryKind = "distance",
            completedAt = null,
            archivedAt = null,
            lifecycleReason = null,
        )
        val active = NativePlanHistoryItem(
            plan = plan,
            goal = NativeGoalSummary(title = "10K", targetDate = "2026-10-18", state = "active", risk = "conservative"),
            summary = NativePlanSummary(
                plannedRuns = 3,
                completedRuns = 2,
                missedRuns = 0,
                skippedRuns = 0,
                completedDistanceMeters = 9_200.0,
            ),
        )
        val weeklySummary = NativeWeekSummary(
            weekNumber = 2,
            startDate = "2026-08-10",
            targetDistanceMeters = 11_000.0,
            completedDistanceMeters = 9_200.0,
            completedDurationSeconds = 3_720.0,
            plannedRuns = 3,
            completedRuns = 2,
            missedRuns = 0,
            skippedRuns = 0,
            hardFlags = 0,
            averagePaceSecondsPerKm = 404.0,
            averageHeartRate = null,
        )
        return RunwayUiState.Ready(
            surface = NativeSurface.Stats(
                NativeStatsPayload(
                    onboardingRequired = false,
                    active = active,
                    detail = NativePlanDetail(
                        listOf(NativeWeek("week-2", 2, "2026-08-10", 11_000.0, null, 9_200.0, "conservative", false)),
                    ),
                    history = NativeTrainingHistory(
                        weeklySummaries = listOf(weeklySummary),
                        todayIso = "2026-08-12",
                        currentSignal = NativeCurrentSignal("conservative", listOf("Recorded work remains below the current plan."), "plan"),
                        hasAcceptedActivities = true,
                        recordedSummary = null,
                        heartRateSample = null,
                    ),
                    planTrace = listOf(
                        NativePlanTraceWeek("trace-1", 1, "2026-08-03", 9_000.0, null, 9_000.0, null),
                        NativePlanTraceWeek("trace-2", 2, "2026-08-10", 11_000.0, null, 11_000.0, null),
                    ),
                    planHistory = null,
                    phaseReview = null,
                ),
            ),
            loading = false,
        )
    }

    private fun settingsState() = RunwayUiState.Ready(
        surface = NativeSurface.Settings(
            NativeSettingsState(
                timeZone = "America/Halifax",
                routePrivacy = NativeRoutePrivacy.KeepPrivate,
                heartRatePrivacy = NativeHeartRatePrivacy.Discard,
                training = NativeTrainingSettings.Active(
                    title = "Autumn 10K",
                    phase = "distance",
                    startedOn = "2026-07-20",
                    targetDate = "2026-10-18",
                    runsPerWeek = 3,
                ),
                notifications = NativeNotificationSettings(
                    runReminderEnabled = true,
                    runReminderMinuteOfDay = 8 * 60,
                    folderImportAlertsEnabled = true,
                ),
                folderImport = NativeImportConnection.Connected,
                appVersion = "0.0.0-screenshot",
                sourceCommit = "documentation-fixture",
            ),
        ),
        loading = false,
    )

    private fun workout(
        id: String,
        date: String,
        purpose: String,
        distanceMeters: Double?,
        type: String = "easy",
    ) = NativeWorkout(
        id = id,
        weekId = "week-2",
        weekNumber = 2,
        scheduledDate = date,
        type = type,
        status = "planned",
        targetDistanceMeters = distanceMeters,
        targetDurationSeconds = null,
        prescriptionKind = "distance",
        intervalStructure = null,
        intensity = "easy",
        purpose = purpose,
        reason = null,
        isRemoved = false,
        isEdited = false,
        adjustment = null,
    )

    private companion object {
        const val SCREENSHOT_DIRECTORY = "documentation-screenshots"
        const val SCREENSHOT_ROOT_TAG = "documentation-screenshot-root"
    }
}
