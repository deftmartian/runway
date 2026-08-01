package dev.deftmartian.runway

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryTruthInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun historyShowsEditedAndRemovedPrescriptionsWithoutRepeatingTheCurrentIdentity() {
        compose.setContent {
            RunwayTheme {
                HistoryDetailScreen(historyDetail(), loading = false)
            }
        }

        val list = compose.onNode(hasScrollAction())
        val generatedLabel = "Generated · ${ledgerDate("2026-07-29")} · Easy run"
        list.performScrollToNode(hasText(generatedLabel))
        compose.onNodeWithText(generatedLabel).assertIsDisplayed()
        compose.onAllNodesWithText("${ledgerDate("2026-07-30")} · Easy run").assertCountEquals(1)
        list.performScrollToNode(hasText("Current"))
        compose.onNodeWithText("Current").assertIsDisplayed()
        list.performScrollToNode(hasText("Before removal"))
        compose.onNodeWithText("Before removal").assertIsDisplayed()
        compose.onAllNodesWithText("${ledgerDate("2026-08-02")} · Easy run").assertCountEquals(1)
        list.performScrollToNode(hasText("Removed from the current plan"))
        compose.onNodeWithText("Removed from the current plan").assertIsDisplayed()
    }

    private fun historyDetail() = NativeHistoryDetailPayload(
        onboardingRequired = false,
        detail = NativeHistoryDetail(
            plan = NativeHistoryDetailPlan(
                id = "plan",
                status = "active",
                phase = "distance",
                startDate = "2026-07-27",
                targetDate = "2026-10-04",
                weeks = 10,
                risk = "conservative",
                completedAt = null,
                archivedAt = null,
                lifecycleReason = null,
            ),
            goal = NativeHistoryDetailGoal("5K", "5k", "finish_healthy"),
            cutoffDate = "2026-07-30",
            timeline = emptyList(),
            weeks = listOf(
                NativeHistoryWeek(
                    id = "week",
                    weekNumber = 1,
                    startDate = "2026-07-27",
                    targetDistanceMeters = 5_000.0,
                    targetDurationSeconds = null,
                    risk = "conservative",
                    isDownWeek = false,
                    isTaper = false,
                    workouts = listOf(
                        historyWorkout("edited", removed = false),
                        historyWorkout("removed", removed = true),
                    ),
                ),
            ),
        ),
    )

    private fun historyWorkout(id: String, removed: Boolean) = NativeHistoryWorkout(
        id = id,
        status = if (removed) "tombstoned" else "planned",
        generated = prescription(
            if (removed) "2026-08-01" else "2026-07-29",
            3_000.0,
            "Easy run",
        ),
        current = prescription(
            if (removed) "2026-08-02" else "2026-07-30",
            if (removed) 2_000.0 else 2_500.0,
            if (removed) "Shortened before removal" else "Moved after a hard day",
        ),
        isRemoved = removed,
        result = null,
    )

    private fun prescription(
        date: String,
        distanceMeters: Double,
        purpose: String,
    ) = NativeHistoryPrescription(
        scheduledDate = date,
        type = "easy",
        prescriptionKind = "distance",
        targetDistanceMeters = distanceMeters,
        targetDurationSeconds = null,
        purpose = purpose,
    )
}
