package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
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

    // Render thread (persists across surface recreations)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

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

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            android.opengl.Matrix.setIdentityM(imageTexMatrix, 0)
            val quad = computeQuad(bitmap.width.toFloat(), bitmap.height.toFloat(), scaleMode)
            vertexBuffer?.clear()
            vertexBuffer?.put(quad)?.position(0)

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
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
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            Log.e(TAG, "renderImage: ${e.message}")
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
        // Save reference before stopping (stopVideoInternal nulls the field)
        val oldThread = videoDecodeThread

        // First stop any existing video
        stopVideoInternal()

        // CRITICAL: Wait for old decode thread to fully exit.
        // Without this, the old thread's finally block can destroy the new video's
    	// decoder/SurfaceTexture (race condition: old thread nulls shared fields).
        if (oldThread != null && oldThread.isAlive) {
            try { oldThread.join(2000) } catch (_: InterruptedException) {}
        }

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
     * Stop video AND atomically render an image — the key to smooth transitions.
     *
     * Cleanup and image rendering happen in a SINGLE render-handler post:
     * render thread: [release video resources] → [draw image] → [swap buffers]
     * No gap, no flash, no stutter.
     *
     * Call from ANY thread (typically IO coroutine thread).
     */
    fun stopVideoAndRender(bitmap: Bitmap, scaleMode: ScaleMode) {
        val gen = videoGeneration.incrementAndGet()
        isVideoPlaying = false

        // Interrupt decode thread
        val decodeThread = videoDecodeThread
        videoDecodeThread = null
        decodeThread?.interrupt()

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
     */
    private fun stopVideoInternal() {
        val gen = videoGeneration.incrementAndGet()
        isVideoPlaying = false

        val decodeThread = videoDecodeThread
        videoDecodeThread = null
        decodeThread?.interrupt()

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
        try {
            // --- Setup MediaExtractor ---
            val ext = MediaExtractor()
            localExtractor = ext
            ext.setDataSource(context, Uri.parse(uriStr), null)
            val trackIdx = (0 until ext.trackCount).firstOrNull { i ->
                ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: run {
                Log.e(TAG, "No video track"); isVideoPlaying = false; ext.release(); return
            }
            ext.selectTrack(trackIdx)
            val format = ext.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var videoW = format.getInteger(MediaFormat.KEY_WIDTH)
            var videoH = format.getInteger(MediaFormat.KEY_HEIGHT)
            val maxDim = maxOf(videoW, videoH)
            if (maxDim > 1280) {
                val scale = 1280f / maxDim
                videoW = (videoW * scale).toInt().and(0xFFFFFFFE.toInt())
                videoH = (videoH * scale).toInt().and(0xFFFFFFFE.toInt())
            }
            val fps = try { format.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { 30 }
            val intervalNs = (1_000_000_000L / fps.coerceIn(15, 60)).coerceAtLeast(16_000_000L)

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
                setupOk = true
                setupLatch.countDown()
            }
            setupLatch.await(3, TimeUnit.SECONDS)
            if (!setupOk) {
                Log.e(TAG, "Video GL setup failed"); isVideoPlaying = false
                ext.release(); return
            }

            // --- Setup MediaCodec on THIS thread (decode thread) ---
            val dec = MediaCodec.createDecoderByType(mime)
            localDecoder = dec
            decoder = dec
            dec.configure(format, codecSurface, null, 0)
            dec.start()

            // Cache render quad on render thread
            handler.post {
                if (videoGeneration.get() != gen) return@post
                val quad = computeVideoQuad(videoW.toFloat(), videoH.toFloat(), scaleMode)
                vertexBuffer?.clear()
                vertexBuffer?.put(quad)?.position(0)
            }

            Log.d(TAG, "Video started: ${videoW}x${videoH} @ ${fps}fps")

            // --- Decode loop ---
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            val st = surfaceTexture!!

            while (videoGeneration.get() == gen && !Thread.interrupted()) {
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
                        dec.flush()
                        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        inputDone = false
                        continue
                    }

                    dec.releaseOutputBuffer(outIdx, true)

                    handler.post {
                        if (videoGeneration.get() != gen) return@post
                        if (!surfaceReady || !contextReady) return@post
                        try {
                            st.updateTexImage()
                            val texMatrix = FloatArray(16)
                            st.getTransformMatrix(texMatrix)
                            renderVideoFrame(texMatrix)
                        } catch (e: Exception) {
                            Log.e(TAG, "renderVideoFrame: ${e.message}")
                        }
                    }

                    val elapsedNs = System.nanoTime() - startNs
                    val sleepNs = intervalNs - elapsedNs
                    if (sleepNs > 0) Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Thread.sleep(1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}", e)
        } finally {
            isVideoPlaying = false
            try { localDecoder?.stop() } catch (_: Exception) {}
            try { localDecoder?.release() } catch (_: Exception) {}
            if (decoder === localDecoder) decoder = null
            try { localExtractor?.release() } catch (_: Exception) {}
            if (extractor === localExtractor) extractor = null
            // Post GL resource cleanup to render thread
            handler.post {
                cleanupVideoResourcesOnRenderThread()
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
     * Render a video frame. Called on render thread.
     * No glClear — the full-screen quad overwrites the entire framebuffer.
     * This prevents black flash during transitions.
     */
    private fun renderVideoFrame(texMatrix: FloatArray) {
        try {
            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return

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
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: Exception) {
            Log.e(TAG, "renderVideoFrame: ${e.message}")
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
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        imageTexId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.Matrix.setIdentityM(imageTexMatrix, 0)
    }

    private fun cleanupGlResources() {
        if (imageProgram != 0) { GLES20.glDeleteProgram(imageProgram); imageProgram = 0 }
        if (videoProgram != 0) { GLES20.glDeleteProgram(videoProgram); videoProgram = 0 }
        if (imageTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(imageTexId), 0); imageTexId = 0 }
        vertexBuffer = null
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

    private fun MediaFormat.getInteger(key: String): Int {
        return try { getInteger(key) } catch (_: Exception) { 0 }
    }
}
