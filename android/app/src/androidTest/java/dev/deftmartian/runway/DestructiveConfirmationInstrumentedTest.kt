package dev.deftmartian.runway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DestructiveConfirmationInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun removing_a_saved_result_requires_explicit_confirmation() {
        var removals = 0
        compose.setContent {
            var recordedRemovals by remember { mutableIntStateOf(0) }
            removals = recordedRemovals
            RunwayTheme {
                FeedbackOutcomeCard(
                    feedback = NativeWorkoutFeedback(
                        id = "feedback-1",
                        workoutId = "workout-1",
                        completedDistanceMeters = 5_000.0,
                        completedDurationSeconds = 1_800.0,
                        feltHard = true,
                        pain = false,
                        consequence = null,
                        canDelete = true,
                    ),
                    actionPending = false,
                    onDecision = {},
                    onDelete = { recordedRemovals++ },
                )
            }
        }

        compose.onNodeWithText("Remove saved result").performClick()
        compose.onNodeWithText("Remove saved result?").assertIsDisplayed()
        compose.onNodeWithText(
            "This removes the saved result and effort report. The planned workout remains unchanged. This cannot be undone.",
        ).assertIsDisplayed()
        assertEquals(0, removals)

        compose.onNodeWithText("Remove result").performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }
}
