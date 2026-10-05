package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.ScaleMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 静态图微动效 (Ken Burns): the zoom must leave the settled layout alone at the
 * cycle boundaries and stay perfectly centred in between.
 */
class WallpaperGeometryKenBurnsTest {

    private fun quad(): FloatArray =
        WallpaperGeometry.computeQuad(1600f, 900f, 1000f, 2000f, ScaleMode.FIT)

    @Test
    fun `phase zero leaves the quad untouched`() {
        val q = quad()
        val before = q.copyOf()
        WallpaperGeometry.applyKenBurns(q, phase = 0f, amplitude = 0.06f)
        assertArrayEquals(before, q, 0f)
    }

    @Test
    fun `phase one wraps back to the untouched quad`() {
        val q = quad()
        val before = q.copyOf()
        WallpaperGeometry.applyKenBurns(q, phase = 1f, amplitude = 0.06f)
        assertArrayEquals(before, q, 0.000001f)
    }

    @Test
    fun `half way is the full zoom`() {
        val q = quad()
        val before = q.copyOf()
        WallpaperGeometry.applyKenBurns(q, phase = 0.5f, amplitude = 0.06f)
        for (i in intArrayOf(0, 4, 8, 12)) {
            assertEquals(before[i] * 1.06f, q[i], 0.000001f)
        }
        for (i in intArrayOf(1, 5, 9, 13)) {
            assertEquals(before[i] * 1.06f, q[i], 0.000001f)
        }
    }

    @Test
    fun `the zoom stays centred around the viewport`() {
        val q = quad()
        WallpaperGeometry.applyKenBurns(q, phase = 0.37f, amplitude = 0.06f)
        // Vertex layout: (-dw,-dh) (dw,-dh) (-dw,dh) (dw,dh): the x pairs stay
        // mirrored around 0 and both y rows stay equal, so the centre is 0,0.
        assertEquals(q[0], -q[4], 0.000001f)
        assertEquals(q[1], q[5], 0.000001f)
        assertEquals(q[8], -q[12], 0.000001f)
        assertEquals(q[9], q[13], 0.000001f)
        assertEquals(0f, (q[0] + q[4]) / 2f, 0.000001f)
        assertEquals(0f, (q[1] + q[9]) / 2f, 0.000001f)
    }

    @Test
    fun `phases wrap around`() {
        val a = quad()
        val b = quad()
        WallpaperGeometry.applyKenBurns(a, phase = 0.25f, amplitude = 0.06f)
        WallpaperGeometry.applyKenBurns(b, phase = 2.25f, amplitude = 0.06f)
        assertArrayEquals(a, b, 0.000001f)
    }

    @Test
    fun `a zero amplitude is a no-op`() {
        val q = quad()
        val before = q.copyOf()
        WallpaperGeometry.applyKenBurns(q, phase = 0.5f, amplitude = 0f)
        assertArrayEquals(before, q, 0f)
    }
}
