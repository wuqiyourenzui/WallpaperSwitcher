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
internal val LightColorScheme = lightColorScheme(
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

internal val DarkColorScheme = darkColorScheme(
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
    // 对话框（AlertDialog 用 extraLarge）：28dp → 20dp，贴近 HyperOS/Miuix
    // 对话框的圆角，同时与页面卡片的 16dp 保持层级差。
    extraLarge = RoundedCornerShape(20.dp),
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
enum class ThemeMode(val value: String, @androidx.annotation.StringRes val labelRes: Int) {
    SYSTEM(
        com.wallpaperswitcher.data.SettingsKeys.THEME_MODE_SYSTEM,
        com.wallpaperswitcher.R.string.theme_mode_system
    ),
    LIGHT(
        com.wallpaperswitcher.data.SettingsKeys.THEME_MODE_LIGHT,
        com.wallpaperswitcher.R.string.theme_mode_light
    ),
    DARK(
        com.wallpaperswitcher.data.SettingsKeys.THEME_MODE_DARK,
        com.wallpaperswitcher.R.string.theme_mode_dark
    );

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
 * Accent roles only: the picked colour drives `primary`, the containers and
 * their "on" colours, while every NEUTRAL role - onBackground/onSurface,
 * **onSurfaceVariant**, surface/background/surfaceVariant, outline, error - keeps
 * the built-in Material 3 value.
 *
 * The earlier version also painted `onSurfaceVariant` (and `surfaceVariant`) with
 * the accent. This app draws most secondary labels, card subtitles, hints and
 * settings icons with `onSurfaceVariant`, so picking a colour turned a large part
 * of the UI - text as well as graphics - into that colour (user report:
 * 「软件界面有些文字和图形会随主题颜色变化，影响观感」). Neutral roles now stay
 * neutral; only chips, buttons, badges, selected states and the few places that
 * deliberately use [LocalAccentColor] follow the accent, which is what "主题颜色"
 * is for.
 *
 * Light/dark are derived from the same hex by mixing toward white/black (a tonal
 * palette in miniature), so contrast holds in both modes - using the raw hex as
 * `onSecondaryContainer` on a dark container (the old behaviour) left almost no
 * contrast for dark accents.
 */
private fun accentScheme(base: ColorScheme, dark: Boolean, accent: Color): ColorScheme {
    // Dark mode lifts a dark accent toward white (M3 tone ~80); light mode keeps
    // the picked colour as it is (tone ~40).
    val primary = if (dark) lerp(accent, ToneWhite, 0.55f) else accent
    val container = lerp(primary, if (dark) ToneBlack else ToneWhite, if (dark) 0.55f else 0.86f)
    val onContainer =
        lerp(primary, if (dark) ToneWhite else ToneBlack, if (dark) 0.85f else 0.62f)
    return base.copy(
        primary = primary,
        onPrimary = Color(ColorContrast.readableOn(primary.toArgb())),
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = lerp(primary, ToneWhite, 0.5f),
        // "Quiet accent" role used by chips / navigation items: the same tonal
        // pair, so contrast never depends on the picked hex.
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        // Elevated surfaces tint toward the accent instead of the built-in
        // purple; the surface colours themselves stay neutral.
        surfaceTint = primary
    )
}

/** Generate a light color scheme from a custom accent colour. */
fun customLightColorScheme(primary: Color): ColorScheme =
    accentScheme(LightColorScheme, dark = false, accent = primary)

/** Generate a dark color scheme from a custom accent colour. */
fun customDarkColorScheme(primary: Color): ColorScheme =
    accentScheme(DarkColorScheme, dark = true, accent = primary)

/** Blend [color] toward [target] by [amount] (0 = color, 1 = target). */
private fun lerp(color: Color, target: Color, amount: Float): Color =
    androidx.compose.ui.graphics.lerp(color, target, amount)

private val ToneBlack = Color(0xFF000000)
private val ToneWhite = Color(0xFFFFFFFF)

/** The accent, darkened/lightened until accent-coloured text is readable on [surface]. */
internal fun readableAccent(primary: Color, surface: Color): Color = Color(
    ColorContrast.ensureReadable(
        primary.toArgb(),
        surface.toArgb(),
        ColorContrast.AA_NORMAL
    )
)

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

    // [LocalAccentColor] is the "accent for TEXT" hook a few places use on
    // purpose (selection counts, a couple of badges). It must be readable on the
    // neutral surface for whichever colour was picked, and it must NOT be the
    // general secondary-text colour - that is `onSurfaceVariant`, which stays
    // neutral now (see accentScheme).
    val accentText = remember(colorScheme, customColor) {
        readableAccent(customColor ?: colorScheme.primary, colorScheme.surface)
    }
    CompositionLocalProvider(LocalAccentColor provides accentText) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = AppShapes,
            typography = AppTypography,
            content = content
        )
    }
}
