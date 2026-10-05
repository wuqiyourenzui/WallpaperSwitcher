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

import java.util.concurrent.CountDownLatch

import java.util.concurrent.TimeUnit

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
    private val context: Context,
    private val holder: SurfaceHolder
) {
    companion object {
        private const val TAG = "WallpaperRenderer"
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

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val IMAGE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTexelSize;
            uniform vec2 uSrcTexel;
            uniform float uSharp;
            uniform float uEnhance;
            uniform float uAlpha;
            varying vec2 vTexCoord;

            vec4 cubicWeights(float t) {
                float t2 = t * t;
                float t3 = t2 * t;
                return vec4(
                    -0.5 * t3 + t2 - 0.5 * t,
                     1.5 * t3 - 2.5 * t2 + 1.0,
                    -1.5 * t3 + 2.0 * t2 + 0.5 * t,
                     0.5 * t3 - 0.5 * t2
                );
            }

            // 画质增强: Catmull-Rom bicubic with 4 hardware-bilinear taps. The
            // pair decomposition mirrors WallpaperGeometry.cubicPairs (unit
            // tested); it only runs while a low-res source is magnified, so the
            // default path keeps its original cost.
            vec4 bicubic4(vec2 uv, vec2 texel) {
                vec2 c = uv / texel;
                vec2 i0 = floor(c);
                vec2 t = c - i0;
                vec4 wx = cubicWeights(t.x);
                vec4 wy = cubicWeights(t.y);
                float wAx = wx.x + wx.y;
                float wBx = wx.z + wx.w;
                float wAy = wy.x + wy.y;
                float wBy = wy.z + wy.w;
                float pAx = wAx > 0.0001 ? wx.y / wAx : 0.5;
                float pBx = wBx > 0.0001 ? wx.w / wBx : 0.5;
                float pAy = wAy > 0.0001 ? wy.y / wAy : 0.5;
                float pBy = wBy > 0.0001 ? wy.w / wBy : 0.5;
                vec2 lo = texel * 0.5;
                vec2 hi = vec2(1.0) - lo;
                vec4 acc = vec4(0.0);
                acc += (wAx * wAy) * texture2D(uTexture, clamp(vec2(i0.x - 1.0 + pAx, i0.y - 1.0 + pAy) * texel, lo, hi));
                acc += (wBx * wAy) * texture2D(uTexture, clamp(vec2(i0.x + 1.0 + pBx, i0.y - 1.0 + pAy) * texel, lo, hi));
                acc += (wAx * wBy) * texture2D(uTexture, clamp(vec2(i0.x - 1.0 + pAx, i0.y + 1.0 + pBy) * texel, lo, hi));
                acc += (wBx * wBy) * texture2D(uTexture, clamp(vec2(i0.x + 1.0 + pBx, i0.y + 1.0 + pBy) * texel, lo, hi));
                return acc;
            }

            void main() {
                if (uEnhance > 0.001) {
                    vec4 e = bicubic4(vTexCoord, uSrcTexel);
                    if (uSharp <= 0.001) {
                        gl_FragColor = clamp(vec4(e.rgb, uAlpha), 0.0, 1.0);
                        return;
                    }
                    float esharp = uSharp * (1.0 + 2.0 * uEnhance);
                    vec4 es = e * (1.0 + 4.0 * esharp)
                           - (texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0))
                            + texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0))
                            + texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y))
                            + texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y))) * esharp;
                    gl_FragColor = clamp(vec4(es.rgb, uAlpha), 0.0, 1.0);
                    return;
                }
                // Mild unsharp mask. uSharp == 0.0 keeps the original pixel
                // exactly (used for downscaled/native media and the black
                // background). The early return also skips the 4 neighbor
                // fetches, so normal/high-res wallpapers cost exactly the
                // same GPU bandwidth as before sharpening was added.
                vec4 c = texture2D(uTexture, vTexCoord);
                if (uSharp <= 0.001) {
                    gl_FragColor = vec4(c.rgb, uAlpha);
                    return;
                }
                vec4 s = c * (1.0 + 4.0 * uSharp)
                       - (texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y))) * uSharp;
                gl_FragColor = clamp(vec4(s.rgb, uAlpha), 0.0, 1.0);
            }
        """

        private const val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexelSize;
            uniform vec2 uSrcTexel;
            uniform float uSharp;
            uniform float uEnhance;
            varying vec2 vTexCoord;

            vec4 cubicWeights(float t) {
                float t2 = t * t;
                float t3 = t2 * t;
                return vec4(
                    -0.5 * t3 + t2 - 0.5 * t,
                     1.5 * t3 - 2.5 * t2 + 1.0,
                    -1.5 * t3 + 2.0 * t2 + 0.5 * t,
                     0.5 * t3 - 0.5 * t2
                );
            }

            vec4 bicubic4(vec2 uv, vec2 texel) {
                vec2 c = uv / texel;
                vec2 i0 = floor(c);
                vec2 t = c - i0;
                vec4 wx = cubicWeights(t.x);
                vec4 wy = cubicWeights(t.y);
                float wAx = wx.x + wx.y;
                float wBx = wx.z + wx.w;
                float wAy = wy.x + wy.y;
                float wBy = wy.z + wy.w;
                float pAx = wAx > 0.0001 ? wx.y / wAx : 0.5;
                float pBx = wBx > 0.0001 ? wx.w / wBx : 0.5;
                float pAy = wAy > 0.0001 ? wy.y / wAy : 0.5;
                float pBy = wBy > 0.0001 ? wy.w / wBy : 0.5;
                vec2 lo = texel * 0.5;
                vec2 hi = vec2(1.0) - lo;
                vec4 acc = vec4(0.0);
                acc += (wAx * wAy) * texture2D(uTexture, clamp(vec2(i0.x - 1.0 + pAx, i0.y - 1.0 + pAy) * texel, lo, hi));
                acc += (wBx * wAy) * texture2D(uTexture, clamp(vec2(i0.x + 1.0 + pBx, i0.y - 1.0 + pAy) * texel, lo, hi));
                acc += (wAx * wBy) * texture2D(uTexture, clamp(vec2(i0.x - 1.0 + pAx, i0.y + 1.0 + pBy) * texel, lo, hi));
                acc += (wBx * wBy) * texture2D(uTexture, clamp(vec2(i0.x + 1.0 + pBx, i0.y + 1.0 + pBy) * texel, lo, hi));
                return acc;
            }

            void main() {
                if (uEnhance > 0.001) {
                    vec4 e = bicubic4(vTexCoord, uSrcTexel);
                    if (uSharp <= 0.001) {
                        gl_FragColor = clamp(e, 0.0, 1.0);
                        return;
                    }
                    float esharp = uSharp * (1.0 + 2.0 * uEnhance);
                    vec4 es = e * (1.0 + 4.0 * esharp)
                           - (texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0))
                            + texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0))
                            + texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y))
                            + texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y))) * esharp;
                    gl_FragColor = clamp(es, 0.0, 1.0);
                    return;
                }
                vec4 c = texture2D(uTexture, vTexCoord);
                if (uSharp <= 0.001) {
                    gl_FragColor = c;
                    return;
                }
                vec4 s = c * (1.0 + 4.0 * uSharp)
                       - (texture2D(uTexture, vTexCoord + vec2(-uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(uTexelSize.x, 0.0))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, -uTexelSize.y))
                        + texture2D(uTexture, vTexCoord + vec2(0.0, uTexelSize.y))) * uSharp;
                gl_FragColor = clamp(s, 0.0, 1.0);
            }
        """
    }

    // EGL — all access on render thread only
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    /**
     * True only when THIS renderer's `eglInitialize` call succeeded.
     *
     * Every renderer in the process gets the same handle from
     * `eglGetDisplay(EGL_DEFAULT_DISPLAY)`, and a home engine and a preview engine can
     * coexist. `setupEglContext()` keeps that handle when `eglInitialize` fails, so
     * without this flag `cleanupAll()` would call `eglTerminate` on a display this
     * instance never initialised - an unbalanced init/terminate pair that can drop a
     * sibling engine's last reference and kill its GL context (frozen wallpaper).
     */
    private var eglInitializedHere = false
    // Written on the render thread, polled from the engine thread.
    @Volatile private var surfaceReady = false
    private var contextReady = false
    private var glResourcesValid = false

    // GL resources (created once, survive surface recreation)
    private var imageProgram = 0
    private var videoProgram = 0
    // Cached shader locations: queried once per program creation instead of
    // 6 times per rendered frame (at 30fps that is ~180 driver queries/sec).
    private var imageTexMatLoc = -1
    private var imageTexLoc = -1
    private var imagePosLoc = -1
    private var imageTcLoc = -1
    private var videoTexMatLoc = -1
    private var videoTexLoc = -1
    private var videoPosLoc = -1
    private var videoTcLoc = -1
    // Sharpening uniforms (queried once per program creation).
    private var imageTexelLoc = -1
    private var imageSharpLoc = -1
    private var imageAlphaLoc = -1
    private var videoTexelLoc = -1
    private var videoSharpLoc = -1
    // 画质增强 uniforms (super-resolution strength + source texel size).
    private var imageEnhanceLoc = -1
    private var imageSrcTexelLoc = -1
    private var videoEnhanceLoc = -1
    private var videoSrcTexelLoc = -1
    /**
     * 画质增强 (AI/超分): when on, a source that is being magnified is sampled
     * with a 4-tap Catmull-Rom bicubic and sharper unsharp masking (images and
     * video frames share the path). Written from the engine thread.
     */
    @Volatile private var qualityEnhance: Boolean = false
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
                audioSession.pause()
            } else {
                synchronized(pauseLock) { pauseLock.notifyAll() }
                // Ken Burns sleeps with the wallpaper; wake its ticker up.
                postToRenderThread { maybeStartKenBurns() }
            }
        }
    /** See [powerSaveMode]; guards the pause waits of the video/audio loops. */
    private val pauseLock = Object()
    /** Safety net for those waits: re-check the state at least this often. */
    private val PAUSE_WAIT_MAX_MS = 5_000L
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
    private var lastImageBitmap: Bitmap? = null
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
    private var vertexBuffer: FloatBuffer? = null
    private var imageTexId = 0
    private var imageTexMatrix = FloatArray(16)
    // Reused on the render thread to avoid allocating a matrix per video frame.
    private val videoTexMatrix = FloatArray(16)
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
    private var blackTexId = 0
    private var backgroundBuffer: FloatBuffer? = null

    // Screen dimensions — only on render thread
    private var screenW = 0f
    private var screenH = 0f

    // Video state — ALL accessed only on render thread (after initial setup)
    private var videoTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var codecSurface: Surface? = null
    // On-screen size + scale mode of the current video (render thread only),
    // used to sharpen the picture when a low-res video is magnified.
    // videoDisplayW/H are the on-screen orientation (90/270°-rotated phone
    // recordings swap the axes), which is what the upscale factor needs; the
    // screen-space kernel reads the quad extents from videoQuadHalfW/H.
    private var videoDisplayW = 0f
    private var videoDisplayH = 0f
    /** Actual decoded texture size (post decode-cap) for bicubic texel steps. */
    private var videoSrcW = 0f
    private var videoSrcH = 0f
    private var videoScaleMode: ScaleMode = ScaleMode.FIT
    // Quad half-extents of the current video (render thread only). The texture
    // footprint on screen is (halfW*screenW, halfH*screenH) pixels, which the
    // screen-space sharpening kernel needs regardless of up/downscale.
    private var videoQuadHalfW = 1f
    private var videoQuadHalfH = 1f
    // FILL/STRETCH rotates orientation-mismatched video content 90° (same rule
    // as static images/GIFs) so more of the frame is visible. Render thread.
    private var videoExtraRotate = false
    // Mirrors the app's "自动旋转适配" setting; written by the engine, read on
    // the render thread when (re)computing the video quad.
    @Volatile var autoRotateMismatch = true
    // Direction used when autoRotateMismatch is on.
    @Volatile var autoRotateClockwise = true
    // True when the current video's quad covers the whole viewport (FILL /
    // STRETCH, or FIT with a matching aspect). Only read/written on the render
    // thread; when false the letterbox needs the black backing quad.
    private var videoQuadFullscreen = false
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var videoDecodeThread: Thread? = null
    @Volatile var isVideoPlaying = false; private set
    // Elapsed realtime of the last successfully presented video frame. The
    // engine's health monitor uses this to detect a stalled decoder (e.g. a
    // cloud file whose stream read blocks forever) and recover automatically.
    @Volatile var lastVideoFrameAt = 0L
        private set

    /**
     * Reset the "last presented video frame" clock. The engine calls this while
     * the screen is off and again on screen-on: a frozen screen-off window has
     * no frames by definition and must never be mistaken for a stalled video.
     */
    fun resetVideoFrameClock() {
        lastVideoFrameAt = SystemClock.elapsedRealtime()
    }
    private val videoGeneration = AtomicInteger(0)
    // Flag to prevent double-cleanup: stopVideoInternal sets this, decodeLoop checks it.
    private val videoCleanupDone = AtomicBoolean(false)

    // --- Video audio (optional: SettingsKeys.VIDEO_SOUND_ENABLED) ---
    //
    // The audio runs on its OWN thread with its own MediaExtractor/MediaCodec/
    // AudioTrack, because the video decode thread must never block: it paces
    // frames against the presentation clock and a blocking PCM write would stall
    // playback. Audio and video are re-anchored at every playback pass (the
    // video increments videoPassCounter when a pass starts; the audio thread
    // waits for it), which keeps the two from drifting apart across loops.
    @Volatile var videoSoundEnabled = false
    private var audioThread: Thread? = null
    private val videoPassCounter = AtomicInteger(0)
    /**
     * Where the next `decodeLoop` pass 1 must start (µs). Set by [startVideo] and
     * consumed by the first round of the decode loop; later rounds always rewind
     * to 0 so the clip still loops from the beginning.
     */
    @Volatile private var pendingStartPositionUs = 0L
    /**
     * Presentation time (µs) of the last frame handed to the surface.
     *
     * Used by "接着上次位置继续播放": when the engine releases the decoder while
     * the device is locked it remembers this value and starts the rebuilt video
     * (and its audio) there instead of at 0, so a long clip does not jump back to
     * the beginning after every lock.
     */
    @Volatile var lastVideoPositionUs: Long = 0L
    /**
     * True while the video's audio was silenced for our OWN UI opening but the
     * picture deliberately kept playing (see [muteAudioKeepingVideo]).
     */
    private var audioMutedForOwnUi = false
        private set
    // Monitor for the pass handshake (wait/notify, not a lock around state).
    private val audioPassLock = Object()
    // The video currently playing, and the cache copy if the video path had to
    // make one (the cache file is seekable, so prefer it for the audio too).
    @Volatile private var currentVideoUri: String? = null
    @Volatile private var currentVideoCachePath: String? = null
    // Generation for which "this video has no audio track" was already logged.
    @Volatile private var audioNoTrackLoggedFor = -1
    /**
     * One AudioTrack for the whole renderer, reused by every video switch and
     * every playback pass. Creating a track per pass/switch (the first version)
     * cost an audible click each time: the framework tears the old mix down and
     * ramps the new one up.
     */
    private val audioSession = AudioSession()
    /** PCM a pause forced us to hold back, written as soon as we are visible. */
    @Volatile
    private var audioPending: ByteBuffer? = null
    // Coalescing flag: at most one render post is queued at a time, so a decode
    // thread that outruns the render thread (rapid switching, heavy load) can
    // never grow the handler queue without bound.
    private val renderPostQueued = AtomicBoolean(false)
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
    private var lastFrameWarnAt = 0L
    private val FRAME_WARN_INTERVAL_MS = 5_000L
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
    private val maxPlaybackLagNs = 500_000_000L
    /**
     * Audio-side sleeps: the audio thread must notice a pause/switch/sound
     * toggle within a few milliseconds, so it never sleeps as long as the video
     * pause poll above.
     *
     * [AUDIO_WRITE_RETRY_MS] paces a full AudioTrack buffer (a blocking write is
     * deliberately avoided - see [writePcm]). [AUDIO_OUTPUT_WAIT_US] is the
     * timeout of the output dequeue when the decoder has nothing to chew on: it
     * replaces the old "poll with timeout 0, then sleep 5ms" pair, which burned
     * up to 200 wakeups/s while merely waiting for the codec.
     */
    private val AUDIO_WRITE_RETRY_MS = 5L
    private val AUDIO_OUTPUT_WAIT_US = 10_000L
    /** Bound for opening the audio source (same idea as the video's 15s). */
    private val AUDIO_OPEN_TIMEOUT_MS = 12_000L
    // Throttle for the "re-anchored" diagnostic line (render/decode thread).
    @Volatile private var lastReanchorLogAt = 0L
    // Logged-once flag for the throttled episode (see the re-anchor below).
    @Volatile private var powerSavePauseLogged = false
    /**
     * Whether the current "wallpaper not visible" episode has already been
     * announced. Cleared as soon as a frame is presented again (see the pacing
     * block), so every hide/show cycle produces exactly one pause/resume pair
     * even when the timer restarts the video while it is hidden.
     */
    @Volatile private var videoPauseAnnounced = false
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
            setupEglContext()
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
                setupEglContext()
                if (contextReady) {
                    setupGlResources()
                    glResourcesValid = true
                }
            }
            if (contextReady) createEglSurface()
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
                destroyEglSurface()
                createEglSurface()
                if (!surfaceReady) return@postToRenderThread
            } else {
                if (surfaceReady) GLES20.glViewport(0, 0, width, height)
            }
            // The viewport changed (rotation / resize): re-present the current
            // static image and recompute the current video quad against the new
            // aspect. Without this, a letterboxed FIT media stays rendered for
            // the old dimensions (distorted / misplaced) until the next switch.
            val bmp = lastImageBitmap
            if (surfaceReady && bmp != null && !bmp.isRecycled && lastRenderWasImage) {
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
            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return@postToRenderThread
            val bmp = lastImageBitmap
            if (lastRenderWasImage && bmp != null && !bmp.isRecycled) {
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
    private fun refreshVideoQuad(displayW: Float, displayH: Float, scaleMode: ScaleMode) {
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
        vertexBuffer?.clear()
        vertexBuffer?.put(quad)?.position(0)
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
    fun applyClarity(scale: Float) {
        postToRenderThread {
            sharpnessScale = scale
            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return@postToRenderThread
            val bmp = lastImageBitmap
            if (lastRenderWasImage && bmp != null && !bmp.isRecycled) {
                renderImageFromTexture()
            }
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
            lastImageRotateCw = rotateCw
            renderImageFromTexture()
        }
    }

    fun surfaceDestroyed() {
        stopVideoInternal()
        postToRenderThread {
            surfaceReady = false
            destroyEglSurface()
        }
    }

    fun release() {
        stopVideoInternal()
        currentVideoUri = null
        currentVideoCachePath = null
        audioPending = null
        audioMutedForOwnUi = false
        audioSession.release()
        val handler = renderHandler
        handler?.removeCallbacks(kenBurnsRunnable)
        val thread = renderThread
        if (handler != null && thread != null) {
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    surfaceReady = false
                    contextReady = false
                    cleanupAll()
                } catch (t: Throwable) {
                    AppLog.e(TAG, "Release task failed", t)
                } finally {
                    latch.countDown()
                }
            }
            // Bound the wait: release() runs on the engine's (main) thread
            // during onDestroy and must never block it for long.
            try { latch.await(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
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
     */
    fun showGifFrame(bitmap: Bitmap, scaleMode: ScaleMode) {
        lastGifFrameAtMs = SystemClock.elapsedRealtime()
        if (!imageRenderPostQueued.compareAndSet(false, true)) return
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
     * 画质增强 (AI/超分): adopt the setting. Every draw reads the flag, so a
     * video applies it on the next frame; a still image is re-presented once so
     * the change is visible immediately.
     */
    fun setQualityEnhanceEnabled(enabled: Boolean) {
        qualityEnhance = enabled
        postToRenderThread {
            if (lastRenderWasImage) renderImageFromTexture()
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
            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return
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
            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return
            if (lastRenderWasImage) {
                val bmp = lastImageBitmap
                if (bmp != null && !bmp.isRecycled) renderImageFromTexture()
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
        if (alpha <= 0f || imageProgram == 0 || blackTexId == 0) return
        if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return
        val bg = backgroundBuffer ?: return
        GLES20.glEnable(GLES20.GL_BLEND)
        try {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(imageProgram)
            GLES20.glUniformMatrix4fv(imageTexMatLoc, 1, false, imageTexMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blackTexId)
            GLES20.glUniform1i(imageTexLoc, 0)
            GLES20.glUniform2f(imageTexelLoc, 1f, 1f)
            GLES20.glUniform1f(imageSharpLoc, 0f)
            GLES20.glUniform1f(imageEnhanceLoc, 0f)
            GLES20.glUniform1f(imageAlphaLoc, alpha)
            bg.position(0)
            GLES20.glEnableVertexAttribArray(imagePosLoc)
            GLES20.glVertexAttribPointer(imagePosLoc, 2, GLES20.GL_FLOAT, false, 16, bg)
            bg.position(2)
            GLES20.glEnableVertexAttribArray(imageTcLoc)
            GLES20.glVertexAttribPointer(imageTcLoc, 2, GLES20.GL_FLOAT, false, 16, bg)
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
            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return
            if (imageProgram == 0 || imageTexId == 0) {
                AppLog.w(TAG, "renderImage skipped: program=$imageProgram tex=$imageTexId")
                return
            }
            // A concurrent switch may recycle the bitmap before this queued
            // render runs; uploading a recycled bitmap would throw.
            if (bitmap.isRecycled) return

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
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
            vertexBuffer?.clear()
            vertexBuffer?.put(quad)?.position(0)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // The black backing quad is only needed when the media quad leaves
            // letterbox areas uncovered (FIT). FILL/STRETCH and fullscreen
            // media overwrite every framebuffer pixel with an opaque quad, so
            // skipping this pass saves one program switch + texture bind +
            // full-screen draw per presented frame (e.g. 30x/sec on video).
            if (!WallpaperGeometry.quadCoversScreen(quad)) drawBlackBackground()
            GLES20.glUseProgram(imageProgram)
            val texMatLoc = imageTexMatLoc
            val texLoc = imageTexLoc
            val posLoc = imagePosLoc
            val tcLoc = imageTcLoc

            GLES20.glUniformMatrix4fv(texMatLoc, 1, false, imageTexMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
            GLES20.glUniform1i(texLoc, 0)
            updateImageScreenTexelDelta(quad)
            GLES20.glUniform2f(imageTexelLoc, imageTexelX, imageTexelY)
            GLES20.glUniform1f(
                imageSharpLoc,
                sharpnessFor(bitmap.width.toFloat(), bitmap.height.toFloat(), scaleMode)
            )
            GLES20.glUniform1f(
                imageEnhanceLoc,
                WallpaperGeometry.enhancementStrength(
                    bitmap.width.toFloat(), bitmap.height.toFloat(),
                    screenW, screenH, scaleMode, qualityEnhance,
                ),
            )
            GLES20.glUniform2f(
                imageSrcTexelLoc,
                1f / bitmap.width.coerceAtLeast(1),
                1f / bitmap.height.coerceAtLeast(1),
            )
            GLES20.glUniform1f(imageAlphaLoc, 1f)

            vertexBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            vertexBuffer?.position(2)
            GLES20.glEnableVertexAttribArray(tcLoc)
            GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (fadeAlpha > 0f) drawFadeOverlayNoSwap(fadeAlpha)
            val swapped = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            if (!swapped) {
                AppLog.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
            }
            // Remember the last presented image so the fade steps can force
            // redraws of static images with the decaying overlay.
            lastImageBitmap = bitmap
            lastImageScaleMode = scaleMode
            lastImageRotateCw = rotateCw
            lastRenderWasImage = true
            maybeStartKenBurns()
        } catch (t: Throwable) {
            AppLog.e(TAG, "renderImage failed", t)
        }
    }

    /**
     * Re-present the image currently uploaded to [imageTexId] WITHOUT
     * re-uploading the bitmap (the fade-overlay steps). The texture content is
     * unchanged between fade steps, so the previous code re-uploaded a full
     * screen-size bitmap and regenerated mipmaps on every step for no visual
     * difference. Runs on the render thread; the texture is guaranteed to be
     * this bitmap's because fade steps are queued behind the switch's render.
     */
    private fun renderImageFromTexture() {
        try {
            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return
            if (imageProgram == 0 || imageTexId == 0) return
            val bmp = lastImageBitmap ?: return
            if (bmp.isRecycled) return
            val quad = WallpaperGeometry.computeQuad(
                bmp.width.toFloat(), bmp.height.toFloat(), screenW, screenH, lastImageScaleMode,
                lastImageRotateCw
            )
            WallpaperGeometry.applyTransition(quad, transitionMode, transitionProgress)
            if (kenBurnsEnabled) {
                WallpaperGeometry.applyKenBurns(
                    quad,
                    kenBurnsPhase(SystemClock.elapsedRealtime()),
                    KEN_BURNS_AMPLITUDE,
                )
            }
            vertexBuffer?.clear()
            vertexBuffer?.put(quad)?.position(0)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (!WallpaperGeometry.quadCoversScreen(quad)) drawBlackBackground()
            GLES20.glUseProgram(imageProgram)

            GLES20.glUniformMatrix4fv(imageTexMatLoc, 1, false, imageTexMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
            GLES20.glUniform1i(imageTexLoc, 0)
            updateImageScreenTexelDelta(quad)
            GLES20.glUniform2f(imageTexelLoc, imageTexelX, imageTexelY)
            GLES20.glUniform1f(
                imageSharpLoc,
                sharpnessFor(bmp.width.toFloat(), bmp.height.toFloat(), lastImageScaleMode)
            )
            GLES20.glUniform1f(
                imageEnhanceLoc,
                WallpaperGeometry.enhancementStrength(
                    bmp.width.toFloat(), bmp.height.toFloat(),
                    screenW, screenH, lastImageScaleMode, qualityEnhance,
                ),
            )
            GLES20.glUniform2f(
                imageSrcTexelLoc,
                1f / bmp.width.coerceAtLeast(1),
                1f / bmp.height.coerceAtLeast(1),
            )
            GLES20.glUniform1f(imageAlphaLoc, 1f)

            vertexBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(imagePosLoc)
            GLES20.glVertexAttribPointer(imagePosLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            vertexBuffer?.position(2)
            GLES20.glEnableVertexAttribArray(imageTcLoc)
            GLES20.glVertexAttribPointer(imageTcLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (fadeAlpha > 0f) drawFadeOverlayNoSwap(fadeAlpha)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
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
        videoPassCounter.set(0)

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
        // (see signalVideoPassStart), so a video that never starts stays silent.
        currentVideoUri = uriStr
        // Not while our own UI is open: a media switch triggered from inside the
        // app must not start playing audio behind it (see muteAudioKeepingVideo).
        if (videoSoundEnabled && !audioMutedForOwnUi) {
            startAudio(uriStr, gen, startPositionUs.coerceAtLeast(0L))
        }
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
        stopAudio()

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
        stopAudio()

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
            stopAudio()
            AppLog.d(TAG, "Video sound OFF")
            return
        }
        val uri = currentVideoUri
        if (uri != null && isVideoPlaying && !audioMutedForOwnUi) {
            // Start the sound where the picture IS, not at 0: switching the
            // setting on mid-playback used to play the audio from the file's
            // start while the picture stayed at its position (the two only met
            // again at the next loop boundary, which can be minutes away for a
            // long clip).
            startAudio(uri, videoGeneration.get(), lastVideoPositionUs)
            AppLog.d(TAG, "Video sound ON (from ${lastVideoPositionUs / 1000}ms)")
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
        audioSession.muteForPolicy()
        stopAudio()
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
        if (!audioMutedForOwnUi) return
        audioMutedForOwnUi = false
        // Lifts the session-level block BEFORE the new audio thread starts.
        audioSession.clearPolicyMute()
        if (!videoSoundEnabled || !isVideoPlaying || powerSaveMode) return
        val uri = currentVideoUri ?: return
        startAudio(uri, videoGeneration.get(), lastVideoPositionUs)
        AppLog.d(TAG, "Audio unmuted, re-anchored at ${lastVideoPositionUs / 1000}ms")
    }

    /** Start the audio thread for [uriStr]; only called when sound is enabled. */
    private fun startAudio(uriStr: String, gen: Int, startPositionUs: Long = 0L) {
        stopAudio()
        val thread = Thread({
            // AUDIO priority, not BACKGROUND: the audio must not be starved by
            // the video decode + GL work that runs at the same time (a starved
            // writer is the classic cause of crackling on a busy device).
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            audioLoop(uriStr, gen, startPositionUs)
        }, "VideoAudio").apply {
            // Never keep the process alive for a wallpaper's sound.
            isDaemon = true
        }
        audioThread = thread
        thread.start()
    }

    /**
     * Ask the audio thread to stop. Deliberately non-blocking and without join():
     * the thread may be inside a codec call, and it releases its own
     * AudioTrack/MediaCodec in its finally block. Sound stops as soon as the
     * thread notices (it never blocks on a PCM write - see [writePcm]).
     */
    private fun stopAudio() {
        val thread = audioThread
        audioThread = null
        // Silence immediately: the thread may be inside a codec call and takes a
        // few ms to exit, and the buffered AudioTrack would keep playing until
        // then. The track itself is kept for the next video (see audioSession).
        audioSession.pause()
        if (thread != null) {
            thread.interrupt()
            synchronized(audioPassLock) { audioPassLock.notifyAll() }
        }
    }

    /**
     * Called by the video decode thread every time a playback pass is ready to
     * present. The audio thread waits for this, so both start a pass together and
     * cannot drift apart across loops.
     */
    private fun signalVideoPassStart(gen: Int) {
        // A late-exiting decode thread of a superseded video must not look like
        // the new video's first pass (the audio would start early and stutter).
        if (videoGeneration.get() != gen) return
        videoPassCounter.incrementAndGet()
        synchronized(audioPassLock) { audioPassLock.notifyAll() }
    }

    /** Wait until a playback pass newer than [lastPass] has started. */
    private fun awaitNextVideoPass(lastPass: Int, gen: Int): Int? {
        while (videoGeneration.get() == gen && videoSoundEnabled &&
            !Thread.currentThread().isInterrupted
        ) {
            val current = videoPassCounter.get()
            if (current > lastPass) return current
            synchronized(audioPassLock) {
                if (videoPassCounter.get() <= lastPass) {
                    try {
                        audioPassLock.wait(250L)
                    } catch (_: InterruptedException) {
                        return null
                    }
                }
            }
        }
        return null
    }

    private fun audioLoop(uriStr: String, gen: Int, startPositionUs: Long = 0L) {
        // The decoder/extractor stay open for the WHOLE video (they are only
        // rewound at each playback pass). Rebuilding them - and the AudioTrack -
        // on every loop is what produced the short gaps and clicks.
        val extractor = MediaExtractor()
        val session = audioSession
        var decoder: MediaCodec? = null
        var afd: AssetFileDescriptor? = null
        // Why this thread ended, for the diagnostic line in `finally`. An audio
        // thread that stops between two passes used to leave nothing in the log
        // beyond "thread finished", which made a silent video impossible to
        // diagnose (see the power-review notes).
        var exitReason = "loop condition"
        AppLog.d(TAG, "Video audio: thread started (gen=$gen)")
        try {
            val cached = currentVideoCachePath
            if (cached != null) {
                // The video decode fell back to a local copy: it is seekable and
                // never blocks on the (possibly cloud) provider again.
                extractor.setDataSource(cached)
            } else {
                afd = openAudioDescriptor(uriStr) ?: run {
                    exitReason = "no audio source"
                    return
                }
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            val trackIdx = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: run {
                if (audioNoTrackLoggedFor != gen) {
                    audioNoTrackLoggedFor = gen
                    AppLog.d(TAG, "Video has no audio track: ${LogText.short(uriStr)}")
                }
                exitReason = "no audio track"
                return
            }
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
                exitReason = "audio track without MIME"
                return
            }
            var sampleRate =
                VideoSound.sampleRateFor(format.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE))
            var channels =
                VideoSound.playbackChannelsFor(format.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT))
            // What the container declares is a hint; the decoder's real output
            // format arrives with INFO_OUTPUT_FORMAT_CHANGED and is applied below.
            var encoding = VideoSound.PCM_ENCODING
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            // Create the track up front from the container's declared format; the
            // handler below retunes it if the decoder turns out to output
            // something else (HE-AAC, downmix). Skipped while the wallpaper is
            // hidden: allocating an AudioTrack only to pause it a millisecond
            // later (tablet log) achieves nothing, the loop below creates it on
            // the first visible frame instead.
            if (!powerSaveMode &&
                session.trackFor(sampleRate, channels, encoding) == null
            ) {
                exitReason = "no AudioTrack while hidden"
                return
            }

            val info = MediaCodec.BufferInfo()
            var pass = 0
            var firstPass = true
            var announcedStart = false
            while (videoGeneration.get() == gen && videoSoundEnabled &&
                !Thread.currentThread().isInterrupted
            ) {
                val nextPass = awaitNextVideoPass(pass, gen) ?: break
                pass = nextPass
                // Rewind for the new pass instead of rebuilding anything. The
                // video restarts its own codec right now; the audio already
                // buffered in the AudioTrack covers that restart, so playback
                // stays gapless and the two stay in lockstep (no drift, because
                // the audio waits for the video at every pass).
                // The first pass starts where the video resumed (lock-release
                // position); later passes rewind to 0 so the audio loops with the
                // picture.
                extractor.seekTo(
                    if (firstPass) startPositionUs.coerceAtLeast(0L) else 0L,
                    MediaExtractor.SEEK_TO_CLOSEST_SYNC
                )
                decoder.flush()
                audioPending = null
                if (firstPass) {
                    // New media: drop whatever the previous video left buffered.
                    if (session.track != null) {
                        session.restart()
                    } else {
                        // Hidden: no track was allocated yet (see below). The
                        // "started" line comes once it really exists, so a
                        // "buffer=0ms" can never appear in the log again.
                        AppLog.d(TAG, "Video audio: waiting for the wallpaper to become visible")
                    }
                    firstPass = false
                } else {
                    session.ensurePlaying()
                }
                var paused = false
                var passDone = false
                var inputDone = false
                while (!passDone && videoGeneration.get() == gen &&
                    videoPassCounter.get() == pass && videoSoundEnabled &&
                    !Thread.currentThread().isInterrupted
                ) {
                    if (powerSaveMode) {
                        // Same rule as the picture: hidden means paused, not
                        // muted - playback continues where it stopped.
                        if (!paused) {
                            paused = true
                            session.pause()
                            AppLog.d(TAG, "Video audio paused (wallpaper not visible)")
                        }
                        // Same event-based wait as the picture: no wakeups while
                        // the wallpaper is hidden, instant resume once it is back.
                        synchronized(pauseLock) {
                            if (powerSaveMode) {
                                try {
                                    pauseLock.wait(PAUSE_WAIT_MAX_MS)
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                }
                            }
                        }
                        continue
                    } else if (paused) {
                        paused = false
                        session.resume()
                        AppLog.d(TAG, "Video audio resumed (wallpaper visible again)")
                    }
                    if (session.track == null) {
                        // First visible frame of this video: create the track now
                        // (see the note at the pipeline setup).
                        if (session.trackFor(sampleRate, channels, encoding) == null) {
                            exitReason = "AudioTrack allocation failed (pass=$pass)"
                            return
                        }
                        session.ensurePlaying()
                    }
                    if (!announcedStart) {
                        announcedStart = true
                        AppLog.d(
                            TAG,
                            "Video audio started: ${sampleRate}Hz ${channels}ch " +
                                "pcm=${if (encoding == VideoSound.PCM_ENCODING_FLOAT) "float" else "16bit"} " +
                                "buffer=${session.bufferMillis}ms mime=$mime"
                        )
                    }
                    // Samples held back by a pause are written first, so a
                    // hide/show cycle does not cut a chunk in half (a click).
                    if (!writePendingPcm(session, gen, pass)) continue
                    // Did this iteration hand the decoder new input? When it did
                    // not, the output dequeue below is the only thing left to
                    // wait for and may block (see AUDIO_OUTPUT_WAIT_US).
                    var fedInput = false
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(0)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)
                            if (inBuf == null) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, 0)
                                fedInput = true
                            } else {
                                val size = extractor.readSampleData(inBuf, 0)
                                if (size < 0) {
                                    decoder.queueInputBuffer(
                                        inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                    fedInput = true
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                    fedInput = true
                                }
                            }
                        }
                    }
                    // Blocking wait when nothing was fed: the old loop polled
                    // with timeout 0 and only slept once the input was already
                    // exhausted, so a slow codec whose input queue was full
                    // (nothing to feed, no output ready yet) could spin here at
                    // 200Hz. Waiting for the codec costs no latency - it
                    // returns as soon as a frame is ready - and cuts the idle
                    // wakeups from one per 5ms to one per produced frame.
                    val outIdx = decoder.dequeueOutputBuffer(
                        info, if (fedInput) 0L else AUDIO_OUTPUT_WAIT_US
                    )
                    if (outIdx >= 0) {
                        try {
                            if (info.size > 0 &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                val outBuf = decoder.getOutputBuffer(outIdx)
                                if (outBuf != null &&
                                    !writePcm(session, outBuf, info.offset, info.size, gen, pass)
                                ) {
                                    if (powerSaveMode) {
                                        paused = true
                                        session.pause()
                                        AppLog.d(
                                            TAG,
                                            "Video audio paused (wallpaper not visible)"
                                        )
                                    } else if (videoGeneration.get() == gen && videoSoundEnabled) {
                                        // The video reached its next playback pass
                                        // while this chunk was being written - a
                                        // normal loop boundary, not a reason to
                                        // stop making sound. Dropping the rest of
                                        // THIS pass and waiting for the next one
                                        // keeps the audio alive across loops; the
                                        // old code killed the whole thread here,
                                        // which is why looping videos could fall
                                        // silent after their first pass (the
                                        // context line in the crash-free log said
                                        // "PCM write refused (pass=1)" while the
                                        // counter had already moved to 2).
                                        passDone = true
                                        exitReason = "pass boundary (pass=$pass)"
                                    } else {
                                        exitReason = "PCM write refused (pass=$pass)"
                                        return
                                    }
                                }
                            }
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                passDone = true
                            }
                        } finally {
                            try { decoder.releaseOutputBuffer(outIdx, false) } catch (_: Throwable) {}
                        }
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // The decoder may output a different rate/channel count
                        // than the container declares (HE-AAC, downmixes): trust
                        // the output format, otherwise the PCM is reinterpreted
                        // at the wrong speed and sounds broken.
                        val outFormat = try { decoder.outputFormat } catch (_: Throwable) { null }
                        if (outFormat != null) {
                            val rate = VideoSound.sampleRateFor(
                                outFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE)
                            )
                            val ch = VideoSound.playbackChannelsFor(
                                outFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT)
                            )
                            // Missing KEY_PCM_ENCODING means 16-bit PCM.
                            val enc = VideoSound.playbackEncodingFor(
                                outFormat.getIntegerSafe(MediaFormat.KEY_PCM_ENCODING)
                            )
                            if (rate != sampleRate || ch != channels || enc != encoding) {
                                AppLog.d(
                                    TAG,
                                    "Video audio format: ${rate}Hz ${ch}ch " +
                                        "pcm=${if (enc == VideoSound.PCM_ENCODING_FLOAT) "float" else "16bit"}"
                                )
                                sampleRate = rate
                                channels = ch
                                encoding = enc
                                session.trackFor(rate, ch, enc)
                                session.ensurePlaying()
                            }
                        }
                    }
                }
                if (videoGeneration.get() != gen) break
            }
        } catch (t: Throwable) {
            AppLog.d(TAG, "Video audio pass ended: ${t.message}")
        } finally {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            try { afd?.close() } catch (_: Throwable) {}
            audioPending = null
            // Reason on the same line: "why did the sound stop" was guesswork in
            // the logs whenever the thread ended between two passes.
            AppLog.d(
                TAG,
                "Video audio: thread finished ($exitReason; gen=$gen interrupted=" +
                    "${Thread.currentThread().isInterrupted} sound=$videoSoundEnabled " +
                    "currentGen=${videoGeneration.get()} passes=${videoPassCounter.get()})"
            )
        }
    }

    /**
     * Write decoded PCM without ever blocking.
     *
     * A blocking write would be fatal here: while the wallpaper is hidden the
     * AudioTrack is paused, so the buffer never drains and the audio thread would
     * hang until the screen came back. Non-blocking writes plus a short retry
     * sleep keep the thread responsive to pauses and switches.
     *
     * A chunk that is interrupted by a pause is NOT thrown away: the tail is
     * copied into [audioPending] and written again after the pause, so a
     * hide/show cycle does not clip a chunk in half (audible as a tick).
     *
     * @return true when the whole chunk was written or safely held back, false
     *   when the pass is no longer current (the audio is going away anyway).
     */
    private fun writePcm(
        session: AudioSession,
        buf: ByteBuffer,
        offset: Int,
        size: Int,
        gen: Int,
        pass: Int
    ): Boolean {
        val track = session.track
        buf.position(offset)
        buf.limit(offset + size)
        var written = 0
        while (written < size) {
            if (!audioWriteStillWanted(gen, pass)) return false
            if (powerSaveMode || track == null) {
                holdBackPcm(buf)
                return true
            }
            val n = writeToTrack(track, buf, size - written)
            if (n < 0) return false
            if (n == 0) {
                // Buffer full: the track is draining it in real time.
                Thread.sleep(AUDIO_WRITE_RETRY_MS)
            } else {
                written += n
            }
        }
        return true
    }

    /**
     * Write PCM that a pause forced us to hold back, before the next chunk.
     *
     * @return false when the caller should re-evaluate (paused again/stopped).
     */
    private fun writePendingPcm(session: AudioSession, gen: Int, pass: Int): Boolean {
        val pending = audioPending ?: return true
        if (powerSaveMode) return false
        val track = session.track ?: return true
        val remaining = pending.remaining()
        if (remaining <= 0) {
            audioPending = null
            return true
        }
        val n = writeToTrack(track, pending, remaining)
        if (n < 0) {
            audioPending = null
            return false
        }
        if (pending.remaining() <= 0) {
            audioPending = null
            return true
        }
        // Buffer full again: keep the rest for the next iteration.
        Thread.sleep(AUDIO_WRITE_RETRY_MS)
        return !powerSaveMode && audioWriteStillWanted(gen, pass)
    }

    /**
     * Write [size] bytes from [buf] to [track], leaving [buf]'s position exactly
     * [n] bytes further along.
     *
     * The framework advances the position itself when it accepts bytes, but that
     * is an implementation detail of the write mode; normalising it here keeps
     * the streaming loop correct either way (a position that did not advance
     * would re-send the same samples forever - which sounds like a distorted,
     * stuttering loop).
     *
     * @return bytes accepted, or -1 when the track refused the call.
     */
    private fun writeToTrack(track: AudioTrack, buf: ByteBuffer, size: Int): Int {
        if (size <= 0) return 0
        val before = buf.position()
        val n = try {
            track.write(buf, size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: Throwable) {
            return -1
        }
        val advanced = when {
            buf.position() != before -> buf.position() - before
            n > 0 -> n
            else -> 0
        }
        buf.position(before + advanced)
        return advanced
    }

    /** Copy the not-yet-written tail of [buf] into [audioPending]. */
    private fun holdBackPcm(buf: ByteBuffer) {
        val remaining = buf.remaining()
        if (remaining <= 0) return
        val existing = audioPending
        val dst = if (existing != null && existing.capacity() >= remaining) {
            existing
        } else {
            ByteBuffer.allocate(remaining)
        }
        dst.clear()
        dst.put(buf)
        dst.flip()
        audioPending = dst
    }

    private fun audioWriteStillWanted(gen: Int, pass: Int): Boolean =
        videoGeneration.get() == gen && videoPassCounter.get() == pass &&
            videoSoundEnabled && !Thread.currentThread().isInterrupted

    /**
     * Open the media descriptor for the audio pass on a helper thread with a
     * timeout, mirroring the video path: a cloud SAF provider can block for
     * dozens of seconds and the audio thread must stay interruptible.
     */
    private fun openAudioDescriptor(uriStr: String): AssetFileDescriptor? {
        val result = java.util.concurrent.atomic.AtomicReference<AssetFileDescriptor?>(null)
        val abandon = AtomicBoolean(false)
        val helper = Thread({
            try {
                val afd = context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r")
                if (abandon.get()) {
                    try { afd?.close() } catch (_: Exception) {}
                } else {
                    result.set(afd)
                    // The timeout can fire between the check above and this publish:
                    // the caller has already given up (it returned null), so nobody
                    // else would ever close this descriptor - one leaked fd per
                    // timed-out open, which accumulates into TooManyOpenFiles on the
                    // flaky cloud providers this code exists for. Take it back out and
                    // close it here; compareAndSet keeps the two sides from closing it
                    // twice.
                    if (abandon.get() && result.compareAndSet(afd, null)) {
                        try { afd?.close() } catch (_: Exception) {}
                    }
                }
            } catch (t: Throwable) {
                AppLog.d(TAG, "Video audio source open failed: ${t.message}")
            }
        }, "VideoAudioOpen").apply {
            isDaemon = true
            start()
        }
        try {
            helper.join(AUDIO_OPEN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            abandon.set(true)
            helper.interrupt()
            Thread.currentThread().interrupt()
            return null
        }
        if (helper.isAlive) {
            abandon.set(true)
            helper.interrupt()
            AppLog.d(TAG, "Timed out opening the video's audio stream")
            return null
        }
        return result.get()
    }

    /**
     * One AudioTrack, reused across playback passes (and across the pause/resume
     * pairs) so looping does not re-allocate a track every time.
     */
    private class AudioSession {
        // Written by the audio thread, read/paused/released from the engine thread
        // (stopAudio(), release()). Without @Volatile the engine could keep acting
        // on a stale track - i.e. fail to silence the old video, or write into a
        // released AudioTrack.
        @Volatile
        var track: AudioTrack? = null
            private set
        /** Buffer length in ms, for the start-up log (diagnostics). */
        @Volatile
        var bufferMillis = 0
            private set
        private var sampleRate = 0
        private var channels = 0
        private var encoding = VideoSound.PCM_ENCODING
        @Volatile
        private var playing = false
        /**
         * Set while the engine must stay silent for a reason OUTSIDE the media
         * ("our own UI is in the foreground"). [pause] alone is not enough: the
         * audio thread calls [ensurePlaying]/[resume]/[restart] on every playback
         * pass, on a decoder format change and on the first visible frame, and any
         * of those would turn the sound back on behind the UI. Those helpers
         * therefore refuse to play while this flag is set; only
         * [clearPolicyMute] (the engine's explicit unmute) lifts it.
         */
        @Volatile
        private var policyMuted = false

        fun trackFor(
            wantedSampleRate: Int,
            wantedChannels: Int,
            wantedEncoding: Int = VideoSound.PCM_ENCODING
        ): AudioTrack? {
            val existing = track
            if (existing != null && wantedSampleRate == sampleRate &&
                wantedChannels == channels && wantedEncoding == encoding
            ) {
                return existing
            }
            release()
            val created = create(wantedSampleRate, wantedChannels, wantedEncoding) ?: return null
            track = created
            sampleRate = wantedSampleRate
            channels = wantedChannels
            encoding = wantedEncoding
            return created
        }

        /**
         * Start (or restart) playback with an empty buffer. Only for a NEW media:
         * flushing between playback passes would cut the music every loop.
         */
        fun restart() {
            val t = track ?: return
            try {
                t.pause()
                t.flush()
                // A new media still drops the previous media's buffered samples
                // (otherwise they would be heard later, when the UI leaves), but
                // it does not play while the engine is policy-muted.
                if (policyMuted) {
                    playing = false
                } else {
                    t.play()
                    playing = true
                }
            } catch (_: Throwable) {
            }
        }

        /** Make sure a (possibly paused) track is playing again. */
        fun ensurePlaying() {
            if (policyMuted) return
            if (playing) return
            resume()
        }

        /** Silence for a reason outside the media (see [policyMuted]). */
        fun muteForPolicy() {
            policyMuted = true
            pause()
        }

        /** Lift [muteForPolicy]; the caller re-starts the audio itself. */
        fun clearPolicyMute() {
            policyMuted = false
        }

        fun pause() {
            if (!playing) return
            playing = false
            try { track?.pause() } catch (_: Throwable) {}
        }

        fun resume() {
            if (policyMuted) return
            val t = track ?: return
            try { t.play() } catch (_: Throwable) {}
            playing = true
        }

        fun release() {
            val t = track ?: return
            track = null
            playing = false
            policyMuted = false
            try { t.pause() } catch (_: Throwable) {}
            try { t.flush() } catch (_: Throwable) {}
            try { t.release() } catch (_: Throwable) {}
            sampleRate = 0
            channels = 0
        }

        private fun create(
            wantedSampleRate: Int,
            wantedChannels: Int,
            wantedEncoding: Int
        ): AudioTrack? {
            return try {
                val mask = VideoSound.channelMaskFor(wantedChannels)
                val bytesPerSample = VideoSound.bytesPerSampleFor(wantedEncoding)
                val minBytes = AudioTrack.getMinBufferSize(
                    wantedSampleRate, mask, wantedEncoding
                )
                val bufferBytes = VideoSound.bufferBytesFor(
                    if (minBytes > 0) minBytes else 0,
                    wantedSampleRate,
                    wantedChannels,
                    bytesPerSample
                )
                bufferMillis = (bufferBytes.toLong() * 1_000L /
                    (wantedSampleRate.toLong() * wantedChannels.coerceAtLeast(1) *
                        bytesPerSample).coerceAtLeast(1L)).toInt()
                val attrs = AudioAttributes.Builder()
                    // USAGE_MEDIA + CONTENT_TYPE_MOVIE: the system media volume
                    // (and mute) applies, exactly like any other video sound.
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(wantedEncoding)
                    .setSampleRate(wantedSampleRate)
                    .setChannelMask(mask)
                    .build()
                val created = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (created.state != AudioTrack.STATE_INITIALIZED) {
                    try { created.release() } catch (_: Throwable) {}
                    AppLog.e(TAG, "AudioTrack was not initialized (video sound off)")
                    return null
                }
                // Report the buffer the framework really allocated, not just the
                // one requested: that is what tells crackling apart from silence.
                try {
                    val frames = created.bufferSizeInFrames
                    if (frames > 0 && wantedSampleRate > 0) {
                        bufferMillis = (frames.toLong() * 1_000L / wantedSampleRate).toInt()
                    }
                } catch (_: Throwable) {
                }
                created.setVolume(1f)
                created
            } catch (t: Throwable) {
                AppLog.e(TAG, "AudioTrack create failed: ${t.message}")
                null
            }
        }
    }

    /**
     * Decode loop — runs on dedicated decode thread.
     * MediaExtractor + MediaCodec I/O only. No GL operations here.
     */
    private fun decodeLoop(uriStr: String, scaleMode: ScaleMode, gen: Int, handler: Handler) {
        // Use local variables to avoid race with new decode thread's instance fields.
        var localExtractor: MediaExtractor? = null
        var localDecoder: MediaCodec? = null
        var localAfd: AssetFileDescriptor? = null
        // Reset the health-monitor timestamp for this video: lastVideoFrameAt
        // is shared across videos, and a stale value from the previous video
        // made the engine misjudge a healthy video as stalled.
        lastVideoFrameAt = 0L
        try {
            // Outer loop: restart the codec cleanly when the video loops.
            // Flushing and re-feeding an in-place codec can crash some hardware
            // decoders during repeat playback, which is how the engine died
            // while just playing (no switch involved) in the captured logs.
            // Prefer an AssetFileDescriptor: MediaExtractor streaming through
            // a ContentResolver on cloud-mounted SAF URIs (e.g. PikPak) can
            // block for tens of seconds, which made the engine look dead.
            // Opening a cloud-hosted document can itself block for tens of
            // seconds, so run it on a helper thread with a 15s timeout:
            // switching must never be stuck on an unresponsive provider.
            // The descriptor is opened ONCE and reused across loop passes so
            // cloud files are not re-opened on every playback loop.
            val openResult = java.util.concurrent.atomic.AtomicReference<AssetFileDescriptor?>(null)
            val openError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            // Set when the 15s timeout fires while the helper thread is still
            // blocked in openAssetFileDescriptor: if it later returns, the
            // descriptor is closed immediately instead of leaking.
            val abandonOpen = java.util.concurrent.atomic.AtomicBoolean(false)
            val openThread = Thread({
                try {
                    val afd = context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r")
                    if (abandonOpen.get()) {
                        try { afd?.close() } catch (_: Exception) {}
                    } else {
                        openResult.set(afd)
                        // Same race as openAudioDescriptor: the 15s timeout can land
                        // between the check and this publish, and the waiting caller
                        // then reports failure without ever seeing the descriptor.
                        // Close it ourselves unless the caller won the handoff.
                        if (abandonOpen.get() && openResult.compareAndSet(afd, null)) {
                            try { afd?.close() } catch (_: Exception) {}
                        }
                    }
                } catch (t: Throwable) {
                    openError.set(t)
                }
            }, "VideoOpen").apply {
                // Never keep the process alive because a cloud provider is
                // unresponsive.
                isDaemon = true
                start()
            }
            try {
                openThread.join(15_000)
            } catch (_: InterruptedException) {
                abandonOpen.set(true)
                openThread.interrupt()
                Thread.currentThread().interrupt()
                return
            }
            if (openThread.isAlive) {
                abandonOpen.set(true)
                openThread.interrupt()
                AppLog.e(TAG, "Timed out opening video stream: ${LogText.short(uriStr)}")
                if (videoGeneration.get() == gen) {
                    isVideoPlaying = false
                    onVideoStartFailed?.invoke()
                }
                return
            }
            val openErr = openError.get()
            if (openErr != null) {
                AppLog.e(TAG, "Failed to open video stream: ${LogText.short(uriStr)}", openErr)
                if (videoGeneration.get() == gen) {
                    isVideoPlaying = false
                    onVideoStartFailed?.invoke()
                }
                return
            }
            val afd = openResult.get()
            if (afd == null) {
                AppLog.e(TAG, "Cannot open video stream: ${LogText.short(uriStr)}")
                if (videoGeneration.get() == gen) {
                    isVideoPlaying = false
                    onVideoStartFailed?.invoke()
                }
                return
            }
            localAfd = afd
            // Some pickers (Google Photos / third-party SAF providers on
            // non-Xiaomi devices) hand out descriptors with UNKNOWN length or
            // that are not seekable; MediaExtractor then fails and the video
            // stays black. Fall back to copying the stream into the app cache
            // and decoding from that seekable file.
            var copiedVideoPath: String? = null
            var errorPasses = 0
            var giveUp = false
            // ---- Warm session, reused across loop passes of the SAME file ----
            //
            // The old code created a MediaCodec + MediaExtractor + a fresh
            // SurfaceTexture on EVERY playback pass, so a 5s clip paid a full
            // codec create/configure/start (~30-80ms) and a GL rebuild at every
            // loop point - visible as a hitch (and, with the old framebuffer
            // clear, a black flash). A loop restart now only flushes the codec
            // and rewinds the extractor; a switch to a DIFFERENT file still
            // rebuilds everything (a codec cannot change format).
            var sessionExtractor: MediaExtractor? = null
            var sessionDecoder: MediaCodec? = null
            var reuseSession = false
            while (videoGeneration.get() == gen && !Thread.interrupted() && !giveUp) {
                // --- Setup MediaExtractor ---
                // On a loop restart the warm extractor is kept AS IS: the source
                // is already attached and only needs a rewind, which the codec
                // block below does with seekTo(0). Re-calling setDataSource on an
                // extractor that already reached EOF threw
                // `IOException: Failed to instantiate extractor` on the tablet,
                // which turned every loop point into a first-frame fallback +
                // recovery switch - i.e. the clip never looped.
                val ext = sessionExtractor ?: MediaExtractor()
                localExtractor = ext
                if (sessionExtractor !== ext) {
                    // Fresh session: attach the source. Use the descriptor's
                    // offset/length - cloud-hosted or container-backed documents
                    // can expose a non-zero start offset, and decoding from the
                    // beginning would fail or read the wrong bytes.
                    try {
                        val existingCopy = copiedVideoPath
                        if (existingCopy != null) {
                            // A previous attempt already copied this stream into
                            // the cache (the descriptor is not seekable): use that
                            // file instead of copying it again.
                            ext.setDataSource(existingCopy)
                        } else if (afd.length > 0L) {
                            ext.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        } else {
                            // length == UNKNOWN_LENGTH: let the framework fstat()
                            // the descriptor instead of passing -1 as the length.
                            ext.setDataSource(afd.fileDescriptor)
                        }
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "Video source not seekable, copying to cache: ${LogText.short(uriStr)}", t)
                        val cached = copyVideoToCache(uriStr)
                        if (cached == null) {
                            AppLog.e(TAG, "Video cache fallback failed: ${LogText.short(uriStr)}")
                            if (videoGeneration.get() == gen) {
                                isVideoPlaying = false
                                onVideoStartFailed?.invoke()
                            }
                            return
                        }
                        copiedVideoPath = cached.absolutePath
                        if (videoGeneration.get() == gen) {
                            // The audio path prefers this copy: it is a seekable
                            // local file, so the sound never re-opens a cloud
                            // provider (which could block for seconds).
                            currentVideoCachePath = cached.absolutePath
                        }
                        ext.setDataSource(cached.absolutePath)
                    }
                }
                val trackIdx = (0 until ext.trackCount).firstOrNull { i ->
                    ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: run {
                    AppLog.e(TAG, "No video track")
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }
                ext.selectTrack(trackIdx)
                // "接着上次位置继续播放": the FIRST round of a startVideo() that
                // carried a remembered position begins there instead of at 0.
                // Later rounds fall into the warm-session branch below and rewind
                // to 0, so the clip still loops from the beginning.
                val resumeUs = pendingStartPositionUs
                if (resumeUs > 0L) {
                    pendingStartPositionUs = 0L
                    try {
                        ext.seekTo(resumeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        AppLog.d(
                            TAG,
                            "Video resumes at ${resumeUs / 1000}ms (kept position, not the start)"
                        )
                    } catch (t: Throwable) {
                        // A source that refuses the seek simply plays from the
                        // start (the old behaviour) instead of failing.
                        AppLog.w(TAG, "Video resume seek failed: ${t.message}")
                    }
                }
                val format = ext.getTrackFormat(trackIdx)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime == null) {
                    AppLog.e(TAG, "Video track has no MIME type")
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }
                var videoW = format.getIntegerSafe(MediaFormat.KEY_WIDTH)
                var videoH = format.getIntegerSafe(MediaFormat.KEY_HEIGHT)
                // Videos with a 90/270 degree rotation (e.g. portrait phone
                // recordings) display with swapped width/height — needed up
                // front to compute the FIT decode cap below.
                val rotation = format.getIntegerSafe(MediaFormat.KEY_ROTATION)
                val isRotated = rotation == 90 || rotation == 270
                val quadW = if (isRotated) videoH else videoW
                val quadH = if (isRotated) videoW else videoH
                val maxDim = maxOf(videoW, videoH)
                // Decode to at most the screen resolution. This keeps the
                // rendered picture pixel-identical to the source on the actual
                // display (the old fixed 1280px cap made large videos blurry)
                // while avoiding the wasted power/memory of decoding far
                // larger sources (4K/8K videos) at full size.
                val screenWpx = context.resources.displayMetrics.widthPixels
                val screenHpx = context.resources.displayMetrics.heightPixels
                // The engine may turn the video another 90° (auto rotate
                // mismatch, see refreshVideoQuad): what lands on screen is then
                // the swapped rect, so the FIT size - and the decode cap derived
                // from it - has to use those dims. Without this a 4K landscape
                // clip shown rotated on a portrait screen was decoded at
                // 1500x842 (fitted WITHOUT the turn) and then magnified 1.7x by
                // the GPU - the "适应模式下视频发虚" case.
                val willTurn = autoRotateMismatch && quadW > 0 && quadH > 0 &&
                    quadW != quadH && ((quadW > quadH) != (screenWpx > screenHpx))
                // One shared rule with the image decode (see displaySpan): what
                // lands on screen after the engine's own quarter turn.
                val (fitW, fitH) = com.wallpaperswitcher.engine.BitmapUtils
                    .displaySpan(quadW, quadH, willTurn)
                val screenMax = maxOf(screenWpx, screenHpx)
                val baseCap = minOf(screenMax, 3200).coerceAtLeast(1280)
                // FIT letterboxes the video: it is displayed at the fitted
                // size, so capping to the raw screen max over-decodes
                // aspect-mismatched videos (e.g. a landscape video on a
                // portrait phone is shown ~1080 wide but used to decode up to
                // 2400, ~5x the pixels). Cap to the fitted size with a 1.25x
                // quality headroom — the GPU then only downscales, never
                // magnifies. FILL/STRETCH keep the full-screen cap (with a
                // 1920 floor so small sources stay sharp when magnified).
                val decodeCapBase = when (scaleMode) {
                    ScaleMode.FIT -> {
                        val fitScale = if (fitW > 0 && fitH > 0) {
                            minOf(screenWpx.toFloat() / fitW, screenHpx.toFloat() / fitH)
                        } else 1f
                        val fittedLong =
                            maxOf(fitW * fitScale, fitH * fitScale).toInt().coerceAtLeast(1)
                        (fittedLong * 1.25f).toInt().coerceIn(1280, baseCap)
                    }
                    ScaleMode.FILL, ScaleMode.STRETCH ->
                        minOf(screenMax, 3200).coerceAtLeast(1920)
                }
                // Frame rate does NOT change the decode resolution: 50/60fps
                // sources are decoded exactly like any other media, i.e. by the
                // screen-pixel rule above (FIT fits the screen, FILL/STRETCH
                // uses the screen max). A 60fps source therefore keeps full
                // sharpness; on a device whose GPU cannot fill the screen at
                // 60fps the presentation rate simply follows the GPU, the
                // playback speed stays 1:1 (pacing follows the frame
                // timestamps).
                val decodeCap = decodeCapBase
                if (maxDim > decodeCap) {
                    val scale = decodeCap.toFloat() / maxDim
                    // Even dimensions keep codec/SurfaceTexture happy, but the
                    // even-bit mask must never produce 0 (a 1px result would
                    // become 0 and break setDefaultBufferSize/decode).
                    videoW = (videoW * scale).toInt().and(0xFFFFFFFE.toInt()).coerceAtLeast(2)
                    videoH = (videoH * scale).toInt().and(0xFFFFFFFE.toInt()).coerceAtLeast(2)
                }
                // Playback pacing follows the source frame timestamps (see the
                // decode loop below); this is only a floor so a very high-fps
                // source cannot spin the loop. KEY_FRAME_RATE is missing on
                // plenty of containers (SAF/ffmpeg files, variable-rate
                // recordings) and guessing 15fps for them made a 30fps clip play
                // in slow motion while a 1.5fps clip raced through its loop
                // every ~0.8s (a repeating "Video started" in the logs).
                val minFrameGapNs = 16_000_000L

                // --- Setup GL texture + SurfaceTexture on render thread ---
                val setupLatch = CountDownLatch(1)
                var setupOk = false
                var setupAttempts = 0
                // A warm session already owns a SurfaceTexture + texture bound
                // to its codec, so a loop restart has nothing to rebuild here.
                val reuseGl = reuseSession && sessionDecoder != null &&
                    surfaceTexture != null && codecSurface != null && videoTexId != 0
                lateinit var attemptVideoSetup: () -> Unit
                attemptVideoSetup = {
                    if (reuseGl) {
                        setupOk = true
                        setupLatch.countDown()
                    } else {
                        handler.post {
                            try {
                                if (videoGeneration.get() != gen) {
                                    setupLatch.countDown()
                                    return@post
                                }
                                if (!surfaceReady || !contextReady) {
                                    // The EGL surface is recreated on rotation (and can
                                    // briefly be unavailable). Retry for ~1.6s instead
                                    // of failing the whole video: on non-Xiaomi devices
                                    // failing here left the wallpaper black for
                                    // seconds until the recovery switch.
                                    setupAttempts++
                                    if (setupAttempts <= 8) {
                                        AppLog.d(TAG, "Video GL setup waiting for surface (attempt $setupAttempts)")
                                        handler.postDelayed(attemptVideoSetup, 200L)
                                    } else {
                                        AppLog.e(TAG, "Video GL setup failed: surface never became ready")
                                        setupLatch.countDown()
                                    }
                                    return@post
                                }
                                if (videoTexId == 0) {
                                    val texIds = IntArray(1)
                                    GLES20.glGenTextures(1, texIds, 0)
                                    videoTexId = texIds[0]
                                }
                                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
                                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        
                                // Some non-Xiaomi devices/drivers fail to compile the
                                // external-texture shader or lose the program after a
                                // context loss. Without this guard the decoder keeps
                                // running while renderVideoFrame() skips every frame,
                                // leaving the wallpaper black for the health monitor's
                                // 15s window. Fail fast so the engine recovers with a
                                // different media (usually a static image) instead.
                                if (videoProgram == 0) {
                                    AppLog.e(TAG, "Video shader program unavailable; aborting video setup")
                                    setupLatch.countDown()
                                    return@post
                                }
        
                                val st = SurfaceTexture(videoTexId)
                                st.setDefaultBufferSize(videoW, videoH)
                                surfaceTexture = st
                                codecSurface = Surface(st)
                                // Remember the on-screen size + scale mode so the frame
                                // renderer can sharpen low-res videos that are magnified
                                // to fill the screen.
                                videoDisplayW = quadW.toFloat()
                                videoDisplayH = quadH.toFloat()
                                videoSrcW = videoW.toFloat()
                                videoSrcH = videoH.toFloat()
                                videoScaleMode = scaleMode
                                // Do NOT clear the framebuffer here.
                                //
                                // This used to swap a fully black frame "so the previous
                                // video's frame cannot linger", which showed up as a
                                // black flash on every image→video switch and on every
                                // loop restart (the codec is recreated per pass, so the
                                // flash happened every few seconds for short clips).
                                // Keeping the previous frame on screen until the new
                                // video's first frame is presented is both seamless
                                // (looping) and less jarring (a switch keeps the old
                                // wallpaper visible instead of flashing black).
                                // A video that never produces a frame is handled by the
                                // engine's recovery (first-frame fallback + media switch)
                                // rather than by painting black over it.
                                setupOk = true
                                setupLatch.countDown()
                            } catch (t: Throwable) {
                                AppLog.e(TAG, "Video GL setup failed", t)
                                setupLatch.countDown()
                            }
                        }
                    }
                }
                attemptVideoSetup()
                try { setupLatch.await(6, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                if (!setupOk || videoGeneration.get() != gen) {
                    // Being superseded by a newer video (switch/rotation right
                    // after this one started) is the normal teardown path, not a
                    // failure: logging it as an error buried the real ones in the
                    // tablet log.
                    if (videoGeneration.get() != gen) {
                        AppLog.d(TAG, "Video GL setup abandoned (superseded)")
                    } else {
                        AppLog.e(TAG, "Video GL setup failed")
                    }
                    // Only reset engine state when THIS video is still the
                    // current one. A superseded setup must not stop a newer
                    // video that is already decoding/playing.
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }
                // A concurrent stopVideoAndRender/stopVideoInternal may have
                // already posted cleanup that nulled the shared fields right
                // after our generation check. Capture the references now and
                // bail out if they are gone instead of hitting an NPE in
                // configure() or the decode loop (seen in the logs as
                // decodeLoop NullPointerException right after a timed switch).
                val st = surfaceTexture
                val cs = codecSurface
                if (st == null || cs == null || videoGeneration.get() != gen) {
                    AppLog.e(TAG, "Video resources torn down during setup")
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }

                // --- Setup MediaCodec on THIS thread (decode thread) ---
                val warm = if (reuseSession) sessionDecoder else null
                val dec: MediaCodec
                if (warm != null) {
                    // Loop restart of the same file: keep the warmed codec and
                    // rewind it instead of re-creating the decoder + surface.
                    dec = try {
                        warm.flush()
                        // Some vendor decoders want the SPS/PPS again after a
                        // flush (the same workaround ExoPlayer applies).
                        requeueCodecSpecificData(warm, format)
                        ext.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        AppLog.d(TAG, "Video loop restart (codec + GL reused, no re-init)")
                        warm
                    } catch (t: Throwable) {
                        // A codec that refuses a flush must not be used again:
                        // drop the whole warm session (codec + extractor + the
                        // GL resources bound to it) and rebuild from scratch on
                        // the next iteration - the same path a new video takes.
                        AppLog.w(
                            TAG,
                            "Codec reuse failed (${t.javaClass.simpleName}: ${t.message}); " +
                                "rebuilding the video session"
                        )
                        try { warm.stop() } catch (_: Exception) {}
                        try { warm.release() } catch (_: Exception) {}
                        if (decoder === warm) decoder = null
                        if (localDecoder === warm) localDecoder = null
                        sessionExtractor?.let { e -> try { e.release() } catch (_: Exception) {} }
                        if (localExtractor === sessionExtractor) localExtractor = null
                        sessionDecoder = null
                        sessionExtractor = null
                        reuseSession = false
                        handler.post {
                            try {
                                if (videoGeneration.get() == gen) {
                                    cleanupVideoResourcesOnRenderThread()
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        continue
                    }
                } else {
                    dec = MediaCodec.createDecoderByType(mime)
                    dec.configure(format, cs, null, 0)
                    dec.start()
                }
                localDecoder = dec
                decoder = dec
                // Audio/video re-anchor: a new playback pass starts here, so the
                // audio thread may start (or restart) its own pass. Kept after
                // dec.start() so a pass that fails to set up never makes noise.
                signalVideoPassStart(gen)

                // Cache render quad on render thread
                handler.post {
                    try {
                        if (videoGeneration.get() != gen) return@post
                        refreshVideoQuad(quadW.toFloat(), quadH.toFloat(), scaleMode)
                        AppLog.d(TAG, "Video quad set: video=${quadW}x${quadH} mode=$scaleMode " +
                                "screen=${screenW.toInt()}x${screenH.toInt()} rotate=$videoExtraRotate")
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "Video quad computation failed", t)
                    }
                }

                val codecName = try { dec.name } catch (_: Exception) { "unknown" }
                // KEY_FRAME_RATE is informational only now (pacing uses the
                // frame timestamps), and it is missing on some containers.
                val declaredFps = format.getIntegerSafe(MediaFormat.KEY_FRAME_RATE)
                AppLog.d(
                    TAG,
                    "Video started: ${videoW}x${videoH} @ " +
                        "${if (declaredFps > 0) "${declaredFps}fps" else "unspecified fps"} codec=$codecName"
                )

                // --- Inner decode loop (one playback pass) ---
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var eof = false
                // Frames presented during THIS pass; a reused pass that presents
                // nothing drops the session so the next pass rebuilds it (see the
                // safety net after the decode loop).
                var passFramesPresented = 0L
                // Presentation clock for this playback pass: frames are paced
                // against their own presentation timestamps so the clip plays
                // at its real speed regardless of the container metadata.
                var passStartNs = -1L
                var firstPtsUs = -1L
                var lastPresentNs = 0L
                // Paused on purpose while the wallpaper is not visible (see
                // below). Tracked so the pause/resume pair is logged once.
                var pausedForVisibility = false
                while (videoGeneration.get() == gen && !Thread.interrupted() && !eof) {
                    try {
                        // Not visible (screen off / another app in front / the
                        // system live-wallpaper dialog): pause COMPLETELY.
                        //
                        // The old behaviour kept a ~1fps slideshow running,
                        // which meant the decoder, the GL upload and the power
                        // all kept working for a wallpaper nobody could see.
                        // Nothing is dequeued here, so the codec stalls by
                        // itself (its output queue fills up and it stops
                        // decoding) and playback resumes from the exact frame
                        // it stopped on - the pacing clock is re-anchored on
                        // the first frame after the pause, so the clip never
                        // fast-forwards to "catch up" either.
                        if (powerSaveMode) {
                            if (!pausedForVisibility) {
                                pausedForVisibility = true
                                // One line per hidden episode, not per playback
                                // pass (the timer may restart the video while it
                                // is hidden).
                                if (!videoPauseAnnounced) {
                                    videoPauseAnnounced = true
                                    AppLog.d(TAG, "Video paused (wallpaper not visible)")
                                }
                            }
                            // Wait for the visibility change to wake us instead
                            // of polling: a screen-off wallpaper then costs no
                            // wakeups at all (see powerSaveMode).
                            synchronized(pauseLock) {
                                if (powerSaveMode) {
                                    try {
                                        pauseLock.wait(PAUSE_WAIT_MAX_MS)
                                    } catch (_: InterruptedException) {
                                        // stopVideo()/engine teardown interrupts
                                        // this thread: restore the flag so the
                                        // loop conditions see it and exit.
                                        Thread.currentThread().interrupt()
                                    }
                                }
                            }
                            continue
                        }
                        if (pausedForVisibility) {
                            pausedForVisibility = false
                            if (videoPauseAnnounced) {
                                AppLog.d(TAG, "Video resumed (wallpaper visible again)")
                            }
                        }
                        if (!inputDone) {
                            // Pre-fill a few input buffers: on cloud-hosted
                            // files each readSampleData can block on the
                            // network, and serializing read->decode->read made
                            // playback stutter. Filling the codec input queue
                            // in batches keeps the reads continuous while the
                            // output cadence stays controlled by intervalNs
                            // (playback speed is unchanged).
                            var fed = 0
                            while (fed < 3 && !inputDone) {
                                val inIdx = dec.dequeueInputBuffer(0)
                                if (inIdx >= 0) {
                                    val buf = dec.getInputBuffer(inIdx) ?: break
                                    val size = ext.readSampleData(buf, 0)
                                    if (size < 0) {
                                        dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        inputDone = true
                                    } else {
                                        dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                                        ext.advance()
                                        fed++
                                    }
                                } else {
                                    break
                                }
                            }
                        }

                        val outIdx = dec.dequeueOutputBuffer(bufferInfo, 10_000)
                        if (outIdx >= 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                dec.releaseOutputBuffer(outIdx, false)
                                eof = true
                                continue
                            }

                           dec.releaseOutputBuffer(outIdx, true)
                           passFramesPresented++
                           // Remember the position for "接着上次位置继续播放"
                           // (read by the engine when it releases the session
                           // while the device is locked).
                           lastVideoPositionUs =
                               bufferInfo.presentationTimeUs.coerceAtLeast(0L)

                            // Only one pending render post at a time; if the
                            // render thread is busy, the newest frame simply
                            // supersedes the previous one (updateTexImage
                            // always picks up the latest buffer).
                            if (renderPostQueued.compareAndSet(false, true)) {
                                handler.post {
                                    renderPostQueued.set(false)
                                    if (videoGeneration.get() != gen) return@post
                                    if (!surfaceReady || !contextReady) return@post
                                    try {
                                        st.updateTexImage()
                                        st.getTransformMatrix(videoTexMatrix)
                                        renderVideoFrame(videoTexMatrix)
                                    } catch (t: Throwable) {
                                        // Once per frame while the SurfaceTexture or the GL
                                        // context is broken, and AppLog flushes every
                                        // important line to disk - an unthrottled throwable
                                        // here was 30-60 line+stack writes per second (see
                                        // FRAME_WARN_INTERVAL_MS). Shares that budget with
                                        // the other render warnings.
                                        val nowWarn = android.os.SystemClock.elapsedRealtime()
                                        if (nowWarn - lastFrameWarnAt > FRAME_WARN_INTERVAL_MS) {
                                            lastFrameWarnAt = nowWarn
                                            AppLog.e(TAG, "renderVideoFrame failed", t)
                                        }
                                    }
                                }
                            }

                            // Pace against the frame's own timestamp: the clip
                            // plays at its true speed even when the container
                            // does not report a frame rate. The floor keeps a
                            // 60fps+ source from spinning the loop. While the
                            // wallpaper is not visible the loop pauses before it
                            // ever gets here (see above), so this floor only
                            // ever applies to visible playback.
                            val nowNs = System.nanoTime()
                            if (passStartNs < 0L) {
                                passStartNs = nowNs
                                firstPtsUs = bufferInfo.presentationTimeUs
                            }
                            val ptsOffsetNs =
                                ((bufferInfo.presentationTimeUs - firstPtsUs) * 1000L)
                                    .coerceAtLeast(0L)
                            // Playback clock vs. wall clock: while throttled
                            // (power save) the frames are ~1s late by design, and
                            // after a freeze they can be seconds late. Re-anchor
                            // so the clip continues at its normal speed from the
                            // frame that is due now instead of fast-forwarding to
                            // catch up the time nobody could see.
                            val lagNs = nowNs - (passStartNs + ptsOffsetNs)
                            if (!powerSaveMode) powerSavePauseLogged = false
                            // Visible playback again: the next hidden episode
                            // announces itself.
                            if (!powerSaveMode) videoPauseAnnounced = false
                            if (lagNs > maxPlaybackLagNs) {
                                passStartNs = nowNs - ptsOffsetNs
                                if (powerSaveMode) {
                                    // One line per throttled episode instead of
                                    // one per frame (screen-off logs must stay
                                    // quiet).
                                    if (!powerSavePauseLogged) {
                                        powerSavePauseLogged = true
                                        AppLog.d(
                                            TAG,
                                            "Playback clock paused for power save " +
                                                "(${lagNs / 1_000_000}ms behind)"
                                        )
                                    }
                                } else {
                                    val elapsed = SystemClock.elapsedRealtime()
                                    if (elapsed - lastReanchorLogAt > 5_000L) {
                                        lastReanchorLogAt = elapsed
                                        AppLog.d(
                                            TAG,
                                            "Playback was ${lagNs / 1_000_000}ms behind; " +
                                            "re-anchored instead of fast-forwarding"
                                        )
                                    }
                                }
                            }
                            val floorNs = if (powerSaveMode) 1_000_000_000L else minFrameGapNs
                            val targetNs = maxOf(passStartNs + ptsOffsetNs, lastPresentNs + floorNs)
                            val sleepNs = targetNs - System.nanoTime()
                            if (sleepNs > 0) {
                                // InterruptedException is the normal "stop" signal.
                                try {
                                    Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                                } catch (_: InterruptedException) {}
                            }
                            lastPresentNs = targetNs
                        } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // 5ms poll interval instead of 1ms: with slow /
                            // cloud-hosted decoders this cuts idle CPU wakeups
                            // by 5x with no visible latency impact.
                            try { Thread.sleep(5) } catch (_: InterruptedException) {}
                        }
                    } catch (t: Throwable) {
                        // A codec can throw (e.g. IllegalStateException) when it
                        // is being torn down concurrently with a switch. End this
                        // pass cleanly: the round cleanup releases the codec and
                        // the outer loop retries (same generation) or exits
                        // (superseded). Never let this freeze the previous frame.
                        errorPasses++
                        if (errorPasses >= 3) {
                            giveUp = true
                            if (videoGeneration.get() == gen) onVideoStartFailed?.invoke()
                        }
                        // A superseded video's decoder is torn down under it; that
                        // is expected during a switch and must not look like an
                        // error (only a pass that failed while still current is).
                        if (videoGeneration.get() != gen) {
                            AppLog.d(TAG, "Decode pass ended (superseded)")
                        } else {
                            AppLog.e(TAG, "Decode pass interrupted", t)
                        }
                        eof = true
                    }
                }

                // Keep this pass's codec + extractor + SurfaceTexture alive when
                // the same file is about to loop again: the next pass then only
                // pays a flush + seek instead of a full rebuild. Everything is
                // released by the thread's own teardown (finally) once the video
                // is switched away, rotated, or the engine is destroyed.
                val keepWarm = eof && passFramesPresented > 0L &&
                    videoGeneration.get() == gen && !Thread.interrupted() && !giveUp
                reuseSession = keepWarm
                if (keepWarm) {
                    sessionExtractor = ext
                    sessionDecoder = dec
                } else {
                    // Release this round's codec cleanly.
                    try { dec.stop() } catch (_: Exception) {}
                    try { dec.release() } catch (_: Exception) {}
                    if (decoder === dec) decoder = null
                    if (localDecoder === dec) localDecoder = null
                    sessionDecoder = null
                    // Release this round's extractor too: it holds a file
                    // descriptor, and looping videos would otherwise accumulate an
                    // extractor + fd per playback pass until the thread exits.
                    try { ext.release() } catch (_: Exception) {}
                    if (localExtractor === ext) localExtractor = null
                    sessionExtractor = null
                    // The cache copy (if any) is only needed while decoding.
                    copiedVideoPath?.let { path ->
                        try { java.io.File(path).delete() } catch (_: Exception) {}
                        copiedVideoPath = null
                    }
                }

                if (videoGeneration.get() != gen || Thread.interrupted()) break
                // 视频播完再切: one notification per completed pass, on BOTH
                // loop paths. The warm path (codec + GL reused, which is the
                // normal loop of the same file) used to `continue` before the
                // eof block below, so the callback never ran and a held timed
                // switch waited forever while the clip looped.
                if (eof && passFramesPresented > 0L) {
                    try {
                        onVideoPassCompleted?.invoke()
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "onVideoPassCompleted callback failed", t)
                    }
                }
                if (keepWarm) {
                    // The warm session already has its GL resources; cleaning
                    // them here would also break the codec's surface, and the
                    // on-screen frame is kept until the next pass presents its
                    // own first frame (no black flash at the loop point).
                    // Only the per-pass frame-rate line is still emitted, so a
                    // looping video keeps the same diagnostics as before.
                    handler.post {
                        if (videoGeneration.get() == gen) logPassFrameRate()
                    }
                    continue
                }
                if (eof) {
                    // A pass that presented nothing (codec reuse rejected the
                    // rewound stream): drop the session so the next iteration
                    // rebuilds the decoder from scratch instead of looping
                    // forever without a frame.
                    if (warm != null && passFramesPresented == 0L) {
                        AppLog.w(
                            TAG,
                            "Reused video codec produced no frame; rebuilding the session"
                        )
                    }
                    // Loop: clean this round's GL resources, then the outer
                    // loop recreates the extractor + codec + SurfaceTexture.
                    handler.post {
                        // Re-check the generation when this actually runs: a
                        // newer video may have started since this EOF cleanup
                        // was queued, and destroying "its" shared GL resources
                        // would corrupt the new video's rendering.
                        if (videoGeneration.get() != gen) return@post
                        try {
                            cleanupVideoResourcesOnRenderThread()
                        } catch (t: Throwable) {
                            AppLog.e(TAG, "Video cleanup failed", t)
                        }
                    }
                    continue
                }
                break
            }
        } catch (t: Throwable) {
            // Catch Throwable (incl. OutOfMemoryError) so a decode failure can
            // never crash the whole process and kill the wallpaper engine.
            if (videoGeneration.get() == gen) {
                // The CURRENT video failed (e.g. codec configure raced a
                // cleanup). Tell the engine to reset lastDisplayedId and retry,
                // otherwise the previous frame stays frozen on screen forever.
                AppLog.e(TAG, "Decode error", t)
                isVideoPlaying = false
                onVideoStartFailed?.invoke()
            } else {
                // Superseded by a newer switch: expected during rapid
                // double-tap switching. The new video owns the screen, so this
                // is not an error.
                AppLog.d(TAG, "Decode thread superseded during setup", t)
            }
        } finally {
            // Only clear the playing flag when THIS thread is still the current
            // video. A superseded thread must never clobber a newer video's
            // state (this used to make the engine restart the new video).
            if (videoGeneration.get() == gen) isVideoPlaying = false
            try { localDecoder?.stop() } catch (_: Exception) {}
            try { localDecoder?.release() } catch (_: Exception) {}
            if (decoder === localDecoder) decoder = null
            try { localExtractor?.release() } catch (_: Exception) {}
            if (extractor === localExtractor) extractor = null
            try { localAfd?.close() } catch (_: Exception) {}
            // Only post GL cleanup if stopVideoInternal/stopVideoAndRender hasn't already done it.
            // Those methods set videoCleanupDone=true and post their own cleanup.
            // Posting here would race: an old decode thread that exits late could
            // clean up the NEW video's resources after its setup. The generation
            // check makes sure only the CURRENT video's thread may clean up.
            if (videoGeneration.get() == gen && !videoCleanupDone.getAndSet(true)) {
                handler.post {
                    try {
                        cleanupVideoResourcesOnRenderThread()
                    } catch (t: Throwable) {
                        AppLog.e(TAG, "Video cleanup failed", t)
                    }
                }
            }
        }
    }

    /**
     * Draw an opaque black quad covering the whole framebuffer. Called on the
     * render thread right before the media quad. Unlike glClear, this draws
     * real pixels into every region of the surface, so no stale content from a
     * previous video can survive in the FIT letterbox area.
     */
    private fun drawBlackBackground() {
        try {
            val bg = backgroundBuffer ?: return
            if (imageProgram == 0 || blackTexId == 0) return
            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return
            GLES20.glUseProgram(imageProgram)
            val texMatLoc = imageTexMatLoc
            val texLoc = imageTexLoc
            val posLoc = imagePosLoc
            val tcLoc = imageTcLoc
            GLES20.glUniformMatrix4fv(texMatLoc, 1, false, imageTexMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blackTexId)
            GLES20.glUniform1i(texLoc, 0)
            // Flat black must never be sharpened (uSharp=0 is identity).
            GLES20.glUniform1f(imageSharpLoc, 0f)
            GLES20.glUniform1f(imageEnhanceLoc, 0f)
            GLES20.glUniform1f(imageAlphaLoc, 1f)

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
    private fun renderVideoFrame(texMatrix: FloatArray) {
        try {
            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return
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
            if (videoProgram == 0 || videoTexId == 0) {
                // Throttled: this runs once per frame, and an unthrottled line
                // here (plus AppLog's per-line flush) would turn a rendering
                // fault into a disk-writing storm at 30-60 lines/second.
                if (now - lastFrameWarnAt > FRAME_WARN_INTERVAL_MS) {
                    lastFrameWarnAt = now
                    AppLog.w(TAG, "renderVideoFrame skipped: program=$videoProgram tex=$videoTexId")
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
            GLES20.glUseProgram(videoProgram)

            val texMatLoc = videoTexMatLoc
            val texLoc = videoTexLoc
            val posLoc = videoPosLoc
            val tcLoc = videoTcLoc

            GLES20.glUniformMatrix4fv(texMatLoc, 1, false, effectiveMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
            GLES20.glUniform1i(texLoc, 0)
            updateVideoScreenTexelDelta()
            GLES20.glUniform2f(videoTexelLoc, videoTexelX, videoTexelY)
            GLES20.glUniform1f(videoSharpLoc, sharpnessFor(videoDisplayW, videoDisplayH, videoScaleMode))
            GLES20.glUniform1f(
                videoEnhanceLoc,
                WallpaperGeometry.enhancementStrength(
                    videoDisplayW, videoDisplayH, screenW, screenH,
                    videoScaleMode, qualityEnhance,
                ),
            )
            GLES20.glUniform2f(
                videoSrcTexelLoc,
                if (videoSrcW > 0f) 1f / videoSrcW else 0f,
                if (videoSrcH > 0f) 1f / videoSrcH else 0f,
            )

            vertexBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            vertexBuffer?.position(2)
            GLES20.glEnableVertexAttribArray(tcLoc)
            GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (fadeAlpha > 0f) drawFadeOverlayNoSwap(fadeAlpha)
            val swapped = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
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
    private fun cleanupVideoResourcesOnRenderThread() {
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

    // ======== EGL: Context (once) + Surface (per recreation) ========

    private fun setupEglContext() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return
        // From here on this instance owns the display and must terminate it (see
        // eglInitializedHere).
        eglInitializedHere = true

        // Some devices/GPUs (e.g. Xiaomi/HyperOS tablets) reject the first
        // RGBA8888 + ES2 window config and eglCreateWindowSurface then fails,
        // which left the wallpaper black. Try a few configs in order:
        // RGBA8888 -> RGB888 (no alpha) -> RGB565, and use the first that
        // eglChooseConfig accepts.
        val configCandidates = arrayOf(
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
            ),
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
            ),
            intArrayOf(
                EGL14.EGL_RED_SIZE, 5, EGL14.EGL_GREEN_SIZE, 6, EGL14.EGL_BLUE_SIZE, 5,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
            )
        )
        eglConfig = null
        for (attribs in configCandidates) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
            if (configs[0] != null) {
                eglConfig = configs[0]
                break
            }
        }
        if (eglConfig == null) {
            AppLog.e(TAG, "No usable EGL config found")
            return
        }

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            AppLog.e(TAG, "EGL context creation failed")
            return
        }
        contextReady = true

        val surface = holder.surface
        if (surface != null && surface.isValid) createEglSurface()
    }

    private fun createEglSurface(attempt: Int = 0) {
        val surface = holder.surface
        if (surface == null || !surface.isValid) {
            // The Surface is transiently invalid while the display rotates on
            // some devices. Retry briefly instead of leaving the wallpaper
            // black until the next surface event.
            if (attempt < 3) {
                renderHandler?.postDelayed({ createEglSurface(attempt + 1) }, 200L)
            } else {
                AppLog.e(TAG, "Surface invalid after $attempt retries")
            }
            return
        }

        // A delayed retry scheduled during an EARLIER rotation can run after a
        // newer surface was already created successfully. Recreating it then
        // would only flicker/re-tear the healthy surface, so skip stale
        // retries when the renderer is already ready.
        if (attempt > 0 && surfaceReady && eglSurface != EGL14.EGL_NO_SURFACE) {
            return
        }

        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        val attribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            AppLog.e(TAG, "eglCreateWindowSurface failed (attempt $attempt): ${EGL14.eglGetError()}")
            if (attempt < 3) {
                renderHandler?.postDelayed({ createEglSurface(attempt + 1) }, 200L)
            }
            return
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            // Destroy the old context before creating a new one to avoid leak
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) { contextReady = false; return }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                contextReady = false; return
            }
            // A new GL context invalidates every object the old one created. The video
            // side has to be dropped with it: surfaceTexture / codecSurface / videoTexId
            // still named objects of the destroyed context, and reuseGl() only checks
            // that they are non-zero, so it kept them - updateTexImage() then failed on
            // every frame and the video stayed frozen on its last frame until a media
            // switch. This is a no-op when they are already cleared.
            cleanupVideoResourcesOnRenderThread()
            cleanupGlResources()
            setupGlResources()
            glResourcesValid = true
        }

        if (!glResourcesValid) { setupGlResources(); glResourcesValid = true }

        val qr = IntArray(2)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, qr, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, qr, 1)
        GLES20.glViewport(0, 0, qr[0], qr[1])
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        surfaceReady = true
        screenW = qr[0].toFloat()
        screenH = qr[1].toFloat()
        AppLog.d(TAG, "EGL surface: ${qr[0]}x${qr[1]}")
    }

    private fun destroyEglSurface() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    // ======== GL Resources ========

    private fun setupGlResources() {
        imageProgram = createProgram(VERTEX_SHADER, IMAGE_FRAGMENT_SHADER)
        videoProgram = createProgram(VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)
        imageTexMatLoc = GLES20.glGetUniformLocation(imageProgram, "uTexMatrix")
        imageTexLoc = GLES20.glGetUniformLocation(imageProgram, "uTexture")
        imagePosLoc = GLES20.glGetAttribLocation(imageProgram, "aPosition")
        imageTcLoc = GLES20.glGetAttribLocation(imageProgram, "aTexCoord")
        imageTexelLoc = GLES20.glGetUniformLocation(imageProgram, "uTexelSize")
        imageSharpLoc = GLES20.glGetUniformLocation(imageProgram, "uSharp")
        imageAlphaLoc = GLES20.glGetUniformLocation(imageProgram, "uAlpha")
        imageEnhanceLoc = GLES20.glGetUniformLocation(imageProgram, "uEnhance")
        imageSrcTexelLoc = GLES20.glGetUniformLocation(imageProgram, "uSrcTexel")
        videoTexMatLoc = GLES20.glGetUniformLocation(videoProgram, "uTexMatrix")
        videoTexLoc = GLES20.glGetUniformLocation(videoProgram, "uTexture")
        videoPosLoc = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        videoTcLoc = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
        videoTexelLoc = GLES20.glGetUniformLocation(videoProgram, "uTexelSize")
        videoSharpLoc = GLES20.glGetUniformLocation(videoProgram, "uSharp")
        videoEnhanceLoc = GLES20.glGetUniformLocation(videoProgram, "uEnhance")
        videoSrcTexelLoc = GLES20.glGetUniformLocation(videoProgram, "uSrcTexel")
        vertexBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        backgroundBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f))
            position(0)
        }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        imageTexId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.Matrix.setIdentityM(imageTexMatrix, 0)

        val black = IntArray(1)
        GLES20.glGenTextures(1, black, 0)
        blackTexId = black[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blackTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val blackBmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        blackBmp.eraseColor(Color.BLACK)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, blackBmp, 0)
        blackBmp.recycle()
    }

    private fun cleanupGlResources() {
        if (imageProgram != 0) { GLES20.glDeleteProgram(imageProgram); imageProgram = 0 }
        if (videoProgram != 0) { GLES20.glDeleteProgram(videoProgram); videoProgram = 0 }
        if (imageTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(imageTexId), 0); imageTexId = 0 }
        if (blackTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(blackTexId), 0); blackTexId = 0 }
        vertexBuffer = null
        backgroundBuffer = null
        // Drop the retained bitmap reference so release() frees it promptly.
        lastImageBitmap = null
        fadeAlpha = 0f
        glResourcesValid = false
    }

    private fun cleanupAll() {
        cleanupVideoResourcesOnRenderThread()
        cleanupGlResources()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            // Only terminate a display THIS renderer initialised: the handle is shared
            // process-wide (see eglInitializedHere), so terminating one we failed to
            // initialise would unbalance the pair and could take a sibling engine's
            // context down with it.
            if (eglInitializedHere) EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY; eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT; eglConfig = null
        eglInitializedHere = false
        contextReady = false; surfaceReady = false
    }

    // ======== Helpers ========

    private fun createProgram(vSrc: String, fSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fSrc)
        if (vs == 0 || fs == 0) {
            if (vs != 0) GLES20.glDeleteShader(vs)
            if (fs != 0) GLES20.glDeleteShader(fs)
            return 0
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            AppLog.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
            return 0
        }
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            AppLog.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(s)}")
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun MediaFormat.getIntegerSafe(key: String): Int {
        return try { getInteger(key) } catch (_: Exception) { 0 }
    }

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
    private fun requeueCodecSpecificData(codec: MediaCodec, format: MediaFormat) {
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
    private fun logPassFrameRate() {
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
     */
    private fun copyVideoToCache(uriStr: String): java.io.File? {
        return try {
            val dir = java.io.File(context.cacheDir, "video_cache").apply { mkdirs() }
            // Best-effort cleanup of stale copies from previous sessions.
            try {
                val stale = dir.listFiles()
                if (stale != null && stale.size > 4) {
                    stale.sortedBy { it.lastModified() }
                        .take(stale.size - 4)
                        .forEach { it.delete() }
                }
            } catch (_: Exception) {
            }
            val out = java.io.File(
                dir,
                "video_${SystemClock.elapsedRealtime()}_${uriStr.hashCode()}.mp4"
            )
            context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { input ->
                out.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            } ?: return null
            if (out.length() <= 0L) {
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
