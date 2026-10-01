package com.wallpaperswitcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

import com.wallpaperswitcher.engine.ColorContrast
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
 * Light/dark choice from Settings: 跟随系统 / 浅色 / 深色.
 *
 * Stored as [com.wallpaperswitcher.data.SettingsKeys.THEME_MODE]'s string value;
 * anything unknown (or a missing row, e.g. right after an update) behaves like
 * [SYSTEM] so the UI never ends up in a state the user cannot name.
 */
enum class ThemeMode(val value: String, val label: String) {
    SYSTEM(com.wallpaperswitcher.data.SettingsKeys.THEME_MODE_SYSTEM, "跟随系统"),
    LIGHT(com.wallpaperswitcher.data.SettingsKeys.THEME_MODE_LIGHT, "浅色"),
    DARK(com.wallpaperswitcher.data.SettingsKeys.THEME_MODE_DARK, "深色");

    /** Whether this mode wants [MaterialTheme]'s dark scheme right now. */
    @Composable
    fun isDark(): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> isSystemInDarkTheme()
    }

    companion object {
        fun from(value: String?): ThemeMode =
            entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

/**
 * Accent colour that is guaranteed to be legible on the current surface.
 *
 * The custom scheme feeds a contrast-corrected accent into `onSurfaceVariant`, so
 * text drawn with this local stays readable no matter which colour the user picks
 * (a pale accent used to make every secondary label pale-on-pale - the reported
 * "文字和图形随主题颜色变化，影响识别"). Falls back to the scheme's primary for
 * previews that are not wrapped in [WallpaperSwitcherTheme].
 */
val LocalAccentColor = staticCompositionLocalOf { Color.Unspecified }

/**
 * Generate a light color scheme with a custom primary color. The container
 * and surface tones are tinted with the primary so the whole UI follows the
 * chosen accent instead of only the buttons.
 *
 * On-colours are derived from the accent instead of hard-coded: [readableOn] picks
 * black or white for text drawn *on* the accent, and [ensureReadable] pushes the
 * accent away from the surface until accent-coloured text reaches WCAG AA.
 */
fun customLightColorScheme(primary: Color): ColorScheme {
    // The scheme below is built from Material defaults (only the accent slots are
    // overridden), so the accent must be tuned against THAT surface - using the
    // app's own LightColorScheme.surface left dark-mode accents below AA.
    val surface = lightColorScheme().surface
    // Tune the accent against what the text really sits on, not the bare surface:
    // the containers are translucent (`primary.copy(alpha = 0.15f)` etc), so a
    // saturated accent drags the backdrop towards the text colour. Asserting only
    // against the surface hid that (see ContainerContrastTest).
    val accentText = readableAccent(primary, surface, CONTAINER_ALPHA_LIGHT)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color(ColorContrast.readableOn(primary.toArgb())),
        primaryContainer = primary.copy(alpha = CONTAINER_ALPHA_LIGHT),
        onPrimaryContainer = accentText,
        secondaryContainer = primary.copy(alpha = 0.12f),
        onSecondaryContainer = accentText,
        surfaceVariant = primary.copy(alpha = SURFACE_VARIANT_ALPHA_LIGHT),
        onSurfaceVariant = accentText,
    )
}

/**
 * Generate a dark color scheme with a custom primary color.
 */
fun customDarkColorScheme(primary: Color): ColorScheme {
    val surface = darkColorScheme().surface
    val accentText = readableAccent(primary, surface, CONTAINER_ALPHA_DARK)
    return darkColorScheme(
        primary = primary,
        onPrimary = Color(ColorContrast.readableOn(primary.toArgb())),
        primaryContainer = primary.copy(alpha = CONTAINER_ALPHA_DARK),
        onPrimaryContainer = accentText,
        secondaryContainer = primary.copy(alpha = 0.22f),
        onSecondaryContainer = accentText,
        surfaceVariant = primary.copy(alpha = SURFACE_VARIANT_ALPHA_DARK),
        onSurfaceVariant = accentText,
    )
}

/** Container/variant alphas, shared with the tests so they assert the real backdrop. */
const val CONTAINER_ALPHA_LIGHT = 0.15f
const val CONTAINER_ALPHA_DARK = 0.30f
const val SURFACE_VARIANT_ALPHA_LIGHT = 0.10f
const val SURFACE_VARIANT_ALPHA_DARK = 0.18f

/**
 * The accent, darkened/lightened until accent-coloured text is readable on the
 * *composited* container it will actually be drawn on (see
 * [ColorContrast.composite]). The stricter of the two backdrops the accent lands
 * on - the translucent container and the translucent surface variant - decides.
 */
private fun readableAccent(primary: Color, surface: Color, containerAlpha: Float): Color {
    val variantAlpha = if (containerAlpha >= CONTAINER_ALPHA_DARK) {
        SURFACE_VARIANT_ALPHA_DARK
    } else {
        SURFACE_VARIANT_ALPHA_LIGHT
    }
    val onContainer = ColorContrast.composite(primary.toArgb(), surface.toArgb(), containerAlpha)
    val onVariant = ColorContrast.composite(primary.toArgb(), surface.toArgb(), variantAlpha)
    val backdrop = if (
        ColorContrast.contrastRatio(primary.toArgb(), onContainer) <=
        ColorContrast.contrastRatio(primary.toArgb(), onVariant)
    ) {
        onContainer
    } else {
        onVariant
    }
    return Color(
        ColorContrast.ensureReadable(primary.toArgb(), backdrop, ACCENT_TARGET_RATIO)
    )
}

/**
 * Target contrast for accent-coloured text, slightly above WCAG AA.
 *
 * The accent text is drawn on several translucent backdrops (the container, the
 * surface variant) and on the bare surface; they differ by a fraction of a percent,
 * but tuning to exactly 4.5 against one of them left another at 4.49 (a pure white
 * accent was the reported case). The margin costs nothing visible and makes the AA
 * guarantee hold for every backdrop the accent lands on.
 */
private const val ACCENT_TARGET_RATIO = ColorContrast.AA_NORMAL + 0.2f

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

    CompositionLocalProvider(LocalAccentColor provides colorScheme.onSurfaceVariant) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = AppShapes,
            typography = AppTypography,
            content = content
        )
    }
}
