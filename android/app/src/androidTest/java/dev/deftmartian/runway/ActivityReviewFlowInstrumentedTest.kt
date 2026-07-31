package dev.deftmartian.runway

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityReviewFlowInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reviewCollectsFeedbackBeforeAcceptingTheActivityRole() {
        var emitted: MobileCommand? = null
        compose.setContent {
            RunwayTheme {
                ActivityDetailSheet(
                    activity = reviewActivity(),
                    candidates = emptyList(),
                    evidence = null,
                    evidenceLoading = false,
                    evidenceFailed = false,
                    actionPending = false,
                    onDismiss = {},
                    onLoadRouteTrace = {},
                    onAction = { emitted = it },
                )
            }
        }

        val feedback = compose.onNodeWithText("How did this run feel?")
        val role = compose.onNodeWithText("Where does this run belong?")
        feedback.performScrollTo()
        assertTrue(
            feedback.fetchSemanticsNode().boundsInRoot.top <
                role.fetchSemanticsNode().boundsInRoot.top,
        )

        compose.onNodeWithText("This run felt harder than expected").performClick()
        compose.onNodeWithText("Pain during or after this run").performClick()
        compose.onNodeWithText("Count as an extra run").performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(
                ConfirmActivityExtraCommand(
                    activityId = "review-activity",
                    feltHard = true,
                    pain = true,
                ),
                emitted,
            )
        }
    }

    private fun reviewActivity() = NativeActivity(
        id = "review-activity",
        workoutId = null,
        source = "gpx",
        reviewState = "review",
        occurredDate = "2026-07-30",
        activityDate = "2026-07-30",
        distanceMeters = 3_000.0,
        durationSeconds = 1_800.0,
        averagePaceSecondsPerKm = 600.0,
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
    )
}
