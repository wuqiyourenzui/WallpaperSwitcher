package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.DisplayMetrics
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.util.AppLog

private const val TAG = "MediaBitmapLoader"

/**
 * 壁纸位图的解码/加载与占位图（从 `LiveWallpaperService` 拆分出来，逻辑逐字搬移）。
 *
 * The loader owns the decode-facing helpers: the engine hands it the current
 * scale mode / auto-rotate settings through [Host] and never has to know
 * whether a bitmap came from ContentResolver, a baked quarter turn or the
 * "Wallpaper Switcher" placeholder.
 */
internal class MediaBitmapLoader(
    private val context: Context,
    private val host: Host,
) {

    internal interface Host {
        /** Screen metrics used for the fallback bitmap and the orientation rule. */
        fun metrics(): DisplayMetrics

        /** Current scale mode (FILL / FIT / STRETCH); read on every decode. */
        fun scaleMode(): ScaleMode

        fun autoRotateMismatch(): Boolean

        fun autoRotateClockwise(): Boolean

        /**
         * 缓存的壁纸表面尺寸（像素）。任一侧 <= 0 表示还没有 surface，
         * 调用方回退到 [metrics]。
         */
        fun cachedScreenSize(): Pair<Int, Int>
    }

    private var defaultBitmap: Bitmap? = null

    /**
     * True when the pixels already decoded into [bmp] are enough to show the
     * media on the current [sw]x[sh] surface: the screen never has to
     * magnify them. The orientation rule is applied through the same quad the
     * renderer will use, so FILL/FIT/STRETCH are all covered.
     *
     * Used by the rotation redraw: when this holds, the bitmap decoded for
     * the previous orientation can simply be re-presented (see
     * refreshImageQuad) instead of decoding the media again - which is what
     * made setting a wallpaper stutter on a landscape tablet (MIUI rotates
     * the display for its portrait-only picker, so the surface turns twice
     * per setting flow and each turn re-decoded a display-sized photo).
     */
    fun bitmapFillsScreen(bmp: Bitmap, sw: Int, sh: Int): Boolean {
        if (bmp.width <= 0 || bmp.height <= 0 || sw <= 0 || sh <= 0) return false
        val rotateCw = com.wallpaperswitcher.engine.BitmapUtils.fillRotationFor(
            bmp, sw, sh, host.autoRotateMismatch(), host.autoRotateClockwise()
        )
        // The axis bookkeeping (a 90° turn feeds the screen's width from the
        // bitmap's HEIGHT) lives in the pure helper so it is unit-tested -
        // getting it wrong silently re-decoded every rotated photo.
        return com.wallpaperswitcher.engine.WallpaperGeometry.bitmapCoversQuad(
            wd = bmp.width,
            ht = bmp.height,
            screenW = sw,
            screenH = sh,
            scaleMode = host.scaleMode(),
            rotateCw = rotateCw
        )
    }

    /**
     * True when the orientation rule (see [com.wallpaperswitcher.engine.BitmapUtils.quarterTurnFor])
     * wants this media turned 90° first because its orientation mismatches
     * the wallpaper surface. Applies to every scale mode now: FIT included,
     * where the turned media is also displayed larger (a mismatched
     * orientation is limited by the screen's short side when fitted).
     */
    fun shouldRotateMediaForScreen(
        srcW: Int,
        srcH: Int
    ): Boolean {
        val (cachedW, cachedH) = host.cachedScreenSize()
        var sw = cachedW
        var sh = cachedH
        if (sw <= 0 || sh <= 0) {
            val metrics = host.metrics()
            sw = metrics.widthPixels
            sh = metrics.heightPixels
        }
        if (sw <= 0 || sh <= 0) return false
        return com.wallpaperswitcher.engine.BitmapUtils.wantsQuarterTurn(
            srcW, srcH, sw, sh, host.autoRotateMismatch()
        )
    }

    fun rotateBitmap90(bmp: Bitmap, clockwise: Boolean): Bitmap {
        if (bmp.isRecycled) return bmp
        // Same implementation as the image path (BitmapUtils.bakeQuarterTurn
        // -> rotateQuarterTurn), so a GIF frame and a still image can never
        // be turned in different directions.
        return com.wallpaperswitcher.engine.BitmapUtils.bakeQuarterTurn(bmp, clockwise)
    }

    /** Longest screen side, for the GIF controller's decode cap. */
    fun screenMaxPx(): Int {
        val m = host.metrics()
        return maxOf(m.widthPixels, m.heightPixels)
    }

    fun getDefaultBitmap(): Bitmap {
        val existing = defaultBitmap
        if (existing != null && !existing.isRecycled) return existing
        return createDefaultBitmap().also { defaultBitmap = it }
    }

    /** Engine teardown: drop (and recycle) the placeholder. */
    fun releaseDefaultBitmap() {
        defaultBitmap?.recycle()
        defaultBitmap = null
    }

    private fun createDefaultBitmap(): Bitmap {
        val m = host.metrics()
        // Cap the placeholder to 2048px on the long side: on large tablets
        // a full-screen-size ARGB bitmap (3040x2032 ~= 24MB) is wasteful
        // for a text placeholder and could contribute to OOM on low-heap
        // device builds. The GPU scales it up trivially.
        val maxDim = maxOf(m.widthPixels, m.heightPixels)
        val scale = if (maxDim > 2048) 2048f / maxDim else 1f
        val w = (m.widthPixels * scale).toInt().coerceAtLeast(1)
        val h = (m.heightPixels * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.DKGRAY)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Wallpaper Switcher", w / 2f, h / 2f, p)
        return bmp
    }

    /**
     * Decode [media]. [media] is null only on the rare fallback paths
     * (GIF first-frame / health fallback) where the row is not at hand; the
     * stored decode metadata then comes from the in-memory cache instead.
     */
    fun loadBitmap(
        uriStr: String,
        media: com.wallpaperswitcher.data.WallpaperImage? = null
    ): com.wallpaperswitcher.engine.BitmapUtils.EngineImage? {
        return com.wallpaperswitcher.engine.BitmapUtils.loadBitmapForEngine(
            context, uriStr, host.scaleMode(),
            null, null, host.autoRotateMismatch(), host.autoRotateClockwise(),
            media?.width ?: 0, media?.height ?: 0, media?.rotationDegrees ?: 0
        )
    }

    /**
     * Load a bitmap with the "orientation mismatch" quarter turn already
     * baked into the pixels. Only the GIF fallback paths need this: they hand
     * the bitmap to their own frame ticker instead of the quad renderer, which
     * is where the engine takes the GPU rotation.
     */
    fun loadBakedBitmap(uriStr: String): Bitmap? {
        val loaded = loadBitmap(uriStr) ?: return null
        return loaded.rotateCw?.let {
            com.wallpaperswitcher.engine.BitmapUtils.bakeQuarterTurn(loaded.bitmap, it)
        } ?: loaded.bitmap
    }

    /**
     * Load a bitmap with a hard timeout. BitmapFactory/ContentResolver
     * calls cannot be interrupted by coroutine cancellation (a plain
     * withTimeout would keep waiting for the blocking call), so the decode
     * runs on a helper thread and the caller abandons it after [timeoutMs].
     * If the abandoned thread ever finishes, its bitmap is recycled.
     */
    fun loadBitmapWithTimeout(
        uriStr: String,
        timeoutMs: Long = BITMAP_LOAD_TIMEOUT_MS,
        screenW: Int? = null,
        screenH: Int? = null,
        media: com.wallpaperswitcher.data.WallpaperImage? = null
    ): com.wallpaperswitcher.engine.BitmapUtils.EngineImage? {
        // Bound concurrent full-screen decodes process-wide (switch + prefetch
        // + recovery can otherwise overlap three screen-size ARGB buffers).
        if (!com.wallpaperswitcher.engine.DecodeThrottle.acquire()) {
            AppLog.w(TAG, "decode throttle busy; load skipped: ${uriStr.takeLast(60)}")
            return null
        }
        try {
        val result = java.util.concurrent.atomic.AtomicReference<
            com.wallpaperswitcher.engine.BitmapUtils.EngineImage?
            >(null)
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread({
            try {
                val loaded = if (screenW != null && screenH != null) {
                    com.wallpaperswitcher.engine.BitmapUtils.loadBitmapForEngine(
                        context, uriStr, host.scaleMode(),
                        screenW, screenH, host.autoRotateMismatch(),
                        host.autoRotateClockwise(),
                        media?.width ?: 0, media?.height ?: 0, media?.rotationDegrees ?: 0
                    )
                } else {
                    loadBitmap(uriStr, media)
                }
                if (abandoned.get()) {
                    loaded?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                } else {
                    result.set(loaded)
                    // The caller may have timed out between the check above and
                    // this store; it then returns null and would never look at
                    // (or recycle) this bitmap - a whole screen-size ARGB would
                    // leak. Re-check and take it back in that case.
                    if (abandoned.get()) {
                        val stored = result.getAndSet(null)
                        stored?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                    }
                }
            } catch (_: Throwable) {}
        }, "BitmapLoad").apply {
            // Never keep the process alive because a provider is stuck.
            isDaemon = true
            start()
        }
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
            abandoned.set(true)
            thread.interrupt()
            return null
        }
        val bmp = result.get()
        if (bmp != null && bmp.bitmap.isRecycled) return null
        return bmp
        } finally {
            com.wallpaperswitcher.engine.DecodeThrottle.release()
        }
    }

    /**
     * Extract the first frame of a video as a fallback static image (used
     * when a device cannot play the video through MediaCodec/SurfaceTexture).
     * Shared with the static applier (see [com.wallpaperswitcher.engine.FirstFrame]):
     * one implementation, with the container rotation applied, so portrait
     * recordings are upright in both modes.
     */
    fun videoFirstFrame(uriStr: String, positionUs: Long = 0L): Bitmap? =
        com.wallpaperswitcher.engine.FirstFrame.video(context, uriStr, positionUs)

    internal companion object {
        /**
         * Hard cap for one media decode. Also passed to the GIF controller so
         * both paths abandon a stuck provider after the same time.
         */
        const val BITMAP_LOAD_TIMEOUT_MS = 15_000L
    }
}
