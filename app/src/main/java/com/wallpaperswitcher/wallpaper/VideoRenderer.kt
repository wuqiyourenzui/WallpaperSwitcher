package com.wallpaperswitcher.wallpaper

import android.graphics.*
import android.media.*
import android.net.Uri
import android.opengl.EGL14
import com.wallpaperswitcher.data.ScaleMode
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.SurfaceHolder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardware-accelerated video renderer using MediaCodec + SurfaceTexture + EGL.
 *
 * Runs entirely on a dedicated HandlerThread:
 *   MediaExtractor → MediaCodec (HW decoder) → SurfaceTexture (GL texture)
 *   → updateTexImage() → getBitmap() (API 31+) or GL readback
 *   → post Bitmap to main thread for Canvas rendering
 *
 * Supports original quality, original frame rate, FIT/FILL/STRETCH scaling.
 */
class VideoRenderer(
    private val context: android.content.Context,
    private val holder: SurfaceHolder,
    private val mainHandler: Handler
) {
    companion object {
        private const val TAG = "VideoRenderer"
        private const val MAX_FPS = 60
    }

    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null

    @Volatile private var running = false
    @Volatile private var paused = false

    // EGL
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    // MediaCodec
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var codecSurface: android.view.Surface? = null
    private var texId = 0

    // Frame delivery
    private val frameBuffer = LinkedBlockingQueue<Bitmap>(3)
    private var fps = 30
    var durationMs: Long = 0L; private set
    var videoWidth: Int = 0; private set
    var videoHeight: Int = 0; private set

    // Reusable
    private val destRect = RectF()
    private var cachedScreenW = 0f
    private var cachedScreenH = 0f

    fun start(uriStr: String) {
        if (running) return
        running = true
        paused = false

        val metrics = com.wallpaperswitcher.engine.BitmapUtils.getScreenMetrics(context)
        cachedScreenW = metrics.widthPixels.toFloat()
        cachedScreenH = metrics.heightPixels.toFloat()

        val thread = HandlerThread("VideoDecode")
        decodeThread = thread
        thread.start()
        val handler = Handler(thread.looper)
        decodeHandler = handler

        handler.post { decodeLoop(uriStr) }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    fun stop() {
        running = false
        paused = false
        // Synchronous cleanup — ensure all resources are released before returning
        val latch = java.util.concurrent.CountDownLatch(1)
        decodeHandler?.post { cleanup(); latch.countDown() }
        try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        decodeThread?.quitSafely()
        decodeThread = null
        decodeHandler = null
        while (frameBuffer.isNotEmpty()) { try { frameBuffer.poll()?.recycle() } catch (_: Exception) {} }
    }

    fun renderFrame(scaleMode: ScaleMode) {
        val frame = frameBuffer.poll() ?: return
        try {
            val canvas = holder.lockCanvas() ?: return
            canvas.drawColor(Color.BLACK)
            calcDestRect(frame.width.toFloat(), frame.height.toFloat(), cachedScreenW, cachedScreenH, scaleMode)
            canvas.drawBitmap(frame, null, destRect, null)
            holder.unlockCanvasAndPost(canvas)
        } catch (_: Exception) {}
        frame.recycle()
    }

    private fun decodeLoop(uriStr: String) {
        try {
            // --- EGL setup (on this thread) ---
            if (!setupEgl()) {
                Log.e(TAG, "EGL setup failed")
                cleanup()
                return
            }

            // --- MediaExtractor ---
            val ext = MediaExtractor()
            ext.setDataSource(context, Uri.parse(uriStr), null)
            extractor = ext

            val trackIdx = (0 until ext.trackCount).firstOrNull { i ->
                ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: run { cleanup(); return }

            ext.selectTrack(trackIdx)
            val format = ext.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
            fps = format.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30).coerceIn(15, MAX_FPS)

            Log.d(TAG, "Video: ${videoWidth}x${videoHeight} @ ${fps}fps, mime=$mime")

            // --- GL texture + SurfaceTexture + Surface ---
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            val st = SurfaceTexture(texId)
            st.setDefaultBufferSize(videoWidth, videoHeight)
            surfaceTexture = st
            val cs = android.view.Surface(st)
            codecSurface = cs

            // --- MediaCodec ---
            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(format, cs, null, 0)
            dec.start()

            // --- Render loop (on this same thread — EGL context is here) ---
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            val frameIntervalNs = (1_000_000_000L / fps)

            while (running) {
                if (paused) {
                    Thread.sleep(50)
                    continue
                }

                val frameStart = System.nanoTime()

                // Feed input
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val inBuf = dec.getInputBuffer(inIdx) ?: continue
                        val size = ext.readSampleData(inBuf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                            ext.advance()
                        }
                    }
                }

                // Get output
                val outIdx = dec.dequeueOutputBuffer(bufferInfo, 1000)
                if (outIdx >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        dec.releaseOutputBuffer(outIdx, false)
                        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        inputDone = false
                        continue
                    }

                    dec.releaseOutputBuffer(outIdx, true) // render to SurfaceTexture

                    // Update texture (must be on same thread as EGL)
                    st.updateTexImage()

                    // Get bitmap from texture
                    val bitmap = tryGetBitmap(videoWidth, videoHeight)
                    if (bitmap != null) {
                        while (frameBuffer.remainingCapacity() <= 0) {
                            try { frameBuffer.poll(10, TimeUnit.MILLISECONDS)?.recycle() } catch (_: Exception) {}
                        }
                        frameBuffer.offer(bitmap)
                    }

                    // Frame pacing
                    val elapsedNs = System.nanoTime() - frameStart
                    val sleepNs = frameIntervalNs - elapsedNs
                    if (sleepNs > 0) Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Thread.sleep(1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decode error: ${e.message}", e)
        } finally {
            cleanup()
        }
    }

    /**
     * Get bitmap from SurfaceTexture.
     * API 31+: getBitmap() (fast, GPU-accelerated)
     * Older: GL readback via framebuffer
     */
    private fun tryGetBitmap(width: Int, height: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    val method = SurfaceTexture::class.java.getMethod("getBitmap")
                    method.invoke(surfaceTexture) as? Bitmap
                } catch (_: Exception) { null }
            } else {
                readGlPixels(width, height)
            }
        } catch (_: Exception) { null }
    }

    private fun readGlPixels(width: Int, height: Int): Bitmap? {
        return try {
            // Create FBO and attach texture for reading
            val fbo = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId, 0
            )

            val buf = java.nio.IntBuffer.allocate(width * height)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)

            val pixels = IntArray(width * height)
            buf.get(pixels)

            // Flip vertically (GL origin is bottom-left)
            for (y in 0 until height / 2) {
                val top = y * width
                val bot = (height - 1 - y) * width
                for (x in 0 until width) {
                    val tmp = pixels[top + x]
                    pixels[top + x] = pixels[bot + x]
                    pixels[bot + x] = tmp
                }
            }

            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) { null }
    }

    private fun setupEgl(): Boolean {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        eglDisplay = display

        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: return false

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) return false
        eglContext = ctx

        val pbufAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val surface = EGL14.eglCreatePbufferSurface(display, config, pbufAttribs, 0)
        if (surface == EGL14.EGL_NO_SURFACE) return false
        eglSurface = surface

        if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) return false

        return true
    }

    private fun cleanup() {
        running = false
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        try { codecSurface?.release() } catch (_: Exception) {}
        codecSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        if (texId != 0) { try { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) } catch (_: Exception) {} }
        texId = 0
        try { eglDisplay?.let { d ->
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            eglSurface?.let { EGL14.eglDestroySurface(d, it) }
            eglContext?.let { EGL14.eglDestroyContext(d, it) }
            EGL14.eglTerminate(d)
        }} catch (_: Exception) {}
        eglDisplay = null; eglContext = null; eglSurface = null
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
        while (frameBuffer.isNotEmpty()) { try { frameBuffer.poll()?.recycle() } catch (_: Exception) {} }
    }

    private fun calcDestRect(bw: Float, bh: Float, sw: Float, sh: Float, scaleMode: ScaleMode) {
        when (scaleMode) {
            ScaleMode.FIT -> {
                val r = bw / bh; val sr = sw / sh
                val dw: Float; val dh: Float
                if (r > sr) { dw = sw; dh = dw / r } else { dh = sh; dw = dh * r }
                destRect.set((sw - dw) / 2f, (sh - dh) / 2f, (sw + dw) / 2f, (sh + dh) / 2f)
            }
            ScaleMode.FILL -> {
                val r = bw / bh; val sr = sw / sh
                val dw: Float; val dh: Float
                if (r < sr) { dw = sw; dh = dw / r } else { dh = sh; dw = dh * r }
                destRect.set((sw - dw) / 2f, (sh - dh) / 2f, (sw + dw) / 2f, (sh + dh) / 2f)
            }
            ScaleMode.STRETCH -> destRect.set(0f, 0f, sw, sh)
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return try { if (containsKey(key)) getInteger(key) else default } catch (_: Exception) { default }
    }
}
