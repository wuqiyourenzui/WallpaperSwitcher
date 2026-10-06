package com.wallpaperswitcher.wallpaper

import com.wallpaperswitcher.util.AppLog

import com.wallpaperswitcher.util.LogText

import android.content.Context

import android.content.res.AssetFileDescriptor

import android.graphics.*

import android.media.*

import android.net.Uri

import android.opengl.*

import android.os.Handler

import android.os.HandlerThread

import android.os.SystemClock

import android.view.Surface

import android.view.SurfaceHolder
import android.view.Choreographer

import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys

import com.wallpaperswitcher.engine.WallpaperGeometry
import com.wallpaperswitcher.engine.TransitionCurve
import com.wallpaperswitcher.engine.VideoSound

import java.nio.ByteBuffer

import java.nio.ByteOrder

import java.nio.FloatBuffer

import java.util.concurrent.atomic.AtomicBoolean

import java.util.concurrent.atomic.AtomicInteger


/**
 * Unified EGL renderer with MediaCodec + SurfaceTexture for video.
 *
 * ARCHITECTURE:
 * - All GL/EGL operations happen exclusively on the render thread.
 * - Video cleanup + image rendering are atomic (single handler post) — no flash/stutter.
 * - SurfaceTexture and GL textures are released together on the render thread.
 * - EGL context survives surface recreation (GLSurfaceView pattern).
 *
 * THREAD SAFETY:
 * - renderHandler is the ONLY thread that touches GL/EGL state.
 * - Decode thread only handles MediaExtractor + MediaCodec I/O.
 * - stopVideo() / release() post cleanup to render thread and return immediately.
 */
