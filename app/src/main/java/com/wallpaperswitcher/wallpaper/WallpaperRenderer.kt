package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.ScaleMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unified EGL renderer following the GLSurfaceView pattern.
 *
 * KEY DESIGN: EGL context is created ONCE and survives surface recreations.
 * Only the EGL surface is destroyed/recreated when the wallpaper surface
 * changes. GL resources (textures, programs, buffers) live as long as the
 * context does — they are never unnecessarily recreated.
 *
 * Lifecycle:
 *   initialize()  → create render thread + EGL context + GL resources
 *   surfaceCreated()  → create new EGL surface from holder
 *   surfaceDestroyed() → destroy EGL surface only (context survives)
 *   release() → destroy everything
 *
 * Video uses generation counter for safe lifecycle transitions.
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

    // EGL — context lives once, surface is recreated per wallpaper surface
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    @Volatile private var surfaceReady = false
    @Volatile private var contextReady = false

    // GL resources (created once, survive surface recreation)
    private var imageProgram = 0
    private var videoProgram = 0
    private var vertexBuffer: FloatBuffer? = null
    private var imageTexId = 0
    private var imageTexMatrix = FloatArray(16)

    // Video state
    private var videoTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var codecSurface: android.view.Surface? = null
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var videoDecodeThread: Thread? = null
    @Volatile var isVideoPlaying = false; private set
    private val videoGeneration = AtomicInteger(0)
    @Volatile private var videoLooping = true

    // Render thread (persists across surface recreations)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var screenW = 0f
    private var screenH = 0f

    // ======== Lifecycle ========

    /**
     * One-time initialization: create render thread + EGL context + GL resources.
     * Called once from onSurfaceCreated. Survives subsequent surface recreations.
     */
    fun initialize(sw: Float, sh: Float) {
        screenW = sw
        screenH = sh

        val thread = HandlerThread("WallpaperRenderer")
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)

        // Create EGL context + GL resources once
        val latch = CountDownLatch(1)
        renderHandler?.post {
            setupEglContext()
            if (contextReady) {
                setupGlResources()
                Log.d(TAG, "EGL context + GL resources initialized")
            }
            latch.countDown()
        }
        try { latch.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    /**
     * Called from onSurfaceCreated. Creates a new EGL surface from the holder.
     * If the context already exists (surface recreation), just creates the surface.
     */
    fun surfaceCreated() {
        val handler = renderHandler ?: return
        handler.post {
            if (!contextReady) {
                Log.e(TAG, "surfaceCreated: no EGL context")
                return@post
            }
            createEglSurface()
        }
    }

    /**
     * Called from onSurfaceChanged. Updates viewport.
     */
    fun surfaceChanged(width: Int, height: Int) {
        screenW = width.toFloat()
        screenH = height.toFloat()
        val handler = renderHandler ?: return
        handler.post {
            if (surfaceReady) {
                GLES20.glViewport(0, 0, width, height)
            }
        }
    }

    /**
     * Called from onSurfaceDestroyed. Destroys EGL surface only.
     * EGL context and GL resources survive — they'll be reused when
     * a new surface is created.
     */
    fun surfaceDestroyed() {
        surfaceReady = false
        videoGeneration.incrementAndGet()
        isVideoPlaying = false
        videoDecodeThread?.interrupt()
        videoDecodeThread = null

        val handler = renderHandler ?: return
        handler.post {
            destroyEglSurface()
            cleanupVideoCodec()
        }
    }

    /**
     * Full release. Destroys everything. Called when engine is destroyed.
     */
    fun release() {
        surfaceReady = false
        contextReady = false
        videoGeneration.incrementAndGet()
        isVideoPlaying = false
        videoDecodeThread?.interrupt()
        videoDecodeThread = null

        val handler = renderHandler
        val thread = renderThread
        if (handler != null && thread != null) {
            val latch = CountDownLatch(1)
            handler.post {
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

    fun showImage(bitmap: Bitmap, scaleMode: ScaleMode) {
        val handler = renderHandler ?: return
        handler.post {
            if (!surfaceReady || !contextReady) return@post
            renderImage(bitmap, scaleMode)
        }
    }

    private fun renderImage(bitmap: Bitmap, scaleMode: ScaleMode) {
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            android.opengl.Matrix.setIdentityM(imageTexMatrix, 0)

            val quad = computeImageQuad(bitmap.width.toFloat(), bitmap.height.toFloat(), scaleMode)
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
            Log.e(TAG, "renderImage error: ${e.message}")
        }
    }

    private fun computeImageQuad(imgW: Float, imgH: Float, scaleMode: ScaleMode): FloatArray {
        if (imgW <= 0 || imgH <= 0 || screenW <= 0 || screenH <= 0) {
            return floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)
        }
        val va = imgW / imgH
        val sa = screenW / screenH
        val (dw, dh) = when (scaleMode) {
            ScaleMode.FIT -> if (va > sa) Pair(1f, sa / va) else Pair(va / sa, 1f)
            ScaleMode.FILL -> if (va > sa) Pair(va / sa, 1f) else Pair(1f, sa / va)
            ScaleMode.STRETCH -> Pair(1f, 1f)
        }
        return floatArrayOf(
            -dw, -dh, 0f, 1f,
             dw, -dh, 1f, 1f,
            -dw,  dh, 0f, 0f,
             dw,  dh, 1f, 0f,
        )
    }

    // ======== Video Rendering ========

    fun startVideo(uriStr: String, scaleMode: ScaleMode) {
        stopVideoSync()

        val gen = videoGeneration.incrementAndGet()
        isVideoPlaying = true

        val handler = renderHandler ?: run {
            isVideoPlaying = false
            return
        }
        handler.post {
            if (!surfaceReady || !contextReady) {
                Log.e(TAG, "startVideo: surface/context not ready")
                isVideoPlaying = false
                return@post
            }
            startVideoInternal(uriStr, scaleMode, gen)
        }
    }

    private fun startVideoInternal(uriStr: String, scaleMode: ScaleMode, gen: Int) {
        try {
            val ext = MediaExtractor()
            ext.setDataSource(context, Uri.parse(uriStr), null)
            extractor = ext

            val trackIdx = (0 until ext.trackCount).firstOrNull { i ->
                ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: run {
                Log.e(TAG, "No video track found")
                isVideoPlaying = false
                return
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

            val fps = try {
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
            } catch (_: Exception) { 30 }
            val intervalNs = (1_000_000_000L / fps.coerceIn(15, 60)).coerceAtLeast(16_000_000L)

            if (videoTexId == 0) {
                val texIds = IntArray(1)
                GLES20.glGenTextures(1, texIds, 0)
                videoTexId = texIds[0]
            }
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            val st = SurfaceTexture(videoTexId)
            surfaceTexture = st
            st.setDefaultBufferSize(videoW, videoH)
            codecSurface = android.view.Surface(st)

            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(format, codecSurface, null, 0)
            dec.start()

            Log.d(TAG, "Video started: ${videoW}x${videoH} @ ${fps}fps, gen=$gen")

            val quad = computeVideoQuad(videoW.toFloat(), videoH.toFloat(), scaleMode)
            vertexBuffer?.clear()
            vertexBuffer?.put(quad)?.position(0)

            videoDecodeThread = Thread({
                decodeLoop(ext, dec, st, intervalNs, gen)
            }, "VideoDecode").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "startVideo error: ${e.message}", e)
            isVideoPlaying = false
            cleanupVideoCodec()
        }
    }

    private fun computeVideoQuad(vidW: Float, vidH: Float, scaleMode: ScaleMode): FloatArray {
        if (vidW <= 0 || vidH <= 0 || screenW <= 0 || screenH <= 0) {
            return floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)
        }
        val va = vidW / vidH
        val sa = screenW / screenH
        val (dw, dh) = when (scaleMode) {
            ScaleMode.FIT -> if (va > sa) Pair(1f, sa / va) else Pair(va / sa, 1f)
            ScaleMode.FILL -> if (va > sa) Pair(va / sa, 1f) else Pair(1f, sa / va)
            ScaleMode.STRETCH -> Pair(1f, 1f)
        }
        return floatArrayOf(
            -dw, -dh, 0f, 0f,
             dw, -dh, 1f, 0f,
            -dw,  dh, 0f, 1f,
             dw,  dh, 1f, 1f,
        )
    }

    private fun decodeLoop(
        ext: MediaExtractor, dec: MediaCodec, st: SurfaceTexture,
        intervalNs: Long, gen: Int
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false

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
                    if (videoLooping && videoGeneration.get() == gen) {
                        dec.flush()
                        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        inputDone = false
                        continue
                    }
                    break
                }

                dec.releaseOutputBuffer(outIdx, true)

                renderHandler?.post {
                    if (videoGeneration.get() != gen) return@post
                    if (!surfaceReady || !contextReady) return@post
                    try {
                        st.updateTexImage()
                        val texMatrix = FloatArray(16)
                        st.getTransformMatrix(texMatrix)
                        renderVideoFrame(texMatrix)
                    } catch (e: Exception) {
                        Log.e(TAG, "renderVideoFrame error: ${e.message}")
                    }
                }

                val elapsedNs = System.nanoTime() - startNs
                val sleepNs = intervalNs - elapsedNs
                if (sleepNs > 0) Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Thread.sleep(1)
            }
        }

        if (videoGeneration.get() == gen) {
            isVideoPlaying = false
        }
    }

    private fun renderVideoFrame(texMatrix: FloatArray) {
        try {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
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
            Log.e(TAG, "renderVideoFrame error: ${e.message}")
        }
    }

    fun stopVideo() { stopVideoSync() }

    private fun stopVideoSync() {
        videoGeneration.incrementAndGet()
        isVideoPlaying = false

        videoDecodeThread?.let { thread ->
            thread.interrupt()
            try { thread.join(2000) } catch (_: InterruptedException) {}
        }
        videoDecodeThread = null

        val handler = renderHandler
        if (handler != null && surfaceReady && contextReady) {
            val latch = CountDownLatch(1)
            handler.post {
                cleanupVideoCodec()
                latch.countDown()
            }
            try { latch.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        } else {
            cleanupVideoCodec()
        }
    }

    private fun cleanupVideoCodec() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        try { codecSurface?.release() } catch (_: Exception) {}
        codecSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
        if (videoTexId != 0 && contextReady) {
            GLES20.glDeleteTextures(1, intArrayOf(videoTexId), 0)
            videoTexId = 0
        }
    }

    // ======== EGL: Context (once) + Surface (per recreation) ========

    /**
     * Create EGL display, config, and context. Called ONCE.
     * The context survives surface recreations.
     */
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

        // Create initial surface if holder is already valid
        if (holder.surface.isValid) {
            createEglSurface()
        }
    }

    /**
     * Create EGL window surface from the current holder surface.
     * Called on each surfaceCreated. Context must already exist.
     */
    private fun createEglSurface() {
        val surface = holder.surface
        if (!surface.isValid) {
            Log.e(TAG, "createEglSurface: surface not valid")
            return
        }

        // Destroy old surface if exists
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "createEglSurface: eglCreateWindowSurface failed")
            return
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "createEglSurface: eglMakeCurrent failed")
            return
        }

        val queryResult = IntArray(2)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, queryResult, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, queryResult, 1)
        GLES20.glViewport(0, 0, queryResult[0], queryResult[1])
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        surfaceReady = true
        screenW = queryResult[0].toFloat()
        screenH = queryResult[1].toFloat()
        Log.d(TAG, "EGL surface created: ${queryResult[0]}x${queryResult[1]}")
    }

    /**
     * Destroy EGL surface only. Context and GL resources survive.
     */
    private fun destroyEglSurface() {
        surfaceReady = false
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
            Log.d(TAG, "EGL surface destroyed (context survives)")
        }
    }

    // ======== GL Resources (created once with context) ========

    private fun setupGlResources() {
        imageProgram = createProgram(VERTEX_SHADER, IMAGE_FRAGMENT_SHADER)
        videoProgram = createProgram(VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)

        vertexBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

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

    private fun cleanupAll() {
        cleanupVideoCodec()

        if (imageProgram != 0) { GLES20.glDeleteProgram(imageProgram); imageProgram = 0 }
        if (videoProgram != 0) { GLES20.glDeleteProgram(videoProgram); videoProgram = 0 }
        if (imageTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(imageTexId), 0); imageTexId = 0 }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
        contextReady = false
    }

    // ======== Helpers ========

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun MediaFormat.getInteger(key: String): Int {
        return try { getInteger(key) } catch (_: Exception) { 0 }
    }
}
