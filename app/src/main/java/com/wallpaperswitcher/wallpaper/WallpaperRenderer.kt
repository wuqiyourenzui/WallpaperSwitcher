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

/**
 * Unified EGL renderer for both images and videos on a WallpaperService surface.
 *
 * Uses a single EGL context + window surface for ALL rendering.
 * This avoids the Canvas→EGL surface state conflict that causes video
 * to fail after an image has been displayed via Canvas.
 *
 * Image pipeline: Bitmap → GL_TEXTURE_2D → shader → eglSwapBuffers
 * Video pipeline:  MediaCodec → SurfaceTexture → GL_TEXTURE_EXTERNAL_OES → shader → eglSwapBuffers
 */
class WallpaperRenderer(
    private val context: Context,
    private val holder: SurfaceHolder
) {
    companion object {
        private const val TAG = "WallpaperRenderer"

        // Shared vertex shader
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

        // Fragment shader for images (sampler2D)
        private const val IMAGE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // Fragment shader for videos (samplerExternalOES)
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

    // EGL
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglReady = false

    // GL programs
    private var imageProgram = 0
    private var videoProgram = 0
    private var vertexBuffer: FloatBuffer? = null

    // Image rendering
    private var imageTexId = 0
    private var imageTexMatrix = FloatArray(16)

    // Video rendering
    private var videoTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var codecSurface: android.view.Surface? = null
    private var extractor: MediaExtractor? = null
    private var decoder: MediaCodec? = null
    private var videoDecodeThread: Thread? = null
    @Volatile var isVideoPlaying = false; private set
    @Volatile private var videoStopped = false
    @Volatile private var videoLooping = true

    // Render thread (all GL ops happen here)
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var screenW = 0f
    private var screenH = 0f

    // ======== Lifecycle ========

    fun initialize(sw: Float, sh: Float) {
        screenW = sw
        screenH = sh

        val thread = HandlerThread("WallpaperRenderer")
        thread.start()
        renderThread = thread
        renderHandler = Handler(thread.looper)

        renderHandler?.post {
            if (!setupEgl()) {
                Log.e(TAG, "EGL setup failed")
            } else {
                setupGl()
                Log.d(TAG, "EGL renderer initialized ${sw}x${sh}")
            }
        }
    }

    fun release() {
        stopVideo()
        val handler = renderHandler ?: return
        handler.post {
            cleanup()
            renderThread?.quitSafely()
        }
        renderThread = null
        renderHandler = null
    }

    // ======== Image Rendering (EGL, no Canvas) ========

    fun showImage(bitmap: Bitmap, scaleMode: ScaleMode) {
        val handler = renderHandler ?: return
        handler.post {
            if (!eglReady) return@post
            renderImage(bitmap, scaleMode)
        }
    }

    private fun renderImage(bitmap: Bitmap, scaleMode: ScaleMode) {
        try {
            // Upload bitmap to texture
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            // Compute transform matrix (identity for images)
            android.opengl.Matrix.setIdentityM(imageTexMatrix, 0)

            // Compute quad for scale mode
            val quad = computeQuad(bitmap.width.toFloat(), bitmap.height.toFloat(), scaleMode)
            vertexBuffer?.put(quad)?.position(0)

            // Render
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

    // ======== Video Rendering (MediaCodec + SurfaceTexture + EGL) ========

    fun startVideo(uriStr: String, scaleMode: ScaleMode) {
        stopVideo()
        videoStopped = false
        isVideoPlaying = true

        val handler = renderHandler ?: return
        handler.post {
            if (!eglReady) {
                Log.e(TAG, "startVideo: EGL not ready")
                isVideoPlaying = false
                return@post
            }
            startVideoInternal(uriStr, scaleMode)
        }
    }

    private fun startVideoInternal(uriStr: String, scaleMode: ScaleMode) {
        try {
            // Setup extractor
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

            // Cap to 720p
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

            // Setup SurfaceTexture
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

            // Setup decoder
            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(format, codecSurface, null, 0)
            dec.start()

            Log.d(TAG, "Video started: ${videoW}x${videoH} @ ${fps}fps")

            // Compute quad
            val quad = computeQuad(videoW.toFloat(), videoH.toFloat(), scaleMode)
            vertexBuffer?.put(quad)?.position(0)

            // Start decode loop on a dedicated thread
            videoDecodeThread = Thread({
                decodeLoop(ext, dec, st, intervalNs, videoW, videoH)
            }, "VideoDecode").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "startVideo error: ${e.message}", e)
            isVideoPlaying = false
            cleanupVideo()
        }
    }

    private fun decodeLoop(
        ext: MediaExtractor, dec: MediaCodec, st: SurfaceTexture,
        intervalNs: Long, videoW: Int, videoH: Int
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        val texMatrix = FloatArray(16)

        while (!videoStopped && !Thread.interrupted()) {
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
            val outIdx = dec.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIdx >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    dec.releaseOutputBuffer(outIdx, false)
                    if (videoLooping && !videoStopped) {
                        dec.flush()
                        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        inputDone = false
                        continue
                    }
                    break
                }

                dec.releaseOutputBuffer(outIdx, true)
                st.updateTexImage()
                st.getTransformMatrix(texMatrix)

                // Render frame via EGL (on render thread)
                renderHandler?.post {
                    if (!videoStopped && eglReady) {
                        renderVideoFrame(texMatrix)
                    }
                }

                val elapsedNs = System.nanoTime() - startNs
                val sleepNs = intervalNs - elapsedNs
                if (sleepNs > 0) Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Thread.sleep(1)
            }
        }

        isVideoPlaying = false
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

    fun stopVideo() {
        videoStopped = true
        isVideoPlaying = false
        videoDecodeThread?.let { thread ->
            thread.interrupt()
            try { thread.join(2000) } catch (_: InterruptedException) {}
        }
        videoDecodeThread = null
        cleanupVideo()
    }

    private fun cleanupVideo() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        decoder = null
        try { codecSurface?.release() } catch (_: Exception) {}
        codecSurface = null
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
    }

    // ======== EGL Setup ========

    private fun setupEgl(): Boolean {
        val surface = holder.surface
        if (!surface.isValid) {
            Log.e(TAG, "Surface not valid")
            return false
        }

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false

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
        val config = configs[0] ?: return false

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

        val queryResult = IntArray(2)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, queryResult, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, queryResult, 1)
        GLES20.glViewport(0, 0, queryResult[0], queryResult[1])
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        eglReady = true
        return true
    }

    private fun setupGl() {
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

    private fun cleanup() {
        eglReady = false
        stopVideo()

        if (imageProgram != 0) { GLES20.glDeleteProgram(imageProgram); imageProgram = 0 }
        if (videoProgram != 0) { GLES20.glDeleteProgram(videoProgram); videoProgram = 0 }
        if (imageTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(imageTexId), 0); imageTexId = 0 }
        if (videoTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(videoTexId), 0); videoTexId = 0 }

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

    // ======== Helpers ========

    private fun computeQuad(imgW: Float, imgH: Float, scaleMode: ScaleMode): FloatArray {
        if (imgW <= 0 || imgH <= 0 || screenW <= 0 || screenH <= 0) {
            return floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)
        }
        val va = imgW / imgH
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
}
