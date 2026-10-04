package com.wallpaperswitcher.engine

/**
 * Timing and easing of the 过渡动画 (see WallpaperRenderer.requestTransition).
 *
 * The renderer drives every transition from the display's frame clock
 * (Choreographer) and asks this object for the progress of a given moment, so
 * the animation is sampled at the real refresh rate (60/90/120Hz) instead of a
 * fixed 25ms step. Kept pure so the curve is unit-testable.
 */
object TransitionCurve {

    /**
     * How long a transition runs. Short enough to feel like a response to the
     * tap, long enough that the eye reads the motion (the old code spread 8
     * coarse steps over 200ms, which judders).
     */
    const val DURATION_MS = 220f

    /**
     * FADE starts at this overlay alpha instead of 1.0: a completely black
     * first frame reads as a flash/flicker, while a 0.72 dim reads as the
     * picture "settling in". The media underneath is never invisible.
     */
    const val FADE_START_ALPHA = 0.72f

    /**
     * Ease-out cubic: most of the movement happens early, then it settles -
     * that is what makes a short transition feel responsive instead of abrupt.
     */
    fun easeOutCubic(linear: Float): Float {
        val t = linear.coerceIn(0f, 1f)
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    /** Black-overlay alpha for an eased FADE progress. */
    fun fadeAlpha(eased: Float): Float =
        (FADE_START_ALPHA * (1f - eased.coerceIn(0f, 1f))).coerceIn(0f, 1f)

    /** Progress of a transition that started [startNanos] ago (frame time based). */
    fun progressAt(startNanos: Long, frameTimeNanos: Long): Float {
        if (startNanos <= 0L) return 0f
        val elapsedMs = (frameTimeNanos - startNanos) / 1_000_000f
        return (elapsedMs / DURATION_MS).coerceIn(0f, 1f)
    }
}
