package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.engine.GifTiming
import com.wallpaperswitcher.util.AppLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * GIF wallpaper playback, split out of `LiveWallpaperService` verbatim.
 *
 * Owns the GIF render thread (HandlerThread), the ping-pong ARGB buffers and
 * the animated drawable, plus the health watchdog that falls back to the
 * file's first frame when a device decodes but never presents a frame.
 *
 * All engine-side state (renderer, visibility, recovery bookkeeping) arrives
 * through [Host]; the controller never touches the engine directly.
 */
internal class GifPlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val tag: String,
    private val decodeTimeoutMs: Long,
    private val host: Host,
) {

    internal interface Host {
        fun surfaceReady(): Boolean
        fun engineDestroyed(): Boolean
        fun powerSaveMode(): Boolean
        fun currentScaleMode(): ScaleMode
        fun autoRotateClockwise(): Boolean
        fun screenMaxPx(): Int
        fun shouldRotateForScreen(width: Int, height: Int): Boolean
        fun rotate90(bitmap: Bitmap, clockwise: Boolean): Bitmap
        fun loadBakedBitmap(uri: String): Bitmap?
        fun showImage(bitmap: Bitmap, scaleMode: ScaleMode)
        /** @return true when the frame was queued; false when it was coalesced away. */
        fun showGifFrame(bitmap: Bitmap, scaleMode: ScaleMode): Boolean
        fun isCurrentMedia(mediaId: Long): Boolean
        /** Runs on the main thread; takes ownership of [bitmap] (shows it or recycles it). */
        fun presentGifFallback(bitmap: Bitmap, mediaId: Long)
        fun onGifSuccess(mediaId: Long)
        fun onGifFailed(mediaId: Long)
    }

    private val gifFrameIntervalMs = 50L
    private val gifMaxFrameIntervalMs = 1_000L
    private val gifFrameDelayMarginMs = 8L
    private val gifPausedPollMs = 5_000L
    private val gifHealthPollMs = 5_000L
    private val gifHealthWaitMs = 60_000L
    private val gifFrameFailureLimit = 5

    // Written on the GIF thread, read from main / engine IO threads, so
    // @Volatile: without it the engine could recycle buffers while the GIF
    // thread is drawing a drawable that was just closed.
    @Volatile private var gifDrawable: AnimatedImageDrawable? = null
    @Volatile private var gifFrameRunnable: Runnable? = null
    /**
     * Identity of the live GIF tick chain; bumped by every [startGifTicker] and
     * every [nudge] (see the runnable's self-check).
     */
    @Volatile private var gifTickGeneration = 0
    // Diagnostics: proves the GIF pipeline actually presented a frame (or
    // explains why the health watchdog never fired).
    @Volatile private var gifFirstFrameLogged = false
    // GIF frame rasterization runs on its own HandlerThread: at 20fps a
    // main-thread ticker would compete with composition whenever a
    // GIF wallpaper was active while the app was open.
    private var gifThread: HandlerThread? = null
    private var gifHandler: Handler? = null
    // Two ARGB buffers, swapped after every presented frame: the render thread
    // may still be uploading the previous one.
    private var gifBitmapBuffer: Bitmap? = null
    private var gifBitmapBufferAlt: Bitmap? = null
    // GIF uri whose decode is in flight; a newer switch clears it so a
    // stale decode discards its result instead of overwriting the new media.
    @Volatile private var pendingGifUri: String? = null
    // GIF watchdog (see startHealthMonitor).
    private var gifHealthJob: Job? = null
    /**
     * True once the "GIF paused" line was logged for the current hidden
     * episode. Cleared when a frame is presented again while visible, so the
     * timer swapping a GIF while hidden does not log a second pause line.
     */
    @Volatile private var gifPauseAnnounced = false

    /** True while an animated GIF is rendering (drawable + ticker both live). */
    fun isActive(): Boolean = gifDrawable != null && gifFrameRunnable != null

    fun play(uriStr: String, scaleMode: ScaleMode, mediaId: Long) {
        if (!host.surfaceReady()) return
        pendingGifUri = uriStr
        // Decode off the main thread: ImageDecoder's first pass for a large
        // GIF can take tens of ms and would jank the UI on the main looper.
        // The drawable (or fallback bitmap) is handed back to the main
        // thread for the actual animation, guarded so a stale decode that
        // lost the race against a newer switch is discarded.
        scope.launch {
            var decodedDrawable: Drawable? = null
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    // Hard timeout: ImageDecoder.decodeDrawable can block
                    // forever on a stuck cloud/SAF provider (coroutine
                    // cancellation cannot interrupt it), which used to
                    // leave pendingGifUri stuck and the wallpaper frozen on
                    // the previous frame until the next switch.
                    val drawable = decodeGifDrawableWithTimeout(uriStr)
                    decodedDrawable = drawable
                    // Frame timing for the ticker (see GifTiming): read here,
                    // on the IO thread, so the drawable's own delays decide
                    // how often a frame is rasterized and uploaded instead
                    // of the fixed 20fps cadence. Null keeps that cadence.
                    val frameDelaysMs = if (drawable != null) {
                        GifTiming.frameDelaysMs(context, uriStr)
                    } else {
                        null
                    }
                    mainHandler.post {
                        if (pendingGifUri != uriStr || !host.surfaceReady()) {
                            try { (drawable as? java.lang.AutoCloseable)?.close() } catch (_: Exception) {}
                            return@post
                        }
                        pendingGifUri = null
                        if (drawable != null) {
                            playGif28(drawable, scaleMode, mediaId, frameDelaysMs)
                        } else {
                            host.onGifFailed(mediaId)
                        }
                    }
                } else {
                    // The GIF ticker draws this frame itself, so the quarter
                    // turn has to be baked here (the renderer's quad rotation
                    // is only used for the direct image path).
                    val bmp = host.loadBakedBitmap(uriStr)
                    mainHandler.post {
                        if (pendingGifUri != uriStr || !host.surfaceReady()) {
                            if (bmp != null && !bmp.isRecycled) bmp.recycle()
                            return@post
                        }
                        pendingGifUri = null
                        if (bmp != null) {
                            host.showImage(bmp, scaleMode)
                            host.onGifSuccess(mediaId)
                        } else {
                            host.onGifFailed(mediaId)
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Engine destroyed while the GIF was decoding: close the
                // decoded drawable so it is not leaked (its posted task is
                // dropped by removeCallbacksAndMessages in shutdown).
                try {
                    (decodedDrawable as? java.lang.AutoCloseable)?.close()
                } catch (_: Exception) {}
                throw ce
            } catch (t: Throwable) {
                AppLog.e(tag, "playGif failed, falling back to static frame", t)
                // Baked: this frame is drawn by the GIF path, not the quad.
                val bmp = host.loadBakedBitmap(uriStr)
                mainHandler.post {
                    if (pendingGifUri != uriStr || !host.surfaceReady()) {
                        if (bmp != null && !bmp.isRecycled) bmp.recycle()
                        return@post
                    }
                    pendingGifUri = null
                    if (bmp != null) {
                        host.showImage(bmp, scaleMode)
                        host.onGifSuccess(mediaId)
                    } else {
                        host.onGifFailed(mediaId)
                    }
                }
            }
        }
    }

    /**
     * Watchdog for GIF playback: some devices (reported on vivo/OriginOS)
     * decode the animation but never present a frame, leaving a black
     * wallpaper. If nothing reached the screen within 8s, show the file's
     * first frame as a static image so the wallpaper is never black.
     */
    fun startHealthMonitor(mediaId: Long, uriStr: String) {
        gifHealthJob?.cancel()
        gifHealthJob = scope.launch {
            // Judge "no frame" only while the wallpaper is actually visible:
            // a screen-off freeze is not a GIF failure (same reasoning as the
            // video watchdog).
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            var waitedForScreen = 0L
            while (waitedForScreen < gifHealthWaitMs &&
                (pm?.isInteractive != true || host.powerSaveMode())
            ) {
                delay(gifHealthPollMs)
                waitedForScreen += gifHealthPollMs
            }
            // Still hidden when the wait ran out (screen off, another app in
            // front, the system live-wallpaper dialog): do NOT decode a
            // full-screen first frame and upload it. That was the one code
            // path that kept doing heavy work while nobody could see the
            // wallpaper - against the "no decode while hidden" rule - and
            // showImage() has no power-save guard of its own.
            // A later visible start goes through a fresh watchdog.
            if (pm?.isInteractive != true || host.powerSaveMode()) {
                AppLog.d(
                    tag,
                    "GIF health check skipped: wallpaper still not visible after " +
                        "${waitedForScreen / 1000}s"
                )
                return@launch
            }
            delay(8_000L)
            if (host.engineDestroyed()) return@launch
            if (!host.isCurrentMedia(mediaId) || gifFirstFrameLogged) return@launch
            AppLog.w(tag, "GIF produced no frame for 8s; falling back to first frame")
            // Baked: this frame is drawn by the GIF path, not the quad.
            val bmp = host.loadBakedBitmap(uriStr) ?: return@launch
            mainHandler.post { host.presentGifFallback(bmp, mediaId) }
        }
    }

    @android.annotation.TargetApi(28)
    private fun playGif28(
        drawable: Drawable,
        scaleMode: ScaleMode,
        mediaId: Long,
        frameDelaysMs: IntArray?
    ) {
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = -1
            drawable.start()

            // Render into a buffer at most the screen size: a large GIF
            // would otherwise upload its full intrinsic resolution to the
            // GPU ~30 times per second, a major power drain. The wallpaper
            // displays at screen resolution anyway, so the visible quality
            // is identical to the original file.
            val intrinsicW = drawable.intrinsicWidth.coerceAtLeast(1)
            val intrinsicH = drawable.intrinsicHeight.coerceAtLeast(1)
            val screenMax = host.screenMaxPx()
            // FIT never enlarges; FILL/STRETCH magnify the GIF, so keep a
            // higher buffer ceiling in those modes to avoid softening large
            // GIFs when they are upscaled.
            val gifCap = when (scaleMode) {
                ScaleMode.FILL, ScaleMode.STRETCH ->
                    minOf(screenMax * 2, 4096).coerceAtLeast(2560)
                else -> screenMax
            }
            val gifScale = if (maxOf(intrinsicW, intrinsicH) > gifCap) {
                gifCap.toFloat() / maxOf(intrinsicW, intrinsicH)
            } else {
                1f
            }
            var frameW = (intrinsicW * gifScale).toInt().coerceAtLeast(1)
            var frameH = (intrinsicH * gifScale).toInt().coerceAtLeast(1)
            // FILL/STRETCH rotates media whose orientation mismatches the
            // screen (same rule as static images) so more of the GIF shows
            // instead of a thin perpendicular strip.
            val rotateForScreen = host.shouldRotateForScreen(intrinsicW, intrinsicH)
            // Hard bound on the two ping-pong ARGB buffers: the 4096 cap in
            // FILL/STRETCH would otherwise allocate ~128MB of buffers on
            // top of the drawable's own decoded frames, which can OOM on
            // tablets/low-RAM devices. Scale the FRAME (aspect preserved)
            // when the monster-GIF budget would be exceeded.
            val maxFramePixels = (96L * 1024 * 1024) / 2 / 4
            if (frameW.toLong() * frameH > maxFramePixels) {
                val shrink = kotlin.math.sqrt(
                    maxFramePixels.toDouble() / (frameW.toDouble() * frameH)
                )
                frameW = (frameW * shrink).toInt().coerceAtLeast(1)
                frameH = (frameH * shrink).toInt().coerceAtLeast(1)
            }
            val frameDrawScale = frameW.toFloat() / intrinsicW
            val bufferW = if (rotateForScreen) frameH else frameW
            val bufferH = if (rotateForScreen) frameW else frameH
            // Hand the drawable + buffer sizes to the GIF thread: it stops
            // the previous GIF (if any), owns the ping-pong buffers and
            // runs the frame ticker OFF the main thread. FIFO ordering on
            // the GIF handler guarantees the pause() stop posted before
            // this runs first, and the start() above is a one-time kick.
            ensureGifThread()
            gifHandler?.post {
                startGifTicker(
                    drawable, bufferW, bufferH, frameDrawScale, mediaId, rotateForScreen,
                    frameDelaysMs
                )
            }
            // The animated drawable started: the GIF is healthy.
            host.onGifSuccess(mediaId)
        } else {
            // Decoder returned a non-animated drawable (e.g. single-frame GIF):
            // render its first frame so the screen is never left blank.
            //
            // Runs on the GIF thread, not on the caller's (main) thread: this
            // path allocates a screen-size ARGB bitmap, rasterizes the frame
            // into it and may rotate it (one more full-size copy). The
            // animated branch above deliberately moved the same kind of work
            // off the main thread - the wallpaper engine shares the app's main
            // looper, so a stutter here is visible while the app is open.
            val mode = scaleMode
            val intrinsicW = drawable.intrinsicWidth.coerceAtLeast(1)
            val intrinsicH = drawable.intrinsicHeight.coerceAtLeast(1)
            ensureGifThread()
            val posted = gifHandler?.post {
                try {
                    // Cap the fallback frame at the screen resolution: a huge
                    // single-frame GIF would otherwise allocate a full-size
                    // ARGB bitmap (the screen only displays at screen size).
                    val screenMax = host.screenMaxPx()
                    val cap = minOf(screenMax, 4096).coerceAtLeast(1920)
                    val maxDim = maxOf(intrinsicW, intrinsicH)
                    val frameScale = if (maxDim > cap) cap.toFloat() / maxDim else 1f
                    val frameW = (intrinsicW * frameScale).toInt().coerceAtLeast(1)
                    val frameH = (intrinsicH * frameScale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(bmp)
                    if (frameScale < 1f) cv.scale(frameScale, frameScale)
                    drawable.draw(cv)
                    val displayed = if (host.shouldRotateForScreen(intrinsicW, intrinsicH)) {
                        host.rotate90(bmp, host.autoRotateClockwise())
                    } else {
                        bmp
                    }
                    if (host.engineDestroyed()) {
                        // The engine went away while this task was queued:
                        // release the frame instead of holding a screen-size
                        // bitmap for a wallpaper that no longer exists.
                        if (!displayed.isRecycled) displayed.recycle()
                    } else {
                        host.showImage(displayed, mode)
                        host.onGifSuccess(mediaId)
                    }
                } catch (t: Throwable) {
                    AppLog.e(tag, "GIF static frame failed", t)
                    mainHandler.post { host.onGifFailed(mediaId) }
                } finally {
                    try { (drawable as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                }
            }
            // The GIF thread is quitting (engine teardown) - post() returned
            // false, so nothing above will run: release the drawable here
            // instead of leaking its decoded frames.
            if (posted != true) {
                try { (drawable as java.lang.AutoCloseable).close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Lazily create the GIF render thread. Runs on the caller's thread
     * (main) only the first time a GIF is actually played.
     */
    private fun ensureGifThread() {
        if (gifHandler == null) {
            val t = HandlerThread("GifRender")
            t.start()
            gifThread = t
            gifHandler = Handler(t.looper)
        }
    }

    /**
     * Runs on the GIF thread: replace the current GIF with [drawable] and
     * start its frame ticker. Buffer allocation, frame rasterization and
     * buffer swaps all stay on this thread; only the GL upload crosses to
     * the render thread via showGifFrame().
     *
     * Only reachable from [playGif28] (AnimatedImageDrawable exists from
     * API 28 on): the annotation states that for lint, which otherwise flags
     * the start()/stop() calls below against minSdk 26.
     */
    @android.annotation.TargetApi(28)
    private fun startGifTicker(
        drawable: AnimatedImageDrawable,
        frameW: Int,
        frameH: Int,
        drawScale: Float,
        mediaId: Long,
        rotateForScreen: Boolean,
        frameDelaysMs: IntArray?
    ) {
        stopAndCloseGifDrawable()
        gifDrawable = drawable
        gifFirstFrameLogged = false
        try {
            gifBitmapBuffer?.recycle()
            gifBitmapBufferAlt?.recycle()
            gifBitmapBuffer = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
            gifBitmapBufferAlt = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            // Buffer allocation failed (OOM on low-RAM devices despite the
            // pixel cap). Never crash the wallpaper process: close the
            // drawable and let the engine recover with a different media.
            AppLog.e(tag, "GIF buffer allocation failed (${frameW}x$frameH)", t)
            stopAndCloseGifDrawable()
            gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
            gifBitmapBufferAlt?.recycle(); gifBitmapBufferAlt = null
            mainHandler.post { host.onGifFailed(mediaId) }
            return
        }

        // Opaque GIFs overwrite every pixel of the buffer, so the
        // full-screen erase (a ~10MB clear at 1080p, 20x/sec) is skipped.
        val opaque = drawable.opacity == PixelFormat.OPAQUE
        // (Re)allocate the two ping-pong rasterization targets. They can be
        // gone while this ticker is still alive: low memory pressure releases
        // them (releaseBuffersForMemory) to hand the system two screen-size
        // ARGB bitmaps back without stopping the animation - the next tick
        // simply rebuilds them. This is why the ticker must not treat a null
        // buffer as a fatal stop (the old `gifBitmapBuffer ?: return` would
        // have frozen the GIF on its current frame, because the early return
        // skips the postDelayed that keeps the chain alive).
        val ensureBuffers: () -> Boolean = {
            if (gifBitmapBuffer == null || gifBitmapBufferAlt == null) {
                try {
                    gifBitmapBuffer?.recycle()
                    gifBitmapBufferAlt?.recycle()
                    gifBitmapBuffer = null
                    gifBitmapBufferAlt = null
                    gifBitmapBuffer = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
                    gifBitmapBufferAlt = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
                } catch (t: Throwable) {
                    // Same bound as the initial allocation: never let an OOM
                    // here kill the wallpaper process. The ticker keeps its
                    // 20fps chain (below) instead of dying with it.
                    AppLog.w(tag, "GIF buffer re-allocation failed (${frameW}x$frameH)", t)
                    gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
                    gifBitmapBufferAlt?.recycle(); gifBitmapBufferAlt = null
                }
            }
            gifBitmapBuffer != null && gifBitmapBufferAlt != null
        }
        // True while the animation is frozen because the wallpaper is not
        // visible (see below).
        var pausedForVisibility = false
        // Position in the GIF's own frame-delay list (see GifTiming).
        // The drawable advances one frame per draw once the elapsed time
        // reaches the frame's duration, so ticking on that duration is what
        // keeps one rasterize + upload per frame instead of the fixed 20fps
        // sampling that re-drew and re-uploaded every frame of a slower GIF.
        var frameIndex = 0
        // Consecutive rasterization failures (see gifFrameFailureLimit).
        // Reset by every frame that is drawn, so a one-off glitch does not
        // count towards the limit.
        var consecutiveFrameFailures = 0
        // Identity of THIS chain. The runnable used to recognise itself only by
        // reference equality with gifFrameRunnable, which cannot tell one chain
        // from two: nudge() removes the pending callback and posts it again, and
        // when it did that while the runnable was already running, the removal
        // was a no-op AND the in-flight body still queued its own follow-up -
        // two chains, double-speed animation, interleaved buffer swaps.
        val tickGen = ++gifTickGeneration
        val runnable = object : Runnable {
            override fun run() {
                // A newer ticker superseded this one (startGifTicker
                // replaced gifFrameRunnable, pause() nulled it, or nudge()
                // restarted it): stop this chain instead of drawing with a
                // stale drawable.
                if (gifFrameRunnable !== this || tickGen != gifTickGeneration) return
                if (gifDrawable == null) return
                // Not visible (screen off / another app in front / the
                // system live-wallpaper dialog): pause the animation
                // COMPLETELY, exactly like video does. stop() freezes it on
                // the current frame and consumes nothing; start() resumes
                // from that frame, so the animation neither advances
                // unseen nor jumps when the desktop comes back.
                if (host.powerSaveMode()) {
                    if (!pausedForVisibility) {
                        pausedForVisibility = true
                        try {
                            gifDrawable?.stop()
                        } catch (_: Exception) {
                        }
                        // One line per hidden episode (the timer may swap in
                        // a new GIF while it is hidden).
                        if (!gifPauseAnnounced) {
                            gifPauseAnnounced = true
                            AppLog.d(tag, "GIF paused (wallpaper not visible)")
                        }
                    }
                    // Safety-net poll only: a resume nudges the
                    // ticker directly on resume (see nudge), so this
                    // can be rare instead of once per second while hidden.
                    gifHandler?.postDelayed(this, gifPausedPollMs)
                    return
                }
                if (pausedForVisibility) {
                    pausedForVisibility = false
                    try {
                        gifDrawable?.start()
                    } catch (_: Exception) {
                    }
                    if (gifPauseAnnounced) {
                        AppLog.d(tag, "GIF resumed (wallpaper visible again)")
                    }
                }
                // Keep ticking even when the surface is temporarily
                // unavailable, so the animation resumes automatically
                // once the surface comes back (previously the runnable
                // returned without rescheduling and the GIF froze).
                if (host.surfaceReady()) {
                    try {
                        // Rebuild the ping-pong buffers if low memory pressure
                        // released them (see releaseBuffersForMemory). When the
                        // allocation still fails this tick presents nothing and
                        // simply reschedules - the animation resumes as soon as
                        // memory allows instead of freezing for good.
                        if (!ensureBuffers()) {
                            gifHandler?.postDelayed(this, gifFrameIntervalMs)
                            return
                        }
                        val bmp = gifBitmapBuffer ?: return
                        if (!opaque) bmp.eraseColor(Color.TRANSPARENT)
                        val cv = Canvas(bmp)
                        if (rotateForScreen) {
                            // Draw the source rotated 90° into the
                            // portrait/landscape buffer so FILL crops the
                            // short axis instead of a thin strip.
                            cv.save()
                            cv.translate(frameW / 2f, frameH / 2f)
                            cv.rotate(if (host.autoRotateClockwise()) 90f else -90f)
                            cv.scale(drawScale, drawScale)
                            cv.translate(
                                -drawable.intrinsicWidth.coerceAtLeast(1) / 2f,
                                -drawable.intrinsicHeight.coerceAtLeast(1) / 2f
                            )
                            drawable.draw(cv)
                            cv.restore()
                        } else {
                            if (drawScale < 1f) {
                                cv.scale(drawScale, drawScale)
                            }
                            drawable.draw(cv)
                        }
                        // Read the CURRENT scale mode every frame so a live
                        // Settings change re-fits the animation without a
                        // restart (currentScaleMode is volatile).
                        val mode = host.currentScaleMode()
                        val queued = host.showGifFrame(bmp, mode)
                        // Visible playback again: the next hidden episode
                        // announces itself.
                        gifPauseAnnounced = false
                        if (!gifFirstFrameLogged) {
                            gifFirstFrameLogged = true
                            AppLog.d(tag, "GIF frame presented: ${bmp.width}x${bmp.height} mode=$mode")
                        }
                        // Swap buffers for the next frame - but ONLY when this
                        // frame really went into the render queue. The renderer
                        // coalesces (at most one queued GIF frame), and the old
                        // code swapped unconditionally: on a dropped frame the
                        // buffer that was still waiting in the queue became the
                        // next draw target, so the frame on screen was torn /
                        // overwritten with a half-drawn one.
                        if (queued) {
                            val tmp = gifBitmapBuffer
                            gifBitmapBuffer = gifBitmapBufferAlt
                            gifBitmapBufferAlt = tmp
                        }
                        // This tick presented a frame: step to the delay of
                        // the frame that is due next.
                        frameIndex++
                        consecutiveFrameFailures = 0
                    } catch (t: Throwable) {
                        // Bounded: a drawable that throws on every frame used
                        // to keep this ticker running forever at the file's
                        // frame rate (20 stack traces + 20 wakeups per second,
                        // each log line flushed to disk). Give up instead and
                        // let the engine recover with another media, exactly
                        // like the load-failure path does.
                        consecutiveFrameFailures++
                        AppLog.e(
                            tag,
                            "GIF frame draw failed " +
                                "($consecutiveFrameFailures/$gifFrameFailureLimit)",
                            t
                        )
                        if (consecutiveFrameFailures >= gifFrameFailureLimit) {
                            gifFrameRunnable = null
                            stopAndCloseGifDrawable()
                            mainHandler.post { host.onGifFailed(mediaId) }
                            return
                        }
                    }
                }
                gifHandler?.postDelayed(this, gifFrameStepMs(frameDelaysMs, frameIndex))
            }
        }
        gifFrameRunnable = runnable
        gifHandler?.post(runnable)
    }

    /**
     * Delay until the GIF's own next frame, from the delays parsed out of
     * the file (see [GifTiming]).
     *
     * The small margin over the declared delay keeps every tick on a frame
     * boundary: the drawable advances one frame per draw once the elapsed
     * time has reached the frame's duration, and a tick that landed a
     * fraction early would present the same frame twice and halve the
     * animation's speed. Falls back to [gifFrameIntervalMs] whenever the
     * delays are unknown (not a GIF, unreadable provider), which is exactly
     * the cadence this ticker had before.
     */
    private fun gifFrameStepMs(frameDelaysMs: IntArray?, frameIndex: Int): Long {
        if (frameDelaysMs == null || frameDelaysMs.isEmpty()) {
            return gifFrameIntervalMs
        }
        val declared = frameDelaysMs[frameIndex % frameDelaysMs.size]
        return (declared + gifFrameDelayMarginMs)
            .coerceIn(gifFrameIntervalMs, gifMaxFrameIntervalMs)
    }

    fun pause() {
        gifHealthJob?.cancel()
        gifHealthJob = null
        gifFrameRunnable?.let { gifHandler?.removeCallbacks(it) }
        gifFrameRunnable = null
        pendingGifUri = null
        // Also stop the underlying animation: without this the drawable
        // keeps decoding frames in the background after a switch away from
        // the GIF, wasting CPU for nothing. Done on the GIF thread (never
        // main) so a frame draw in flight can't race the stop/close;
        // playGif28() starts a fresh drawable, so returning to GIFs
        // restarts cleanly.
        gifHandler?.post {
            stopAndCloseGifDrawable()
        }
    }

    /**
     * Runs on the GIF thread: stop + close the current animated drawable.
     * Idempotent; safe to call twice (pause + startGifTicker).
     */
    private fun stopAndCloseGifDrawable() {
        gifDrawable?.let { g ->
            // Drop the frame clock first: the ticker must not keep a
            // timestamp from a drawable that is about to be closed.
            try { g.callback = null } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= 28) {
                try { g.stop() } catch (_: Exception) {}
                try { (g as java.lang.AutoCloseable).close() } catch (_: Exception) {}
            }
        }
        gifDrawable = null
    }

    /**
     * Resume the GIF ticker immediately. Only the paused branch posts with
     * [gifPausedPollMs], so without this nudge the animation would restart
     * up to 5s after the desktop came back. Pending callbacks are removed
     * first: posting the same runnable twice would start two tick chains and
     * play the animation at double speed.
     */
    fun nudge() {
        val runnable = gifFrameRunnable ?: return
        val handler = gifHandler ?: return
        // Even when removeCallbacks is a no-op (the runnable is running right
        // now), the bump makes that in-flight chain retire instead of queueing
        // its own follow-up - so exactly one chain survives.
        gifTickGeneration++
        handler.removeCallbacks(runnable)
        handler.post(runnable)
    }

    /** Parked-media release (see LiveWallpaperEngine.scheduleMediaReleaseWhileLocked). */
    fun releaseWhileLocked() {
        pause()
        // Recycled on the GIF thread (queued behind pause's own stop/close
        // task): a frame draw may still be in flight, and releasing the
        // buffers from the main thread would race it.
        gifHandler?.post {
            gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
            gifBitmapBufferAlt?.recycle(); gifBitmapBufferAlt = null
        }
    }

    /**
     * Low-memory release of the two ping-pong rasterization targets (two
     * screen-size ARGB bitmaps, ~8MB each at 1080p) WITHOUT stopping the
     * animation - the next tick rebuilds them (see the ticker's ensureBuffers).
     *
     * Only called by the engine while the GIF is NOT driving the screen
     * (see LiveWallpaperEngine.trimMemoryNow): then the buffers are pure
     * idle waste. The recycle itself is serialized on the GIF thread, exactly
     * like [releaseWhileLocked]: the main thread must never recycle a buffer a
     * frame draw in flight is still painting into.
     */
    fun releaseBuffersForMemory() {
        gifHandler?.post {
            gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
            gifBitmapBufferAlt?.recycle(); gifBitmapBufferAlt = null
        }
    }

    /**
     * Engine teardown: stop the ticker, close the drawable and recycle the
     * ping-pong buffers on the GIF thread (a frame draw in flight must never
     * race a close/recycle from main), then quit the thread. quitSafely()
     * drains the posted task first.
     */
    fun shutdown() {
        gifHealthJob?.cancel()
        gifHealthJob = null
        gifFrameRunnable?.let { gifHandler?.removeCallbacks(it) }
        gifFrameRunnable = null
        pendingGifUri = null
        val lastGif = gifDrawable
        gifDrawable = null
        gifHandler?.post {
            lastGif?.let { g ->
                // AnimatedImageDrawable only exists on API 28+; on older
                // versions gifDrawable is always null here, so this guard
                // is both required for lint and correct at runtime.
                if (Build.VERSION.SDK_INT >= 28) {
                    try { g.stop() } catch (_: Exception) {}
                    try { (g as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                }
            }
            gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
            gifBitmapBufferAlt?.recycle(); gifBitmapBufferAlt = null
        }
        gifThread?.quitSafely()
        gifThread = null
        gifHandler = null
    }

    /**
     * Decode an animated GIF drawable with a hard timeout, mirroring the
     * engine's loadBitmapWithTimeout: ImageDecoder.decodeDrawable cannot be
     * interrupted by coroutine cancellation, so the decode runs on a
     * helper thread and the caller abandons it after [decodeTimeoutMs]. If the
     * abandoned thread ever finishes, its drawable is closed. Returns null
     * on timeout / failure / API < 28 (the caller falls back to recovery).
     */
    private fun decodeGifDrawableWithTimeout(
        uriStr: String,
        timeoutMs: Long = decodeTimeoutMs
    ): Drawable? {
        if (Build.VERSION.SDK_INT < 28) return null
        val result = AtomicReference<Drawable?>(null)
        val abandoned = AtomicBoolean(false)
        val thread = Thread({
            try {
                val source = android.graphics.ImageDecoder.createSource(
                    context.contentResolver, android.net.Uri.parse(uriStr)
                )
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                if (abandoned.get()) {
                    try { (drawable as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                } else {
                    result.set(drawable)
                    if (abandoned.get()) {
                        val stored = result.getAndSet(null)
                        try { (stored as? java.lang.AutoCloseable)?.close() } catch (_: Exception) {}
                    }
                }
            } catch (_: Throwable) {}
        }, "GifDecode").apply {
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
        return result.get()
    }
}
