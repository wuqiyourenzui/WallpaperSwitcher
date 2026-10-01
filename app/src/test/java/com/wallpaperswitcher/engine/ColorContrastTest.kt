package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorContrastTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `luminance spans black to white`() {
        assertEquals(0f, ColorContrast.luminance(black), 0.001f)
        assertEquals(1f, ColorContrast.luminance(white), 0.001f)
    }

    @Test
    fun `contrast ratio matches the WCAG extremes`() {
        assertEquals(21f, ColorContrast.contrastRatio(black, white), 0.01f)
        assertEquals(1f, ColorContrast.contrastRatio(white, white), 0.001f)
        // Order does not matter.
        assertEquals(
            ColorContrast.contrastRatio(black, white),
            ColorContrast.contrastRatio(white, black),
            0.001f
        )
    }

    @Test
    fun `readableOn picks the opposite end for extreme accents`() {
        assertEquals(white, ColorContrast.readableOn(0xFF3F51B5.toInt()))   // dark indigo
        assertEquals(black, ColorContrast.readableOn(0xFFFFF59D.toInt()))   // pale yellow
    }

    @Test
    fun `readableOn always yields a usable pairing for mid tones`() {
        for (color in listOf(0xFF808080, 0xFF6750A4, 0xFF00BCD4, 0xFFF44336, 0xFF4CAF50)) {
            val accent = color.toInt()
            val on = ColorContrast.readableOn(accent)
            assertTrue(
                "on-colour for %08X must reach 3:1".format(accent),
                ColorContrast.contrastRatio(on, accent) >= ColorContrast.AA_LARGE
            )
        }
    }

    @Test
    fun `ensureReadable fixes a pale accent on a light surface`() {
        val surface = 0xFFFFFBFE.toInt()
        val paleAccent = 0xFFFFF9C4.toInt()                     // 淡黄，正是问题场景
        assertTrue(
            "pale yellow on white is unreadable to begin with",
            ColorContrast.contrastRatio(paleAccent, surface) < ColorContrast.AA_NORMAL
        )
        val fixed = ColorContrast.ensureReadable(paleAccent, surface)
        assertTrue(
            "adjusted accent must reach AA: %.2f".format(
                ColorContrast.contrastRatio(fixed, surface)
            ),
            ColorContrast.contrastRatio(fixed, surface) >= ColorContrast.AA_NORMAL
        )
        // Hue is preserved (yellow stays yellow, it just gets darker).
        val before = ColorContrast.toHsl(paleAccent)
        val after = ColorContrast.toHsl(fixed)
        assertEquals("hue", before[0], after[0], 2f)
        assertTrue("must get darker", after[2] < before[2])
    }

    @Test
    fun `ensureReadable fixes a dark accent on a dark surface`() {
        val darkSurface = 0xFF141218.toInt()
        val darkAccent = 0xFF1B1B2F.toInt()
        val fixed = ColorContrast.ensureReadable(darkAccent, darkSurface)
        assertTrue(
            "adjusted accent must reach AA on dark: %.2f".format(
                ColorContrast.contrastRatio(fixed, darkSurface)
            ),
            ColorContrast.contrastRatio(fixed, darkSurface) >= ColorContrast.AA_NORMAL
        )
        assertTrue("must get lighter", ColorContrast.toHsl(fixed)[2] > ColorContrast.toHsl(darkAccent)[2])
    }

    @Test
    fun `ensureReadable leaves an already readable accent untouched`() {
        val surface = 0xFFFFFBFE.toInt()
        val good = 0xFF6750A4.toInt()
        assertTrue(ColorContrast.contrastRatio(good, surface) >= ColorContrast.AA_NORMAL)
        assertEquals(good, ColorContrast.ensureReadable(good, surface))
    }

    @Test
    fun `hsl conversion round trips through the picker grid`() {
        for (column in 0 until ColorPickerGrid.COLUMNS) {
            for (row in 0 until ColorPickerGrid.ROWS) {
                val argb = ColorPickerGrid.colorAt(column, row)
                val hsl = ColorContrast.toHsl(argb)
                val rebuilt = ColorPickerGrid.hslToRgb(hsl[0], hsl[2], hsl[1])
                // Byte-accurate would be nicer, but the grid is generated *from*
                // HSL, so a 1/255 rounding difference per channel is expected.
                for (shift in listOf(16, 8, 0)) {
                    val a = (argb shr shift) and 0xFF
                    val b = (rebuilt shr shift) and 0xFF
                    assertTrue(
                        "cell ($column,$row) channel %d: %d vs %d".format(shift, a, b),
                        kotlin.math.abs(a - b) <= 1
                    )
                }
            }
        }
    }
}
