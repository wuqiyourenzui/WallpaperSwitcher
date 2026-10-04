package com.wallpaperswitcher.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.wallpaperswitcher.engine.ColorContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The custom colour scheme must (a) leave every NEUTRAL role alone and (b) stay
 * legible for *any* accent the user picks.
 *
 * (a) is the regression test for 「软件界面有些文字和图形会随主题颜色变化，影响观感」:
 * this app paints most secondary labels, card subtitles and settings icons with
 * `onSurfaceVariant`, and the scheme used to set it (plus `surfaceVariant`) to the
 * accent - so picking a colour re-coloured a large part of the UI. Those roles
 * must now be bit-identical to the built-in scheme; only the accent roles may
 * differ.
 *
 * (b) covers the accent roles themselves: `onPrimary` on `primary`, and each
 * container's "on" colour on its container, for hostile picks (pale yellow, pure
 * white, pure black, saturated green/magenta).
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
    fun `neutral roles never follow the accent`() {
        for (hex in accents) {
            val accent = Color(hex.toInt())
            val light = customLightColorScheme(accent)
            val dark = customDarkColorScheme(accent)
            for ((where, scheme, builtin) in listOf(
                Triple("light", light, LightColorScheme),
                Triple("dark", dark, DarkColorScheme)
            )) {
                assertEquals(
                    "$where: onSurfaceVariant must stay neutral for %08X".format(hex),
                    builtin.onSurfaceVariant, scheme.onSurfaceVariant
                )
                assertEquals(
                    "$where: surfaceVariant must stay neutral for %08X".format(hex),
                    builtin.surfaceVariant, scheme.surfaceVariant
                )
                assertEquals(
                    "$where: onSurface must stay neutral for %08X".format(hex),
                    builtin.onSurface, scheme.onSurface
                )
                assertEquals(
                    "$where: surface must stay neutral for %08X".format(hex),
                    builtin.surface, scheme.surface
                )
                assertEquals(
                    "$where: outline must stay neutral for %08X".format(hex),
                    builtin.outline, scheme.outline
                )
                assertEquals(
                    "$where: error must stay neutral for %08X".format(hex),
                    builtin.error, scheme.error
                )
            }
        }
    }

    @Test
    fun `accent pairs stay readable for every pick`() {
        for (hex in accents) {
            val scheme = customLightColorScheme(Color(hex.toInt()))
            val onPrimary = ColorContrast.contrastRatio(scheme.onPrimary.toArgb(), scheme.primary.toArgb())
            assertTrue(
                "light: onPrimary for %08X contrasts %.2f".format(hex, onPrimary),
                onPrimary >= ColorContrast.AA_LARGE
            )
            val onContainer =
                ColorContrast.contrastRatio(
                    scheme.onPrimaryContainer.toArgb(), scheme.primaryContainer.toArgb()
                )
            assertTrue(
                "light: onPrimaryContainer for %08X contrasts %.2f".format(hex, onContainer),
                onContainer >= ColorContrast.AA_NORMAL
            )
            val onSecondary =
                ColorContrast.contrastRatio(
                    scheme.onSecondaryContainer.toArgb(), scheme.secondaryContainer.toArgb()
                )
            assertTrue(
                "light: onSecondaryContainer for %08X contrasts %.2f".format(hex, onSecondary),
                onSecondary >= ColorContrast.AA_NORMAL
            )
        }
    }

    @Test
    fun `dark accent pairs stay readable for every pick`() {
        for (hex in accents) {
            val scheme = customDarkColorScheme(Color(hex.toInt()))
            val onPrimary = ColorContrast.contrastRatio(scheme.onPrimary.toArgb(), scheme.primary.toArgb())
            assertTrue(
                "dark: onPrimary for %08X contrasts %.2f".format(hex, onPrimary),
                onPrimary >= ColorContrast.AA_LARGE
            )
            val onContainer =
                ColorContrast.contrastRatio(
                    scheme.onPrimaryContainer.toArgb(), scheme.primaryContainer.toArgb()
                )
            assertTrue(
                "dark: onPrimaryContainer for %08X contrasts %.2f".format(hex, onContainer),
                onContainer >= ColorContrast.AA_NORMAL
            )
        }
    }

    @Test
    fun `accent text hook stays readable on the surface`() {
        // LocalAccentColor is the "accent for TEXT" hook (selection counts, a few
        // badges) and is drawn on the NEUTRAL surface - so it must be tuned for
        // that surface regardless of which colour was picked.
        for (hex in accents) {
            val accentText = readableAccent(Color(hex.toInt()), LightColorScheme.surface)
            val ratio = ColorContrast.contrastRatio(
                accentText.toArgb(), LightColorScheme.surface.toArgb()
            )
            assertTrue(
                "accent text for %08X contrasts %.2f".format(hex, ratio),
                ratio >= ColorContrast.AA_NORMAL
            )
        }
    }
}
