package dev.deftmartian.runway

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val runwayLightColors = lightColorScheme(
    primary = Color(0xFF176F67),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC3E9E2),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF52635F),
    secondaryContainer = Color(0xFFD5E8E3),
    background = Color(0xFFF7F3EA),
    onBackground = Color(0xFF18302E),
    surface = Color(0xFFFFFEFB),
    onSurface = Color(0xFF18302E),
    surfaceVariant = Color(0xFFE8F1EF),
    onSurfaceVariant = Color(0xFF536B67),
    outline = Color(0xFF718783),
    error = Color(0xFF9A4523),
)

private val runwayDarkColors = darkColorScheme(
    primary = Color(0xFF88D4C9),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFA5F2E7),
    secondary = Color(0xFFB8CCC6),
    secondaryContainer = Color(0xFF354B46),
    background = Color(0xFF07131D),
    onBackground = Color(0xFFDCE6E3),
    surface = Color(0xFF0E1B24),
    onSurface = Color(0xFFDCE6E3),
    surfaceVariant = Color(0xFF22343C),
    onSurfaceVariant = Color(0xFFB7C9C5),
    outline = Color(0xFF819793),
    error = Color(0xFFFFB59B),
)

@Composable
fun RunwayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> runwayDarkColors
        else -> runwayLightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
