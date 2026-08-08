package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified EGL renderer for images and video frames.
 *
 * Design: video uses MediaMetadataRetriever to extract frames as Bitmaps,
 * then renders them through the SAME image pipeline (GL_TEXTURE_2D + shader).
 * This avoids MediaCodec/SurfaceTexture lifecycle complexity entirely.
 *
 * EGL context survives surface recreation (GLSurfaceView pattern).
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

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
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
    private var program = 0
    private var vertexBuffer: FloatBuffer? = null
    private var texId = 0
    private var texMatrix = FloatArray(16)

    // Screen dimensions — only on render thread
    private var screenW = 0f
    private var screenH = 0f

    // Video state — MediaMetadataRetriever based
    @Volatile var isVideoPlaying = false; private set
    private val videoStop = AtomicBoolean(false)
    private var videoThread: Thread? = null
    private var retriever: MediaMetadataRetriever? = null

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
        stopVideo()
        renderHandler?.post {
            surfaceReady = false
            destroyEglSurface()
        }
    }

    fun release() {
        stopVideo()
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

    fun showImage(bitmap: Bitmap, scaleMode: ScaleMode) {
        renderHandler?.post {
            if (!surfaceReady || !contextReady) return@post
            renderBitmap(bitmap, scaleMode)
        }
    }

    private fun renderBitmap(bitmap: Bitmap, scaleMode: ScaleMode) {
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            android.opengl.Matrix.setIdentityM(texMatrix, 0)
            drawQuad(bitmap.width.toFloat(), bitmap.height.toFloat(), scaleMode)
        } catch (e: Exception) {
            Log.e(TAG, "renderBitmap error: ${e.message}")
        }
    }

    private fun drawQuad(imgW: Float, imgH: Float, scaleMode: ScaleMode) {
        val quad = computeQuad(imgW, imgH, scaleMode)
        vertexBuffer?.clear()
        vertexBuffer?.put(quad)?.position(0)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val texMatLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val texLoc = GLES20.glGetUniformLocation(program, "uTexture")
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val tcLoc = GLES20.glGetAttribLocation(program, "aTexCoord")

        GLES20.glUniformMatrix4fv(texMatLoc, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(texLoc, 0)

        vertexBuffer?.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer?.position(2)
        GLES20.glEnableVertexAttribArray(tcLoc)
        GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
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
        return floatArrayOf(
            -dw, -dh, 0f, 1f,
             dw, -dh, 1f, 1f,
            -dw,  dh, 0f, 0f,
             dw,  dh, 1f, 0f,
        )
    }

    // ======== Video: MediaMetadataRetriever + Bitmap frames ========

    /**
     * Start video playback using MediaMetadataRetriever.
     * Extracts frames as Bitmaps on a background thread, renders via image pipeline.
     * No MediaCodec, no SurfaceTexture — just Bitmap extraction + GL_TEXTURE_2D.
     */
    fun startVideo(uriStr: String, scaleMode: ScaleMode) {
        stopVideo()
        videoStop.set(false)
        isVideoPlaying = true

        videoThread = Thread({
            try {
                videoExtractLoop(uriStr, scaleMode)
            } catch (e: Exception) {
                Log.e(TAG, "Video error: ${e.message}")
            } finally {
                isVideoPlaying = false
                synchronized(this) {
                    try { retriever?.release() } catch (_: Exception) {}
                    retriever = null
                }
            }
        }, "VideoExtract").also { it.start() }
    }

    private fun videoExtractLoop(uriStr: String, scaleMode: ScaleMode) {
        val r = MediaMetadataRetriever()
        synchronized(this) { retriever = r }
        r.setDataSource(context, android.net.Uri.parse(uriStr))

        val durationMs = try {
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L }
        if (durationMs <= 0) {
            Log.e(TAG, "Video duration is 0")
            return
        }

        val fps = 30
        val intervalMs = (1000L / fps).coerceAtLeast(33L)
        var posUs = 0L

        while (!videoStop.get() && !Thread.interrupted() && !holder.surface?.isValid == false) {
            val startMs = System.currentTimeMillis()

            // Extract frame at current position
            val frame = try {
                r.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) { null }

            if (frame != null) {
                // Post to render thread — use same image pipeline
                val handler = renderHandler ?: break
                val drawn = java.util.concurrent.atomic.AtomicBoolean(false)
                handler.post {
                    if (!videoStop.get() && surfaceReady && contextReady) {
                        renderBitmap(frame, scaleMode)
                        drawn.set(true)
                    }
                }
                // Wait for render to complete before recycling
                Thread.sleep(intervalMs / 2)
                if (!frame.isRecycled) frame.recycle()
            }

            // Advance position
            posUs += intervalMs * 1000
            if (posUs >= durationMs * 1000) posUs = 0L

            // Frame pacing
            val elapsed = System.currentTimeMillis() - startMs
            val sleepMs = intervalMs - elapsed
            if (sleepMs > 0) Thread.sleep(sleepMs)
        }
    }

    fun stopVideo() {
        videoStop.set(true)
        isVideoPlaying = false
        videoThread?.let { thread ->
            thread.interrupt()
            try { thread.join(1000) } catch (_: InterruptedException) {}
        }
        videoThread = null
        synchronized(this) {
            try { retriever?.release() } catch (_: Exception) {}
            retriever = null
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
            // Context Lost — rebuild
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
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        vertexBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.Matrix.setIdentityM(texMatrix, 0)
    }

    private fun cleanupGlResources() {
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
        if (texId != 0) { GLES20.glDeleteTextures(1, intArrayOf(texId), 0); texId = 0 }
        vertexBuffer = null
        glResourcesValid = false
    }

    private fun cleanupAll() {
        cleanupGlResources()
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
        surfaceReady = false
    }

    // ======== Helpers ========

    private fun createProgram(vSrc: String, fSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }
}
