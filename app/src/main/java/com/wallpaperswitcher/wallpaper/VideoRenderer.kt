package com.wallpaperswitcher.wallpaper

import android.graphics.SurfaceTexture
import android.opengl.*
import android.view.Surface
import com.wallpaperswitcher.data.ScaleMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL-based video renderer.
 * Renders SurfaceTexture frames to an EGL Surface with fill/fit/stretch scaling.
 */
class VideoRenderer {

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var eglConfig: EGLConfig? = null

    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private var videoWidth = 0
    private var videoHeight = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var scaleMode = ScaleMode.FIT

    private val vertexCoords = floatArrayOf(
        -1f, -1f,  // bottom left
         1f, -1f,  // bottom right
        -1f,  1f,  // top left
         1f,  1f   // top right
    )

    private var texCoords: FloatBuffer? = null
    private var vertexBuffer: FloatBuffer? = null

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }

    /**
     * Initialize EGL and OpenGL resources.
     * Must be called from the thread that will render.
     */
    fun init(surface: Surface, width: Int, height: Int): Boolean {
        return try {
            screenWidth = width
            screenHeight = height

            // EGL setup
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            eglConfig = configs[0]

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            // Compile shaders
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)

            // Create texture
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Create SurfaceTexture
            surfaceTexture = SurfaceTexture(textureId)
            inputSurface = Surface(surfaceTexture)

            // Vertex buffer
            vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertexBuffer?.put(vertexCoords)?.position(0)

            true
        } catch (e: Exception) {
            android.util.Log.e("VideoRenderer", "init failed", e)
            false
        }
    }

    /**
     * Get the Surface that MediaCodec should output to.
     */
    fun getInputSurface(): Surface? = inputSurface

    /**
     * Set video dimensions and scale mode.
     */
    fun setVideoSize(width: Int, height: Int, mode: ScaleMode) {
        videoWidth = width
        videoHeight = height
        scaleMode = mode
        updateTexCoords()
    }

    /**
     * Render the latest frame to the screen.
     */
    fun drawFrame() {
        surfaceTexture?.updateTexImage()

        GLES20.glViewport(0, 0, screenWidth, screenHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Update texture coordinates based on scale mode.
     */
    private fun updateTexCoords() {
        if (videoWidth <= 0 || videoHeight <= 0) return

        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()

        val coords = when (scaleMode) {
            ScaleMode.FIT -> {
                // Fit: show entire video, may have black bars
                // Texture coords stay at 0-1, the video is centered by aspect ratio
                // We adjust the vertex coords instead
                floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            }
            ScaleMode.FILL -> {
                // Fill: crop to fill screen
                // Adjust texture coords to crop the video
                if (videoRatio > screenRatio) {
                    // Video is wider - crop sides
                    val crop = (1f - screenRatio / videoRatio) / 2f
                    floatArrayOf(crop, 1f, 1f - crop, 1f, crop, 0f, 1f - crop, 0f)
                } else {
                    // Video is taller - crop top/bottom
                    val crop = (1f - videoRatio / screenRatio) / 2f
                    floatArrayOf(0f, 1f - crop, 1f, 1f - crop, 0f, crop, 1f, crop)
                }
            }
            ScaleMode.STRETCH -> {
                // Stretch: fill entire screen, ignore aspect ratio
                floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
            }
        }

        // Also adjust vertex coords for FIT mode
        if (scaleMode == ScaleMode.FIT) {
            val verts: FloatArray
            if (videoRatio > screenRatio) {
                // Video is wider - fit horizontally, add bars top/bottom
                val h = screenRatio / videoRatio
                verts = floatArrayOf(-1f, -h, 1f, -h, -1f, h, 1f, h)
            } else {
                // Video is taller - fit vertically, add bars left/right
                val w = videoRatio / screenRatio
                verts = floatArrayOf(-w, -1f, w, -1f, -w, 1f, w, 1f)
            }
            vertexBuffer?.clear()
            vertexBuffer?.put(verts)?.position(0)
        } else {
            vertexBuffer?.clear()
            vertexBuffer?.put(vertexCoords)?.position(0)
        }

        texCoords = ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        texCoords?.put(coords)?.position(0)
    }

    /**
     * Update screen size (e.g., on rotation).
     */
    fun updateScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        updateTexCoords()
    }

    /**
     * Release all resources.
     */
    fun release() {
        try {
            inputSurface?.release()
            surfaceTexture?.release()
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
            }
            eglDisplay?.let { display ->
                eglSurface?.let { EGL14.eglDestroySurface(display, it) }
                eglContext?.let { EGL14.eglDestroyContext(display, it) }
                EGL14.eglTerminate(display)
            }
        } catch (_: Exception) {}
        inputSurface = null
        surfaceTexture = null
        eglDisplay = null
        eglContext = null
        eglSurface = null
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}
