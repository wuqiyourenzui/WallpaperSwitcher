package com.wallpaperswitcher.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.wallpaperswitcher.engine.ColorContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the *real* backdrop of accent-coloured text.
 *
 * The custom scheme draws `onPrimaryContainer` / `onSurfaceVariant` text on
 * translucent containers (`primary.copy(alpha = …)` painted over the surface), not
 * on the bare surface. The earlier test asserted against `scheme.surface`, so it
 * could not see that a saturated accent leaves those labels below WCAG AA - these
 * assertions use the composited backdrop instead, which is what the user sees.
 */
class ContainerContrastTest {

    private val accents = listOf(
        0xFFFF0000, // 纯红 - 容器被拉向文字颜色，最坏情况
        0xFFFFF9C4, // 淡黄
        0xFFFFFFFF, // 纯白
        0xFF000000, // 纯黑
        0xFF00FF00, // 高饱和绿
        0xFF6750A4, // 默认紫
        0xFF1E88E5, // 默认蓝
        0xFF00FFFF, // 青
    )

    private fun composite(fg: Int, bg: Int, alpha: Float) = ColorContrast.composite(fg, bg, alpha)

    @Test
    fun `composite blends towards each endpoint and the middle`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        // alpha 0 -> the background shows through, alpha 1 -> the foreground wins.
        assertEquals(black, composite(white, black, 0f))
        assertEquals(white, composite(white, black, 1f))
        assertEquals(black, composite(black, white, 1f))
        val half = composite(white, black, 0.5f)
        assertEquals("half blend of white over black", 128.0, ((half shr 16) and 0xFF).toDouble(), 1.0)
    }

    @Test
    fun `light scheme keeps container labels readable on the composited container`() {
        for (hex in accents) {
            val scheme = customLightColorScheme(Color(hex.toInt()))
            val surface = scheme.surface.toArgb()
            val container = composite(hex.toInt(), surface, CONTAINER_ALPHA_LIGHT)
            val onContainer = scheme.onPrimaryContainer.toArgb()
            val ratio = ColorContrast.contrastRatio(onContainer, container)
            assertTrue(
                "light: %08X on its container gives %.2f:1".format(hex, ratio),
                ratio >= ColorContrast.AA_NORMAL
            )
            val variant = composite(hex.toInt(), surface, SURFACE_VARIANT_ALPHA_LIGHT)
            val onVariant = ColorContrast.contrastRatio(scheme.onSurfaceVariant.toArgb(), variant)
            assertTrue(
                "light: %08X on surfaceVariant gives %.2f:1".format(hex, onVariant),
                onVariant >= ColorContrast.AA_NORMAL
            )
        }
    }

    @Test
    fun `dark scheme keeps container labels readable on the composited container`() {
        for (hex in accents) {
            val scheme = customDarkColorScheme(Color(hex.toInt()))
            val surface = scheme.surface.toArgb()
            val container = composite(hex.toInt(), surface, CONTAINER_ALPHA_DARK)
            val ratio = ColorContrast.contrastRatio(scheme.onPrimaryContainer.toArgb(), container)
            assertTrue(
                "dark: %08X on its container gives %.2f:1".format(hex, ratio),
                ratio >= ColorContrast.AA_NORMAL
            )
            val variant = composite(hex.toInt(), surface, SURFACE_VARIANT_ALPHA_DARK)
            val onVariant = ColorContrast.contrastRatio(scheme.onSurfaceVariant.toArgb(), variant)
            assertTrue(
                "dark: %08X on surfaceVariant gives %.2f:1".format(hex, onVariant),
                onVariant >= ColorContrast.AA_NORMAL
            )
        }
    }
}
