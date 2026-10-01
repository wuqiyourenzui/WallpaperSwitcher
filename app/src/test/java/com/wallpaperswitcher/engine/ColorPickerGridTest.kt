package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorPickerGridTest {

    @Test
    fun `hue columns are evenly spaced around the wheel`() {
        assertEquals(0f, ColorPickerGrid.hueAt(0), 0.01f)
        assertEquals(30f, ColorPickerGrid.hueAt(1), 0.01f)
        assertEquals(330f, ColorPickerGrid.hueAt(11), 0.01f)
        // Clamped, never out of range.
        assertEquals(330f, ColorPickerGrid.hueAt(99), 0.01f)
        assertEquals(0f, ColorPickerGrid.hueAt(-3), 0.01f)
    }

    @Test
    fun `tones go light to dark and cover the whole grid`() {
        assertEquals(ColorPickerGrid.ROWS, ColorPickerGrid.TONES.size)
        for (i in 1 until ColorPickerGrid.TONES.size) {
            assertTrue(
                "row $i must be darker than row ${i - 1}",
                ColorPickerGrid.TONES[i] < ColorPickerGrid.TONES[i - 1]
            )
        }
        assertEquals(0.95f, ColorPickerGrid.toneAt(0), 0.001f)
        assertEquals(0.20f, ColorPickerGrid.toneAt(ColorPickerGrid.ROWS - 1), 0.001f)
    }

    @Test
    fun `every cell is opaque and the corners match the expected hues`() {
        for (row in 0 until ColorPickerGrid.ROWS) {
            for (column in 0 until ColorPickerGrid.COLUMNS) {
                val argb = ColorPickerGrid.colorAt(column, row)
                assertEquals("cell ($column,$row) must be opaque", 0xFF, (argb ushr 24) and 0xFF)
            }
        }
        // Column 0 at full saturation is red, column 4 (120°) green, column 8 (240°) blue.
        val red = ColorPickerGrid.colorAt(0, 3)
        val green = ColorPickerGrid.colorAt(4, 3)
        val blue = ColorPickerGrid.colorAt(8, 3)
        assertTrue("red cell should be red-dominant", (red shr 16 and 0xFF) > (red and 0xFF))
        assertTrue("green cell should be green-dominant", (green shr 8 and 0xFF) > (green and 0xFF))
        assertTrue("blue cell should be blue-dominant", (blue and 0xFF) > (blue shr 16 and 0xFF))
    }

    @Test
    fun `hsl conversion hits the primaries`() {
        assertEquals(0xFFFF0000.toInt(), ColorPickerGrid.hslToRgb(0f, 0.5f))
        assertEquals(0xFF00FF00.toInt(), ColorPickerGrid.hslToRgb(120f, 0.5f))
        assertEquals(0xFF0000FF.toInt(), ColorPickerGrid.hslToRgb(240f, 0.5f))
        // Extremes are black/white regardless of hue.
        assertEquals(0xFF000000.toInt(), ColorPickerGrid.hslToRgb(200f, 0f))
        assertEquals(0xFFFFFFFF.toInt(), ColorPickerGrid.hslToRgb(200f, 1f))
    }

    @Test
    fun `hex round trip drops alpha and uppercases`() {
        assertEquals("#1E88E5", ColorPickerGrid.toHex(0xFF1E88E5.toInt()))
        assertEquals("#00FF00", ColorPickerGrid.toHex(0x8000FF00.toInt()))
    }

    @Test
    fun `alpha percent maps onto the high byte`() {
        val base = 0xFF1E88E5.toInt()
        assertEquals(0xFF, (ColorPickerGrid.withAlphaPercent(base, 100) ushr 24) and 0xFF)
        assertEquals(0x80, (ColorPickerGrid.withAlphaPercent(base, 50) ushr 24) and 0xFF)
        assertEquals(0x00, (ColorPickerGrid.withAlphaPercent(base, 0) ushr 24) and 0xFF)
        // Clamped and colour-preserving.
        assertEquals(0xFF, (ColorPickerGrid.withAlphaPercent(base, 250) ushr 24) and 0xFF)
        assertEquals("#1E88E5", ColorPickerGrid.toHex(ColorPickerGrid.withAlphaPercent(base, 30)))
    }

    @Test
    fun `nearest cell finds the swatch a colour came from`() {
        for (row in 0 until ColorPickerGrid.ROWS) {
            for (column in 0 until ColorPickerGrid.COLUMNS) {
                val hex = ColorPickerGrid.toHex(ColorPickerGrid.colorAt(column, row))
                assertEquals(
                    "round trip for $hex",
                    column to row,
                    ColorPickerGrid.nearestCellOf(hex)
                )
            }
        }
    }

    @Test
    fun `nearest cell tolerates unknown input`() {
        assertNull(ColorPickerGrid.nearestCellOf("not-a-color"))
        assertNull(ColorPickerGrid.nearestCellOf(""))
        // The floating button's default (#1E88E5) must land somewhere sane.
        val cell = ColorPickerGrid.nearestCellOf("#1E88E5")
        assertTrue("default button colour should map to a cell", cell != null)
        val (column, row) = cell!!
        assertTrue(column in 0 until ColorPickerGrid.COLUMNS)
        assertTrue(row in 0 until ColorPickerGrid.ROWS)
    }
}
