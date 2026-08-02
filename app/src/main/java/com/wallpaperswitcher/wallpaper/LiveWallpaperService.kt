package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import com.wallpaperswitcher.data.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class LiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "LiveWallpaperService"
        const val ACTION_SWITCH = "com.wallpaperswitcher.ACTION_SWITCH"
    }

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val mainHandler = Handler(Looper.getMainLooper())
        private lateinit var db: AppDatabase
        private var surfaceReady = false
        private var isVisible = false
        private var isSwitching = false
        private var currentBitmap: Bitmap? = null
        private var currentScaleMode: ScaleMode = ScaleMode.FIT

        // Video playback with OpenGL
        private var mediaPlayer: MediaPlayer? = null
        private var videoRenderer: VideoRenderer? = null
        private var videoRenderRunnable: Runnable? = null

        // GIF playback
        private var gifDrawable: android.graphics.drawable.AnimatedImageDrawable? = null
        private var gifFrameRunnable: Runnable? = null

        private val switchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_SWITCH) doSwitch("broadcast")
            }
        }

        private val gestureDetector = GestureDetector(
            applicationContext,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    doSwitch("double-tap")
                    return true
                }
            }
        )

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            db = AppDatabase.getInstance(applicationContext)
            setTouchEventsEnabled(true)
            try {
                applicationContext.registerReceiver(switchReceiver, IntentFilter(ACTION_SWITCH))
            } catch (_: Exception) {}
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            surfaceReady = true
            drawCurrentImage()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            drawCurrentImage()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            stopVideo()
            stopGif()
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) drawCurrentImage()
            else { stopVideo(); stopGif() }
        }

        override fun onDestroy() {
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            stopVideo()
            stopGif()
            currentBitmap?.recycle(); currentBitmap = null
            scope.cancel()
            super.onDestroy()
        }

        // ======== Switch logic ========

        private fun doSwitch(source: String) {
            if (isSwitching) return
            isSwitching = true
            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageDao = db.wallpaperImageDao()
                    val groupDao = db.wallpaperGroupDao()

                    val groups = groupDao.getEnabledGroupsSync()
                    if (groups.isEmpty()) { isSwitching = false; return@launch }

                    val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    val switchMode = try {
                        SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                    } catch (_: Exception) { SwitchMode.RANDOM }
                    val scaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }

                    val nextImage = when (switchMode) {
                        SwitchMode.RANDOM -> {
                            imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                                ?: imageDao.getRandomImageFromEnabledGroups()
                        }
                        SwitchMode.SEQUENTIAL -> {
                            val count = imageDao.countByEnabledGroups()
                            if (count == 0) null
                            else {
                                val idx = dao.getLong(SettingsKeys.SEQUENTIAL_INDEX).toInt()
                                val next = (idx + 1) % count
                                dao.setLong(SettingsKeys.SEQUENTIAL_INDEX, next.toLong())
                                imageDao.getRandomImageFromEnabledGroups()
                            }
                        }
                        SwitchMode.SHUFFLE -> {
                            imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                                ?: imageDao.getRandomImageFromEnabledGroups()
                        }
                    }

                    if (nextImage == null) { isSwitching = false; return@launch }

                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
                    val mediaType = nextImage.mediaType ?: "IMAGE"

                    when (mediaType) {
                        "VIDEO" -> mainHandler.post { playVideo(nextImage.uri, scaleMode) }
                        "GIF" -> mainHandler.post { playGif(nextImage.uri, scaleMode) }
                        else -> {
                            val bitmap = loadBitmap(nextImage.uri)
                            if (bitmap != null) mainHandler.post { showBitmap(bitmap, scaleMode) }
                        }
                    }
                    Log.d(TAG, "$source: ${nextImage.displayName} ($mediaType)")
                } catch (e: Exception) {
                    Log.e(TAG, "$source error", e)
                } finally {
                    isSwitching = false
                }
            }
        }

        private fun drawCurrentImage() {
            if (!surfaceReady || !isVisible) return
            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    val scaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }
                    val image = if (imageId > 0) db.wallpaperImageDao().getImageById(imageId) else null
                    if (image != null) {
                        val mediaType = image.mediaType ?: "IMAGE"
                        when (mediaType) {
                            "VIDEO" -> { mainHandler.post { playVideo(image.uri, scaleMode) }; return@launch }
                            "GIF" -> { mainHandler.post { playGif(image.uri, scaleMode) }; return@launch }
                            else -> {
                                val bitmap = loadBitmap(image.uri)
                                if (bitmap != null) { mainHandler.post { showBitmap(bitmap, scaleMode) }; return@launch }
                            }
                        }
                    }
                    val first = db.wallpaperImageDao().getRandomImage()
                    if (first != null) {
                        dao.setLong(SettingsKeys.LAST_IMAGE_ID, first.id)
                        val bitmap = loadBitmap(first.uri)
                        if (bitmap != null) { mainHandler.post { showBitmap(bitmap, scaleMode) }; return@launch }
                    }
                    mainHandler.post { showDefault() }
                } catch (e: Exception) {
                    Log.e(TAG, "drawCurrentImage error", e)
                    mainHandler.post { showDefault() }
                }
            }
        }

        // ======== Video playback (MediaPlayer + OpenGL) ========

        private fun playVideo(uriStr: String, scaleMode: ScaleMode = ScaleMode.FIT) {
            stopVideo()
            stopGif()
            if (!surfaceReady) return
            try {
                videoRenderer = VideoRenderer()
                videoRenderer!!.initialize()

                val uri = Uri.parse(uriStr)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, uri)
                    isLooping = true
                    setSurface(videoRenderer!!.getSurface())
                    setOnPreparedListener { mp ->
                        videoRenderer!!.setVideoSize(mp.videoWidth, mp.videoHeight)
                        mp.start()
                        startVideoRendering(scaleMode)
                        Log.d(TAG, "Video started: ${mp.videoWidth}x${mp.videoHeight}")
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: $what / $extra")
                        stopVideo(); showDefault(); true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "playVideo failed", e)
                stopVideo()
            }
        }

        private fun startVideoRendering(scaleMode: ScaleMode) {
            stopVideoRendering()
            val runnable = object : Runnable {
                override fun run() {
                    if (!surfaceReady || !isVisible || mediaPlayer == null || videoRenderer == null) return
                    try {
                        val canvas = surfaceHolder.lockCanvas() ?: return
                        canvas.drawColor(Color.BLACK)
                        val m = getMetrics()
                        videoRenderer!!.drawFrame(canvas, m.widthPixels, m.heightPixels, scaleMode)
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {}
                    mainHandler.postDelayed(this, 33) // ~30fps
                }
            }
            videoRenderRunnable = runnable
            mainHandler.post(runnable)
        }

        private fun stopVideoRendering() {
            videoRenderRunnable?.let { mainHandler.removeCallbacks(it) }
            videoRenderRunnable = null
        }

        private fun stopVideo() {
            stopVideoRendering()
            try { mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() } } catch (_: Exception) {}
            mediaPlayer = null
            videoRenderer?.release()
            videoRenderer = null
        }

        // ======== GIF playback (ImageDecoder, API 28+) ========

        private fun playGif(uriStr: String, scaleMode: ScaleMode = ScaleMode.FIT) {
            stopGif()
            stopVideo()
            if (!surfaceReady) return
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28) playGifImageDecoder(uriStr, scaleMode)
                else { loadBitmap(uriStr)?.let { showBitmap(it, scaleMode) } }
            } catch (e: Exception) {
                Log.e(TAG, "playGif failed", e)
                loadBitmap(uriStr)?.let { showBitmap(it, scaleMode) }
            }
        }

        @android.annotation.TargetApi(28)
        private fun playGifImageDecoder(uriStr: String, scaleMode: ScaleMode) {
            val uri = Uri.parse(uriStr)
            val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
            val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                gifDrawable = drawable
                drawable.repeatCount = -1 // INFINITE
                drawable.start()
                val renderRunnable = object : Runnable {
                    override fun run() {
                        if (!surfaceReady || !isVisible || gifDrawable == null) return
                        try {
                            val canvas = surfaceHolder.lockCanvas() ?: return
                            canvas.drawColor(Color.BLACK)
                            val m = getMetrics()
                            val sw = m.widthPixels.toFloat(); val sh = m.heightPixels.toFloat()
                            val bw = drawable.intrinsicWidth.toFloat(); val bh = drawable.intrinsicHeight.toFloat()
                            val dest = calcDestRect(bw, bh, sw, sh, scaleMode)
                            drawable.setBounds(dest.left.toInt(), dest.top.toInt(), dest.right.toInt(), dest.bottom.toInt())
                            drawable.draw(canvas)
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {}
                        mainHandler.postDelayed(this, 33)
                    }
                }
                gifFrameRunnable = renderRunnable
                mainHandler.post(renderRunnable)
                Log.d(TAG, "GIF started")
            }
        }

        private fun stopGif() {
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
            gifFrameRunnable = null
            try { gifDrawable?.stop() } catch (_: Exception) {}
            gifDrawable = null
        }

        // ======== Static image rendering ========

        private fun showBitmap(bitmap: Bitmap, scaleMode: ScaleMode = ScaleMode.FIT) {
            if (!surfaceReady) return
            try {
                currentBitmap?.recycle(); currentBitmap = bitmap
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val m = getMetrics()
                val dest = calcDestRect(bitmap.width.toFloat(), bitmap.height.toFloat(),
                    m.widthPixels.toFloat(), m.heightPixels.toFloat(), scaleMode)
                canvas.drawBitmap(bitmap, null, dest, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) { Log.e(TAG, "showBitmap error", e) }
        }

        private fun calcDestRect(bw: Float, bh: Float, sw: Float, sh: Float, scaleMode: ScaleMode): RectF {
            return when (scaleMode) {
                ScaleMode.FIT -> {
                    val r = bw / bh; val sr = sw / sh
                    val dw: Float; val dh: Float
                    if (r > sr) { dw = sw; dh = dw / r } else { dh = sh; dw = dh * r }
                    RectF((sw - dw) / 2f, (sh - dh) / 2f, (sw + dw) / 2f, (sh + dh) / 2f)
                }
                ScaleMode.FILL -> {
                    val r = bw / bh; val sr = sw / sh
                    val dw: Float; val dh: Float
                    if (r < sr) { dw = sw; dh = dw / r } else { dh = sh; dw = dh * r }
                    RectF((sw - dw) / 2f, (sh - dh) / 2f, (sw + dw) / 2f, (sh + dh) / 2f)
                }
                ScaleMode.STRETCH -> RectF(0f, 0f, sw, sh)
            }
        }

        private fun showDefault() {
            if (!surfaceReady) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.DKGRAY)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER }
                val m = getMetrics()
                canvas.drawText("Wallpaper Switcher", m.widthPixels / 2f, m.heightPixels / 2f, p)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }

        private fun loadBitmap(uriStr: String): Bitmap? {
            return try {
                val uri = Uri.parse(uriStr)
                val m = getMetrics()
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
                var s = 1
                while (opts.outWidth / s > m.widthPixels * 2 || opts.outHeight / s > m.heightPixels * 2) s *= 2
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565
                    })
                }
            } catch (_: Exception) { null }
        }

        private fun getMetrics(): DisplayMetrics {
            val wm = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
            return DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        }
    }
}

