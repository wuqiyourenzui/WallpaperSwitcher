package com.wallpaperswitcher.engine

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Process-wide bound on blocking full-screen decodes.
 *
 * The wallpaper engine runs independent decode paths that can overlap - the
 * visual switch, the next-image prefetch and the failure-recovery fallback -
 * and each helper thread allocates a screen-size ARGB bitmap. Previously the
 * only limit was "one thread per caller", so rapid switching could leave two
 * or three full-screen decodes in flight at once on a low-memory device.
 *
 * Two permits keep the peak predictable. A caller that cannot get a permit
 * within [ACQUIRE_TIMEOUT_MS] gives up and returns null, which both loaders
 * already treat as a load failure (the normal recovery path takes over).
 * The permit is released when the caller stops waiting; a helper thread that
 * outlives its timeout keeps its own bitmap alive only until it finishes, and
 * the abandoned-bitmap handshake recycles it as before.
 */
internal object DecodeThrottle {

    private const val MAX_CONCURRENT_DECODES = 2
    private const val ACQUIRE_TIMEOUT_MS = 5_000L

    private val permits = Semaphore(MAX_CONCURRENT_DECODES)

    /** @return true when a permit was taken and [release] must be called. */
    fun acquire(timeoutMs: Long = ACQUIRE_TIMEOUT_MS): Boolean = try {
        permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    fun release() {
        permits.release()
    }
}
