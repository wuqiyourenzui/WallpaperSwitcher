package com.wallpaperswitcher.engine

import kotlin.math.hypot

/**
 * Pure double-tap state machine used by the wallpaper engine's touch handler.
 *
 * Recognizes a double tap from either the second ACTION_DOWN (the classic
 * case) or the second ACTION_UP (fallback for launchers that swallow one of
 * the DOWN events). Kept free of Android state so the timing/slop rules are
 * unit testable.
 */
class DoubleTapDetector(
    private val timeoutMs: Long = 300L,
    private val slopPx: Float = 40f
) {

    private var lastTapUpTime = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f
    private var lastDownWasDouble = false

    /**
     * @return true when this DOWN completes a double tap (the caller switches).
     */
    fun onDown(x: Float, y: Float, now: Long): Boolean {
        val withinTime = lastTapUpTime != 0L && now - lastTapUpTime <= timeoutMs
        val withinSlop = lastTapUpTime != 0L && hypot(x - lastTapUpX, y - lastTapUpY) <= slopPx
        lastDownWasDouble = withinTime && withinSlop
        if (lastDownWasDouble) {
            // Clear the UP marker so the UP fallback below cannot fire a second
            // switch for the same gesture.
            lastTapUpTime = 0L
            return true
        }
        return false
    }

    /**
     * @return true when this UP completes a double tap (the UP fallback).
     */
    fun onUp(x: Float, y: Float, now: Long): Boolean {
        if (lastDownWasDouble) {
            // Already switched on the DOWN of this tap.
            lastDownWasDouble = false
            lastTapUpTime = 0L
            return false
        }
        val withinTime = lastTapUpTime != 0L && now - lastTapUpTime <= timeoutMs
        val withinSlop = lastTapUpTime != 0L && hypot(x - lastTapUpX, y - lastTapUpY) <= slopPx
        if (withinTime && withinSlop) {
            lastTapUpTime = 0L
            return true
        }
        lastTapUpTime = now
        lastTapUpX = x
        lastTapUpY = y
        return false
    }

    fun onCancel() {
        lastDownWasDouble = false
        lastTapUpTime = 0L
    }
}
