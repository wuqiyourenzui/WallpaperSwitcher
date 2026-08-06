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
 * Hardware-accelerated video renderer using MediaCodec + SurfaceTexture + EGL window surface.
 *
 * Pipeline (all on dedicated decode thread, zero CPU readback):
 *   MediaExtractor → MediaCodec (HW decoder) → SurfaceTexture (GL external texture)
 *   → GL shader renders texture directly to EGL window surface (the SurfaceHolder)
 *   → eglSwapBuffers presents the frame
 *
 * No FBO, no glReadPixels, no Bitmap, no main-thread posting.
 * Video frames stay entirely on the GPU from decode to display.
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
    private var vertexBuffer: FloatBuffer? = null

    // Cached uniform/attribute locations
    private var uTexMatrixLoc = -1
    private var uTextureLoc = -1
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1

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
        decodeThread?.let { thread ->
            thread.interrupt()
            try { thread.join(2000) } catch (_: InterruptedException) {}
            if (thread.isAlive) {
                Log.w(TAG, "Decode thread did not stop in time, forcing cleanup")
                cleanup()
            }
        }
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

            // Pre-compute display quad and cache GL locations
            updateQuad(scaleMode, screenW, screenH)
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")

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
                val outIdx = dec.dequeueOutputBuffer(bufferInfo, 100)
                if (outIdx >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        dec.releaseOutputBuffer(outIdx, false)
                        dec.flush()
                        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        inputDone = false
                        continue
                    }

                    // Render frame to SurfaceTexture, then draw to screen via GL
                    dec.releaseOutputBuffer(outIdx, true)
                    st.updateTexImage()
                    drawFrame(st)
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface)

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

    // ======== GL Rendering (direct to screen, no readback) ========

    private fun drawFrame(st: SurfaceTexture) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // SurfaceTexture transform matrix handles video orientation/crop
        val texMatrix = FloatArray(16)
        st.getTransformMatrix(texMatrix)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(uTextureLoc, 0)

        vertexBuffer?.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer?.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Compute quad vertices for the given scale mode.
     * Vertex format: x, y, s, t (position + texcoord)
     *
     * FIT:   maintain aspect ratio, letterbox
     * FILL:  maintain aspect ratio, crop to fill (viewport clips overflow)
     * STRETCH: stretch to fill
     */
    private fun updateQuad(scaleMode: ScaleMode, screenW: Float, screenH: Float) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val va = videoWidth.toFloat() / videoHeight.toFloat()
        val sa = screenW / screenH

        val (dw, dh) = when (scaleMode) {
            ScaleMode.FIT -> {
                if (va > sa) Pair(1f, sa / va) else Pair(va / sa, 1f)
            }
            ScaleMode.FILL -> {
                // Quad larger than screen → viewport clips overflow
                if (va > sa) Pair(va / sa, 1f) else Pair(1f, sa / va)
            }
            ScaleMode.STRETCH -> Pair(1f, 1f)
        }

        val vertices = floatArrayOf(
            -dw, -dh, 0f, 1f,  // bottom-left
             dw, -dh, 1f, 1f,  // bottom-right
            -dw,  dh, 0f, 0f,  // top-left
             dw,  dh, 1f, 0f,  // top-right
        )

        vertexBuffer?.put(vertices)?.position(0)
    }

    // ======== EGL Setup (window surface from SurfaceHolder) ========

    private fun setupEgl(): Boolean {
        val surface = holder.surface
        if (!surface.isValid) {
            Log.e(TAG, "SurfaceHolder surface is not valid")
            return false
        }

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: return false

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        // Create window surface from SurfaceHolder (not pbuffer)
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

        // Set viewport to match surface size
        val w = EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH)
        val h = EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        return true
    }

    // ======== GL Setup ========

    private fun setupGl() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        vertexBuffer = ByteBuffer.allocateDirect(16 * 4) // 4 vertices * 4 floats * 4 bytes
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(texId)
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

        // Cap to 720p for performance (wallpaper doesn't need full resolution)
        val maxDim = maxOf(videoWidth, videoHeight)
        if (maxDim > 1280) {
            val scale = 1280f / maxDim
            videoWidth = (videoWidth * scale).toInt().and(0xFFFFFFFE.toInt())
            videoHeight = (videoHeight * scale).toInt().and(0xFFFFFFFE.toInt())
        }

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
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return try { if (containsKey(key)) getInteger(key) else default } catch (_: Exception) { default }
    }
}
