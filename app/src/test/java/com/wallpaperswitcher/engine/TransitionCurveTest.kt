package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionCurveTest {

    @Test
    fun easingStartsAndEndsExactly() {
        assertEquals(0f, TransitionCurve.easeOutCubic(0f), 0.0001f)
        assertEquals(1f, TransitionCurve.easeOutCubic(1f), 0.0001f)
    }

    @Test
    fun easingIsEaseOut() {
        // Ease-out: most of the movement happens in the first half, which is
        // what makes the short transition feel responsive instead of abrupt.
        assertTrue(TransitionCurve.easeOutCubic(0.25f) > 0.25f)
        assertTrue(TransitionCurve.easeOutCubic(0.5f) > 0.5f)
        assertTrue(TransitionCurve.easeOutCubic(0.75f) > 0.75f)
    }

    @Test
    fun easingIsMonotonicAndClamped() {
        var previous = -1f
        var t = 0f
        while (t <= 1.0001f) {
            val value = TransitionCurve.easeOutCubic(t)
            assertTrue("not monotonic at $t", value >= previous)
            assertTrue(value in 0f..1f)
            previous = value
            t += 0.01f
        }
        assertEquals(0f, TransitionCurve.easeOutCubic(-5f), 0.0001f)
        assertEquals(1f, TransitionCurve.easeOutCubic(5f), 0.0001f)
    }

    @Test
    fun fadeNeverShowsAFullBlackFrame() {
        // The old fade started at alpha = 1 (a completely black first frame,
        // which reads as a flash). It now starts dimmed, and the media below is
        // always partly visible.
        assertEquals(TransitionCurve.FADE_START_ALPHA, TransitionCurve.fadeAlpha(0f), 0.0001f)
        assertTrue(TransitionCurve.fadeAlpha(0f) < 1f)
        assertEquals(0f, TransitionCurve.fadeAlpha(1f), 0.0001f)
        // Monotonically brighter.
        var previous = 2f
        var t = 0f
        while (t <= 1.0001f) {
            val alpha = TransitionCurve.fadeAlpha(t)
            assertTrue(alpha <= previous)
            previous = alpha
            t += 0.05f
        }
    }

    @Test
    fun progressIsDrivenByFrameTime() {
        val start = 1_000_000_000L
        assertEquals(0f, TransitionCurve.progressAt(start, start), 0.0001f)
        assertEquals(
            0.5f,
            TransitionCurve.progressAt(start, start + (TransitionCurve.DURATION_MS / 2f * 1_000_000f).toLong()),
            0.001f
        )
        // Overdue frames clamp instead of overshooting.
        assertEquals(1f, TransitionCurve.progressAt(start, start + 10_000_000_000L), 0.0001f)
        // A transition that has not started yet reports 0, not a negative value.
        assertEquals(0f, TransitionCurve.progressAt(0L, start), 0.0001f)
    }
}
