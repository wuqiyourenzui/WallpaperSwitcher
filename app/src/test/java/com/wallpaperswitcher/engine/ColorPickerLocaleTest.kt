package com.wallpaperswitcher.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Pins the hex string the picker stores to ASCII, whatever the user's locale.
 *
 * Why this exists: an audit claimed `String.format("#%06X", …)` without a Locale
 * would emit non-ASCII digits under ar-EG/fa-IR, so the colour stored in the
 * database would fail to parse later. That was tested directly on the JVM (see
 * ColorPickerGrid.toHex) and REFUTED - the JDK localises digits for `%d`, not for
 * `x`/`X`. The explicit Locale.ROOT in toHex is therefore defensive hygiene rather
 * than a bug fix, and this test keeps it that way if a future runtime changes.
 */
class ColorPickerLocaleTest {

    private lateinit var original: Locale

    @Before
    fun setUp() {
        original = Locale.getDefault()
    }

    @After
    fun tearDown() {
        Locale.setDefault(original)
    }

    @Test
    fun `hex stays ascii in locales whose digits are not ascii`() {
        for (tag in listOf(Locale("ar", "EG"), Locale("fa", "IR"), Locale("th", "TH"))) {
            Locale.setDefault(tag)
            val hex = ColorPickerGrid.toHex(0xFF1E88E5.toInt())
            assertEquals("locale $tag", "#1E88E5", hex)
            assertTrue("locale $tag must stay ASCII: $hex", hex.all { it.code < 128 })
        }
    }

    @Test
    fun `every grid colour still round trips through toHex and parseHex`() {
        Locale.setDefault(Locale("ar", "EG"))
        for (column in 0 until ColorPickerGrid.COLUMNS) {
            for (row in 0 until ColorPickerGrid.ROWS) {
                val argb = ColorPickerGrid.colorAt(column, row)
                assertEquals(
                    "cell ($column,$row)",
                    argb,
                    ColorPickerGrid.parseHex(ColorPickerGrid.toHex(argb))
                )
            }
        }
    }
}
