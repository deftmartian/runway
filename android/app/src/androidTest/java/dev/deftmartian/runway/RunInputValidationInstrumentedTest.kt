package dev.deftmartian.runway

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunInputValidationInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun manualRunExplainsInvalidDateBeforeSubmission() {
        compose.setContent {
            RunwayTheme {
                ManualRunDialog(
                    actionPending = false,
                    defaultDate = "2026-07-31",
                    errorMessage = null,
                    onDismiss = {},
                    onSubmit = {},
                )
            }
        }

        compose.onNodeWithText("Date (YYYY-MM-DD)")
            .performTextReplacement("2026-7-1")
        compose.onNodeWithText("Use a complete date in YYYY-MM-DD format.")
            .assertIsDisplayed()
        compose.onNodeWithText("Save for review").assertIsNotEnabled()
    }
}
