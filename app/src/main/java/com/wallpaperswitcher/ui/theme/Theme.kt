package com.wallpaperswitcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFB3261E),
    outline = Color(0xFF79747E),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    error = Color(0xFFF2B8B5),
    outline = Color(0xFF938F99),
)

/**
 * Parse a hex color string like "#6750A4" to a Color. Returns null if invalid.
 */
fun parseHexColor(hex: String): Color? {
    return try {
        val clean = hex.removePrefix("#")
        if (clean.length == 6) {
            Color(android.graphics.Color.parseColor("#$clean"))
        } else null
    } catch (_: Exception) { null }
}

/**
 * Generate a light color scheme with a custom primary color.
 */
fun customLightColorScheme(primary: Color): ColorScheme {
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = primary.copy(alpha = 0.15f),
        onPrimaryContainer = primary,
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
        content = content
    )
}