// ======== OpenGL ES 2.0 Video Renderer ========
// Renders MediaPlayer frames to a Canvas with FIT/FILL/STRETCH scaling

class VideoRenderer {
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    private var renderSurface: android.view.Surface? = null
    private var program = 0
    private var videoWidth = 1
    private var videoHeight = 1

    private val vertexCoords = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f)
    private val texCoords = floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f)

    fun initialize() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0]!!, surfAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        textureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        surfaceTexture = android.graphics.SurfaceTexture(textureId)
        renderSurface = android.view.Surface(surfaceTexture)

        program = createProgram()
    }

    fun getSurface(): android.view.Surface = renderSurface!!

    fun setVideoSize(w: Int, h: Int) { videoWidth = w; videoHeight = h }

    fun drawFrame(canvas: Canvas, screenW: Int, screenH: Int, scaleMode: ScaleMode) {
        surfaceTexture?.updateTexImage()

        GLES20.glViewport(0, 0, screenW, screenH)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val texLoc = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(texLoc, 0)

        // Calculate scale for FIT/FILL/STRETCH
        val sw = screenW.toFloat(); val sh = screenH.toFloat()
        val vw = videoWidth.toFloat(); val vh = videoHeight.toFloat()
        val sx: Float; val sy: Float

        when (scaleMode) {
            ScaleMode.FIT -> {
                val videoR = vw / vh; val screenR = sw / sh
                if (videoR > screenR) { sx = 1f; sy = screenR / videoR }
                else { sy = 1f; sx = videoR / screenR }
            }
            ScaleMode.FILL -> {
                val videoR = vw / vh; val screenR = sw / sh
                if (videoR < screenR) { sx = 1f; sy = screenR / videoR }
                else { sy = 1f; sx = videoR / screenR }
            }
            ScaleMode.STRETCH -> { sx = 1f; sy = 1f }
        }

        val scaleLoc = GLES20.glGetUniformLocation(program, "uScale")
        GLES20.glUniform2f(scaleLoc, sx, sy)

        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val tcLoc = GLES20.glGetAttribLocation(program, "aTexCoord")

        val posBuf = createFloatBuffer(vertexCoords)
        val tcBuf = createFloatBuffer(texCoords)

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(tcLoc)
        GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 0, tcBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(tcLoc)
    }

    fun release() {
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surfaceTexture = null; renderSurface = null
        try {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay)
        } catch (_: Exception) {}
        eglDisplay = EGL14.EGL_NO_DISPLAY; eglContext = EGL14.EGL_NO_CONTEXT; eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun createProgram(): Int {
        val vs = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform vec2 uScale;
            void main() {
                gl_Position = vec4(aPosition.xy * uScale, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
        val vsId = loadShader(GLES20.GL_VERTEX_SHADER, vs)
        val fsId = loadShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vsId); GLES20.glAttachShader(p, fsId)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun loadShader(type: Int, code: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, code); GLES20.glCompileShader(s)
        return s
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }
    }
}
