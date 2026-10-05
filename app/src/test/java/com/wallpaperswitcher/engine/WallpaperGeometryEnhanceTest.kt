package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.ScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画质增强: the strength ramp and the bicubic pair decomposition the shader
 * mirrors. The pair maths is the part that is easy to get subtly wrong, so it
 * is verified against the direct Catmull-Rom weights on synthetic samples.
 */
class WallpaperGeometryEnhanceTest {

    @Test
    fun `disabled means no enhancement`() {
        assertEquals(
            0f,
            WallpaperGeometry.enhancementStrength(320f, 240f, 1440f, 3200f, ScaleMode.FILL, false),
            0f,
        )
    }

    @Test
    fun `native and downscaled sources are left alone`() {
        assertEquals(
            0f,
            WallpaperGeometry.enhancementStrength(1440f, 1200f, 1440f, 1200f, ScaleMode.FILL, true),
            0.0001f,
        )
        assertEquals(
            0f,
            WallpaperGeometry.enhancementStrength(2880f, 2000f, 1440f, 1200f, ScaleMode.FILL, true),
            0.0001f,
        )
    }

    @Test
    fun `the ramp reaches full strength at two times`() {
        val two = WallpaperGeometry.enhancementStrength(720f, 800f, 1440f, 1600f, ScaleMode.FILL, true)
        assertEquals(1f, two, 0.0001f)
        val eight = WallpaperGeometry.enhancementStrength(180f, 400f, 1440f, 3200f, ScaleMode.FILL, true)
        assertEquals(1f, eight, 0.0001f)
    }

    @Test
    fun `a slight magnification gets a proportional head start`() {
        // 1080p on a 1440p screen (1.33x): the old curve gave ~0.03, the new
        // one gives ~0.33 so the selected algorithm is actually applied.
        val strength = WallpaperGeometry
            .enhancementStrength(1080f, 1920f, 1440f, 1440f, ScaleMode.FILL, true)
        assertEquals(0.33f, strength, 0.01f)
    }

    @Test
    fun `the ramp grows monotonically`() {
        var previous = -1f
        for (width in intArrayOf(1152, 900, 720, 576, 480, 360)) {
            val strength = WallpaperGeometry.enhancementStrength(
                width.toFloat(), width.toFloat(), 1440f, 3200f, ScaleMode.FILL, true,
            )
            assertTrue("width=$width must not decrease", strength >= previous)
            previous = strength
        }
    }

    @Test
    fun `fit uses the smaller magnification axis`() {
        // A wide image in FIT: the short axis is magnified 2x, the long one 9x.
        val fit = WallpaperGeometry.enhancementStrength(160f, 720f, 1440f, 1440f, ScaleMode.FIT, true)
        val fill = WallpaperGeometry.enhancementStrength(160f, 720f, 1440f, 1440f, ScaleMode.FILL, true)
        assertTrue("fill must enhance at least as much as fit", fill >= fit)
    }

    @Test
    fun `the pair decomposition reproduces the direct weights`() {
        // Synthetic 1D signal: samples at -1, 0, 1, 2 relative to the floor.
        val samples = floatArrayOf(0.3f, -0.2f, 0.8f, 0.1f)
        for (t in floatArrayOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 0.99f)) {
            val w = WallpaperGeometry.cubicWeights(t)
            var direct = 0f
            for (i in 0..3) direct += w[i] * samples[i]
            // Pair version: bilinear between samples 0/1 at posA, and between
            // samples 2/3 at posB, combined with the pair weights.
            val pairs = WallpaperGeometry.cubicPairs(t)
            val posA = pairs[0]
            val posB = pairs[1]
            val weightA = pairs[2]
            val weightB = pairs[3]
            val pairA = samples[0] * (1f - posA) + samples[1] * posA
            val pairB = samples[2] * (1f - posB) + samples[3] * posB
            val reconstructed = pairA * weightA + pairB * weightB
            assertEquals("t=$t", direct, reconstructed, 0.0001f)
        }
    }

    @Test
    fun `at t zero the second pair carries no weight`() {
        val pairs = WallpaperGeometry.cubicPairs(0f)
        // The first pair must land exactly on the texel at 0: posA = 1 from -1.
        assertEquals(1f, pairs[0], 0.0001f)
        assertEquals(0f, pairs[3], 0.0001f)
        assertEquals(1f, pairs[2], 0.0001f)
    }
}
