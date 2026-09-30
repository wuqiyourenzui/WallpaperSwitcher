package com.wallpaperswitcher.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleTapDetectorTest {

    private val slop = 40f

    @Test
    fun classicDoubleTapFiresOnSecondDown() {
        val d = DoubleTapDetector(300L, slop)
        assertFalse(d.onDown(10f, 10f, 0L))
        assertFalse(d.onUp(10f, 10f, 50L))
        assertTrue(d.onDown(12f, 11f, 200L))
    }

    @Test
    fun upFallbackFiresWhenSecondDownIsSwallowed() {
        val d = DoubleTapDetector(300L, slop)
        assertFalse(d.onDown(10f, 10f, 0L))
        assertFalse(d.onUp(10f, 10f, 50L))
        // Launcher swallowed the second DOWN; only the second UP arrives.
        assertTrue(d.onUp(12f, 11f, 200L))
    }

    @Test
    fun timeoutPreventsDoubleTap() {
        val d = DoubleTapDetector(300L, slop)
        assertFalse(d.onUp(10f, 10f, 0L))
        assertFalse(d.onDown(12f, 11f, 400L))
    }

    @Test
    fun slopPreventsDoubleTap() {
        val d = DoubleTapDetector(300L, slop)
        assertFalse(d.onUp(0f, 0f, 0L))
        assertFalse(d.onDown(200f, 0f, 200L))
    }

    @Test
    fun downDoubleDoesNotFireAgainOnItsOwnUp() {
        val d = DoubleTapDetector(300L, slop)
        assertFalse(d.onDown(10f, 10f, 0L))
        assertFalse(d.onUp(10f, 10f, 50L))
        assertTrue(d.onDown(12f, 11f, 200L))
        // The UP of the second tap must not trigger the fallback a second time.
        assertFalse(d.onUp(12f, 11f, 250L))
    }

    @Test
    fun tripleTapDoesNotFireASecondSwitch() {
        val d = DoubleTapDetector(300L, slop)
        assertFalse(d.onDown(10f, 10f, 0L))
        assertFalse(d.onUp(10f, 10f, 50L))
        assertTrue(d.onDown(12f, 11f, 200L))
        assertFalse(d.onUp(12f, 11f, 250L))
        // A third tap within the window must not switch again.
        assertFalse(d.onDown(13f, 12f, 400L))
    }

    @Test
    fun cancelClearsPendingState() {
        val d = DoubleTapDetector(300L, slop)
        assertFalse(d.onUp(10f, 10f, 0L))
        d.onCancel()
        assertFalse(d.onDown(12f, 11f, 200L))
    }
}
