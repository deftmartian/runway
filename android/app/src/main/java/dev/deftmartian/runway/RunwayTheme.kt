package dev.deftmartian.runway

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Runway deliberately does not use Android's dynamic colour scheme. A plan and its
 * recorded work keep the same semantic colours across phones: blue is planned,
 * green is accepted actual work, and amber asks for review.
 */
private val runwayLightColors = lightColorScheme(
    primary = Color(0xFF236B80),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCECEF),
    onPrimaryContainer = Color(0xFF123C49),
    secondary = Color(0xFF596963),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFECEFE9),
    onSecondaryContainer = Color(0xFF26352F),
    tertiary = Color(0xFF3E7658),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDEDE1),
    onTertiaryContainer = Color(0xFF193A27),
    background = Color(0xFFF4F2EC),
    onBackground = Color(0xFF1D2926),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF1D2926),
    surfaceVariant = Color(0xFFECEFE9),
    onSurfaceVariant = Color(0xFF596963),
    outline = Color(0xFF71837A),
    outlineVariant = Color(0xFFD7DDD6),
    error = Color(0xFFAA4650),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6DEE0),
    onErrorContainer = Color(0xFF4B111A),
)

private val runwayDarkColors = darkColorScheme(
    primary = Color(0xFF79BBCD),
    onPrimary = Color(0xFF103E4A),
    primaryContainer = Color(0xFF234E5B),
    onPrimaryContainer = Color(0xFFD8EEF4),
    secondary = Color(0xFFB3BBB4),
    onSecondary = Color(0xFF26302B),
    secondaryContainer = Color(0xFF303A35),
    onSecondaryContainer = Color(0xFFE1E7E0),
    tertiary = Color(0xFF87BF98),
    onTertiary = Color(0xFF173C26),
    tertiaryContainer = Color(0xFF315B40),
    onTertiaryContainer = Color(0xFFDCF1E0),
    background = Color(0xFF151A18),
    onBackground = Color(0xFFF0EEE7),
    surface = Color(0xFF1D2421),
    onSurface = Color(0xFFF0EEE7),
    surfaceVariant = Color(0xFF27302C),
    onSurfaceVariant = Color(0xFFB3BBB4),
    outline = Color(0xFF70847A),
    outlineVariant = Color(0xFF3A4640),
    error = Color(0xFFEF8C94),
    onError = Color(0xFF571B24),
    errorContainer = Color(0xFF71313A),
    onErrorContainer = Color(0xFFFFD9DD),
)

private val runwayShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

private val runwayTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun RunwayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) runwayDarkColors else runwayLightColors,
        shapes = runwayShapes,
        typography = runwayTypography,
        content = content,
    )
}
