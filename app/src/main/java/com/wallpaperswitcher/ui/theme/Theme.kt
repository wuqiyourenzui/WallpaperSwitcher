package com.wallpaperswitcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Full Material 3 tonal palette: every role is filled in so components like
// the top/bottom bars, dialogs and sliders pick up consistent surface tones
// instead of falling back to defaults.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    inversePrimary = Color(0xFFD0BCFF),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    surfaceTint = Color(0xFF6750A4),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    inversePrimary = Color(0xFF6750A4),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    surfaceTint = Color(0xFFD0BCFF),
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    scrim = Color(0xFF000000),
)

// Friendly, rounded shape system applied to all Material components.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// Slightly tightened, bolder typography hierarchy.
private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

/**
 * Parse a hex color string like "#6750A4" to a Color. Returns null if invalid.
 */
fun parseHexColor(hex: String): Color? =
    com.wallpaperswitcher.util.parseHexColorInt(hex)?.let { Color(it) }

/**
 * Generate a light color scheme with a custom primary color. The container
 * and surface tones are tinted with the primary so the whole UI follows the
 * chosen accent instead of only the buttons.
 */
fun customLightColorScheme(primary: Color): ColorScheme {
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = primary.copy(alpha = 0.15f),
        onPrimaryContainer = primary,
        secondaryContainer = primary.copy(alpha = 0.12f),
        onSecondaryContainer = primary,
        surfaceVariant = primary.copy(alpha = 0.10f),
        onSurfaceVariant = primary,
    )
}

/**
 * Generate a dark color scheme with a custom primary color.
 */
fun customDarkColorScheme(primary: Color): ColorScheme {
    return darkColorScheme(
        primary = primary,
        onPrimary = Color.Black,
        primaryContainer = primary.copy(alpha = 0.3f),
        onPrimaryContainer = primary,
        secondaryContainer = primary.copy(alpha = 0.22f),
        onSecondaryContainer = primary,
        surfaceVariant = primary.copy(alpha = 0.18f),
        onSurfaceVariant = primary,
    )
}

@Composable
fun WallpaperSwitcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColorHex: String = "",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val customColor = remember(themeColorHex) {
        if (themeColorHex.isNotEmpty()) parseHexColor(themeColorHex) else null
    }

    // Build the color scheme only when an input actually changed. Recreating
    // dynamic/custom color schemes on every recomposition was a visible source
    // of jank when switching theme colors (each color tap rebuilt the whole
    // scheme tree and re-queried the system palette).
    val colorScheme = remember(darkTheme, themeColorHex, customColor, context) {
        when {
            // Custom color takes priority
            customColor != null -> {
                if (darkTheme) customDarkColorScheme(customColor)
                else customLightColorScheme(customColor)
            }
            // Android 12+ dynamic color
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
