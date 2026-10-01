package com.wallpaperswitcher.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG contrast helpers for the custom theme colour.
 *
 * The user picks any colour as the accent; two things then have to stay readable:
 *
 *  * text/icons drawn *on* the accent (chips, switches) - solved by
 *    [readableOn] picking black or white by luminance instead of hard-coding
 *    `Color.White` (a pale accent with white text was unreadable);
 *  * accent-coloured text drawn on the *surface* (section labels, values) - solved
 *    by [ensureReadable], which darkens/lightens the accent along its own lightness
 *    axis (hue and saturation untouched) until it reaches a target contrast ratio.
 *    The scheme used to set `onSurfaceVariant = primary`, so a pale accent turned
 *    every secondary label into pale-on-pale (the reported "文字和图形随主题颜色
 *    变化，影响识别").
 *
 * Pure Kotlin so the maths can be unit tested; see ColorContrastTest.
 */
object ColorContrast {

    /** WCAG AA for normal text. */
    const val AA_NORMAL = 4.5f

    /** WCAG AA for large text / graphical objects. */
    const val AA_LARGE = 3.0f

    /** Relative luminance of an opaque ARGB colour (WCAG 2.1). */
    fun luminance(argb: Int): Float {
        fun channel(value: Int): Float {
            val c = (value and 0xFF) / 255f
            return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        val r = channel(argb shr 16)
        val g = channel(argb shr 8)
        val b = channel(argb)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** Contrast ratio between two opaque colours, 1.0 .. 21.0. */
    fun contrastRatio(a: Int, b: Int): Float {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = max(la, lb)
        val darker = min(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /**
     * Black or white - whichever reads better on [background]. Used for the
     * "on accent" slots of the custom colour scheme.
     */
    fun readableOn(background: Int): Int {
        val onWhite = contrastRatio(0xFFFFFFFF.toInt(), background)
        val onBlack = contrastRatio(0xFF000000.toInt(), background)
        // Prefer white on a tie: Material's default look for a dark accent.
        return if (onWhite >= onBlack) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }

    /**
     * [color] adjusted along its lightness axis until it contrasts with
     * [background] by at least [minRatio]. Hue and saturation are preserved, so the
     * accent still looks like the colour the user picked - it is just pushed away
     * from the background until it is legible.
     *
     * If the ratio cannot be reached (e.g. a mid grey background), the extreme that
     * got closest is returned.
     */
    fun ensureReadable(color: Int, background: Int, minRatio: Float = AA_NORMAL): Int {
        if (contrastRatio(color, background) >= minRatio) return color
        val hsl = toHsl(color)
        // Walk away from the background's lightness, in small steps.
        val backgroundIsLight = luminance(background) > 0.5f
        val step = if (backgroundIsLight) -0.02f else 0.02f
        var best = color
        var bestRatio = contrastRatio(color, background)
        var lightness = hsl[2]
        repeat(50) {
            lightness = (lightness + step).coerceIn(0f, 1f)
            val candidate = ColorPickerGrid.hslToRgb(hsl[0], lightness, hsl[1])
            val ratio = contrastRatio(candidate, background)
            if (ratio > bestRatio) {
                bestRatio = ratio
                best = candidate
            }
            if (ratio >= minRatio) return candidate
            if (lightness <= 0f || lightness >= 1f) return best
        }
        return best
    }

    /** ARGB -> [hue (deg), saturation, lightness]. */
    fun toHsl(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        if (d == 0f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return floatArrayOf(((h % 360f) + 360f) % 360f, s, l)
    }
}
