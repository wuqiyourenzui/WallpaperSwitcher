package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.*
import android.media.*
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.ScaleMode
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
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private const val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }

    // EGL — all access on render thread only
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var surfaceReady = false
    private var contextReady = false
    private var glResourcesValid = false

    // GL resources (created once, survive surface recreation)
    private var imageProgram = 0
    private var videoProgram = 0
    private var vertexBuffer: FloatBuffer? = null
    private var imageTexId = 0
    private var imageTexMatrix = FloatArray(16)
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
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var videoDecodeThread: Thread? = null
    @Volatile var isVideoPlaying = false; private set
    private val videoGeneration = AtomicInteger(0)
    // Flag to prevent double-cleanup: stopVideoInternal sets this, decodeLoop checks it.
    private val videoCleanupDone = AtomicBoolean(false)
    // Coalescing flag: at most one render post is queued at a time, so a decode
    // thread that outruns the render thread (rapid switching, heavy load) can
    // never grow the handler queue without bound.
    private val renderPostQueued = AtomicBoolean(false)

    // Render thread (persists across surface recreations)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    /**
     * Invoked (on the decode thread) when a video fails to start, e.g. the GL
     * setup was skipped because a newer switch already replaced it, or the
     * surface/resources were torn down concurrently. The engine uses this to
     * reset its state and retry the current media instead of leaving the
     * previous video's last frame frozen on screen.
     */
    @Volatile
    var onVideoStartFailed: (() -> Unit)? = null
    @Volatile
    private var lastRenderLogAt = 0L

    // ======== Lifecycle ========

    fun initialize(initW: Float, initH: Float) {
        val thread = HandlerThread("WallpaperRenderer")
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)

        val latch = CountDownLatch(1)
        renderHandler?.post {
            screenW = initW
            screenH = initH
            setupEglContext()
            if (contextReady) {
                setupGlResources()
                glResourcesValid = true
                Log.d(TAG, "EGL initialized")
            }
            latch.countDown()
        }
        try { latch.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    fun surfaceCreated() {
        renderHandler?.post {
            if (!contextReady) return@post
            createEglSurface()
        }
    }

    /**
     * True once the EGL surface is ready to be drawn on. surfaceCreated() is
     * asynchronous (posted to the render thread), so drawing must wait for
     * this flag before calling showImage/renderImage.
     */
    fun isSurfaceReady(): Boolean = surfaceReady

    fun surfaceChanged(width: Int, height: Int) {
        renderHandler?.post {
            screenW = width.toFloat()
            screenH = height.toFloat()
            if (surfaceReady) GLES20.glViewport(0, 0, width, height)
        }
    }

    fun surfaceDestroyed() {
        stopVideoInternal()
        renderHandler?.post {
            surfaceReady = false
            destroyEglSurface()
        }
    }

    fun release() {
        stopVideoInternal()
        val handler = renderHandler
        val thread = renderThread
        if (handler != null && thread != null) {
            val latch = CountDownLatch(1)
            handler.post {
                surfaceReady = false
                contextReady = false
                cleanupAll()
                latch.countDown()
            }
            try { latch.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
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
        val handler = renderHandler ?: return
        handler.post {
            if (!surfaceReady || !contextReady) return@post
            renderImage(bitmap, scaleMode)
        }
    }

    private fun renderImage(bitmap: Bitmap, scaleMode: ScaleMode) {
        try {
            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return
            if (imageProgram == 0 || imageTexId == 0) {
                Log.w(TAG, "renderImage skipped: program=$imageProgram tex=$imageTexId")
                return
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            android.opengl.Matrix.setIdentityM(imageTexMatrix, 0)
            val quad = computeQuad(bitmap.width.toFloat(), bitmap.height.toFloat(), scaleMode)
            vertexBuffer?.clear()
            vertexBuffer?.put(quad)?.position(0)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawBlackBackground()
            GLES20.glUseProgram(imageProgram)
            val texMatLoc = GLES20.glGetUniformLocation(imageProgram, "uTexMatrix")
            val texLoc = GLES20.glGetUniformLocation(imageProgram, "uTexture")
            val posLoc = GLES20.glGetAttribLocation(imageProgram, "aPosition")
            val tcLoc = GLES20.glGetAttribLocation(imageProgram, "aTexCoord")

            GLES20.glUniformMatrix4fv(texMatLoc, 1, false, imageTexMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
            GLES20.glUniform1i(texLoc, 0)

            vertexBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            vertexBuffer?.position(2)
            GLES20.glEnableVertexAttribArray(tcLoc)
            GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val swapped = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            if (!swapped) {
                Log.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "renderImage failed", t)
        }
    }

    private fun computeQuad(imgW: Float, imgH: Float, scaleMode: ScaleMode): FloatArray {
        if (imgW <= 0 || imgH <= 0 || screenW <= 0 || screenH <= 0) {
            return floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)
        }
        val va = imgW / imgH; val sa = screenW / screenH
        val (dw, dh) = when (scaleMode) {
            ScaleMode.FIT -> if (va > sa) Pair(1f, sa / va) else Pair(va / sa, 1f)
            ScaleMode.FILL -> if (va > sa) Pair(va / sa, 1f) else Pair(1f, sa / va)
            ScaleMode.STRETCH -> Pair(1f, 1f)
        }
        return floatArrayOf(-dw,-dh,0f,1f, dw,-dh,1f,1f, -dw,dh,0f,0f, dw,dh,1f,0f)
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
    fun startVideo(uriStr: String, scaleMode: ScaleMode) {
        // First stop any existing video
        stopVideoInternal()

        // Give the old decode thread a short grace period to exit. It is NOT
        // mandatory to wait: the generation guard in decodeLoop's finally block
        // ensures a late-exiting old thread can never clean up the new video's
        // resources. Waiting too long here just makes the switch appear as a
        // long black screen when the old thread is stuck in blocking I/O.
        val oldThread = videoDecodeThread
        if (oldThread != null && oldThread.isAlive) {
            try { oldThread.join(500) } catch (_: InterruptedException) {}
        }
        videoDecodeThread = null

        // Reset cleanup flag for the new video
        videoCleanupDone.set(false)
        val gen = videoGeneration.incrementAndGet()
        isVideoPlaying = true

        val handler = renderHandler ?: run { isVideoPlaying = false; return }

        videoDecodeThread = Thread({
            decodeLoop(uriStr, scaleMode, gen, handler)
        }, "VideoDecode").also { it.start() }
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
    fun stopVideoAndRender(bitmap: Bitmap, scaleMode: ScaleMode) {
        videoGeneration.incrementAndGet()
        isVideoPlaying = false
        videoCleanupDone.set(true)

        // Interrupt decode thread (don't null — caller may need to join)
        videoDecodeThread?.interrupt()

        // Atomic: cleanup + render on same handler post
        val handler = renderHandler ?: return
        handler.post {
            cleanupVideoResourcesOnRenderThread()
            if (surfaceReady && contextReady) {
                renderImage(bitmap, scaleMode)
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

        videoDecodeThread?.interrupt()

        val handler = renderHandler ?: return
        handler.post {
            cleanupVideoResourcesOnRenderThread()
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
        try {
            // Outer loop: restart the codec cleanly when the video loops.
            // Flushing and re-feeding an in-place codec can crash some hardware
            // decoders during repeat playback, which is how the engine died
            // while just playing (no switch involved) in the captured logs.
            var errorPasses = 0
            var giveUp = false
            while (videoGeneration.get() == gen && !Thread.interrupted() && !giveUp) {
                // --- Setup MediaExtractor ---
                val ext = MediaExtractor()
                localExtractor = ext
                // Prefer an AssetFileDescriptor: MediaExtractor streaming through
                // a ContentResolver on cloud-mounted SAF URIs (e.g. PikPak) can
                // block for tens of seconds, which made the engine look dead.
                val afd = context.contentResolver.openAssetFileDescriptor(Uri.parse(uriStr), "r")
                if (afd == null) {
                    Log.e(TAG, "Cannot open video stream: $uriStr")
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }
                localAfd = afd
                ext.setDataSource(afd.fileDescriptor)
                val trackIdx = (0 until ext.trackCount).firstOrNull { i ->
                    ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: run {
                    Log.e(TAG, "No video track")
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }
                ext.selectTrack(trackIdx)
                val format = ext.getTrackFormat(trackIdx)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                var videoW = format.getIntegerSafe(MediaFormat.KEY_WIDTH)
                var videoH = format.getIntegerSafe(MediaFormat.KEY_HEIGHT)
                val maxDim = maxOf(videoW, videoH)
                // Decode to at most the screen resolution. This keeps the
                // rendered picture pixel-identical to the source on the actual
                // display (the old fixed 1280px cap made large videos blurry)
                // while avoiding the wasted power/memory of decoding far
                // larger sources (4K/8K videos) at full size.
                val screenMax = maxOf(
                    context.resources.displayMetrics.widthPixels,
                    context.resources.displayMetrics.heightPixels
                )
                val decodeCap = minOf(screenMax, 3200).coerceAtLeast(1280)
                if (maxDim > decodeCap) {
                    val scale = decodeCap.toFloat() / maxDim
                    videoW = (videoW * scale).toInt().and(0xFFFFFFFE.toInt())
                    videoH = (videoH * scale).toInt().and(0xFFFFFFFE.toInt())
                }
                // Videos with a 90/270 degree rotation (e.g. portrait phone
                // recordings) display with swapped width/height.
                val rotation = format.getIntegerSafe(MediaFormat.KEY_ROTATION)
                val isRotated = rotation == 90 || rotation == 270
                val quadW = if (isRotated) videoH else videoW
                val quadH = if (isRotated) videoW else videoH
                val fps = format.getIntegerSafe(MediaFormat.KEY_FRAME_RATE).coerceIn(15, 60)
                val intervalNs = (1_000_000_000L / fps).coerceAtLeast(16_000_000L)

                // --- Setup GL texture + SurfaceTexture on render thread ---
                val setupLatch = CountDownLatch(1)
                var setupOk = false
                handler.post {
                    if (videoGeneration.get() != gen) {
                        setupLatch.countDown()
                        return@post
                    }
                    if (!surfaceReady || !contextReady) {
                        setupLatch.countDown()
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

                    val st = SurfaceTexture(videoTexId)
                    st.setDefaultBufferSize(videoW, videoH)
                    surfaceTexture = st
                    codecSurface = Surface(st)
                    // Clear immediately so the previous video's frame cannot
                    // linger around/behind the new video while its first frame
                    // is being decoded.
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    }
                    setupOk = true
                    setupLatch.countDown()
                }
                try { setupLatch.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                if (!setupOk || videoGeneration.get() != gen) {
                    Log.e(TAG, "Video GL setup failed")
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
                    Log.e(TAG, "Video resources torn down during setup")
                    if (videoGeneration.get() == gen) {
                        isVideoPlaying = false
                        onVideoStartFailed?.invoke()
                    }
                    return
                }

                // --- Setup MediaCodec on THIS thread (decode thread) ---
                val dec = MediaCodec.createDecoderByType(mime)
                localDecoder = dec
                decoder = dec
                dec.configure(format, cs, null, 0)
                dec.start()

                // Cache render quad on render thread
                handler.post {
                    if (videoGeneration.get() != gen) return@post
                    val quad = computeVideoQuad(quadW.toFloat(), quadH.toFloat(), scaleMode)
                    vertexBuffer?.clear()
                    vertexBuffer?.put(quad)?.position(0)
                    Log.d(TAG, "Video quad set: video=${quadW}x${quadH} mode=$scaleMode " +
                            "screen=${screenW.toInt()}x${screenH.toInt()} quad=${quad.toList()}")
                }

                Log.d(TAG, "Video started: ${videoW}x${videoH} @ ${fps}fps")

                // --- Inner decode loop (one playback pass) ---
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var eof = false
                while (videoGeneration.get() == gen && !Thread.interrupted() && !eof) {
                    try {
                        val startNs = System.nanoTime()

                        if (!inputDone) {
                            val inIdx = dec.dequeueInputBuffer(0)
                            if (inIdx >= 0) {
                                val buf = dec.getInputBuffer(inIdx) ?: continue
                                val size = ext.readSampleData(buf, 0)
                                if (size < 0) {
                                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                                    ext.advance()
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
                                        val texMatrix = FloatArray(16)
                                        st.getTransformMatrix(texMatrix)
                                        renderVideoFrame(texMatrix)
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "renderVideoFrame failed", t)
                                    }
                                }
                            }

                            val elapsedNs = System.nanoTime() - startNs
                            val sleepNs = intervalNs - elapsedNs
                            if (sleepNs > 0) {
                                // InterruptedException is the normal "stop" signal.
                                try {
                                    Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                                } catch (_: InterruptedException) {}
                            }
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
                            onVideoStartFailed?.invoke()
                        }
                        Log.e(TAG, "Decode pass interrupted", t)
                        eof = true
                    }
                }

                // Release this round's codec cleanly.
                try { dec.stop() } catch (_: Exception) {}
                try { dec.release() } catch (_: Exception) {}
                if (decoder === dec) decoder = null
                val afdToClose = localAfd
                localAfd = null
                try { afdToClose.close() } catch (_: Exception) {}

                if (videoGeneration.get() != gen || Thread.interrupted()) break
                if (eof) {
                    // Loop: clean this round's GL resources, then the outer
                    // loop recreates the extractor + codec + SurfaceTexture.
                    handler.post {
                        cleanupVideoResourcesOnRenderThread()
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
                Log.e(TAG, "Decode error", t)
                isVideoPlaying = false
                onVideoStartFailed?.invoke()
            } else {
                // Superseded by a newer switch: expected during rapid
                // double-tap switching. The new video owns the screen, so this
                // is not an error.
                Log.d(TAG, "Decode thread superseded during setup", t)
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
                    cleanupVideoResourcesOnRenderThread()
                }
            }
        }
    }

    private fun computeVideoQuad(vidW: Float, vidH: Float, scaleMode: ScaleMode): FloatArray {
        if (vidW <= 0 || vidH <= 0 || screenW <= 0 || screenH <= 0) {
            return floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)
        }
        val va = vidW / vidH; val sa = screenW / screenH
        val (dw, dh) = when (scaleMode) {
            ScaleMode.FIT -> if (va > sa) Pair(1f, sa / va) else Pair(va / sa, 1f)
            ScaleMode.FILL -> if (va > sa) Pair(va / sa, 1f) else Pair(1f, sa / va)
            ScaleMode.STRETCH -> Pair(1f, 1f)
        }
        return floatArrayOf(-dw,-dh,0f,0f, dw,-dh,1f,0f, -dw,dh,0f,1f, dw,dh,1f,1f)
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
            val texMatLoc = GLES20.glGetUniformLocation(imageProgram, "uTexMatrix")
            val texLoc = GLES20.glGetUniformLocation(imageProgram, "uTexture")
            val posLoc = GLES20.glGetAttribLocation(imageProgram, "aPosition")
            val tcLoc = GLES20.glGetAttribLocation(imageProgram, "aTexCoord")
            android.opengl.Matrix.setIdentityM(imageTexMatrix, 0)
            GLES20.glUniformMatrix4fv(texMatLoc, 1, false, imageTexMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blackTexId)
            GLES20.glUniform1i(texLoc, 0)

            bg.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, bg)
            bg.position(2)
            GLES20.glEnableVertexAttribArray(tcLoc)
            GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, bg)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (t: Throwable) {
            Log.e(TAG, "drawBlackBackground failed", t)
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
            if (videoProgram == 0 || videoTexId == 0) {
                Log.w(TAG, "renderVideoFrame skipped: program=$videoProgram tex=$videoTexId")
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastRenderLogAt > 5000L) {
                lastRenderLogAt = now
                Log.d(TAG, "Video frame rendered: tex=$videoTexId screen=${screenW.toInt()}x${screenH.toInt()}")
            }
            // Clear the whole framebuffer first. In FIT/STRETCH-less modes the
            // video quad does not cover the full screen; without clearing, the
            // letterbox area keeps showing the PREVIOUS video's last frame
            // (user-visible as "the old video stays on screen after switching").
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // Belt-and-suspenders: draw real black pixels over the whole
            // surface. Some devices/drivers do not fully invalidate preserved
            // window buffers on glClear alone, which left the previous video
            // visible in the FIT letterbox even after the clear was added.
            drawBlackBackground()
            GLES20.glUseProgram(videoProgram)

            val texMatLoc = GLES20.glGetUniformLocation(videoProgram, "uTexMatrix")
            val texLoc = GLES20.glGetUniformLocation(videoProgram, "uTexture")
            val posLoc = GLES20.glGetAttribLocation(videoProgram, "aPosition")
            val tcLoc = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")

            GLES20.glUniformMatrix4fv(texMatLoc, 1, false, texMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
            GLES20.glUniform1i(texLoc, 0)

            vertexBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            vertexBuffer?.position(2)
            GLES20.glEnableVertexAttribArray(tcLoc)
            GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val swapped = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            if (!swapped) {
                Log.w(TAG, "eglSwapBuffers failed: ${EGL14.eglGetError()}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "renderVideoFrame failed", t)
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

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, num, 0)
        eglConfig = configs[0] ?: return

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return
        contextReady = true

        val surface = holder.surface
        if (surface != null && surface.isValid) createEglSurface()
    }

    private fun createEglSurface() {
        val surface = holder.surface ?: return
        if (!surface.isValid) return

        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        val attribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return

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
        Log.d(TAG, "EGL surface: ${qr[0]}x${qr[1]}")
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
        glResourcesValid = false
    }

    private fun cleanupAll() {
        cleanupVideoResourcesOnRenderThread()
        cleanupGlResources()
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY; eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT; eglConfig = null
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
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(p)}")
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
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(s)}")
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun MediaFormat.getIntegerSafe(key: String): Int {
        return try { getInteger(key) } catch (_: Exception) { 0 }
    }
}
