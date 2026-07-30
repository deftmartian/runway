package dev.deftmartian.runway

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunwayThemeTest {
    @Test
    fun `dynamic color begins on Android 12 and can be disabled for deterministic fallback`() {
        assertFalse(shouldUseDynamicColor(dynamicColor = true, sdkInt = 30))
        assertTrue(shouldUseDynamicColor(dynamicColor = true, sdkInt = 31))
        assertFalse(shouldUseDynamicColor(dynamicColor = false, sdkInt = 36))
    }

    @Test
    fun `training meanings map to active Material roles instead of fixed hues`() {
        val scheme = lightColorScheme(
            primary = Color(0xFF010101),
            secondary = Color(0xFF020202),
            secondaryContainer = Color(0xFF030303),
            onSecondaryContainer = Color(0xFF040404),
            tertiary = Color(0xFF050505),
            error = Color(0xFF060606),
            onSurfaceVariant = Color(0xFF070707),
        )

        assertEquals(scheme.primary, runwaySemanticColor(scheme, RunwaySemanticRole.Planned))
        assertEquals(scheme.tertiary, runwaySemanticColor(scheme, RunwaySemanticRole.Actual))
        assertEquals(scheme.secondary, runwaySemanticColor(scheme, RunwaySemanticRole.Review))
        assertEquals(
            scheme.secondaryContainer,
            runwaySemanticColor(scheme, RunwaySemanticRole.ReviewContainer),
        )
        assertEquals(
            scheme.onSecondaryContainer,
            runwaySemanticColor(scheme, RunwaySemanticRole.OnReviewContainer),
        )
        assertEquals(scheme.error, runwaySemanticColor(scheme, RunwaySemanticRole.Danger))
        assertEquals(scheme.onSurfaceVariant, runwaySemanticColor(scheme, RunwaySemanticRole.Neutral))
    }
}
