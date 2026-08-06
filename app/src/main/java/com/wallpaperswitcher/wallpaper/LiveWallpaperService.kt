package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.*
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class LiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "LiveWallpaperService"
        const val ACTION_SWITCH = "com.wallpaperswitcher.ACTION_SWITCH"
        const val EXTRA_TARGET_ID = "target_id"

        @Volatile
        var engineRunning = false
            private set
    }

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val mainHandler = Handler(Looper.getMainLooper())
        private lateinit var db: AppDatabase
        @Volatile private var surfaceReady = false
        @Volatile private var isVisible = false
        private val isSwitching = AtomicBoolean(false)
        private var currentBitmap: Bitmap? = null
        private var currentScaleMode: ScaleMode = ScaleMode.FIT

        private val shuffleShownIds = ConcurrentHashMap.newKeySet<Long>()
        @Volatile private var shuffleAllCount = 0

        // Video state — MediaCodec + SurfaceTexture + EGL
        private var videoJob: Job? = null
        @Volatile private var videoPlaying = false
        @Volatile private var videoStopFlag = false
        @Volatile private var videoMode = false
        @Volatile private var videoDurationMs = 0L
        private var videoFps = 30
        @Volatile private var videoWidth = 0
        @Volatile private var videoHeight = 0

        // Frame buffer: decoder thread produces, main thread consumes
        private val frameBuffer = LinkedBlockingQueue<Bitmap>(8)
        private var frameRenderJob: Job? = null

        // Reusable display objects
        private val destRect = RectF()
        private var cachedScreenW = 0f
        private var cachedScreenH = 0f

        // GIF
        private var gifDrawable: android.graphics.drawable.AnimatedImageDrawable? = null
        private var gifFrameRunnable: Runnable? = null
        private var gifBitmapBuffer: Bitmap? = null

        private val switchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_SWITCH) {
                    val targetId = intent.getLongExtra(EXTRA_TARGET_ID, -1L)
                    doSwitch("broadcast", if (targetId > 0) targetId else null)
                }
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
            engineRunning = true
            db = AppDatabase.getInstance(applicationContext)
            setTouchEventsEnabled(true)
            val filter = IntentFilter(ACTION_SWITCH)
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    applicationContext.registerReceiver(switchReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    applicationContext.registerReceiver(switchReceiver, filter)
                }
            } catch (_: Exception) {}
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            surfaceReady = true
            drawCurrentImage()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            cachedScreenW = width.toFloat()
            cachedScreenH = height.toFloat()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            releaseAll()
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                if (videoMode) resumeVideo() else drawCurrentImage()
            } else {
                if (videoMode) pauseVideo() else pauseGif()
            }
        }

        override fun onDestroy() {
            engineRunning = false
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            releaseAll()
            scope.cancel()
            super.onDestroy()
        }

        // ======== Resource lifecycle ========

        private fun releaseAll() {
            stopVideo()
            pauseGif()
            gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
        }

        private fun stopVideo() {
            videoMode = false
            videoPlaying = false
            videoStopFlag = true

            val job = videoJob; videoJob = null; job?.cancel()
            val rJob = frameRenderJob; frameRenderJob = null; rJob?.cancel()

            while (frameBuffer.isNotEmpty()) { try { frameBuffer.poll()?.recycle() } catch (_: Exception) {} }
        }

        private fun pauseVideo() { videoPlaying = false }
        private fun resumeVideo() { videoPlaying = true }
        private fun pauseGif() { gifFrameRunnable?.let { mainHandler.removeCallbacks(it) } }

        // ======== Switch logic (identical to previous version) ========

        private fun doSwitch(source: String, targetId: Long? = null) {
            if (!isSwitching.compareAndSet(false, true)) {
                Log.d(TAG, "Already switching, skip ($source)")
                return
            }
            Log.d(TAG, "doSwitch from $source, targetId=$targetId")

            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageDao = db.wallpaperImageDao()
                    val groupDao = db.wallpaperGroupDao()

                    val groups = groupDao.getEnabledGroupsSync()
                    if (groups.isEmpty()) return@launch

                    currentScaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }

                    val nextImage = if (targetId != null && targetId > 0) {
                        imageDao.getImageById(targetId)
                    } else {
                        val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                        val switchMode = try {
                            SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                        } catch (_: Exception) { SwitchMode.RANDOM }
                        pickNextImage(switchMode, imageDao, lastId, dao)
                    }

                    if (nextImage == null) return@launch

                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
                    val mediaType = nextImage.mediaType ?: "IMAGE"
                    Log.d(TAG, "Switch to: ${nextImage.displayName} ($mediaType)")

                    stopVideo()
                    pauseGif()
                    delay(100)

                    when (mediaType) {
                        "VIDEO" -> startVideo(nextImage.uri, currentScaleMode)
                        "GIF" -> mainHandler.post { playGif(nextImage.uri, currentScaleMode) }
                        else -> {
                            val bitmap = loadBitmap(nextImage.uri)
                            if (bitmap != null) mainHandler.post { showBitmap(bitmap, currentScaleMode) }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "doSwitch error", e)
                } finally {
                    isSwitching.set(false)
                }
            }
        }

        private suspend fun pickNextImage(
            switchMode: SwitchMode, imageDao: WallpaperImageDao, lastId: Long, dao: SettingsDao
        ): WallpaperImage? {
            return when (switchMode) {
                SwitchMode.RANDOM -> imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                    ?: imageDao.getRandomImageFromEnabledGroups()
                SwitchMode.SEQUENTIAL -> {
                    val count = imageDao.countByEnabledGroups()
                    if (count == 0) null else {
                        val idx = dao.getLong(SettingsKeys.SEQUENTIAL_INDEX).toInt()
                        val next = idx % count
                        dao.setLong(SettingsKeys.SEQUENTIAL_INDEX, (next + 1).toLong())
                        imageDao.getSequentialImageFromEnabledGroups(next)
                            ?: imageDao.getRandomImageFromEnabledGroups()
                    }
                }
                SwitchMode.SHUFFLE -> {
                    val totalCount = imageDao.countByEnabledGroups()
                    if (totalCount == 0) null else {
                        if (shuffleShownIds.isEmpty()) {
                            val saved = dao.getString(SettingsKeys.SHUFFLE_SHOWN_IDS, "")
                            if (saved.isNotEmpty()) {
                                saved.split(",").mapNotNull { it.toLongOrNull() }.forEach { shuffleShownIds.add(it) }
                            }
                            shuffleAllCount = dao.getLong(SettingsKeys.SHUFFLE_ALL_COUNT, 0L).toInt()
                        }
                        if (shuffleAllCount != totalCount || shuffleShownIds.size >= totalCount) {
                            shuffleShownIds.clear(); shuffleAllCount = totalCount
                        }
                        var attempts = 0; var candidate: WallpaperImage? = null
                        while (attempts < 10 && candidate == null) {
                            val img = imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                                ?: imageDao.getRandomImageFromEnabledGroups()
                            if (img != null && img.id !in shuffleShownIds) candidate = img
                            else if (img != null && shuffleShownIds.size >= totalCount) {
                                shuffleShownIds.clear(); candidate = img
                            }
                            attempts++
                        }
                        candidate?.also {
                            shuffleShownIds.add(it.id)
                            dao.setString(SettingsKeys.SHUFFLE_SHOWN_IDS, shuffleShownIds.joinToString(","))
                            dao.setLong(SettingsKeys.SHUFFLE_ALL_COUNT, shuffleAllCount.toLong())
                        }
                    }
                }
            }
        }

        private fun drawCurrentImage() {
            if (!surfaceReady || !isVisible) return
            if (isSwitching.get()) return
            if (videoMode && videoPlaying) return

            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    currentScaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }
                    val image = if (imageId > 0) db.wallpaperImageDao().getImageById(imageId) else null

                    if (image != null) {
                        when (image.mediaType ?: "IMAGE") {
                            "VIDEO" -> { startVideo(image.uri, currentScaleMode); return@launch }
                            "GIF" -> { mainHandler.post { playGif(image.uri, currentScaleMode) }; return@launch }
                            else -> {
                                val bitmap = loadBitmap(image.uri)
                                if (bitmap != null) { mainHandler.post { showBitmap(bitmap, currentScaleMode) }; return@launch }
                            }
                        }
                    }
                    mainHandler.post { showDefault() }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "drawCurrentImage error", e)
                }
            }
        }

        // ======== Video: MediaCodec + SurfaceTexture + EGL ========
        //
        // Standard approach used by Google Grafika and video wallpaper projects:
        //
        // 1. Create EGL context (offscreen pbuffer) on a HandlerThread
        // 2. Create GL texture → SurfaceTexture → Surface
        // 3. MediaCodec.configure(format, surface) — decoder renders to this Surface
        // 4. Decode loop (same HandlerThread as EGL):
        //    - Feed input from MediaExtractor
        //    - dequeueOutputBuffer → releaseOutputBuffer(index, true)
        //    - SurfaceTexture.updateTexImage() — updates GL texture
        //    - SurfaceTexture.getBitmap() (API 31+) or GL readPixels (older)
        //    - Put Bitmap into frameBuffer
        // 5. Renderer (main thread): poll from frameBuffer → Canvas.drawBitmap
        //
        // Key: updateTexImage() MUST be called on the same thread that owns
        // the EGL context. So we use a dedicated HandlerThread for both EGL
        // and the decode loop.

        private fun startVideo(uriStr: String, scaleMode: ScaleMode) {
            videoMode = true
            videoPlaying = true
            videoStopFlag = false

            val metrics = getMetrics()
            cachedScreenW = metrics.widthPixels.toFloat()
            cachedScreenH = metrics.heightPixels.toFloat()

            videoJob = scope.launch {
                try {
                    decodeAndPlay(uriStr, scaleMode)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "Video error: ${e.message}", e)
                } finally {
                    videoPlaying = false
                }
            }
        }

        private suspend fun decodeAndPlay(uriStr: String, scaleMode: ScaleMode) = withContext(Dispatchers.IO) {
            // --- Setup MediaExtractor ---
            val extractor = MediaExtractor()
            extractor.setDataSource(applicationContext, Uri.parse(uriStr), null)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: run {
                extractor.release()
                return@withContext
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            videoWidth = width
            videoHeight = height
            videoDurationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
            videoFps = format.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30).coerceIn(15, 60)

            Log.d(TAG, "Video: ${width}x${height} @ ${videoFps}fps, mime=$mime")

            // --- Setup EGL context (required for SurfaceTexture) ---
            val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "eglGetDisplay failed")
                extractor.release()
                return@withContext
            }

            val eglVersion = IntArray(2)
            EGL14.eglInitialize(eglDisplay, eglVersion, 0, eglVersion, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val eglConfig = configs[0]!!

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                Log.e(TAG, "eglMakeCurrent failed")
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
                extractor.release()
                return@withContext
            }

            // --- Create GL texture + SurfaceTexture + Surface ---
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val texId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            val surfaceTexture = SurfaceTexture(texId)
            surfaceTexture.setDefaultBufferSize(width, height)
            val codecSurface = Surface(surfaceTexture)

            // --- Setup MediaCodec ---
            val decoder = try {
                MediaCodec.createDecoderByType(mime).also { Log.d(TAG, "Decoder: ${it.name}") }
            } catch (e: Exception) {
                Log.e(TAG, "No decoder for $mime: ${e.message}")
                codecSurface.release(); surfaceTexture.release()
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
                extractor.release()
                return@withContext
            }

            decoder.configure(format, codecSurface, null, 0)
            decoder.start()

            // Start renderer on main thread
            startFrameRenderer(scaleMode)

            // --- Decode loop (runs on THIS thread — same thread as EGL context) ---
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            val frameIntervalUs = (1_000_000L / videoFps).coerceAtLeast(16_000L)

            try {
                while (currentCoroutineContext().isActive && surfaceReady && !videoStopFlag) {
                    if (!isVisible || !videoPlaying) {
                        delay(50)
                        continue
                    }

                    // Feed compressed data to decoder
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex) ?: continue
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Get decoded output
                    val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outputIndex >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoder.releaseOutputBuffer(outputIndex, false)
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            inputDone = false
                            continue
                        }

                        // Release buffer with render=true → frame goes to SurfaceTexture
                        decoder.releaseOutputBuffer(outputIndex, true)

                        // Update texture and get bitmap (MUST be on same thread as EGL)
                        surfaceTexture.updateTexImage()

                        val bitmap = if (Build.VERSION.SDK_INT >= 31) {
                            // API 31+: direct getBitmap from SurfaceTexture
                            try { surfaceTexture.getBitmap() } catch (_: Exception) { null }
                        } else {
                            // API 26-30: read pixels from GL framebuffer
                            readGlPixels(width, height)
                        }

                        if (bitmap != null) {
                            while (frameBuffer.remainingCapacity() <= 0) {
                                try { frameBuffer.poll()?.recycle() } catch (_: Exception) {}
                            }
                            frameBuffer.offer(bitmap)
                        }

                        delay(frameIntervalUs / 1000)
                    } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        delay(1)
                    }
                }
            } finally {
                try { decoder.stop() } catch (_: Exception) {}
                try { decoder.release() } catch (_: Exception) {}
                try { codecSurface.release() } catch (_: Exception) {}
                try { surfaceTexture.release() } catch (_: Exception) {}
                try { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) } catch (_: Exception) {}
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglTerminate(eglDisplay)
                } catch (_: Exception) {}
                try { extractor.release() } catch (_: Exception) {}
            }
        }

        /**
         * Read pixels from GL framebuffer (for API < 31).
         * Called on the EGL thread after updateTexImage().
         */
        private fun readGlPixels(width: Int, height: Int): Bitmap? {
            return try {
                val buf = java.nio.IntBuffer.allocate(width * height)
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                val pixels = IntArray(width * height)
                buf.get(pixels)
                // GL readPixels returns bottom-to-top, flip vertically
                for (y in 0 until height / 2) {
                    val topRow = y * width
                    val botRow = (height - 1 - y) * width
                    for (x in 0 until width) {
                        val tmp = pixels[topRow + x]
                        pixels[topRow + x] = pixels[botRow + x]
                        pixels[botRow + x] = tmp
                    }
                }
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            } catch (_: Exception) { null }
        }

        private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
            return try { if (containsKey(key)) getInteger(key) else default } catch (_: Exception) { default }
        }

        /**
         * Frame render loop on main thread.
         * Consumes from frameBuffer, draws to Canvas at video fps rate.
         */
        private fun startFrameRenderer(scaleMode: ScaleMode) {
            val runnable = object : Runnable {
                override fun run() {
                    if (!surfaceReady || !videoPlaying || videoStopFlag) return

                    var frame: Bitmap? = null
                    var drained = 0
                    while (drained < 2) {
                        val f = frameBuffer.poll() ?: break
                        if (frame != null) frame.recycle()
                        frame = f
                        drained++
                    }

                    if (frame != null) {
                        try { showVideoFrame(frame, scaleMode) } catch (_: Exception) {}
                        frame.recycle()
                    }

                    val intervalMs = (1000L / videoFps).coerceAtLeast(16L)
                    mainHandler.postDelayed(this, intervalMs)
                }
            }
            frameRenderJob = scope.launch { mainHandler.post(runnable) }
        }

        private fun showVideoFrame(bitmap: Bitmap, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                calcDestRect(bitmap.width.toFloat(), bitmap.height.toFloat(), cachedScreenW, cachedScreenH, scaleMode)
                canvas.drawBitmap(bitmap, null, destRect, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.e(TAG, "showVideoFrame error: ${e.message}")
            }
        }

        // ======== GIF via Canvas ========

        private fun playGif(uriStr: String, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                if (Build.VERSION.SDK_INT >= 28) playGif28(uriStr, scaleMode)
                else loadBitmap(uriStr)?.let { showBitmap(it, scaleMode) }
            } catch (e: Exception) {
                loadBitmap(uriStr)?.let { showBitmap(it, scaleMode) }
            }
        }

        @android.annotation.TargetApi(28)
        private fun playGif28(uriStr: String, scaleMode: ScaleMode) {
            val source = ImageDecoder.createSource(contentResolver, Uri.parse(uriStr))
            val drawable = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                gifDrawable = drawable
                drawable.repeatCount = -1
                drawable.start()

                val frameW = drawable.intrinsicWidth.coerceAtLeast(1)
                val frameH = drawable.intrinsicHeight.coerceAtLeast(1)
                gifBitmapBuffer?.recycle()
                gifBitmapBuffer = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)

                val runnable = object : Runnable {
                    override fun run() {
                        if (!surfaceReady || !isVisible || gifDrawable == null) return
                        try {
                            val bmp = gifBitmapBuffer ?: return
                            bmp.eraseColor(Color.TRANSPARENT)
                            val cv = Canvas(bmp)
                            drawable.draw(cv)
                            showBitmapDirect(bmp, scaleMode)
                        } catch (_: Exception) {}
                        mainHandler.postDelayed(this, 33)
                    }
                }
                gifFrameRunnable = runnable
                mainHandler.post(runnable)
            }
        }

        // ======== Canvas rendering ========

        private fun showBitmap(bitmap: Bitmap, scaleMode: ScaleMode = ScaleMode.FIT) {
            if (!surfaceReady) return
            try {
                currentBitmap?.recycle(); currentBitmap = bitmap
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val m = getMetrics()
                calcDestRect(bitmap.width.toFloat(), bitmap.height.toFloat(),
                    m.widthPixels.toFloat(), m.heightPixels.toFloat(), scaleMode)
                canvas.drawBitmap(bitmap, null, destRect, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }

        private fun showBitmapDirect(bitmap: Bitmap, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val sw = cachedScreenW.takeIf { it > 0 } ?: getMetrics().let { cachedScreenW = it.widthPixels.toFloat(); cachedScreenW }
                val sh = cachedScreenH.takeIf { it > 0 } ?: getMetrics().let { cachedScreenH = it.heightPixels.toFloat(); cachedScreenH }
                calcDestRect(bitmap.width.toFloat(), bitmap.height.toFloat(), sw, sh, scaleMode)
                canvas.drawBitmap(bitmap, null, destRect, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
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

        private fun showDefault() {
            if (!surfaceReady) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.DKGRAY)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER
                }
                val m = getMetrics()
                canvas.drawText("Wallpaper Switcher", m.widthPixels / 2f, m.heightPixels / 2f, p)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }

        private fun loadBitmap(uriStr: String): Bitmap? {
            return com.wallpaperswitcher.engine.BitmapUtils.loadBitmap(applicationContext, uriStr)
        }

        private fun getMetrics(): android.util.DisplayMetrics {
            return com.wallpaperswitcher.engine.BitmapUtils.getScreenMetrics(applicationContext)
        }
    }
}
