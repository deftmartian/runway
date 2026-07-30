package dev.deftmartian.runway

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunwayThemeInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    fun android12UsesSystemLightAndDarkPalettesForMaterialAndSemanticRoles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expectedLight = dynamicLightColorScheme(context)
        val expectedDark = dynamicDarkColorScheme(context)
        var lightPrimary = Color.Unspecified
        var lightReview = Color.Unspecified
        var lightReviewContainer = Color.Unspecified
        var darkBackground = Color.Unspecified
        var darkActual = Color.Unspecified

        compose.setContent {
            Column {
                RunwayTheme(darkTheme = false) {
                    val scheme = MaterialTheme.colorScheme
                    val review = RunwayThemeTokens.review
                    val reviewContainer = RunwayThemeTokens.reviewContainer
                    SideEffect {
                        lightPrimary = scheme.primary
                        lightReview = review
                        lightReviewContainer = reviewContainer
                    }
                }
                RunwayTheme(darkTheme = true) {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect {
                        darkBackground = scheme.background
                        darkActual = runwaySemanticColor(scheme, RunwaySemanticRole.Actual)
                    }
                }
            }
        }
        compose.waitForIdle()

        assertEquals(expectedLight.primary, lightPrimary)
        assertEquals(expectedLight.secondary, lightReview)
        assertEquals(expectedLight.secondaryContainer, lightReviewContainer)
        assertEquals(expectedDark.background, darkBackground)
        assertEquals(expectedDark.tertiary, darkActual)
    }
}
