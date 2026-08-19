/**
 * Spendly — design system.
 *
 * One canvas, one accent, two semantic colours, and a six-hue series palette
 * used only to tell data apart. Colour is never decoration: a hue on screen
 * always stands for a category, a direction, or a comparison against the plan.
 *
 * The values are the web app's tokens, unchanged, so the two builds are
 * recognisably the same product.
 */
package az.spendly.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class SpendlyColors(
    val background: Color,
    val surface: Color,
    val surfaceTop: Color,
    val surfaceSunken: Color,
    val surfaceInset: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val positive: Color,
    val positiveSoft: Color,
    val negative: Color,
    val negativeSoft: Color,
    /** Data series, assigned by rank, never by taste. */
    val series: List<Color>,
    val track: Color,
    val onAccent: Color,
)

private val LightColors = SpendlyColors(
    background = Color(0xFFF2F3FA),
    surface = Color(0xFFFFFFFF),
    surfaceTop = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEEF0F8),
    surfaceInset = Color(0xFFF6F7FC),
    border = Color(0xFFE4E6F1),
    borderStrong = Color(0xFFCFD3E6),
    text = Color(0xFF14172A),
    textMuted = Color(0xFF5C6180),
    textFaint = Color(0xFF8B90AC),
    accent = Color(0xFF4A5BF0),
    accentSoft = Color(0x1A4A5BF0),
    positive = Color(0xFF0F9D76),
    positiveSoft = Color(0x1F0F9D76),
    negative = Color(0xFFE0356F),
    negativeSoft = Color(0x1CE0356F),
    series = listOf(
        Color(0xFF4B5BEF),
        Color(0xFF0DA97E),
        Color(0xFFE6407A),
        Color(0xFFDD8A1B),
        Color(0xFF8B52E8),
        Color(0xFF1499C9),
    ),
    track = Color(0xFFE7E9F4),
    onAccent = Color(0xFFFFFFFF),
)

private val DarkColors = SpendlyColors(
    background = Color(0xFF090B16),
    surface = Color(0xFF12162B),
    surfaceTop = Color(0xFF171C36),
    surfaceSunken = Color(0xFF1B2140),
    surfaceInset = Color(0xFF0D1022),
    border = Color(0x12FFFFFF),
    borderStrong = Color(0x29FFFFFF),
    text = Color(0xFFEEF0FB),
    textMuted = Color(0xFF9AA1C4),
    textFaint = Color(0xFF6D7398),
    accent = Color(0xFF6B7BFF),
    accentSoft = Color(0x2E6B7BFF),
    positive = Color(0xFF2FD6A5),
    positiveSoft = Color(0x262FD6A5),
    negative = Color(0xFFFF5C93),
    negativeSoft = Color(0x29FF5C93),
    series = listOf(
        Color(0xFF6B7BFF),
        Color(0xFF2FD6A5),
        Color(0xFFFF5C93),
        Color(0xFFFFB057),
        Color(0xFFB07BFF),
        Color(0xFF3FC9F0),
    ),
    track = Color(0x14FFFFFF),
    onAccent = Color(0xFF0B0E1C),
)

val LocalSpendlyColors = staticCompositionLocalOf { LightColors }

/** Corner radii, matching the web app's --radius scale. */
object Radius {
    val lg = 20.dp
    val md = 16.dp
    val sm = 10.dp
    val xs = 7.dp
}

private val SpendlyTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    /** The `.micro` label: small, upper, tracked out. */
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
    ),
)

@Composable
fun SpendlyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    val material = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surfaceSunken,
            onSurfaceVariant = colors.textMuted,
            error = colors.negative,
            outline = colors.borderStrong,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surfaceSunken,
            onSurfaceVariant = colors.textMuted,
            error = colors.negative,
            outline = colors.borderStrong,
        )
    }

    CompositionLocalProvider(LocalSpendlyColors provides colors) {
        MaterialTheme(
            colorScheme = material,
            typography = SpendlyTypography,
            content = content,
        )
    }
}

/** The palette, from anywhere in the tree. */
val spendlyColors: SpendlyColors
    @Composable get() = LocalSpendlyColors.current
