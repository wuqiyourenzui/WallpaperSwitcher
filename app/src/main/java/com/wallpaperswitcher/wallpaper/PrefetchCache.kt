package com.wallpaperswitcher.wallpaper

import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.util.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Next-image prefetch cache, from `LiveWallpaperService` (逻辑逐字搬移):
 * after a switch the engine decodes the next candidate image in the background
 * so a follow-up switch (rapid double-tap burst, unlock, next timer tick) can
 * display it with near-zero latency instead of decoding ~100-200ms on demand.
 *
 * Owns the lock, the cached bitmap and the "unused prefetch" expiry task; the
 * engine only picks and decodes the candidate.
 */
internal class PrefetchCache(
    private val mainHandler: Handler,
    private val tag: String,
) {

    /** Prefetched next image: id, pixels and the GPU quarter turn they need. */
    data class Image(
        val imageId: Long,
        val bitmap: Bitmap?,
        val rotateCw: Boolean?,
        /** The group this prefetch belongs to (0 = the screen-wide pick). */
        val groupId: Long = 0L,
    )

    private val lock = Any()
    private var imageId = 0L
    private var bitmap: Bitmap? = null
    /**
     * Group the cached image belongs to (0 = the screen-wide pick). A
     * per-group switch must not consume a screen-wide prefetch (or another
     * group's), and vice versa.
     */
    private var groupId = 0L
    // When the current prefetch was stored (elapsedRealtime); the delayed
    // expiry task only drops a bitmap that is still the same one.
    @Volatile private var storedAt = 0L
    // Pending "drop the unused prefetch" task, replaced (not stacked) by a
    // newer prefetch.
    private var expiryRunnable: Runnable? = null
    // The switch mode the prefetch was computed for. If the user changes
    // the mode (e.g. RANDOM -> SEQUENTIAL) between the prefetch and the
    // next switch, the cached "next" image is stale and must be dropped —
    // otherwise SEQUENTIAL would start from a random prefetch instead of
    // continuing after the currently displayed wallpaper.
    @Volatile private var switchMode: SwitchMode? = null
    /** GPU quarter turn the prefetched bitmap needs (see [take]). */
    @Volatile private var rotateCw: Boolean? = null
    private val storeInProgress = AtomicBoolean(false)

    fun hasCache(): Boolean = synchronized(lock) { imageId > 0L || bitmap != null }

    fun isStoreInProgress(): Boolean = storeInProgress.get()

    fun beginStore(): Boolean = storeInProgress.compareAndSet(false, true)

    fun endStore() {
        storeInProgress.set(false)
    }

    /**
     * Atomically consume the prefetch cache. Both id and bitmap are zero/null
     * when nothing is cached; the caller owns the bitmap once returned. The
     * rotation travels with it - the prefetched pixels are NOT rotated (the
     * renderer applies it), so the consuming switch must use the same value.
     */
    fun take(): Image {
        synchronized(lock) {
            val id = imageId
            val bmp = bitmap
            val rotate = rotateCw
            val group = groupId
            imageId = 0L
            bitmap = null
            groupId = 0L
            switchMode = null
            rotateCw = null
            if (id <= 0L || bmp == null || bmp.isRecycled) {
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
                return Image(0L, null, null)
            }
            return Image(id, bmp, rotate, group)
        }
    }

    fun clear() {
        expiryRunnable?.let { mainHandler.removeCallbacks(it) }
        expiryRunnable = null
        synchronized(lock) {
            val b = bitmap
            bitmap = null
            imageId = 0L
            groupId = 0L
            switchMode = null
            rotateCw = null
            storedAt = 0L
            if (b != null && !b.isRecycled) b.recycle()
        }
    }

    /** Drop a cached image that was picked for a different switch mode. */
    fun discardIfModeMismatch(mode: SwitchMode) {
        synchronized(lock) {
            if (imageId > 0L && switchMode != mode) {
                val b = bitmap
                bitmap = null
                imageId = 0L
                switchMode = null
                rotateCw = null
                if (b != null && !b.isRecycled) b.recycle()
            }
        }
    }

    /**
     * Store a freshly decoded candidate. Returns false when another prefetch
     * won the race; the caller keeps ownership of [bmp] and must recycle it.
     */
    fun store(
        newImageId: Long,
        newBitmap: Bitmap,
        newGroupId: Long,
        mode: SwitchMode,
        newRotateCw: Boolean?,
    ): Boolean {
        var stored = false
        synchronized(lock) {
            if (imageId <= 0L && bitmap == null) {
                imageId = newImageId
                bitmap = newBitmap
                groupId = newGroupId
                switchMode = mode
                rotateCw = newRotateCw
                stored = true
            }
        }
        if (stored) scheduleExpiry()
        return stored
    }

    /**
     * Drop the prefetched bitmap if nobody consumed it within
     * [PREFETCH_KEEP_MS]. It only exists to make a rapid follow-up switch
     * instant, and it is a screen-size ARGB bitmap (tens of MB) - holding it
     * for an idle wallpaper is pure waste. A newer prefetch replaces
     * [storedAt], so a stale expiry task cannot drop it.
     */
    private fun scheduleExpiry() {
        val at = SystemClock.elapsedRealtime()
        storedAt = at
        // Replace the previous expiry task instead of stacking one per
        // prefetch: a rapid-tap burst would otherwise leave dozens of them
        // pending for two minutes.
        expiryRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val dropped = synchronized(lock) {
                if (bitmap != null && storedAt == at) {
                    clear()
                    true
                } else {
                    false
                }
            }
            if (dropped) {
                AppLog.d(tag, "Dropped unused prefetch after ${PREFETCH_KEEP_MS / 1000}s")
            }
        }
        expiryRunnable = runnable
        mainHandler.postDelayed(runnable, PREFETCH_KEEP_MS)
    }

    private companion object {
        private const val PREFETCH_KEEP_MS = 120_000L
    }
}
