package com.wallpaperswitcher.engine

/**
 * Pure schedule math for the timed wallpaper switch.
 *
 * The timer is paused while the screen is off (no wakeups, no decodes) and the
 * time spent there does NOT count: the service moves the anchor to "now" when
 * the screen comes back on, so the next switch is a full interval away. The
 * catch-up maths below therefore only covers the other ways a tick can be late:
 * the service was killed/frozen while it should have been counting.
 *
 * Kept free of Android types so it is unit-testable.
 */
object SwitchSchedule {

    /** Hard lower bound for the auto-switch interval (10 seconds). */
    const val MIN_INTERVAL_MS = 10_000L

    /**
     * Upper bound for the slack that still counts as a "normal" tick. The real
     * slack is half the interval, capped here: with a 24h interval a 12h slack
     * would hide a long pause, while a 60s cap still recognises a restart that
     * happened minutes after the due time.
     */
    const val CATCH_UP_SLACK_MAX_MS = 60_000L

    /**
     * A persisted anchor older than this is treated as stale: the timer was
     * probably disabled or force-stopped for a long time and should start a
     * fresh interval instead of "catching up" from days ago.
     */
    const val MAX_CATCH_UP_AGE_MS = 24 * 60 * 60 * 1000L

    /**
     * Sanitize a stored anchor for [nowMs]. Missing (<= 0), future (the clock
     * moved backwards) or implausibly old anchors fall back to [nowMs], which
     * starts a fresh interval.
     */
    fun resolveAnchor(anchorMs: Long, nowMs: Long, maxAgeMs: Long = MAX_CATCH_UP_AGE_MS): Long {
        if (anchorMs <= 0L) return nowMs
        val age = nowMs - anchorMs
        return if (age in 0..maxAgeMs) anchorMs else nowMs
    }

    /**
     * Milliseconds until the next switch is due. 0 means "switch now": the
     * schedule is due or overdue because the timer was paused while locked.
     */
    fun waitMs(anchorMs: Long, intervalMs: Long, nowMs: Long): Long {
        val interval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        return (anchorMs + interval - nowMs).coerceAtLeast(0L)
    }

    /**
     * True when the tick is so late that it can only be the catch-up after the
     * timer was paused (e.g. the service was killed while the screen was
     * locked). A normal tick is dispatched within milliseconds of its due
     * time, so the slack keeps normal ticks from being mistaken for catch-ups.
     */
    fun isCatchUp(
        anchorMs: Long,
        intervalMs: Long,
        nowMs: Long,
        slackMaxMs: Long = CATCH_UP_SLACK_MAX_MS,
    ): Boolean {
        val interval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val slack = minOf(interval / 2, slackMaxMs)
        val lateness = nowMs - anchorMs - interval
        return lateness >= slack
    }
}
