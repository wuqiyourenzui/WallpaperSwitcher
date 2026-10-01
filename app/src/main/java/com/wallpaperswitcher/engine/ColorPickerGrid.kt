package com.wallpaperswitcher.engine

/**
 * Model behind the colour picker (the Material-style grid the user asked for):
 * a hue x tone matrix plus the hex/alpha helpers the settings screens need.
 *
 * Pure maths on purpose - no Compose, no Android - so the grid can be unit tested
 * and both the theme colour and the floating-button colour share exactly one
 * definition of "what the picker offers".
 *
 * Layout: columns are evenly spaced hues around the wheel (red -> orange -> ...
 * -> magenta -> red), rows are tones from near-white down to near-black, which is
 * what makes the grid read like the palette in Material's own pickers.
 */
object ColorPickerGrid {

    /** Hue columns (12 x 30 degrees). */
    const val COLUMNS = 12

    /** Tone rows, light to dark. */
    val TONES = floatArrayOf(0.95f, 0.80f, 0.65f, 0.50f, 0.35f, 0.20f)

    val ROWS: Int get() = TONES.size

    /** Hue in degrees for a column (0-based, clamped). */
    fun hueAt(column: Int): Float =
        column.coerceIn(0, COLUMNS - 1) * (360f / COLUMNS)

    /** Lightness for a row (0-based, clamped). */
    fun toneAt(row: Int): Float = TONES[row.coerceIn(0, TONES.size - 1)]

    /** Opaque ARGB for a grid cell. */
    fun colorAt(column: Int, row: Int): Int =
        hslToRgb(hueAt(column), toneAt(row), saturation = 1f)

    /**
     * HSL -> ARGB, fully saturated by default. Hue wraps, lightness is clamped to
     * 0..1, and the result is always opaque (the picker's alpha slider only ever
     * changes the window/button translucency, never the stored colour).
     */
    fun hslToRgb(hue: Float, lightness: Float, saturation: Float = 1f): Int {
        val l = lightness.coerceIn(0f, 1f)
        val s = saturation.coerceIn(0f, 1f)
        val h = ((hue % 360f) + 360f) % 360f
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** `#RRGGBB` (upper case) for an ARGB colour; alpha is dropped. */
    fun toHex(argb: Int): String =
        String.format("#%06X", argb and 0x00FFFFFF)

    /** Same colour with the given 0..100 alpha (used for previews only). */
    fun withAlphaPercent(argb: Int, percent: Int): Int {
        val a = Math.round(percent.coerceIn(0, 100) * 255f / 100f) and 0xFF
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /**
     * Parse `#RRGGBB` / `RRGGBB` into an opaque ARGB int, or null.
     *
     * Deliberately pure Kotlin instead of [com.wallpaperswitcher.util.parseHexColorInt]:
     * that helper goes through `android.graphics.Color`, which is not available in
     * plain JVM unit tests, and the picker's "which cell is this colour?" logic has
     * to stay testable.
     */
    fun parseHex(hex: String?): Int? {
        val clean = hex?.trim()?.removePrefix("#") ?: return null
        if (clean.length != 6) return null
        val value = clean.toLongOrNull(radix = 16) ?: return null
        return (0xFF shl 24) or (value.toInt() and 0x00FFFFFF)
    }

    /**
     * Grid cell closest to a stored colour, so the picker can mark the current
     * selection. Compares in HSL space (hue distance wraps around). Returns null
     * when [hex] cannot be parsed - the caller then simply marks nothing.
     */
    fun nearestCellOf(hex: String): Pair<Int, Int>? {
        val argb = parseHex(hex) ?: return null
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { ((it % 360f) + 360f) % 360f }

        var bestColumn = 0
        var bestRow = 0
        var bestDistance = Float.MAX_VALUE
        for (row in 0 until ROWS) {
            for (column in 0 until COLUMNS) {
                val hueDistance = kotlin.math.abs(
                    ((h - hueAt(column) + 540f) % 360f) - 180f
                )
                val toneDistance = kotlin.math.abs(l - toneAt(row)) * 360f
                val distance = hueDistance + toneDistance
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestColumn = column
                    bestRow = row
                }
            }
        }
        return bestColumn to bestRow
    }
}
