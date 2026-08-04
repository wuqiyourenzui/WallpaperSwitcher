package com.wallpaperswitcher.wallpaper

import android.graphics.SurfaceTexture
import android.opengl.*
import android.view.Surface
import com.wallpaperswitcher.data.ScaleMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL video renderer with fill/fit/stretch support.
 * Create new instance for each video playback.
 */
class VideoRenderer(
    private val targetSurface: Surface,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    private var program = 0
    private var textureId = 0
    var surfaceTexture: SurfaceTexture? = null
        private set

    private var vertexBuffer: FloatBuffer? = null
    private var texBuffer: FloatBuffer? = null

    private var initialized = false

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

        private val FULL_QUAD_VERTS = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        )
        private val FULL_QUAD_TEX = floatArrayOf(
            0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
        )
    }

    fun init(): Boolean {
        try {
            // EGL
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)

            val cfgAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, cfgs, 0, 1, num, 0)
            val cfg = cfgs[0] ?: return false

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, targetSurface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            // Shader program
            val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)

            // Texture
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            textureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            // SurfaceTexture
            surfaceTexture = SurfaceTexture(textureId)

            // Buffers
            vertexBuffer = makeBuffer(FULL_QUAD_VERTS)
            texBuffer = makeBuffer(FULL_QUAD_TEX)

            initialized = true
            return true
        } catch (e: Exception) {
            android.util.Log.e("VideoRenderer", "init failed", e)
            return false
        }
    }

    fun configureScale(videoW: Int, videoH: Int, mode: ScaleMode) {
        if (!initialized) return
        val vr = videoW.toFloat() / videoH.toFloat()
        val sr = screenWidth.toFloat() / screenHeight.toFloat()

        when (mode) {
            ScaleMode.FIT -> {
                val verts: FloatArray
                if (vr > sr) {
                    val h = sr / vr
                    verts = floatArrayOf(-1f, -h, 1f, -h, -1f, h, 1f, h)
                } else {
                    val w = vr / sr
                    verts = floatArrayOf(-w, -1f, w, -1f, -w, 1f, w, 1f)
                }
                vertexBuffer = makeBuffer(verts)
                texBuffer = makeBuffer(FULL_QUAD_TEX)
            }
            ScaleMode.FILL -> {
                vertexBuffer = makeBuffer(FULL_QUAD_VERTS)
                val tex: FloatArray
                if (vr > sr) {
                    val c = (1f - sr / vr) / 2f
                    tex = floatArrayOf(c, 1f, 1f - c, 1f, c, 0f, 1f - c, 0f)
                } else {
                    val c = (1f - vr / sr) / 2f
                    tex = floatArrayOf(0f, 1f - c, 1f, 1f - c, 0f, c, 1f, c)
                }
                texBuffer = makeBuffer(tex)
            }
            ScaleMode.STRETCH -> {
                vertexBuffer = makeBuffer(FULL_QUAD_VERTS)
                texBuffer = makeBuffer(FULL_QUAD_TEX)
            }
        }
    }

    fun drawFrame() {
        if (!initialized) return
        surfaceTexture?.updateTexImage()

        GLES20.glViewport(0, 0, screenWidth, screenHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        val texUni = GLES20.glGetUniformLocation(program, "uTexture")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(texUni, 0)

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        try { surfaceTexture?.release() } catch (_: Exception) {}
        try {
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
        } catch (_: Exception) {}
        try {
            eglDisplay?.let { d ->
                eglSurface?.let { EGL14.eglDestroySurface(d, it) }
                eglContext?.let { EGL14.eglDestroyContext(d, it) }
                EGL14.eglTerminate(d)
            }
        } catch (_: Exception) {}
        surfaceTexture = null
        initialized = false
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun makeBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(data); position(0)
            }
    }
}
