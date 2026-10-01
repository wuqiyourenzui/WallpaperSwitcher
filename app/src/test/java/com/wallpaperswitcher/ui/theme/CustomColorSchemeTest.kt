package com.wallpaperswitcher.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.wallpaperswitcher.engine.ColorContrast
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The custom colour scheme must stay legible for *any* accent the user picks.
 *
 * This is the regression test for "软件界面有些文字和图形会随主题颜色变化，影响识别":
 * the scheme used to hard-code `onPrimary = White` and set
 * `onSurfaceVariant = primary`, so a pale accent (pale yellow on a white surface,
 * or a dark accent in dark mode) made accent text unreadable.
 */
class CustomColorSchemeTest {

    /** Accents a user can realistically pick, including the hostile ones. */
    private val accents = listOf(
        0xFFFFF9C4, // 淡黄 - the reported case
        0xFFFFFFFF, // 纯白
        0xFFEEEEEE, // 浅灰
        0xFF6750A4, // 默认紫
        0xFF1E88E5, // 默认蓝
        0xFF000000, // 纯黑
        0xFF141218, // 近黑
        0xFF00FF00, // 高饱和绿
        0xFFFF00FF, // 品红
    )

    @Test
    fun `light scheme keeps accent text readable on the surface`() {
        for (hex in accents) {
            val scheme = customLightColorScheme(Color(hex.toInt()))
            val surface = scheme.surface.toArgb()
            val ratio = ColorContrast.contrastRatio(scheme.onSurfaceVariant.toArgb(), surface)
            assertTrue(
                "light: accent %08X gives onSurfaceVariant contrast %.2f".format(hex, ratio),
                ratio >= ColorContrast.AA_NORMAL
            )
            val onPrimary = ColorContrast.contrastRatio(scheme.onPrimary.toArgb(), hex.toInt())
            assertTrue(
                "light: onPrimary for %08X contrasts %.2f".format(hex, onPrimary),
                onPrimary >= ColorContrast.AA_LARGE
            )
        }
    }

    @Test
    fun `dark scheme keeps accent text readable on the surface`() {
        for (hex in accents) {
            val scheme = customDarkColorScheme(Color(hex.toInt()))
            val surface = scheme.surface.toArgb()
            val ratio = ColorContrast.contrastRatio(scheme.onSurfaceVariant.toArgb(), surface)
            assertTrue(
                "dark: accent %08X gives onSurfaceVariant contrast %.2f".format(hex, ratio),
                ratio >= ColorContrast.AA_NORMAL
            )
            val onPrimary = ColorContrast.contrastRatio(scheme.onPrimary.toArgb(), hex.toInt())
            assertTrue(
                "dark: onPrimary for %08X contrasts %.2f".format(hex, onPrimary),
                onPrimary >= ColorContrast.AA_LARGE
            )
        }
    }

    @Test
    fun `container text stays readable too`() {
        for (hex in accents) {
            val scheme = customLightColorScheme(Color(hex.toInt()))
            val surface = scheme.surface.toArgb()
            val onContainer =
                ColorContrast.contrastRatio(scheme.onPrimaryContainer.toArgb(), surface)
            assertTrue(
                "onPrimaryContainer for %08X contrasts %.2f".format(hex, onContainer),
                onContainer >= ColorContrast.AA_NORMAL
            )
        }
    }
}
