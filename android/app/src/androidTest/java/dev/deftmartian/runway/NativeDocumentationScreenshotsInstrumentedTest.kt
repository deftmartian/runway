package dev.deftmartian.runway

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
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
        capture("calendar-dark", darkTheme = true, calendarState())

    @Test
    fun capturesInboxInLightTheme() =
        capture("inbox-light", darkTheme = false, inboxState())

    @Test
    fun capturesStatsInLightTheme() =
        capture("stats-light", darkTheme = false, statsState())

    @Test
    fun capturesSettingsInDarkTheme() =
        capture("settings-dark", darkTheme = true, settingsState())

    private fun capture(name: String, darkTheme: Boolean, state: RunwayUiState.Ready) {
        compose.setContent {
            RunwayTheme(darkTheme = darkTheme, dynamicColor = false) {
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
                    onHealthContextChanged = {},
                    onEraseImportedActivityData = {},
                    onEraseAllData = {},
                )
            }
        }
        compose.waitForIdle()

        val directory = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            SCREENSHOT_DIRECTORY,
        ).apply { mkdirs() }
        val output = File(directory, "$name.png")
        FileOutputStream(output).use { stream ->
            assertTrue(
                "Could not write $output",
                compose.onRoot().captureToImage().asAndroidBitmap().compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    stream,
                ),
            )
        }
        assertTrue("Screenshot is empty: $output", output.length() > 0)
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
                nextWorkout = workout("w-2", "2026-08-15", "Long easy run", 6_000.0),
                activityCandidates = emptyList(),
                pendingReviewCount = 2,
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
                        pain = false,
                        extraPlanImpactConfirmed = false,
                        consequence = null,
                        routeSummary = null,
                        matchedWorkoutPurpose = null,
                        matchedWorkoutDate = null,
                    ),
                ),
            ),
        ),
        loading = false,
    )

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
            summary = NativePlanSummary(3, 2, 0, 0, 0, 9_200.0),
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
            painFlags = 0,
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
                        currentSignal = NativeCurrentSignal("conservative", listOf("Recorded work remains below the current plan."), "plan", null),
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
    }
}
