package com.wallpaperswitcher.wallpaper

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.opengl.*
import android.os.Handler
import android.util.Log
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.ScaleMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Hardware-accelerated video renderer using MediaCodec + SurfaceTexture + EGL + GL.
 *
 * Pipeline (all on dedicated HandlerThread):
 *   MediaExtractor → MediaCodec (HW decoder) → SurfaceTexture (GL external texture)
 *   → GL shader renders texture to FBO → glReadPixels → Bitmap
 *   → post to main thread → Canvas.drawBitmap
 *
 * The GL shader applies SurfaceTexture's transform matrix, handling video
 * orientation and UV mapping correctly. FBO + glReadPixels reads the
 * rendered frame back to CPU memory as a Bitmap.
 */
class VideoRenderer(
    private val context: Context,
    private val holder: SurfaceHolder,
    private val mainHandler: Handler
) {
    companion object {
        private const val TAG = "VideoRenderer"

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
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,  // position(x,y) + texcoord(s,t)
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
        )
    }

    @Volatile var isPlaying = false; private set
    @Volatile private var stopped = false
    var durationMs: Long = 0L; private set
    var videoWidth: Int = 0; private set
    var videoHeight: Int = 0; private set
    var fps: Int = 30; private set

    // EGL
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    // GL
    private var program = 0
    private var texId = 0
    private var fboId = 0
    private var fboTexId = 0
    private var vertexBuffer: FloatBuffer? = null

    // MediaCodec
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var codecSurface: android.view.Surface? = null

    private var decodeThread: Thread? = null

    fun start(uriStr: String, scaleMode: ScaleMode, screenW: Float, screenH: Float) {
        stopped = false
        isPlaying = true

        decodeThread = Thread({
            decodeLoop(uriStr, scaleMode, screenW, screenH)
        }, "VideoDecode").also { it.start() }
    }

    fun pause() { isPlaying = false }
    fun resume() { isPlaying = true }

    fun release() {
        stopped = true
        isPlaying = false
        decodeThread?.interrupt()
        decodeThread = null
    }

    private fun decodeLoop(uriStr: String, scaleMode: ScaleMode, screenW: Float, screenH: Float) {
        try {
            if (!setupEgl()) { Log.e(TAG, "EGL setup failed"); return }
            setupGl()
            if (!setupCodec(uriStr)) { Log.e(TAG, "Codec setup failed"); cleanup(); return }

            val st = surfaceTexture ?: return
            val dec = decoder ?: return
            val ext = extractor ?: return

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            val intervalNs = (1_000_000_000L / fps).coerceAtLeast(16_000_000L)
            val bitmapW = videoWidth
            val bitmapH = videoHeight

            while (!stopped && !Thread.interrupted()) {
                if (!isPlaying) { Thread.sleep(50); continue }

                val startNs = System.nanoTime()

                // Feed input
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

                // Get output
                val outIdx = dec.dequeueOutputBuffer(bufferInfo, 1000)
                if (outIdx >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        dec.releaseOutputBuffer(outIdx, false)
                        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        inputDone = false
                        continue
                    }

                    dec.releaseOutputBuffer(outIdx, true)
                    st.updateTexImage()

                    val bitmap = drawTextureToBitmap(st, bitmapW, bitmapH)
                    if (bitmap != null) {
                        mainHandler.post {
                            if (!stopped) drawToCanvas(bitmap, scaleMode, screenW, screenH)
                            bitmap.recycle()
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
            cleanup()
        }
    }

    private fun drawToCanvas(bitmap: Bitmap, scaleMode: ScaleMode, screenW: Float, screenH: Float) {
        try {
            val canvas = holder.lockCanvas() ?: return
            canvas.drawColor(Color.BLACK)
            val dest = calcDestRect(bitmap.width.toFloat(), bitmap.height.toFloat(), screenW, screenH, scaleMode)
            canvas.drawBitmap(bitmap, null, dest, null)
            holder.unlockCanvasAndPost(canvas)
        } catch (_: Exception) {}
    }

    private val destRect = RectF()
    private fun calcDestRect(bw: Float, bh: Float, sw: Float, sh: Float, scaleMode: ScaleMode): RectF {
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
        return destRect
    }

    // ======== EGL Setup ========

    private fun setupEgl(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: return false

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        val pbufAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    // ======== GL Setup ========

    private fun setupGl() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        vertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer?.put(QUAD_VERTICES)?.position(0)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        surfaceTexture = SurfaceTexture(texId)

        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]

        val fboTextures = IntArray(1)
        GLES20.glGenTextures(1, fboTextures, 0)
        fboTexId = fboTextures[0]
    }

    private fun setupCodec(uriStr: String): Boolean {
        val ext = MediaExtractor()
        ext.setDataSource(context, Uri.parse(uriStr), null)
        extractor = ext

        val trackIdx = (0 until ext.trackCount).firstOrNull { i ->
            ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: return false

        ext.selectTrack(trackIdx)
        val format = ext.getTrackFormat(trackIdx)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
        fps = format.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30).coerceIn(15, 60)

        Log.d(TAG, "Video: ${videoWidth}x${videoHeight} @ ${fps}fps, mime=$mime")

        val st = surfaceTexture ?: return false
        st.setDefaultBufferSize(videoWidth, videoHeight)
        codecSurface = android.view.Surface(st)

        val dec = MediaCodec.createDecoderByType(mime)
        decoder = dec
        dec.configure(format, codecSurface, null, 0)
        dec.start()
        return true
    }

    // ======== GL Rendering: SurfaceTexture → FBO → Bitmap ========

    private fun drawTextureToBitmap(st: SurfaceTexture, width: Int, height: Int): Bitmap? {
        return try {
            // Setup FBO texture for this frame size
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexId)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, fboTexId, 0)

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Draw SurfaceTexture to FBO using shader
            GLES20.glUseProgram(program)

            // Texture matrix from SurfaceTexture (handles video orientation)
            val texMatrix = FloatArray(16)
            st.getTransformMatrix(texMatrix)
            val matrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            GLES20.glUniformMatrix4fv(matrixLoc, 1, false, texMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

            val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
            val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            vertexBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            vertexBuffer?.position(2)
            GLES20.glEnableVertexAttribArray(texLoc)
            GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Read pixels from FBO
            val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            buf.position(0)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buf)

            // Flip vertically (GL origin is bottom-left)
            val matrix = Matrix()
            matrix.postScale(1f, -1f, width / 2f, height / 2f)
            val flipped = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
            if (flipped !== bitmap) bitmap.recycle()

            // Unbind
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            flipped
        } catch (e: Exception) {
            Log.e(TAG, "drawTextureToBitmap error: ${e.message}")
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            null
        }
    }

    // ======== Cleanup ========

    private fun cleanup() {
        stopped = true
        isPlaying = false

        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        try { codecSurface?.release() } catch (_: Exception) {}
        codecSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null

        if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
        if (texId != 0) { GLES20.glDeleteTextures(1, intArrayOf(texId), 0); texId = 0 }
        if (fboId != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0); fboId = 0 }
        if (fboTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(fboTexId), 0); fboTexId = 0 }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
    }

    // ======== GL Helpers ========

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

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return try { if (containsKey(key)) getInteger(key) else default } catch (_: Exception) { default }
    }
}
