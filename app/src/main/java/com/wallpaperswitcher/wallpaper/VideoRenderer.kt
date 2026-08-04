package com.wallpaperswitcher.wallpaper

import android.graphics.SurfaceTexture
import android.opengl.*
import android.view.Surface
import com.wallpaperswitcher.data.ScaleMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL video renderer with scaling support.
 * Renders SurfaceTexture frames to the wallpaper Surface.
 * Single instance, reused across video switches.
 */
class VideoRenderer {

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
        private const val TAG = "VideoRenderer"
        private const val VS = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = aTex; }
        """
        private const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """
    }

    fun init(targetSurface: Surface, w: Int, h: Int): Boolean {
        if (initialized) return true
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)

            val cfgAttr = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, 4, EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, num, 0)
            val cfg = cfgs[0] ?: return false

            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, targetSurface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val vs = loadShader(GLES20.GL_VERTEX_SHADER, VS)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FS)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            textureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            surfaceTexture = SurfaceTexture(textureId)
            vertexBuffer = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            texBuffer = buf(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

            initialized = true
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "init failed", e)
            return false
        }
    }

    fun isInitialized() = initialized

    fun configureScale(videoW: Int, videoH: Int, screenW: Int, screenH: Int, mode: ScaleMode) {
        if (!initialized) return
        val vr = videoW.toFloat() / videoH.toFloat()
        val sr = screenW.toFloat() / screenH.toFloat()

        when (mode) {
            ScaleMode.FIT -> {
                val v: FloatArray
                if (vr > sr) { val h = sr / vr; v = floatArrayOf(-1f, -h, 1f, -h, -1f, h, 1f, h) }
                else { val w = vr / sr; v = floatArrayOf(-w, -1f, w, -1f, -w, 1f, w, 1f) }
                vertexBuffer = buf(v)
                texBuffer = buf(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
            }
            ScaleMode.FILL -> {
                vertexBuffer = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                val t: FloatArray
                if (vr > sr) { val c = (1f - sr / vr) / 2f; t = floatArrayOf(c, 1f, 1f - c, 1f, c, 0f, 1f - c, 0f) }
                else { val c = (1f - vr / sr) / 2f; t = floatArrayOf(0f, 1f - c, 1f, 1f - c, 0f, c, 1f, c) }
                texBuffer = buf(t)
            }
            ScaleMode.STRETCH -> {
                vertexBuffer = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                texBuffer = buf(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))
            }
        }
    }

    fun drawFrame(screenW: Int, screenH: Int) {
        if (!initialized) return
        surfaceTexture?.updateTexImage()

        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val pLoc = GLES20.glGetAttribLocation(program, "aPos")
        val tLoc = GLES20.glGetAttribLocation(program, "aTex")
        val uLoc = GLES20.glGetUniformLocation(program, "uTex")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uLoc, 0)

        GLES20.glEnableVertexAttribArray(pLoc)
        GLES20.glVertexAttribPointer(pLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(tLoc)
        GLES20.glVertexAttribPointer(tLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(pLoc)
        GLES20.glDisableVertexAttribArray(tLoc)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    fun release() {
        try { surfaceTexture?.release() } catch (_: Exception) {}
        try { if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) } catch (_: Exception) {}
        try { if (program != 0) GLES20.glDeleteProgram(program) } catch (_: Exception) {}
        try {
            eglDisplay?.let { d ->
                eglSurface?.let { EGL14.eglDestroySurface(d, it) }
                eglContext?.let { EGL14.eglDestroyContext(d, it) }
                EGL14.eglTerminate(d)
            }
        } catch (_: Exception) {}
        surfaceTexture = null
        eglDisplay = null; eglContext = null; eglSurface = null
        initialized = false
    }

    private fun loadShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun buf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }
}