class WallpaperRenderer(
    internal val context: Context,
    private val holder: SurfaceHolder
) {
    companion object {
        private const val TAG = "WallpaperRenderer"
        /**
         * Hard ceiling for the one-off "provider is not seekable" copy into
         * cacheDir. Without it a multi-GB cloud video was copied in full even
         * though the decode only ever needs a bounded window, filling the
         * cache partition (and the copy could not be interrupted, so a switch
         * away kept it running in the background).
         */
        private const val VIDEO_CACHE_COPY_MAX_BYTES = 512L * 1024 * 1024
        /** Keep this much free space on the cache partition after a copy. */
        private const val VIDEO_CACHE_FREE_RESERVE_BYTES = 256L * 1024 * 1024
        private const val VIDEO_COPY_BUFFER_BYTES = 256 * 1024
        /** Left-over copies older than this are deleted on the next copy. */
        private const val VIDEO_CACHE_STALE_MS = 60L * 60 * 1000
        /** How many leftover copies (newest first) to keep for diagnosis/resume. */
        private const val VIDEO_CACHE_KEEP_FILES = 2
        /**
         * Minimum minification (texture pixels per screen pixel, on one axis)
         * before the mip chain is worth generating for a static image.
         *
         * A 1.2-1.5x downscale samples mip level 0 anyway, so building the whole
         * chain costs a full extra GPU pass per switch for no visible gain. On a
         * 3200x2136 tablet that pass lands exactly when the system dialog is
         * closing and the wallpaper is being re-composed - the "点击设置壁纸后
         * 卡顿" report. Real minification (≥1.6x) still gets mipmaps.
         */
        private const val MIPMAP_MIN_DOWNSCALE = 1.6f
        /** 静态图微动效 (Ken Burns): one zoom tick every ~66ms (~15fps). */
        private const val KEN_BURNS_FRAME_MS = 66L
        /**
         * 音频看门狗: how often a live video session re-checks that the sound
         * is really playing while the wallpaper is visible. The check is a
         * couple of volatile reads and one synchronized flag read - cheap
         * enough to run next to the video.
         */
        private const val AUDIO_WATCHDOG_INTERVAL_MS = 1_200L
        /**
         * Grace before the watchdog declares "silent": a video that just
         * started (or just came back from a power-save pause) needs a moment
         * for its first pass and the AudioTrack. Two intervals cover any
         * normal start-up.
         */
        private const val AUDIO_SILENT_GRACE_MS = 2_000L
        /** One full zoom-in + zoom-out cycle. */
        private const val KEN_BURNS_PERIOD_MS = 24_000L
        /** Peak extra scale: 6% is clearly visible but crops very little. */
        private const val KEN_BURNS_AMPLITUDE = 0.06f
        /** While GIF frames arrive this often, Ken Burns stays out of the way. */
        private const val KEN_BURNS_GIF_SUPPRESS_MS = 1_500L
        /** Codec-specific-data keys re-submitted after a codec flush. */
        private val CODEC_CONFIG_KEYS = arrayOf("csd-0", "csd-1", "csd-2")

        // Column-major GL matrices for the FILL/STRETCH auto-rotation, matching
        // the static-image direction for each user-selectable direction.
        // clockwise:     x' = y,      y' = 1 - x
        // counter-cw:    x' = 1 - y,  y' = x
        private val EXTRA_ROTATE_90_CW_MATRIX = floatArrayOf(
            0f, -1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f
        )
        private val EXTRA_ROTATE_90_CCW_MATRIX = floatArrayOf(
            0f, 1f, 0f, 0f,
            -1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            1f, 0f, 0f, 1f
        )

    }

    // EGL — all access on render thread only (see [EglSurfaceHolder]).
    private val egl = EglSurfaceHolder(
        tag = TAG,
        host = object : EglSurfaceHolder.Host {
            override fun surface(): Surface = holder.surface
            override fun surfaceReady(): Boolean = this@WallpaperRenderer.surfaceReady
            override fun setContextReady(ready: Boolean) {
                contextReady = ready
            }
            override fun setSurfaceReady(ready: Boolean) {
                surfaceReady = ready
            }
            override fun ensureGlResources() {
                if (!glResourcesValid) {
                    setupGlResources()
                    glResourcesValid = true
                }
            }
            override fun onContextRecreated() {
                cleanupVideoResourcesOnRenderThread()
                cleanupGlResources()
                setupGlResources()
                glResourcesValid = true
            }
            override fun onSurfaceCreated(width: Int, height: Int) {
                surfaceReady = true
                screenW = width.toFloat()
                screenH = height.toFloat()
            }
            override fun postRetry(delayMs: Long, block: () -> Unit) {
                renderHandler?.postDelayed({ block() }, delayMs)
            }
        },
    )
    // Written on the render thread, polled from the engine thread.
    @Volatile internal var surfaceReady = false
    internal var contextReady = false
    private var glResourcesValid = false

    // GL resources (created once, survive surface recreation)
    internal val gl = GlResources(TAG)
    // Cached shader locations: queried once per program creation instead of
    // 6 times per rendered frame (at 30fps that is ~180 driver queries/sec).
    // Sharpening uniforms (queried once per program creation).
    // 画质增强 uniforms (super-resolution strength + source texel size).
    /**
     * 画质增强 (AI/超分): when on, a source that is being magnified is sampled
     * with a 4-tap Catmull-Rom bicubic and sharper unsharp masking (images and
     * video frames share the path). Written from the engine thread.
     */
    @Volatile private var qualityEnhance: Boolean = false
    /**
     * 放大算法: [com.wallpaperswitcher.engine.EnhanceMode]（0 = 内置双三次、
     * 1 = FSR1 EASU/RCAS、2 = Anime4K）。只在增强分支（uEnhance > 0）里生效。
     */
    @Volatile private var enhanceMode: Int = 0
    /** 静态图的降噪强度（CPU 小样检测，随新图重算）。 */
    private var lastImageDenoise = 0f
    private var lastDenoiseBitmap: Bitmap? = null
    // Engine-controlled clarity strength multiplier: 0 = off, 1.25 = default
    // curve, >1 = stronger. Written from the engine thread on each switch,
    // read on the render thread. Default matches the "auto" clarity mode so
    // the very first frame is already rendered with the improved sharpening.
    @Volatile var sharpnessScale: Float = 1.25f
    // Screen-off low-power mode: while true the decode loop throttles to
    // ~10fps instead of the source rate. Playback is never stopped/restarted;
    // the position simply advances slowly while the screen is off and resumes
    // full speed on the next screen-on. Written from the engine thread.
    /**
     * Screen-off / covered power save: the video and audio loops stop feeding
     * their decoders and WAIT (instead of polling) until this flips back.
     *
     * The setter notifies [pauseLock], so a hidden wallpaper costs **zero**
     * periodic wakeups - the old 250ms poll was 4 wakeups/second per loop (8/s
     * with audio + video) for the whole time the screen was off - while a resume
     * is still immediate.
     */
    @Volatile
    var powerSaveMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                // Silence the video's audio in the same instant the wallpaper is
                // hidden (screen off, another app, our own app opening). The
                // audio loop would otherwise notice on its next PCM buffer, and
                // with the app-open path the visibility callback itself can lag
                // behind the window animation - the "声音无法立刻关闭" report.
                // AudioTrack.pause() is thread-safe and drops nothing buffered.
                audio.pauseTrack()
            } else {
                synchronized(pauseLock) { pauseLock.notifyAll() }
                // Ken Burns sleeps with the wallpaper; wake its ticker up.
                postToRenderThread { maybeStartKenBurns() }
            }
        }
    /** See [powerSaveMode]; guards the pause waits of the video/audio loops. */
    internal val pauseLock = Object()
    /** Safety net for those waits: re-check the state at least this often. */
    internal val PAUSE_WAIT_MAX_MS = 5_000L
    // Switch fade-in state (render thread only): a black overlay whose alpha
    // decays over ~250ms after a switch, drawn on top of every presented frame.
    private var fadeAlpha = 0f
    private var fadeGeneration = 0
    /**
     * 过渡动画 (see SettingsKeys.SWITCH_TRANSITION_*): "fade" (the historical
     * black fade-in), "slide" / "zoom" (quad geometry animation) or "none".
     * Render-thread state: written through [requestTransition].
     */
    private var transitionMode = SettingsKeys.SWITCH_TRANSITION_DEFAULT
    /** 0 at the start of a slide/zoom transition, 1 when settled. */
    private var transitionProgress = 1f
    /**
     * Frame-clock driver of the running transition (null = none). The animation
     * is sampled in [android.view.Choreographer.FrameCallback], i.e. once per
     * displayed frame, instead of on a fixed 25ms timer - that is what makes it
     * smooth on 60/90/120Hz panels.
     */
    private var transitionCallback: android.view.Choreographer.FrameCallback? = null
    /** Frame time the running transition started at (0 = not started yet). */
    private var transitionStartNanos = 0L
    /** Frames the running transition has presented (for the completion log). */
    private var transitionFrameCount = 0
    /**
     * CPU bitmap behind the image currently uploaded to `gl.imageTexId`.
     *
     * Since [dropImageBitmapForMemory] can release the engine's bitmap, this
     * reference may point at a RECYCLED bitmap: it is kept only as the
     * "there was an image upload" marker and must NEVER be uploaded again or
     * have its pixels read. Volatile because the drop runs on the engine's
     * (main) thread while the render thread reads it for the re-present
     * guards.
     */
    @Volatile private var lastImageBitmap: Bitmap? = null
    /**
     * Texture-side twin of [lastImageBitmap]: true once [renderImage] put THIS
     * media's pixels into `gl.imageTexId`, so the still image can be drawn
     * again straight from the GPU - with the CPU bitmap recycled or released
     * (see [dropImageBitmapForMemory]).
     *
     * Read only on the render thread; the drop task runs there too.
     */
    private var imageTextured = false
    /**
     * Source size (unrotated pixels) of the image uploaded to `gl.imageTexId`.
     *
     * `renderImageFromTexture()` only ever needed the bitmap's WIDTH/HEIGHT to
     * rebuild the quad and the shader texel/upscale uniforms - the pixels come
     * from the texture. Caching the size here is what makes a redraw possible
     * once the CPU bitmap is gone (a recycled Bitmap still answers width/height
     * today, but that is an implementation detail, not a contract).
     *
     * Written on the render thread before [imageTextured] is published.
     */
    private var imageSrcW = 0f
    private var imageSrcH = 0f
    private var lastImageScaleMode: ScaleMode = ScaleMode.FIT
    /** GPU quarter turn applied to the image currently uploaded (see computeQuad). */
    private var lastImageRotateCw: Boolean? = null
    private var lastRenderWasImage = false
    // --- 静态图微动效 (Ken Burns) ---
    /** Render-thread state; written through [setKenBurnsEnabled]. */
    private var kenBurnsEnabled = false
    /** When the still image's zoom cycle started (render thread). */
    private var kenBurnsStartMs = 0L
    private var kenBurnsFramePosted = false
    /**
     * Timestamp of the last GIF-ticker frame (written on the engine thread,
     * read by [kenBurnsRunnable]): while a GIF animates itself, Ken Burns stays
     * out of the way instead of doubling its frame rate.
     */
    @Volatile private var lastGifFrameAtMs = 0L
    // Reused on the render thread to avoid allocating a matrix per video frame.
    internal val videoTexMatrix = FloatArray(16)
    // Reused by renderVideoFrame when the auto-rotate feature rotates the quad:
    // allocating a FloatArray per frame (30-60/s) was pure garbage.
    private val rotatedTexMatrix = FloatArray(16)
    // Screen-pixel UV steps, computed per draw into fields instead of a Pair
    // (see updateImageScreenTexelDelta). Render thread only.
    private var imageTexelX = 1f
    private var imageTexelY = 1f
    private var videoTexelX = 1f
    private var videoTexelY = 1f
    // 1x1 opaque black texture + full-screen quad: drawn under every media so
    // the FIT/letterbox area always contains freshly presented black pixels
    // instead of whatever was left in the framebuffer (e.g. the previous
    // video's last frame), even on devices/drivers where glClear alone does
    // not invalidate the whole window surface.

    // Screen dimensions — only on render thread
    internal var screenW = 0f
    internal var screenH = 0f

    // Video state — ALL accessed only on render thread (after initial setup)
    internal var videoTexId = 0
    internal var surfaceTexture: SurfaceTexture? = null
    internal var codecSurface: Surface? = null
    // On-screen size + scale mode of the current video (render thread only),
    // used to sharpen the picture when a low-res video is magnified.
    // videoDisplayW/H are the on-screen orientation (90/270°-rotated phone
    // recordings swap the axes), which is what the upscale factor needs; the
    // screen-space kernel reads the quad extents from videoQuadHalfW/H.
    internal var videoDisplayW = 0f
    internal var videoDisplayH = 0f
    /** Actual decoded texture size (post decode-cap) for bicubic texel steps. */
    internal var videoSrcW = 0f
    internal var videoSrcH = 0f
    internal var videoScaleMode: ScaleMode = ScaleMode.FIT
    // Quad half-extents of the current video (render thread only). The texture
    // footprint on screen is (halfW*screenW, halfH*screenH) pixels, which the
    // screen-space sharpening kernel needs regardless of up/downscale.
    private var videoQuadHalfW = 1f
    private var videoQuadHalfH = 1f
    // FILL/STRETCH rotates orientation-mismatched video content 90° (same rule
    // as static images/GIFs) so more of the frame is visible. Render thread.
    internal var videoExtraRotate = false
    // Mirrors the app's "自动旋转适配" setting; written by the engine, read on
    // the render thread when (re)computing the video quad.
    @Volatile var autoRotateMismatch = true
    // Direction used when autoRotateMismatch is on.
    @Volatile var autoRotateClockwise = true
    // True when the current video's quad covers the whole viewport (FILL /
    // STRETCH, or FIT with a matching aspect). Only read/written on the render
    // thread; when false the letterbox needs the black backing quad.
    private var videoQuadFullscreen = false
    internal var extractor: MediaExtractor? = null
    internal var decoder: MediaCodec? = null
    private var videoDecodeThread: Thread? = null
    @Volatile var isVideoPlaying = false; internal set
    // Elapsed realtime of the last successfully presented video frame. The
    // engine's health monitor uses this to detect a stalled decoder (e.g. a
    // cloud file whose stream read blocks forever) and recover automatically.
    @Volatile var lastVideoFrameAt = 0L
        internal set

    /**
     * Reset the "last presented video frame" clock. The engine calls this while
     * the screen is off and again on screen-on: a frozen screen-off window has
     * no frames by definition and must never be mistaken for a stalled video.
     */
    fun resetVideoFrameClock() {
        lastVideoFrameAt = SystemClock.elapsedRealtime()
    }
    internal val videoGeneration = AtomicInteger(0)
    // Flag to prevent double-cleanup: stopVideoInternal sets this, decodeLoop checks it.
    internal val videoCleanupDone = AtomicBoolean(false)

    // --- Video audio (optional: SettingsKeys.VIDEO_SOUND_ENABLED) ---
    // The audio thread / AudioTrack / pass handshake live in [VideoAudioPlayer];
    // the renderer only owns the video-side state the player reads.
    @Volatile var videoSoundEnabled = false
    /**
     * True while the video's audio was silenced for our OWN UI opening but the
     * picture deliberately kept playing (see [muteAudioKeepingVideo]).
     */
    private var audioMutedForOwnUi = false
        private set
    /**
     * Where the next `decodeLoop` pass 1 must start (µs). Set by [startVideo] and
     * consumed by the first round of the decode loop; later rounds always rewind
     * to 0 so the clip still loops from the beginning.
     */
    @Volatile internal var pendingStartPositionUs = 0L
    /**
     * Presentation time (µs) of the last frame handed to the surface.
     *
     * Used by "接着上次位置继续播放": when the engine releases the decoder while
     * the device is locked it remembers this value and starts the rebuilt video
     * (and its audio) there instead of at 0, so a long clip does not jump back to
     * the beginning after every lock.
     */
    @Volatile var lastVideoPositionUs: Long = 0L
    // The video currently playing, and the cache copy if the video path had to
    // make one (the cache file is seekable, so prefer it for the audio too).
    @Volatile private var currentVideoUri: String? = null
    @Volatile internal var currentVideoCachePath: String? = null
    internal val audio = VideoAudioPlayer(
        context = context,
        tag = TAG,
        soundEnabled = { videoSoundEnabled },
        generation = { videoGeneration.get() },
        cachePath = { currentVideoCachePath },
        powerSavePaused = { powerSaveMode },
        waitWhilePaused = {
            // Same monitor the video loop waits on: the power-save setter
            // notifies it, so a hidden wallpaper costs zero wakeups.
            synchronized(pauseLock) {
                if (powerSaveMode) {
                    try {
                        pauseLock.wait(PAUSE_WAIT_MAX_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        },
    )
    // Coalescing flag: at most one render post is queued at a time, so a decode
    // thread that outruns the render thread (rapid switching, heavy load) can
    // never grow the handler queue without bound.
    internal val renderPostQueued = AtomicBoolean(false)
    // Same coalescing for GIF frames: the GIF loop runs at ~20fps, but if the
    // render thread is momentarily busy (e.g. mid-switch) the queued posts
    // must not pile up. GIF frames are disposable - the newest one wins, so
    // skipping an intermediate frame costs nothing visually.
    private val imageRenderPostQueued = AtomicBoolean(false)
    // Diagnostics: log the first GIF frame that had to be skipped because the
    // EGL surface was not ready (helps identify black-GIF reports on devices
    // where surface creation lags).
    @Volatile private var gifSkipLogged = false

    // Render thread (persists across surface recreations)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    /**
     * Post a task to the render thread with a safety net: an unexpected
     * exception from GL/EGL/driver code is logged instead of crashing the
     * render Looper (which would take down the whole wallpaper process).
     */
    private fun postToRenderThread(block: () -> Unit) {
        val h = renderHandler ?: return
        h.post {
            try {
                block()
            } catch (t: Throwable) {
                AppLog.e(TAG, "Render thread task failed", t)
            }
        }
    }

    /**
     * Invoked (on the decode thread) when a video fails to start, e.g. the GL
     * setup was skipped because a newer switch already replaced it, or the
     * surface/resources were torn down concurrently. The engine uses this to
     * reset its state and retry the current media instead of leaving the
     * previous video's last frame frozen on screen.
     */
    @Volatile
    var onVideoStartFailed: (() -> Unit)? = null
    /**
     * Invoked (once per video, on the render thread) when the first frame of the
     * video has actually been presented.
     *
     * The engine used to start the fade-in the moment `startVideo()` returned,
     * which is 200-400ms BEFORE the first frame reaches the screen: the fade
     * faded the OLD frame to black and the video then appeared at the end of it,
     * so switching to a video looked like "black, then a pop". Fading from the
     * first real frame makes the transition continuous.
     */
    @Volatile
    var onFirstVideoFrame: (() -> Unit)? = null
    /**
     * Invoked (on the decode thread) every time a video pass reaches its end,
     * right before the clip loops. The engine uses it for 视频播完再切: a timed
     * switch that arrived while the video was playing is executed here instead
     * of cutting the clip off mid-pass.
     */
    @Volatile
    var onVideoPassCompleted: (() -> Unit)? = null
    // Set by startVideo, cleared when the first frame has been reported.
    private val videoFirstFramePending = AtomicBoolean(false)
    @Volatile
    private var lastRenderLogAt = 0L
    /**
     * Throttle for the two per-frame warning paths (missing GL program/texture,
     * failed eglSwapBuffers). Rendering happens 30-60 times a second and AppLog
     * flushes every important line to disk, so an unthrottled warning here would
     * turn a rendering fault into hundreds of write syscalls per second.
     */
    internal var lastFrameWarnAt = 0L
    internal val FRAME_WARN_INTERVAL_MS = 5_000L
    // Render-side presentation floor. It used to be 33ms (~30fps), which threw
    // away every other frame of a 50/60fps video - the wallpaper then visibly
    // ran at half the source frame rate. The floor is now 16ms (60fps): a
    // normal 30/60fps clip is presented at exactly its own rate, while an
    // unusually fast source cannot spin the render thread. Screen-off /
    // not-visible power saving is handled by the decode loop (~1fps), not here.
    private val minVideoSwapGapMs = 16L
    private var lastVideoFrameSwappedAt = 0L
    /**
     * How far behind its own presentation time a frame may be before the
     * playback clock is re-anchored instead of "catching up".
     *
     * While the wallpaper is hidden/throttled (screen off, another app open,
     * the system live-wallpaper dialog) the decode loop keeps presenting ~1
     * frame per second, so the wall clock runs away from the playback clock.
     * Without this bound every frame is "late" when the desktop comes back and
     * the clip is presented back-to-back (the render floor allows ~60fps) to
     * catch up - the reported "video plays fast at first, then normal again".
     */
    internal val maxPlaybackLagNs = 500_000_000L
    // Throttle for the "re-anchored" diagnostic line (render/decode thread).
    @Volatile internal var lastReanchorLogAt = 0L
    // Logged-once flag for the throttled episode (see the re-anchor below).
    @Volatile internal var powerSavePauseLogged = false
    /**
     * Whether the current "wallpaper not visible" episode has already been
     * announced. Cleared as soon as a frame is presented again (see the pacing
     * block), so every hide/show cycle produces exactly one pause/resume pair
     * even when the timer restarts the video while it is hidden.
     */
    @Volatile internal var videoPauseAnnounced = false
    // Rolling render-rate statistics (diagnostics only, ~1 log line/minute).
    private var renderFpsWindowStart = 0L
    private var renderFpsCount = 0

    // ======== Lifecycle ========

    fun initialize(initW: Float, initH: Float) {
        val thread = HandlerThread("WallpaperRenderer")
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)

        // Never block the caller (the engine's surface-created callback runs
        // on the MAIN thread): a busy render thread must not ANR the process.
        // The engine already polls isSurfaceReady() before drawing, and the
        // subsequent surfaceCreated() post is queued behind this task (FIFO),
        // so asynchronous initialization is safe.
        postToRenderThread {
            screenW = initW
            screenH = initH
            egl.setupContext()
            if (contextReady) {
                setupGlResources()
                glResourcesValid = true
                AppLog.d(TAG, "EGL initialized")
            }
        }
    }

    fun surfaceCreated() {
        // Synchronously drop the stale "ready" flag from the PREVIOUS surface.
        // During a recreation (e.g. tablet rotation) the destroy task may still
        // be queued on the render thread, so a stale true would make the
        // engine's redraw poll draw into the old/dead EGL surface (or skip the
        // wait for the new one). Clearing here makes isSurfaceReady() honest
        // until the posted create actually completes.
        surfaceReady = false
        postToRenderThread {
            if (!contextReady) {
                // EGL initialization may have failed earlier (transient driver
                // or memory issue). Retry on every surface creation so the
                // wallpaper never stays blank forever.
                egl.setupContext()
                if (contextReady) {
                    setupGlResources()
                    glResourcesValid = true
                }
            }
            if (contextReady) egl.createSurface()
        }
    }

    /**
     * Re-attempt the EGL setup only when the surface is not yet ready (e.g.
     * the first attempt failed on a particular device/driver, leaving the
     * wallpaper black). No-op when the surface is healthy, so calling this on
     * every visibility change never causes a flicker.
     */
    fun retrySurfaceIfNeeded() {
        if (surfaceReady) return
        surfaceCreated()
    }

    /**
     * True once the EGL surface is ready to be drawn on. surfaceCreated() is
     * asynchronous (posted to the render thread), so drawing must wait for
     * this flag before calling showImage/renderImage.
     */
    fun isSurfaceReady(): Boolean = surfaceReady

    /**
     * True when [uriStr] is the clip this renderer holds a session for - it is
     * playing, parked while the wallpaper is hidden, or was just torn down
     * because the surface was recreated by a rotation (see stopVideoInternal(),
     * which deliberately keeps the URI + last position so the engine can
     * continue the same clip instead of restarting it from 0:00).
     *
     * Cleared when the session is replaced by an image/GIF (stopVideoAndRender)
     * or when the renderer is released.
     */
    fun isCurrentVideo(uriStr: String): Boolean = currentVideoUri == uriStr

    fun surfaceChanged(width: Int, height: Int) {
        postToRenderThread {
            val sizeChanged = !surfaceReady ||
                screenW != width.toFloat() || screenH != height.toFloat()
            if (sizeChanged) {
                // Some OEM surfaces (HyperOS tablets) do NOT resize the EGL
                // window buffer just because glViewport changed; rendering with
                // a stale buffer leaves part of the wallpaper black. Recreate
                // the EGL surface so the buffer dimensions match the new
                // surface before drawing.
                egl.destroySurface()
                egl.createSurface()
                if (!surfaceReady) return@postToRenderThread
            } else {
                if (surfaceReady) GLES20.glViewport(0, 0, width, height)
            }
            // The viewport changed (rotation / resize): re-present the current
            // static image and recompute the current video quad against the new
            // aspect. Without this, a letterboxed FIT media stays rendered for
            // the old dimensions (distorted / misplaced) until the next switch.
            //
            // The guard is the TEXTURE, not the CPU bitmap: under memory
            // pressure the engine releases the bitmap while the uploaded
            // texture keeps the pixels, and a rotation must still redraw (see
            // canRepaintFromTexture).
            if (surfaceReady && lastRenderWasImage && canRepaintFromTexture()) {
                renderImageFromTexture()
            }
            if (surfaceReady && videoTexId != 0) {
                refreshVideoQuad(videoDisplayW, videoDisplayH, videoScaleMode)
            }
        }
    }

    /**
     * Live scale-mode change from the Settings screen: re-fit the currently
     * displayed media without restarting it. A static image (or the last GIF
     * frame) is re-presented from the existing texture with the new quad; a
     * video's quad + sharpening mode are recomputed so the next decoded frame
     * (~≤100ms away) renders with the new fit. No-op while the surface is not
     * ready — the next switch/redraw picks the new mode up anyway.
     */
    fun applyScaleMode(scaleMode: ScaleMode) {
        postToRenderThread {
            lastImageScaleMode = scaleMode
            videoScaleMode = scaleMode
            if (!surfaceReady || !egl.hasSurface) return@postToRenderThread
            // Texture-side guard: the CPU bitmap may have been released for
            // memory (canRepaintFromTexture) - the GPU still has the pixels.
            if (lastRenderWasImage && canRepaintFromTexture()) {
                renderImageFromTexture()
            } else if (videoTexId != 0 && videoDisplayW > 0f && videoDisplayH > 0f) {
                refreshVideoQuad(videoDisplayW, videoDisplayH, videoScaleMode)
            }
        }
    }

    /**
     * Recompute the video quad, applying the extra 90° rotation when the video
     * orientation mismatches the screen orientation. Runs on the render thread.
     *
     * Every scale mode rotates (FIT included): a mismatched orientation fitted
     * into the screen is limited by the screen's short side, so the turned video
     * is displayed much larger while staying fully visible.
     */
    internal fun refreshVideoQuad(displayW: Float, displayH: Float, scaleMode: ScaleMode) {
        val screenLandscape = screenW > screenH
        val mediaLandscape = displayW > displayH
        videoExtraRotate = autoRotateMismatch &&
            screenW > 0f && screenH > 0f && displayW > 0f && displayH > 0f &&
            displayW != displayH && mediaLandscape != screenLandscape
        val effW = if (videoExtraRotate) displayH else displayW
        val effH = if (videoExtraRotate) displayW else displayH
        val quad = WallpaperGeometry.applyTransition(
            WallpaperGeometry.computeVideoQuad(effW, effH, screenW, screenH, scaleMode),
            transitionMode,
            transitionProgress
        )
        gl.vertexBuffer?.clear()
        gl.vertexBuffer?.put(quad)?.position(0)
        videoQuadFullscreen = WallpaperGeometry.quadCoversScreen(quad)
        videoQuadHalfW = quad[4]
        videoQuadHalfH = kotlin.math.abs(quad[5])
    }

    /**
     * Live clarity change from Settings: update the sharpening strength and
     * re-present a displayed static image (or GIF frame) right away, so the
     * toggle is visible immediately instead of only after the next switch.
     * Videos pick the new strength up on the next decoded frame.
     */
    fun applyClarity(scale: Float, qualityBoost: Boolean = false, mode: Int = 0) {
        postToRenderThread {
            sharpnessScale = scale
            qualityEnhance = qualityBoost
            enhanceMode = mode.coerceIn(0, 2)
            if (!surfaceReady || !egl.hasSurface) return@postToRenderThread
            // Same texture-side guard as applyScaleMode: re-present from the
            // GPU when the CPU bitmap has been released for memory.
            if (lastRenderWasImage && canRepaintFromTexture()) {
                renderImageFromTexture()
            }
        }
    }

    /**
     * 画质增强 flag without re-rendering: the per-switch path uses it, while a
     * live settings change goes through [applyClarity] (which also re-presents
     * the current still image).
     */
    fun setQualityBoost(enabled: Boolean) {
        qualityEnhance = enabled
    }

    /**
     * 切换放大算法；静态图立刻重绘一次，视频下一帧生效。
     */
    fun setEnhanceMode(mode: Int) {
        enhanceMode = mode.coerceIn(0, 2)
        postToRenderThread {
            if (lastRenderWasImage) renderImageFromTexture()
        }
    }

    /**
     * Re-evaluate the current video after the auto-rotation setting changes
     * (images/GIFs are re-decoded by the engine; videos only need the quad +
     * UV rotation refreshed here).
     */
    fun refreshAfterAutoRotateChange() {
        postToRenderThread {
            if (!surfaceReady || videoTexId == 0) return@postToRenderThread
            refreshVideoQuad(videoDisplayW, videoDisplayH, videoScaleMode)
        }
    }

    /**
     * Re-present the image that is already uploaded with a re-computed quad.
     *
     * Used when the wallpaper surface changed orientation but the decoded
     * bitmap is still big enough for the new size (see the engine's rotation
     * redraw): the texture content is unchanged, so only the 90° decision and
     * the fit have to be re-applied. Re-decoding there cost 150-500ms plus a
     * fresh full-size upload exactly when the user is looking at the screen
     * ("横屏设置动态壁纸卡顿").
     */
    fun refreshImageQuad(rotateCw: Boolean?) {
        postToRenderThread {
            if (!surfaceReady || !lastRenderWasImage) return@postToRenderThread
            if (!canRepaintFromTexture()) return@postToRenderThread
            lastImageRotateCw = rotateCw
            renderImageFromTexture()
        }
    }

    fun surfaceDestroyed() {
        stopVideoInternal()
        postToRenderThread {
            surfaceReady = false
            egl.destroySurface()
        }
    }

    fun release() {
        stopVideoInternal()
        currentVideoUri = null
        currentVideoCachePath = null
        audioMutedForOwnUi = false
        audio.release()
        val handler = renderHandler
        handler?.removeCallbacks(kenBurnsRunnable)
        val thread = renderThread
        if (handler != null && thread != null) {
            handler.post {
                try {
                    surfaceReady = false
                    contextReady = false
                    cleanupAll()
                } catch (t: Throwable) {
                    AppLog.e(TAG, "Release task failed", t)
                }
            }
            // No await: release() runs on the engine's (main) thread during
            // onDestroy, and waiting for the GL driver (up to the old 500ms
            // cap, longer when it hung) was a visible freeze. quitSafely()
            // still delivers the cleanup post above - it is due immediately -
            // before the looper quits.
            thread.quitSafely()
        }
        renderHandler = null
        renderThread = null
    }

    // ======== Image Rendering ========

    /**
     * Show image on render thread. Use this when NO video is playing.
     * If video might be playing, use stopVideoAndRender() instead.
     */
    fun showImage(bitmap: Bitmap, scaleMode: ScaleMode) {
        postToRenderThread {
            if (!surfaceReady || !contextReady) return@postToRenderThread
            renderImage(bitmap, scaleMode, useMipmap = false)
        }
    }

    /**
     * Show a GIF frame with coalescing: at most one GIF render post is queued,
     * so the GIF loop can never pile up behind a busy render thread.
     * The static showImage() is intentionally NOT coalesced - a switch result
     * must always be drawn even if the previous frame is still queued.
     *
     * @return true when the frame was queued. false means it was coalesced away
     *   and the caller still owns the buffer it passed in.
     */
    fun showGifFrame(bitmap: Bitmap, scaleMode: ScaleMode): Boolean {
        lastGifFrameAtMs = SystemClock.elapsedRealtime()
        // false = coalesced away (a render is already queued). The caller must
        // NOT hand this buffer to the next frame in that case: the frame it
        // would overwrite is the one still waiting in the queue.
        if (!imageRenderPostQueued.compareAndSet(false, true)) return false
        postToRenderThread {
            imageRenderPostQueued.set(false)
            if (!surfaceReady || !contextReady) {
                if (!gifSkipLogged) {
                    gifSkipLogged = true
                    AppLog.w(TAG, "GIF frame skipped: surfaceReady=$surfaceReady contextReady=$contextReady")
                }
                return@postToRenderThread
            }
            gifSkipLogged = false
            renderImage(bitmap, scaleMode, useMipmap = false)
        }
        return true
    }

    /**
     * 过渡动画: play the user's chosen transition after a switch.
     *
     * "fade" dims and brightens the new media; "slide" and "zoom" animate its
     * quad; "none" presents the frame as-is. All of them are overlay/geometry
     * effects that work for images, GIFs and videos alike (video frames pick the
     * transform up on their next frame).
     *
     * The animation is driven by the display's frame clock with an ease-out
     * curve (see [TransitionCurve]) rather than by fixed 25ms steps: the old
     * step timer was not aligned to vsync, so identical steps landed on 1 or 2
     * frames each and the motion juddered, and both fade and slide started from
     * a fully black frame.
     */
    fun requestTransition(mode: String) {
        postToRenderThread {
            transitionMode = mode
            startTransition()
        }
    }

    /** Adopt a 过渡动画 change without playing anything (next switch uses it). */
    fun setTransitionMode(mode: String) {
        postToRenderThread {
            transitionMode = mode
            cancelTransition()
            fadeAlpha = 0f
            transitionProgress = 1f
        }
    }

    /**
     * 静态图微动效 (Ken Burns): adopt the setting without restarting anything.
     *
     * Enabling it starts the zoom ticker on the image already on screen;
     * disabling it cancels the ticker and re-presents the settled layout once,
     * so the wallpaper never stays frozen mid-zoom.
     */
    fun setKenBurnsEnabled(enabled: Boolean) {
        postToRenderThread {
            if (kenBurnsEnabled == enabled) return@postToRenderThread
            kenBurnsEnabled = enabled
            if (enabled) {
                kenBurnsStartMs = SystemClock.elapsedRealtime()
                maybeStartKenBurns()
            } else {
                renderHandler?.removeCallbacks(kenBurnsRunnable)
                kenBurnsFramePosted = false
                if (lastRenderWasImage) renderImageFromTexture()
            }
        }
    }

    /**
     * The zoom ticker: ~15fps is plenty for a very slow breathing zoom and
     * keeps a still wallpaper far below the video path's cost. It stops while
     * hidden ([powerSaveMode]) or while a GIF owns the screen, and restarts on
     * the next still frame or visibility resume.
     */
    private val kenBurnsRunnable = object : Runnable {
        override fun run() {
            kenBurnsFramePosted = false
            if (!kenBurnsEnabled || powerSaveMode || !lastRenderWasImage) return
            if (SystemClock.elapsedRealtime() - lastGifFrameAtMs <
                KEN_BURNS_GIF_SUPPRESS_MS
            ) {
                return
            }
            if (!surfaceReady || !egl.hasSurface) return
            renderImageFromTexture()
            if (kenBurnsEnabled && !powerSaveMode && lastRenderWasImage) {
                kenBurnsFramePosted = true
                renderHandler?.postDelayed(this, KEN_BURNS_FRAME_MS)
            }
        }
    }

    private fun maybeStartKenBurns() {
        if (!kenBurnsEnabled || powerSaveMode || !lastRenderWasImage) return
        if (SystemClock.elapsedRealtime() - lastGifFrameAtMs <
            KEN_BURNS_GIF_SUPPRESS_MS
        ) {
            return
        }
        if (kenBurnsFramePosted) return
        kenBurnsFramePosted = true
        renderHandler?.postDelayed(kenBurnsRunnable, KEN_BURNS_FRAME_MS)
    }

    /** Position within the zoom cycle: 0..1, wrapping every [KEN_BURNS_PERIOD_MS]. */
    private fun kenBurnsPhase(nowMs: Long): Float =
        ((nowMs - kenBurnsStartMs).coerceAtLeast(0L) % KEN_BURNS_PERIOD_MS)
            .toFloat() / KEN_BURNS_PERIOD_MS

    /**
     * Render thread: (re)start the transition for [transitionMode], on the frame
     * clock. The first frame is applied immediately so the effect starts with
     * the switch instead of one vsync later.
     */
    private fun startTransition() {
        cancelTransition()
        val fade = transitionMode == SettingsKeys.SWITCH_TRANSITION_FADE
        val geometry = transitionMode == SettingsKeys.SWITCH_TRANSITION_SLIDE ||
            transitionMode == SettingsKeys.SWITCH_TRANSITION_ZOOM
        if (!fade && !geometry) {
            fadeAlpha = 0f
            transitionProgress = 1f
            return
        }
        val generation = ++fadeGeneration
        transitionStartNanos = 0L
        transitionFrameCount = 0
        // Frame 0: dim the media / place the quad at its start position at once.
        applyTransitionProgress(TransitionCurve.easeOutCubic(0f), fade)
        val choreographer = try {
            Choreographer.getInstance()
        } catch (_: Throwable) {
            null
        }
        if (choreographer == null) {
            // No frame clock available (never expected on a Looper thread):
            // settle immediately rather than stranding a half-applied effect.
            finishTransition(fade)
            return
        }
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (generation != fadeGeneration) return
                if (transitionStartNanos == 0L) transitionStartNanos = frameTimeNanos
                val linear = TransitionCurve.progressAt(transitionStartNanos, frameTimeNanos)
                transitionFrameCount++
                applyTransitionProgress(TransitionCurve.easeOutCubic(linear), fade)
                if (linear < 1f) {
                    try {
                        choreographer.postFrameCallback(this)
                    } catch (_: Throwable) {
                        finishTransition(fade)
                    }
                } else {
                    finishTransition(fade)
                }
            }
        }
        transitionCallback = callback
        try {
            choreographer.postFrameCallback(callback)
        } catch (_: Throwable) {
            transitionCallback = null
            finishTransition(fade)
        }
    }

    /** Publish one animation frame (eased progress) and repaint the media. */
    private fun applyTransitionProgress(eased: Float, fade: Boolean) {
        if (fade) {
            fadeAlpha = TransitionCurve.fadeAlpha(eased)
        } else {
            transitionProgress = eased
        }
        repaintForTransition()
    }

    /** Settle on the final state and stop the frame callback. */
    private fun finishTransition(fade: Boolean) {
        if (fade) fadeAlpha = 0f
        transitionProgress = 1f
        repaintForTransition()
        transitionCallback = null
        // One line per transition (not per frame): the frame count vs the
        // elapsed time is what tells a smooth run (e.g. 13 frames / 220ms =
        // 60Hz) from a juddering one, and it lands in the exported log.
        AppLog.d(
            TAG,
            "Transition done: $transitionMode frames=$transitionFrameCount " +
                "duration=${TransitionCurve.DURATION_MS.toInt()}ms"
        )
    }

    /** Stop a running transition without touching the settled state. */
    private fun cancelTransition() {
        fadeGeneration++
        val callback = transitionCallback ?: return
        transitionCallback = null
        try {
            Choreographer.getInstance().removeFrameCallback(callback)
        } catch (_: Throwable) {
        }
    }

    /** Re-present the current media with the current transition transform. */
    private fun repaintForTransition() {
        try {
            if (!surfaceReady || !egl.hasSurface) return
            if (lastRenderWasImage) {
                if (canRepaintFromTexture()) renderImageFromTexture()
            } else if (videoTexId != 0) {
                // Video: recompute the quad; the frame itself arrives from the
                // decoder. If it is throttled (power save), the transform is
                // simply in place for the next presented frame.
                refreshVideoQuad(videoDisplayW, videoDisplayH, videoScaleMode)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "transition repaint failed", t)
            transitionProgress = 1f
            fadeAlpha = 0f
        }
    }

    /**
     * Draw the fade overlay quad (black, alpha = [alpha]) over the current
     * framebuffer. Must be called right before eglSwapBuffers on the render
     * thread; blending is disabled afterwards.
     */
    private fun drawFadeOverlayNoSwap(alpha: Float) {
        if (alpha <= 0f || gl.imageProgram == 0 || gl.blackTexId == 0) return
        if (!surfaceReady || !egl.hasSurface) return
        val bg = gl.backgroundBuffer ?: return
        GLES20.glEnable(GLES20.GL_BLEND)
        try {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(gl.imageProgram)
            GLES20.glUniformMatrix4fv(gl.imageTexMatLoc, 1, false, gl.imageTexMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gl.blackTexId)
            GLES20.glUniform1i(gl.imageTexLoc, 0)
            GLES20.glUniform2f(gl.imageTexelLoc, 1f, 1f)
            GLES20.glUniform1f(gl.imageSharpLoc, 0f)
            GLES20.glUniform1f(gl.imageEnhanceLoc, 0f)
            GLES20.glUniform1f(gl.imageDenoiseLoc, 0f)
            GLES20.glUniform1f(gl.imageAlphaLoc, alpha)
            bg.position(0)
            GLES20.glEnableVertexAttribArray(gl.imagePosLoc)
            GLES20.glVertexAttribPointer(gl.imagePosLoc, 2, GLES20.GL_FLOAT, false, 16, bg)
            bg.position(2)
            GLES20.glEnableVertexAttribArray(gl.imageTcLoc)
            GLES20.glVertexAttribPointer(gl.imageTcLoc, 2, GLES20.GL_FLOAT, false, 16, bg)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } finally {
            // Blending must never be left enabled: a later exception could
            // otherwise make subsequent frames render translucent.
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun renderImage(
        bitmap: Bitmap,
        scaleMode: ScaleMode,
        useMipmap: Boolean,
        rotateCw: Boolean? = null
    ) {
        try {
            if (!surfaceReady || !egl.hasSurface) return
            if (gl.imageProgram == 0 || gl.imageTexId == 0) {
                AppLog.w(TAG, "renderImage skipped: program=$gl.imageProgram tex=$gl.imageTexId")
                return
            }
            // A concurrent switch may recycle the bitmap before this queued
            // render runs; uploading a recycled bitmap would throw.
            if (bitmap.isRecycled) return
            // 降噪分支: analyse a still once per new bitmap (4x 16x16 sample
            // patches); GIF frames pass useMipmap=false and keep the last value.
            if (useMipmap && bitmap !== lastDenoiseBitmap) {
                lastDenoiseBitmap = bitmap
                lastImageDenoise = com.wallpaperswitcher.engine.ImageQuality
                    .denoiseStrength(bitmap)
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gl.imageTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            // Source size of what is now in the texture: recorded BEFORE the
            // first draw so renderImageFromTexture() can rebuild the quad and
            // the texel/upscale uniforms even after the CPU bitmap is released
            // for memory (see dropImageBitmapForMemory). Functional equivalent
            // of the bitmap.width/height used below - the bitmap can no longer
            // change size between those lines.
            imageSrcW = bitmap.width.toFloat()
            imageSrcH = bitmap.height.toFloat()
            // 静态图微动效: a freshly uploaded still starts its zoom cycle at
            // 1x, so the settled layout is presented first.
            kenBurnsStartMs = SystemClock.elapsedRealtime()
            // One quad for both decisions below: where the image lands on screen
            // (drawnW/drawnH, in pixels) decides whether mipmaps are worth a
            // full-texture GPU pass.
            val quad = WallpaperGeometry.computeQuad(
                bitmap.width.toFloat(), bitmap.height.toFloat(), screenW, screenH, scaleMode,
                rotateCw
            )
            val drawnW = kotlin.math.abs(quad[4]) * screenW
            val drawnH = kotlin.math.abs(quad[5]) * screenH
            // 过渡动画 (slide/zoom): the quad grows/slides in during the first
            // ~200ms; the settled state (progress = 1) is the exact layout.
            // Applied AFTER the mipmap decision above, which must see the real
            // on-screen size of the media.
            WallpaperGeometry.applyTransition(quad, transitionMode, transitionProgress)
            if (kenBurnsEnabled) {
                WallpaperGeometry.applyKenBurns(
                    quad,
                    kenBurnsPhase(SystemClock.elapsedRealtime()),
                    KEN_BURNS_AMPLITUDE,
                )
            }
            // Mipmaps only help when the texture is DOWNSCALED on screen (the
            // minification filter is never used when the image is magnified).
            // With the display-aware decode, many images are shown at ~1:1 or
            // upscaled, so skipping glGenerateMipmap saves a full-texture GPU
            // pass on every switch (a real win under rapid switching).
            //
            // The comparison axes follow the quad, not the bitmap: with a 90°
            // turn the on-screen width comes from the bitmap's HEIGHT (see
            // WallpaperGeometry.computeQuad), so a naive
            // `bitmap.width > screenW * k` test skips mipmaps for an image that
            // IS being downscaled.
            val srcW = if (rotateCw != null) bitmap.height.toFloat() else bitmap.width.toFloat()
            val srcH = if (rotateCw != null) bitmap.width.toFloat() else bitmap.height.toFloat()
            val doMipmap = useMipmap &&
                (srcW > drawnW * MIPMAP_MIN_DOWNSCALE || srcH > drawnH * MIPMAP_MIN_DOWNSCALE)
            if (doMipmap) {
                // Trilinear mipmapping removes aliasing/shimmer when a large
                // image is downscaled (FIT mode). Not used for GIF frames,
                // where regenerating mipmaps every frame would cost power.
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR_MIPMAP_LINEAR
                )
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
            } else {
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR
                )
            }
            gl.vertexBuffer?.clear()
            gl.vertexBuffer?.put(quad)?.position(0)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // The black backing quad is only needed when the media quad leaves
            // letterbox areas uncovered (FIT). FILL/STRETCH and fullscreen
            // media overwrite every framebuffer pixel with an opaque quad, so
            // skipping this pass saves one program switch + texture bind +
            // full-screen draw per presented frame (e.g. 30x/sec on video).
            if (!WallpaperGeometry.quadCoversScreen(quad)) drawBlackBackground()
            GLES20.glUseProgram(gl.imageProgram)
            val texMatLoc = gl.imageTexMatLoc
            val texLoc = gl.imageTexLoc
            val posLoc = gl.imagePosLoc
            val tcLoc = gl.imageTcLoc

            GLES20.glUniformMatrix4fv(texMatLoc, 1, false, gl.imageTexMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gl.imageTexId)
            GLES20.glUniform1i(texLoc, 0)
            updateImageScreenTexelDelta(quad)
            GLES20.glUniform2f(gl.imageTexelLoc, imageTexelX, imageTexelY)
            GLES20.glUniform1f(
                gl.imageSharpLoc,
                sharpnessFor(bitmap.width.toFloat(), bitmap.height.toFloat(), scaleMode)
            )
            GLES20.glUniform1f(
                gl.imageEnhanceLoc,
                WallpaperGeometry.enhancementStrength(
                    bitmap.width.toFloat(), bitmap.height.toFloat(),
                    screenW, screenH, scaleMode, qualityEnhance,
                ),
            )
            GLES20.glUniform2f(
                gl.imageSrcTexelLoc,
                1f / bitmap.width.coerceAtLeast(1),
                1f / bitmap.height.coerceAtLeast(1),
            )
            GLES20.glUniform1f(gl.imageDenoiseLoc, lastImageDenoise)
            GLES20.glUniform1f(gl.imageEnhanceModeLoc, enhanceMode.toFloat())
            GLES20.glUniform2f(
                gl.imageEasuScaleLoc,
                bitmap.width.toFloat() / drawnW.coerceAtLeast(1f),
                bitmap.height.toFloat() / drawnH.coerceAtLeast(1f),
            )
            GLES20.glUniform1f(gl.imageAlphaLoc, 1f)

            gl.vertexBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, gl.vertexBuffer)
            gl.vertexBuffer?.position(2)
            GLES20.glEnableVertexAttribArray(tcLoc)
            GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, gl.vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (fadeAlpha > 0f) drawFadeOverlayNoSwap(fadeAlpha)
            val swapped = egl.swapBuffers()
            if (!swapped) {
                AppLog.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
            }
            // Remember the last presented image so the fade steps can force
            // redraws of static images with the decaying overlay.
            lastImageBitmap = bitmap
            // The texture now holds this media: a later surface recreation can
            // re-present it from the GPU alone (see canRepaintFromTexture).
            // Set only after the frame was really swapped in, so a failed
            // upload never claims to be re-presentable.
            imageTextured = true
            lastImageScaleMode = scaleMode
            lastImageRotateCw = rotateCw
            lastRenderWasImage = true
            maybeStartKenBurns()
        } catch (t: Throwable) {
            AppLog.e(TAG, "renderImage failed", t)
        }
    }

    /**
     * True when the still image on screen can be drawn again from the GPU
     * texture WITHOUT the CPU bitmap - i.e. the pixels are in [gl]'s image
     * texture and the source size needed to rebuild the quad and the shader
     * uniforms is cached ([imageSrcW]/[imageSrcH]).
     *
     * This is the "GL 纹理还在 + CPU 位图已释放" state the engine's
     * low-memory release relies on: a rotation / surface recreation / Ken
     * Burns / transition step keeps redrawing the wallpaper instead of going
     * black. Safer than the previous `bitmap != null && !bitmap.isRecycled`
     * guard because a bitmap that the engine recycled no longer makes a
     * redraw impossible.
     */
    private fun canRepaintFromTexture(): Boolean =
        imageTextured && gl.imageTexId != 0 && imageSrcW > 0f && imageSrcH > 0f

    /**
     * Drop the CPU bitmap reference behind the image on screen while KEEPING
     * the GL texture, so the wallpaper can still be re-presented (rotation,
     * surface recreation, 静态图微动效, transition step - see
     * [canRepaintFromTexture]).
     *
     * Ownership / threading:
     * - CALLER (the engine's main thread, from `trimMemoryNow`) still owns the
     *   Bitmap object: this only forgets the renderer's references, so the
     *   engine decides when the pixels really go away (it can recycle them or
     *   just drop its own reference and let the GC do it).
     * - The texture is NOT touched: `gl.imageTexId` survives surface
     *   recreation because the EGL context is created once per renderer
     *   ([EglSurfaceHolder.setupContext]) and `imageTexId` is only deleted by
     *   `gl.cleanup()` during [release]. A surface recreation therefore keeps
     *   the pixels; the redraw path reads them from the texture.
     * - Safe with an upload in flight: the render task holds its OWN reference
     *   to the bitmap, so a drop queued after it cannot pull the bitmap out
     *   from under `GLUtils.texImage2D`.
     * - [lastImageBitmap] is intentionally NOT nulled: it stays as the cheap
     *   "an image was uploaded" marker (see the field doc); only the
     *   [lastDenoiseBitmap] reference, which does keep the bitmap object
     *   alive, is dropped.
     */
    fun dropImageBitmapForMemory() {
        postToRenderThread {
            lastDenoiseBitmap = null
            AppLog.d(
                TAG,
                "Image CPU bitmap released for memory (texture kept: " +
                    "${imageSrcW.toInt()}x${imageSrcH.toInt()} repaintable=${canRepaintFromTexture()})"
            )
        }
    }

    /**
     * Re-present the image currently uploaded to [gl.imageTexId] WITHOUT
     * re-uploading the bitmap (the fade-overlay steps). The texture content is
     * unchanged between fade steps, so the previous code re-uploaded a full
     * screen-size bitmap and regenerated mipmaps on every step for no visual
     * difference. Runs on the render thread; the texture is guaranteed to be
     * this bitmap's because fade steps are queued behind the switch's render.
     *
     * Needs no CPU bitmap: the quad and the sharpening/enhancement uniforms
     * are computed from the cached source size ([imageSrcW]/[imageSrcH]), and
     * the pixels come from the texture. Keeping that true is what makes the
     * engine's low-memory bitmap release safe.
     */
    private fun renderImageFromTexture() {
        try {
            if (!surfaceReady || !egl.hasSurface) return
            if (gl.imageProgram == 0 || gl.imageTexId == 0) return
            if (!canRepaintFromTexture()) return
            val quad = WallpaperGeometry.computeQuad(
                imageSrcW, imageSrcH, screenW, screenH, lastImageScaleMode,
                lastImageRotateCw
            )
            val drawnW = kotlin.math.abs(quad[4]) * screenW
            val drawnH = kotlin.math.abs(quad[5]) * screenH
            WallpaperGeometry.applyTransition(quad, transitionMode, transitionProgress)
            if (kenBurnsEnabled) {
                WallpaperGeometry.applyKenBurns(
                    quad,
                    kenBurnsPhase(SystemClock.elapsedRealtime()),
                    KEN_BURNS_AMPLITUDE,
                )
            }
            gl.vertexBuffer?.clear()
            gl.vertexBuffer?.put(quad)?.position(0)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!WallpaperGeometry.quadCoversScreen(quad)) drawBlackBackground()
            GLES20.glUseProgram(gl.imageProgram)

            GLES20.glUniformMatrix4fv(gl.imageTexMatLoc, 1, false, gl.imageTexMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gl.imageTexId)
            GLES20.glUniform1i(gl.imageTexLoc, 0)
            updateImageScreenTexelDelta(quad)
            GLES20.glUniform2f(gl.imageTexelLoc, imageTexelX, imageTexelY)
            GLES20.glUniform1f(
                gl.imageSharpLoc,
                sharpnessFor(imageSrcW, imageSrcH, lastImageScaleMode)
            )
            GLES20.glUniform1f(
                gl.imageEnhanceLoc,
                WallpaperGeometry.enhancementStrength(
                    imageSrcW, imageSrcH,
                    screenW, screenH, lastImageScaleMode, qualityEnhance,
                ),
            )
            GLES20.glUniform2f(
                gl.imageSrcTexelLoc,
                1f / imageSrcW.coerceAtLeast(1f),
                1f / imageSrcH.coerceAtLeast(1f),
            )
            GLES20.glUniform1f(gl.imageDenoiseLoc, lastImageDenoise)
            GLES20.glUniform1f(gl.imageEnhanceModeLoc, enhanceMode.toFloat())
            GLES20.glUniform2f(
                gl.imageEasuScaleLoc,
                imageSrcW / drawnW.coerceAtLeast(1f),
                imageSrcH / drawnH.coerceAtLeast(1f),
            )
            GLES20.glUniform1f(gl.imageAlphaLoc, 1f)

            gl.vertexBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(gl.imagePosLoc)
            GLES20.glVertexAttribPointer(gl.imagePosLoc, 2, GLES20.GL_FLOAT, false, 16, gl.vertexBuffer)
            gl.vertexBuffer?.position(2)
            GLES20.glEnableVertexAttribArray(gl.imageTcLoc)
            GLES20.glVertexAttribPointer(gl.imageTcLoc, 2, GLES20.GL_FLOAT, false, 16, gl.vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (fadeAlpha > 0f) drawFadeOverlayNoSwap(fadeAlpha)
            egl.swapBuffers()
        } catch (t: Throwable) {
            AppLog.e(TAG, "renderImageFromTexture failed", t)
        }
    }

    /**
     * Strength for the screen-space unsharp mask. The kernel samples neighbors
     * ~1 SCREEN pixel away (see [imageScreenTexelDelta]), so it works on
     * native, upscaled AND downscaled media — which is what makes the clarity
     * option perceptible instead of only firing on rare magnified sources.
     * Returns 0 for the "off" mode (sharpnessScale == 0), which reproduces the
     * original sampling exactly.
     */
    private fun sharpnessFor(sourceW: Float, sourceH: Float, scaleMode: ScaleMode): Float {
        if (sourceW <= 0f || sourceH <= 0f || screenW <= 0f || screenH <= 0f) return 0f
        val scaleX = screenW / sourceW
        val scaleY = screenH / sourceH
        val upscale = when (scaleMode) {
            ScaleMode.FIT -> minOf(scaleX, scaleY)
            ScaleMode.FILL, ScaleMode.STRETCH -> maxOf(scaleX, scaleY)
        }
        val strength = sharpnessScale.coerceIn(0f, 2f)
        if (strength <= 0f) return 0f
        // Gentle always-on base (visible on every wallpaper) plus a boost when
        // a low-res source is magnified to fill the screen.
        val magnifyBoost = if (upscale > 1f) minOf(upscale - 1f, 3f) * 0.10f else 0f
        return strength * (0.13f + magnifyBoost)
    }

    /**
     * UV step that equals ~1 screen pixel for a quad whose half-extents are
     * [halfW]/[halfH] (the texture footprint is halfW*screenW pixels wide).
     * Used by the screen-space sharpening kernel.
     *
     * Results land in [imageTexelX]/[imageTexelY] instead of a Pair: the image is
     * drawn on every fade frame too, and returning a Pair allocated one small
     * object per draw for no reason.
     */
    private fun updateImageScreenTexelDelta(quad: FloatArray) {
        val w = (quad[4] * screenW).coerceAtLeast(1f)
        val h = (kotlin.math.abs(quad[5]) * screenH).coerceAtLeast(1f)
        imageTexelX = 1f / w
        imageTexelY = 1f / h
    }

    /** Video counterpart of [updateImageScreenTexelDelta] (once per frame). */
    private fun updateVideoScreenTexelDelta() {
        val w = (videoQuadHalfW * screenW).coerceAtLeast(1f)
        val h = (videoQuadHalfH * screenH).coerceAtLeast(1f)
        videoTexelX = 1f / w
        videoTexelY = 1f / h
    }

    // ======== Video: MediaCodec + SurfaceTexture ========

    /**
     * Start video playback.
     *
     * Flow:
     * 1. Stop any existing video (generation flag + post cleanup to render thread)
     * 2. WAIT for old decode thread to finish (prevents resource conflicts)
     * 3. Start decode thread which:
     *    a. Sets up MediaExtractor
     *    b. Posts GL texture + SurfaceTexture creation to render thread (needs EGL context)
     *    c. Creates MediaCodec on decode thread
     *    d. Runs decode loop
     */
    /**
     * @param startPositionUs where playback should begin (µs, 0 = the file's
     *   start). Non-zero is only passed by the lock-release resume path.
     */
    fun startVideo(uriStr: String, scaleMode: ScaleMode, startPositionUs: Long = 0L) {
        // A fresh start (or one at the very beginning) must not inherit the
        // previous clip's position.
        pendingStartPositionUs = startPositionUs.coerceAtLeast(0L)
        if (pendingStartPositionUs == 0L) lastVideoPositionUs = 0L
        videoFirstFramePending.set(true)
        // A stale cache path from the previous video would make the audio thread
        // open the wrong file.
        currentVideoCachePath = null
        // A fresh video starts its own soundtrack: any "muted because our own UI
        // opened" state belonged to the previous clip.
        audioMutedForOwnUi = false
        // First stop any existing video
        stopVideoInternal()

        // Give the old decode thread a short grace period to exit. It is NOT
        // mandatory to wait: the generation guard in decodeLoop's finally block
        // ensures a late-exiting old thread can never clean up the new video's
        // resources. Waiting too long here just makes the switch appear as a
        // long black screen when the old thread is stuck in blocking I/O.
        val oldThread = videoDecodeThread
        if (oldThread != null && oldThread.isAlive) {
            // Halved from 500ms: a stuck old thread (blocking cloud I/O) would
            // otherwise freeze the switch as a long black screen. The
            // generation guard already makes late cleanup harmless.
            try { oldThread.join(250) } catch (_: InterruptedException) {}
        }
        videoDecodeThread = null

        // Reset cleanup flag for the new video
        videoCleanupDone.set(false)
        val gen = videoGeneration.incrementAndGet()
        isVideoPlaying = true
        // Playback passes are counted per video: the audio thread waits for the
        // FIRST pass of this video before it makes any sound. Without the reset
        // it saw the previous video's (higher) counter, started playing into a
        // picture that was still setting up, and then had to restart as soon as
        // the real first pass arrived - an audible stutter right after a switch.
        audio.resetPassCounter()

        val handler = renderHandler ?: run { isVideoPlaying = false; return }

        videoDecodeThread = Thread({
            // The decode thread shares the process with the UI. Background
            // priority keeps it from stealing CPU from scrolling/composition
            // on low-end devices; when the CPU is idle it still decodes at
            // full speed.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            decodeLoop(uriStr, scaleMode, gen, handler)
        }, "VideoDecode").apply {
            // Daemon like every other helper thread here: a decode stuck in
            // un-interruptible cloud/SAF I/O must never outlive the process's
            // usefulness (release() can only interrupt(), not unblock it).
            isDaemon = true
            start()
        }

        // Audio: same generation guard as the video, started only when the user
        // asked for sound. It waits for the first playback pass of THIS video
        // (see VideoAudioPlayer.onVideoPassStart), so a video that never starts
        // stays silent.
        currentVideoUri = uriStr
        // Not while our own UI is open: a media switch triggered from inside the
        // app must not start playing audio behind it (see muteAudioKeepingVideo).
        if (videoSoundEnabled && !audioMutedForOwnUi) {
            audio.start(uriStr, gen, startPositionUs.coerceAtLeast(0L))
        }
        // The watchdog covers the window where this start was skipped because
        // of the UI mute and the unmute later failed to restart the thread.
        scheduleAudioWatchdog()
    }

    /**
     * Stop video — non-blocking. Posts cleanup to render thread.
     * Use this when switching to nothing (e.g., visibility lost).
     */
    fun stopVideo() {
        stopVideoInternal()
    }

    /**
     * Wait for the decode thread to exit. Call after stopVideo() when you need
     * the decode thread's cleanup to complete before the next operation.
     */
    fun waitForDecodeThread(timeoutMs: Long) {
        val thread = videoDecodeThread ?: return
        if (thread.isAlive) {
            try { thread.join(timeoutMs) } catch (_: InterruptedException) {}
        }
    }

    /**
     * Stop video AND atomically render an image — the key to smooth transitions.
     *
     * Cleanup and image rendering happen in a SINGLE render-handler post:
     * render thread: [release video resources] → [draw image] → [swap buffers]
     * No gap, no flash, no stutter.
     *
     * Call from ANY thread (typically IO coroutine thread).
     */
    fun stopVideoAndRender(bitmap: Bitmap, scaleMode: ScaleMode, rotateCw: Boolean? = null) {
        videoGeneration.incrementAndGet()
        isVideoPlaying = false
        // The video is no longer the displayed media: forget which clip the
        // session belonged to, so a later rotation redraw of an image/GIF can
        // never mistake the old clip for "the one that is on screen" and
        // resume it at a stale position (see isCurrentVideo()).
        currentVideoUri = null
        videoCleanupDone.set(true)
        audio.stop()

        // Interrupt decode thread (don't null — caller may need to join)
        videoDecodeThread?.interrupt()

        // Atomic: cleanup + render on same handler post
        postToRenderThread {
            cleanupVideoResourcesOnRenderThread()
            if (surfaceReady && contextReady) {
                renderImage(bitmap, scaleMode, useMipmap = true, rotateCw = rotateCw)
            }
        }
    }

    /**
     * Internal stop: interrupt decode thread, post cleanup to render thread.
     * Does NOT block waiting for decode thread.
     * Sets cleanupDone so the decode loop's finally block won't duplicate cleanup.
     * Does NOT null videoDecodeThread — callers may need to join() on it.
     * startVideo() nulls it after join().
     */
    private fun stopVideoInternal() {
        videoGeneration.incrementAndGet()
        isVideoPlaying = false
        videoCleanupDone.set(true)
        audio.stop()

        videoDecodeThread?.interrupt()

        postToRenderThread {
            cleanupVideoResourcesOnRenderThread()
        }
    }

    // ---------------------------------------------------------------------
    // Video audio (optional, see videoSoundEnabled / SettingsKeys.VIDEO_SOUND_ENABLED)
    // ---------------------------------------------------------------------

    /**
     * Turn the video's audio on or off, live.
     *
     * Enabling starts the sound for the video that is playing right now (it joins
     * the current playback pass); disabling stops it immediately. Nothing else
     * about playback changes - the video keeps its own clock either way.
     */
    fun applyVideoSound(enabled: Boolean) {
        if (videoSoundEnabled == enabled) return
        videoSoundEnabled = enabled
        if (!enabled) {
            audio.stop()
            AppLog.d(TAG, "Video sound OFF")
            return
        }
        val uri = currentVideoUri
        if (uri != null && !audioMutedForOwnUi) {
            // Start the sound where the picture IS, not at 0: switching the
            // setting on mid-playback used to play the audio from the file's
            // start while the picture stayed at its position (the two only met
            // again at the next loop boundary, which can be minutes away for a
            // long clip).
            //
            // 不要求 isVideoPlaying 已为 true：视频刚起播/刚从 power-save 恢复时
            // 它可能还是旧值，直接落进 "no video playing" 分支会让声音永远不来
            // （音频线程从没被启动）。线程自己会等第一轮 pass。
            audio.start(uri, videoGeneration.get(), lastVideoPositionUs)
            AppLog.d(TAG, "Video sound ON (from ${lastVideoPositionUs / 1000}ms)")
            scheduleAudioWatchdog()
        } else {
            if (audioMutedForOwnUi) {
                // Enabled from inside the app while our UI is open: remember it
                // (the flag is on) and stay silent until the UI is gone.
                AppLog.d(TAG, "Video sound ON (kept silent: our UI is in front)")
            } else {
            // Enabled while an image/GIF is showing: the next video starts with
            // sound (startVideo checks this flag).
            AppLog.d(TAG, "Video sound ON (no video playing right now)")
            }
        }
    }

    /**
     * Silence the video's audio immediately while the PICTURE keeps playing.
     *
     * Used when our own UI comes to the foreground: the sound has to stop with
     * the tap (user requirement), but pausing the decode at that instant froze
     * the video in the middle of the app-open animation - visible only for our
     * own app, because other apps reach us through the system's "covered" report
     * ~1.1s later, when the wallpaper is already hidden
     * (see LiveWallpaperService.APP_ENTRY_PAUSE_GRACE_MS).
     *
     * [unmuteAudioReanchored] puts the sound back - joined to the frame that is
     * on screen by then, so the muted interval is skipped instead of playing late.
     */
    fun muteAudioKeepingVideo() {
        // No `isVideoPlaying` gate: a video that is (re)starting right now - a
        // switch, a decoder rebuild after a failure, or a lagging
        // `isVideoPlaying` flag - still must not be audible behind our UI. The
        // old guard skipped the mute in exactly those windows, and because the
        // flag below stayed false nothing ever retried it: the sound kept
        // playing until the (deferred) power-save pause, or came back with the
        // next media.
        if (!videoSoundEnabled) return
        val firstTime = !audioMutedForOwnUi
        audioMutedForOwnUi = true
        // Sticky at the session level too: the audio thread's own
        // ensurePlaying()/resume()/restart() calls (per playback pass, on a
        // decoder format change, on the first visible frame) used to be able to
        // turn the sound back on while our UI was in front.
        audio.muteForPolicy()
        audio.stop()
        if (firstTime) {
            AppLog.d(TAG, "Audio muted (own UI opening; video keeps playing)")
        } else {
            // Re-asserted (a video/audio restart slipped through): worth a line,
            // it is the difference between "muted" and "audible behind the UI".
            AppLog.d(TAG, "Audio mute re-asserted while the UI is open")
        }
    }

    /**
     * Undo [muteAudioKeepingVideo]. The audio starts at the picture's CURRENT
     * position: while it was muted the video kept advancing, so continuing the
     * audio where it stopped would leave the sound behind the picture by the
     * whole muted interval.
     */
    fun unmuteAudioReanchored() {
        // 无条件解除会话级静音：系统选择器（设为动态壁纸）流程里引擎/渲染器会被
        // 重建，renderer 的 flag 与 AudioSession 里的 policyMuted 可能不同步。
        // 会话静音一旦残留，音频线程会在“轨道不消费数据”的循环里无声空转 ——
        // 日志里只有 resumed、没有 started —— 要等看门狗才会被救回，用户看到
        // 的就是“过一会才有声音”。这里先清掉，空转的线程下一轮 ensurePlaying
        // 就会真正播放。
        audio.clearPolicyMute()
        if (!audioMutedForOwnUi) return
        audioMutedForOwnUi = false
        if (!videoSoundEnabled) return
        // 不要在这里判断 powerSaveMode / isVideoPlaying：两者都由别的线程异步
        // 应用，此刻可能仍是旧值（刚起播、刚切换、刚从 power-save 恢复）。
        // 直接 return 会留下一个永远不再启动音频线程的静音状态（“设为动态壁纸
        // 后回到桌面没声音，点一下切张图才有声音”）。音频线程自己会等可见、
        // 等第一轮 pass（awaitNextVideoPass），提前启动不会先出声。
        val uri = currentVideoUri ?: return
        // 已经在出声（例如上面的 clearPolicyMute 刚把空转线程救活）：不重启。
        if (audio.isPlaying()) return
        audio.start(uri, videoGeneration.get(), lastVideoPositionUs)
        AppLog.d(TAG, "Audio unmuted, re-anchored at ${lastVideoPositionUs / 1000}ms")
        scheduleAudioWatchdog()
    }

    // ------------------------------------------------------------------
    // 音频看门狗: the "silent until the next switch" states all end with the
    // same fingerprint - a visible video, sound enabled, no policy mute, yet
    // nothing plays (the audio thread was never started because startVideo
    // ran while muted, the session-level mute got out of sync with the
    // renderer flag, or a track transition got wedged). This watchdog notices
    // that and restarts the audio pipeline, so the sound recovers on its own
    // instead of waiting for the user to switch media.
    // ------------------------------------------------------------------

    @Volatile
    private var audioWatchdogPosted = false
    private var audioSilentSince = 0L
    private var audioWatchdogRestarts = 0

    private val audioWatchdogRunnable = object : Runnable {
        override fun run() {
            audioWatchdogPosted = false
            evaluateAudioWatchdog()
        }
    }

    private fun scheduleAudioWatchdog() {
        if (audioWatchdogPosted) return
        val h = renderHandler ?: return
        audioWatchdogPosted = true
        h.postDelayed(audioWatchdogRunnable, AUDIO_WATCHDOG_INTERVAL_MS)
    }

    private fun evaluateAudioWatchdog() {
        val uri = currentVideoUri
        val shouldPlay = uri != null && videoSoundEnabled && !audioMutedForOwnUi &&
            !powerSaveMode && isVideoPlaying
        if (!shouldPlay) {
            audioSilentSince = 0L
            audioWatchdogRestarts = 0
            return
        }
        val videoUri = uri ?: return
        if (audio.hasNoAudioTrack(videoGeneration.get())) {
            // Silence is the correct output for this clip: nothing to recover.
            audioSilentSince = 0L
            audioWatchdogRestarts = 0
            return
        }
        if (audio.isPlaying()) {
            audioSilentSince = 0L
            audioWatchdogRestarts = 0
            scheduleAudioWatchdog()
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (audioSilentSince == 0L) {
            audioSilentSince = now
            scheduleAudioWatchdog()
            return
        }
        if (now - audioSilentSince < AUDIO_SILENT_GRACE_MS) {
            scheduleAudioWatchdog()
            return
        }
        // Bounded retries per silent episode: a video whose audio really cannot
        // be decoded (or a confirmed dead output) must not be restarted forever.
        if (audioWatchdogRestarts >= 3) return
        audioWatchdogRestarts++
        AppLog.w(
            TAG,
            "Audio watchdog: video visible but silent for " +
                "${(now - audioSilentSince) / 1000}s (attempt=$audioWatchdogRestarts); restarting audio"
        )
        // The session-level mute can be out of sync with our own flag (that is
        // one of the states this watchdog exists for) - lift it explicitly.
        if (audioWatchdogRestarts >= 2) {
            // A wedged AudioTrack must be recreated, not reused: release() drops
            // it (and the pending PCM) so the fresh thread builds a new one.
            // First attempt keeps the track - a soft restart is enough for the
            // "thread was never started / mute out of sync" states.
            audio.release()
        } else {
            audio.clearPolicyMute()
        }
        audio.stop()
        audio.start(videoUri, videoGeneration.get(), lastVideoPositionUs)
        audioSilentSince = now
        scheduleAudioWatchdog()
    }


    /**
     * Decode loop — runs on dedicated decode thread.
     * MediaExtractor + MediaCodec I/O only. No GL operations here.
     */

    /**
     * Draw an opaque black quad covering the whole framebuffer. Called on the
     * render thread right before the media quad. Unlike glClear, this draws
     * real pixels into every region of the surface, so no stale content from a
     * previous video can survive in the FIT letterbox area.
     */
    private fun drawBlackBackground() {
        try {
            val bg = gl.backgroundBuffer ?: return
            if (gl.imageProgram == 0 || gl.blackTexId == 0) return
            if (!surfaceReady || !egl.hasSurface) return
            GLES20.glUseProgram(gl.imageProgram)
            val texMatLoc = gl.imageTexMatLoc
            val texLoc = gl.imageTexLoc
            val posLoc = gl.imagePosLoc
            val tcLoc = gl.imageTcLoc
            GLES20.glUniformMatrix4fv(texMatLoc, 1, false, gl.imageTexMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gl.blackTexId)
            GLES20.glUniform1i(texLoc, 0)
            // Flat black must never be sharpened (uSharp=0 is identity).
            GLES20.glUniform1f(gl.imageSharpLoc, 0f)
            GLES20.glUniform1f(gl.imageEnhanceLoc, 0f)
            GLES20.glUniform1f(gl.imageDenoiseLoc, 0f)
            GLES20.glUniform1f(gl.imageAlphaLoc, 1f)

            bg.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, bg)
            bg.position(2)
            GLES20.glEnableVertexAttribArray(tcLoc)
            GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, bg)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (t: Throwable) {
            AppLog.e(TAG, "drawBlackBackground failed", t)
        }
    }

    /**
     * Render a video frame. Called on render thread.
     * No glClear — the full-screen quad overwrites the entire framebuffer.
     * This prevents black flash during transitions.
     */
    internal fun renderVideoFrame(texMatrix: FloatArray) {
        try {
            if (!surfaceReady || !egl.hasSurface) return
            val effectiveMatrix = if (videoExtraRotate) {
                // Reused buffer: this used to allocate a FloatArray per frame.
                val rotated = rotatedTexMatrix
                android.opengl.Matrix.multiplyMM(
                    rotated, 0,
                    if (autoRotateClockwise) EXTRA_ROTATE_90_CW_MATRIX else EXTRA_ROTATE_90_CCW_MATRIX,
                    0, texMatrix, 0
                )
                rotated
            } else {
                texMatrix
            }
            val now = SystemClock.elapsedRealtime()
            if (gl.videoProgram == 0 || videoTexId == 0) {
                // Throttled: this runs once per frame, and an unthrottled line
                // here (plus AppLog's per-line flush) would turn a rendering
                // fault into a disk-writing storm at 30-60 lines/second.
                if (now - lastFrameWarnAt > FRAME_WARN_INTERVAL_MS) {
                    lastFrameWarnAt = now
                    AppLog.w(TAG, "renderVideoFrame skipped: program=$gl.videoProgram tex=$videoTexId")
                }
                return
            }

            if (now - lastVideoFrameSwappedAt < minVideoSwapGapMs) return
            lastVideoFrameSwappedAt = now
            if (renderFpsWindowStart == 0L) renderFpsWindowStart = now
            renderFpsCount++
            if (now - renderFpsWindowStart >= 60_000L) {
                val elapsed = (now - renderFpsWindowStart).coerceAtLeast(1L)
                val fps = renderFpsCount * 1000f / elapsed
                AppLog.d(TAG, "Video render rate: %.1f fps over %ds".format(fps, elapsed / 1000))
                renderFpsWindowStart = now
                renderFpsCount = 0
            }
            if (now - lastRenderLogAt > 5000L) {
                lastRenderLogAt = now
                AppLog.d(TAG, "Video frame rendered: tex=$videoTexId screen=${screenW.toInt()}x${screenH.toInt()} last=$lastVideoFrameAt")
            }
            // Screen-off power save: the texture was already updated above, so
            // playback state keeps advancing; just skip the invisible draw +
            // buffer swap (the biggest GPU cost). lastVideoFrameAt stays fresh
            // so the health monitor never mistakes throttled playback for a
            // stall, and the next screen-on frame presents immediately.
            if (powerSaveMode) {
                lastVideoFrameAt = now
                return
            }
            // Only wipe the framebuffer when the video quad leaves part of the
            // window uncovered (FIT letterbox): there the previous frame would
            // otherwise stay visible ("the old video stays on screen after
            // switching"). When the quad covers every pixel (FILL/STRETCH and
            // fullscreen FIT) the clear is pure extra fill rate - it writes the
            // whole 1440x3200 buffer just to have the opaque quad overwrite it
            // again, which is a measurable share of the per-frame GPU cost for
            // 60fps sources.
            if (!videoQuadFullscreen) GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // Belt-and-suspenders for the letterbox area: some devices/drivers
            // do not fully invalidate preserved window buffers on glClear alone,
            // so draw real black pixels there as well. Only for the letterbox
            // case: FILL/STRETCH (and a fullscreen FIT) already overwrite every
            // pixel, so this would be a wasted pass per video frame.
            if (!videoQuadFullscreen) drawBlackBackground()
            GLES20.glUseProgram(gl.videoProgram)

            val texMatLoc = gl.videoTexMatLoc
            val texLoc = gl.videoTexLoc
            val posLoc = gl.videoPosLoc
            val tcLoc = gl.videoTcLoc

            GLES20.glUniformMatrix4fv(texMatLoc, 1, false, effectiveMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
            GLES20.glUniform1i(texLoc, 0)
            updateVideoScreenTexelDelta()
            GLES20.glUniform2f(gl.videoTexelLoc, videoTexelX, videoTexelY)
            GLES20.glUniform1f(gl.videoSharpLoc, sharpnessFor(videoDisplayW, videoDisplayH, videoScaleMode))
            // The gate must use the DECODED texture size: a 4K clip is decoded
            // down to ~screen pixels, and comparing its original size made the
            // strength ~0 even though the texture IS being magnified.
            val enhancedSourceW = if (videoSrcW > 0f) videoSrcW else videoDisplayW
            val enhancedSourceH = if (videoSrcH > 0f) videoSrcH else videoDisplayH
            val videoEnhance = WallpaperGeometry.enhancementStrength(
                enhancedSourceW, enhancedSourceH, screenW, screenH,
                videoScaleMode, qualityEnhance,
            )
            GLES20.glUniform1f(gl.videoEnhanceLoc, videoEnhance)
            // 视频不做逐帧检测：按放大倍数给一个固定的小降噪强度（高倍率下再
            // 大会把细节磨掉，反而不像"增强"）。
            GLES20.glUniform1f(gl.videoDenoiseLoc, 0.22f * videoEnhance)
            val videoDrawnW = (videoQuadHalfW * screenW).coerceAtLeast(1f)
            val videoDrawnH =
                (kotlin.math.abs(videoQuadHalfH) * screenH).coerceAtLeast(1f)
            GLES20.glUniform1f(gl.videoEnhanceModeLoc, enhanceMode.toFloat())
            GLES20.glUniform2f(
                gl.videoEasuScaleLoc,
                if (videoSrcW > 0f) videoSrcW / videoDrawnW else 0f,
                if (videoSrcH > 0f) videoSrcH / videoDrawnH else 0f,
            )
            GLES20.glUniform2f(
                gl.videoSrcTexelLoc,
                if (videoSrcW > 0f) 1f / videoSrcW else 0f,
                if (videoSrcH > 0f) 1f / videoSrcH else 0f,
            )

            gl.vertexBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, gl.vertexBuffer)
            gl.vertexBuffer?.position(2)
            GLES20.glEnableVertexAttribArray(tcLoc)
            GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, gl.vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (fadeAlpha > 0f) drawFadeOverlayNoSwap(fadeAlpha)
            val swapped = egl.swapBuffers()
            if (swapped) {
                lastVideoFrameAt = now
                // First real frame of this video on screen: let the engine start
                // the fade now (see onFirstVideoFrame).
                if (videoFirstFramePending.compareAndSet(true, false)) {
                    try {
                        onFirstVideoFrame?.invoke()
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "onFirstVideoFrame callback failed", t)
                    }
                }
            } else {
                // Per frame on a broken surface: throttle it (see above).
                if (now - lastFrameWarnAt > FRAME_WARN_INTERVAL_MS) {
                    lastFrameWarnAt = now
                    AppLog.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
                }
            }
            lastRenderWasImage = false
        } catch (t: Throwable) {
            AppLog.e(TAG, "renderVideoFrame failed", t)
        }
    }

    /**
     * Clean up video GL resources. MUST be called on render thread (EGL context required).
     *
     * Order matters:
     * 1. Release SurfaceTexture first (it holds a reference to the GL texture)
     * 2. Release codecSurface (backed by SurfaceTexture)
     * 3. Delete GL texture (SurfaceTexture no longer references it)
     *
     * Does NOT clear the screen — the last rendered frame stays visible.
     * The caller (stopVideoAndRender) will immediately draw the new image.
     */
    internal fun cleanupVideoResourcesOnRenderThread() {
        logPassFrameRate()
        videoQuadFullscreen = false
        videoSrcW = 0f
        videoSrcH = 0f
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        try { codecSurface?.release() } catch (_: Exception) {}
        codecSurface = null
        if (videoTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(videoTexId), 0)
            videoTexId = 0
        }
    }

    // EGL context/surface lifecycle lives in [EglSurfaceHolder].
    // ======== GL Resources ========

    private fun setupGlResources() {
        gl.setup()
        // A fresh EGL context means fresh GL object names: `gl.setup()` above
        // just generated a new (empty) image texture, so whatever was in the
        // previous context - including the pixels of the displayed still image
        // and the source size that describes them - is gone. Without this a
        // later surface recreation could "redraw" an empty texture through
        // canRepaintFromTexture() and leave a blank wallpaper.
        imageTextured = false
        imageSrcW = 0f
        imageSrcH = 0f
        lastDenoiseBitmap = null
    }

    private fun cleanupGlResources() {
        gl.cleanup()
        // Drop the retained bitmap reference so release() frees it promptly.
        lastImageBitmap = null
        lastDenoiseBitmap = null
        // gl.cleanup() deleted the image texture above: nothing is
        // re-presentable any more, so the texture-side marker must go with it
        // (otherwise a stale canRepaintFromTexture() could make a redraw claim
        // to succeed while drawing a deleted texture).
        imageTextured = false
        imageSrcW = 0f
        imageSrcH = 0f
        fadeAlpha = 0f
        glResourcesValid = false
    }

    private fun cleanupAll() {
        cleanupVideoResourcesOnRenderThread()
        cleanupGlResources()
        egl.release()
    }

    // ======== Helpers ========



    /**
     * Re-submit the codec-specific data (`csd-0`/`csd-1`/`csd-2`) to a decoder
     * that is being reused for a loop restart.
     *
     * `MediaCodec.flush()` keeps the configured format, so this is normally a
     * no-op - but several vendor decoders (Qualcomm among them) only resume
     * producing frames after the SPS/PPS have been handed to them again, which
     * is the same workaround ExoPlayer applies after a flush. A failure here is
     * harmless: the pass that then presents no frame drops the session and the
     * next pass rebuilds the codec from scratch.
     */
    internal fun requeueCodecSpecificData(codec: MediaCodec, format: MediaFormat) {
        for (key in CODEC_CONFIG_KEYS) {
            val src = try { format.getByteBuffer(key) } catch (_: Exception) { null } ?: continue
            try {
                src.position(0)
                val size = src.remaining()
                if (size <= 0) continue
                val inIdx = codec.dequeueInputBuffer(10_000L)
                if (inIdx < 0) continue
                val dst = codec.getInputBuffer(inIdx) ?: continue
                dst.clear()
                val copy = minOf(size, dst.capacity())
                src.limit(copy)
                dst.put(src)
                codec.queueInputBuffer(
                    inIdx, 0, copy, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                )
            } catch (t: Throwable) {
                AppLog.d(TAG, "CSD re-submit failed ($key): ${t.message}")
            }
        }
    }

    /**
     * Report the frame rate actually presented for the playback pass that just
     * ended, then reset the window. This is the line to compare against the
     * source frame rate logged by "Video started".
     *
     * Called both when the session is torn down (switch / rotation) and at every
     * LOOP restart - a warm session keeps its GL resources, so the teardown path
     * no longer runs on a loop, and without this the per-pass numbers would
     * disappear entirely for a video that just loops.
     */
    internal fun logPassFrameRate() {
        if (renderFpsWindowStart > 0L && renderFpsCount > 0) {
            val elapsed = (SystemClock.elapsedRealtime() - renderFpsWindowStart).coerceAtLeast(1L)
            AppLog.d(
                TAG,
                "Video pass: %d frames presented in %dms (%.1f fps)".format(
                    renderFpsCount, elapsed, renderFpsCount * 1000f / elapsed
                )
            )
        }
        // Reset the window so the per-minute diagnostic only measures continuous
        // playback, not idle gaps between switches.
        renderFpsWindowStart = 0L
        renderFpsCount = 0
    }

    /**
     * Copy a content URI into the app cache as a seekable file. Used when a
     * provider (common on non-Xiaomi devices / cloud pickers) returns a
     * descriptor MediaExtractor cannot seek in, which otherwise made videos
     * display black. The copy is deleted as soon as the decode round ends.
     *
     * Bounded and cancellable on purpose:
     * - at most [VIDEO_CACHE_COPY_MAX_BYTES] is written, and only while the
     *   cache partition keeps [VIDEO_CACHE_FREE_RESERVE_BYTES] free;
     * - [isCancelled] is checked between chunks, so switching away stops the
     *   copy instead of letting a daemon thread keep writing (an interrupt is
     *   honoured too, but ContentResolver streams do not always react to it).
     */
    internal fun copyVideoToCache(
        uriStr: String,
        maxBytes: Long = VIDEO_CACHE_COPY_MAX_BYTES,
        isCancelled: () -> Boolean = { false },
    ): java.io.File? {
        return try {
            val dir = java.io.File(context.cacheDir, "video_cache").apply { mkdirs() }
            // Best-effort cleanup of stale copies from previous sessions:
            // age first, then a small newest-first cap.
            try {
                val stale = dir.listFiles()
                if (stale != null) {
                    val now = System.currentTimeMillis()
                    val expired = stale.filter { now - it.lastModified() > VIDEO_CACHE_STALE_MS }
                    expired.forEach { it.delete() }
                    val alive = stale.filter { it.exists() }.sortedByDescending { it.lastModified() }
                    alive.drop(VIDEO_CACHE_KEEP_FILES).forEach { it.delete() }
                }
            } catch (_: Exception) {
            }
            // Coarse "is there room" guard for a file in OUR cache; no need to
            // request an allocation / let the system clear other apps' caches.
            @Suppress("UsableSpace")
            val usable = try {
                dir.usableSpace
            } catch (_: Throwable) {
                Long.MAX_VALUE
            }
            val budget = minOf(maxBytes, usable - VIDEO_CACHE_FREE_RESERVE_BYTES)
            if (budget <= 0L) {
                AppLog.w(
                    TAG,
                    "Video cache fallback skipped: only ${usable / (1024 * 1024)}MB free"
                )
                return null
            }
            val out = java.io.File(
                dir,
                "video_${SystemClock.elapsedRealtime()}_${uriStr.hashCode()}.mp4"
            )
            var complete = false
            try {
                val input = context.contentResolver.openInputStream(Uri.parse(uriStr))
                if (input == null) return null
                input.use { source ->
                    out.outputStream().use { sink ->
                        val result = com.wallpaperswitcher.engine.StreamCopy.copy(
                            input = source,
                            output = sink,
                            maxBytes = budget,
                            isCancelled = isCancelled,
                            bufferSize = VIDEO_COPY_BUFFER_BYTES,
                        )
                        complete = result.complete
                        if (result.exceededLimit) {
                            AppLog.w(
                                TAG,
                                "Video cache fallback aborted at " +
                                    "${result.bytes / (1024 * 1024)}MB " +
                                    "(budget ${budget / (1024 * 1024)}MB)"
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "copyVideoToCache failed: ${LogText.short(uriStr)}", t)
            }
            if (!complete || out.length() <= 0L) {
                out.delete()
                null
            } else {
                out
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "copyVideoToCache failed: ${LogText.short(uriStr)}", t)
            null
        }
    }
}
