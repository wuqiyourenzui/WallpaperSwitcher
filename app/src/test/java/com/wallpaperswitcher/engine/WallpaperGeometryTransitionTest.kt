package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperGeometryTransitionTest {

    private fun quad() = WallpaperGeometry.computeQuad(
        1000f, 2000f, 1000f, 2000f, ScaleMode.FILL
    )

    @Test
    fun settledProgressLeavesTheQuadUntouched() {
        val q = quad()
        val before = q.copyOf()
        WallpaperGeometry.applyTransition(q, SettingsKeys.SWITCH_TRANSITION_SLIDE, 1f)
        assertEquals(before.toList(), q.toList())
    }

    @Test
    fun fadeModeNeverTouchesTheQuad() {
        val q = quad()
        val before = q.copyOf()
        WallpaperGeometry.applyTransition(q, SettingsKeys.SWITCH_TRANSITION_FADE, 0f)
        assertEquals(before.toList(), q.toList())
    }

    @Test
    fun slideNeverStartsFullyOffScreenAndEndsInPlace() {
        val start = quad()
        WallpaperGeometry.applyTransition(start, SettingsKeys.SWITCH_TRANSITION_SLIDE, 0f)
        // x at indices 0, 4, 8, 12. The media is shifted right by the (short)
        // travel; a full 2-NDC shift would mean a completely black first frame.
        assertEquals(
            -1f + WallpaperGeometry.TRANSITION_SLIDE_TRAVEL_NDC, start[0], 0.0001f
        )
        assertTrue("the media must still be on screen at t=0", start[0] > -1f)
        val half = quad()
        WallpaperGeometry.applyTransition(half, SettingsKeys.SWITCH_TRANSITION_SLIDE, 0.5f)
        assertEquals(
            -1f + WallpaperGeometry.TRANSITION_SLIDE_TRAVEL_NDC / 2f, half[0], 0.0001f
        )
        // y is untouched by a horizontal slide.
        assertEquals(-1f, half[1], 0.0001f)
    }

    @Test
    fun zoomGrowsFromBelowFullSize() {
        val q = quad()
        WallpaperGeometry.applyTransition(q, SettingsKeys.SWITCH_TRANSITION_ZOOM, 0f)
        assertEquals(-WallpaperGeometry.TRANSITION_ZOOM_START_SCALE, q[0], 0.0001f)
        assertEquals(-WallpaperGeometry.TRANSITION_ZOOM_START_SCALE, q[1], 0.0001f)
        assertEquals(WallpaperGeometry.TRANSITION_ZOOM_START_SCALE, q[4], 0.0001f)
        // The dark border stays thin (the old 0.85 left 15% of the screen black).
        assertTrue(WallpaperGeometry.TRANSITION_ZOOM_START_SCALE >= 0.9f)
    }

    @Test
    fun zoomIsCloseToFullSizeBeforeTheLastStep() {
        val q = quad()
        WallpaperGeometry.applyTransition(q, SettingsKeys.SWITCH_TRANSITION_ZOOM, 0.9f)
        assertNotEquals(-1f, q[0], 0.001f)
        val start = WallpaperGeometry.TRANSITION_ZOOM_START_SCALE
        assertEquals(-(start + (1f - start) * 0.9f), q[0], 0.0001f)
    }

    @Test
    fun progressIsClamped() {
        val q = quad()
        WallpaperGeometry.applyTransition(q, SettingsKeys.SWITCH_TRANSITION_SLIDE, 5f)
        assertEquals(-1f, q[0], 0.0001f)
        val negative = quad()
        WallpaperGeometry.applyTransition(negative, SettingsKeys.SWITCH_TRANSITION_SLIDE, -3f)
        assertEquals(
            -1f + WallpaperGeometry.TRANSITION_SLIDE_TRAVEL_NDC, negative[0], 0.0001f
        )
    }
}
