package com.wallpaperswitcher.wallpaper

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import com.wallpaperswitcher.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GL programs, uniform/attribute locations, textures and quads, from
 * `WallpaperRenderer` (逻辑逐字搬移). Created once and surviving surface
 * recreation; the renderer's render paths read the handles through [gl].
 */
internal class GlResources(private val tag: String) {

    // GL resources (created once, survive surface recreation)
    var imageProgram = 0
    var videoProgram = 0
    // Cached shader locations: queried once per program creation instead of
    // 6 times per rendered frame (at 30fps that is ~180 driver queries/sec).
    var imageTexMatLoc = -1
    var imageTexLoc = -1
    var imagePosLoc = -1
    var imageTcLoc = -1
    var videoTexMatLoc = -1
    var videoTexLoc = -1
    var videoPosLoc = -1
    var videoTcLoc = -1
    // Sharpening uniforms (queried once per program creation).
    var imageTexelLoc = -1
    var imageSharpLoc = -1
    var imageAlphaLoc = -1
    var videoTexelLoc = -1
    var videoSharpLoc = -1
    // 画质增强 uniforms (super-resolution strength + source texel size).
    var imageEnhanceLoc = -1
    var imageSrcTexelLoc = -1
    var videoEnhanceLoc = -1
    var videoSrcTexelLoc = -1
    var imageDenoiseLoc = -1
    var videoDenoiseLoc = -1
    var imageEnhanceModeLoc = -1
    var imageEasuScaleLoc = -1
    var videoEnhanceModeLoc = -1
    var videoEasuScaleLoc = -1

    var vertexBuffer: FloatBuffer? = null
    var imageTexId = 0
    var imageTexMatrix = FloatArray(16)
    // 1x1 opaque black texture + full-screen quad: drawn under every media so
    // the FIT/letterbox area always contains freshly presented black pixels
    // instead of whatever was left in the framebuffer (e.g. the previous
    // video's last frame), even on devices/drivers where glClear alone does
    // not invalidate the whole window surface.
    var blackTexId = 0
    var backgroundBuffer: FloatBuffer? = null

    fun setup() {
        imageProgram = createProgram(GlShaders.VERTEX_SHADER, GlShaders.IMAGE_FRAGMENT_SHADER)
        videoProgram = createProgram(GlShaders.VERTEX_SHADER, GlShaders.VIDEO_FRAGMENT_SHADER)
        // A driver that rejects the enhancement shader must not leave a black
        // wallpaper behind: fall back to the pre-enhancement source (the extra
        // uniform locations stay -1 and their glUniform calls are ignored).
        if (imageProgram == 0) {
            AppLog.w(tag, "Enhanced image shader failed; using the classic source")
            imageProgram = createProgram(
                GlShaders.VERTEX_SHADER, GlShaders.IMAGE_FRAGMENT_SHADER_FALLBACK
            )
        }
        if (videoProgram == 0) {
            AppLog.w(tag, "Enhanced video shader failed; using the classic source")
            videoProgram = createProgram(
                GlShaders.VERTEX_SHADER, GlShaders.VIDEO_FRAGMENT_SHADER_FALLBACK
            )
        }
        imageTexMatLoc = GLES20.glGetUniformLocation(imageProgram, "uTexMatrix")
        imageTexLoc = GLES20.glGetUniformLocation(imageProgram, "uTexture")
        imagePosLoc = GLES20.glGetAttribLocation(imageProgram, "aPosition")
        imageTcLoc = GLES20.glGetAttribLocation(imageProgram, "aTexCoord")
        imageTexelLoc = GLES20.glGetUniformLocation(imageProgram, "uTexelSize")
        imageSharpLoc = GLES20.glGetUniformLocation(imageProgram, "uSharp")
        imageAlphaLoc = GLES20.glGetUniformLocation(imageProgram, "uAlpha")
        imageEnhanceLoc = GLES20.glGetUniformLocation(imageProgram, "uEnhance")
        imageSrcTexelLoc = GLES20.glGetUniformLocation(imageProgram, "uSrcTexel")
        imageDenoiseLoc = GLES20.glGetUniformLocation(imageProgram, "uDenoise")
        imageEnhanceModeLoc = GLES20.glGetUniformLocation(imageProgram, "uEnhanceMode")
        imageEasuScaleLoc = GLES20.glGetUniformLocation(imageProgram, "uEasuScale")
        videoTexMatLoc = GLES20.glGetUniformLocation(videoProgram, "uTexMatrix")
        videoTexLoc = GLES20.glGetUniformLocation(videoProgram, "uTexture")
        videoPosLoc = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        videoTcLoc = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
        videoTexelLoc = GLES20.glGetUniformLocation(videoProgram, "uTexelSize")
        videoSharpLoc = GLES20.glGetUniformLocation(videoProgram, "uSharp")
        videoEnhanceLoc = GLES20.glGetUniformLocation(videoProgram, "uEnhance")
        videoSrcTexelLoc = GLES20.glGetUniformLocation(videoProgram, "uSrcTexel")
        videoDenoiseLoc = GLES20.glGetUniformLocation(videoProgram, "uDenoise")
        videoEnhanceModeLoc = GLES20.glGetUniformLocation(videoProgram, "uEnhanceMode")
        videoEasuScaleLoc = GLES20.glGetUniformLocation(videoProgram, "uEasuScale")
        vertexBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        backgroundBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f))
            position(0)
        }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        imageTexId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.Matrix.setIdentityM(imageTexMatrix, 0)

        val black = IntArray(1)
        GLES20.glGenTextures(1, black, 0)
        blackTexId = black[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blackTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val blackBmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        blackBmp.eraseColor(Color.BLACK)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, blackBmp, 0)
        blackBmp.recycle()
    }

    fun cleanup() {
        if (imageProgram != 0) { GLES20.glDeleteProgram(imageProgram); imageProgram = 0 }
        if (videoProgram != 0) { GLES20.glDeleteProgram(videoProgram); videoProgram = 0 }
        if (imageTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(imageTexId), 0); imageTexId = 0 }
        if (blackTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(blackTexId), 0); blackTexId = 0 }
        vertexBuffer = null
        backgroundBuffer = null
    }

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
            AppLog.e(tag, "Program link error: ${GLES20.glGetProgramInfoLog(p)}")
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
            AppLog.e(tag, "Shader compile error: ${GLES20.glGetShaderInfoLog(s)}")
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }
}
