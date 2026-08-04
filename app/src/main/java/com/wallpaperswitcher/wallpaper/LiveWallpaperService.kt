package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "LiveWallpaperService"
        const val ACTION_SWITCH = "com.wallpaperswitcher.ACTION_SWITCH"
        const val EXTRA_TARGET_ID = "target_id"
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

        // Video state - pure Canvas, no EGL/OpenGL
        private var mediaCodecJob: Job? = null
        @Volatile private var videoPlaying = false
        @Volatile private var videoStopFlag = false
        private var videoRetriever: MediaMetadataRetriever? = null

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
            db = AppDatabase.getInstance(applicationContext)
            setTouchEventsEnabled(true)
            val filter = IntentFilter(ACTION_SWITCH)
            try {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
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

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {}

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
            if (visible) drawCurrentImage() else pauseMedia()
        }

        override fun onDestroy() {
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            releaseAll()
            scope.cancel()
            super.onDestroy()
        }

        // ======== Resource lifecycle ========

        private fun releaseAll() {
            videoPlaying = false
            videoStopFlag = true
            mediaCodecJob?.cancel()
            mediaCodecJob = null
            try { videoRetriever?.release() } catch (_: Exception) {}
            videoRetriever = null
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
            gifFrameRunnable = null
            try { gifDrawable?.stop() } catch (_: Exception) {}
            gifDrawable = null
            gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
        }

        private suspend fun stopVideoAndWait() {
            videoPlaying = false
            videoStopFlag = true
            val job = mediaCodecJob
            mediaCodecJob = null
            job?.cancel()
            try { job?.join() } catch (_: Exception) {}
            try { videoRetriever?.release() } catch (_: Exception) {}
            videoRetriever = null
        }

        private fun pauseMedia() {
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
        }

        // ======== Switch logic ========

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

                    when (mediaType) {
                        "VIDEO" -> {
                            stopVideoAndWait(); delay(30)
                            startVideoDecoder(nextImage.uri, currentScaleMode)
                        }
                        "GIF" -> {
                            stopVideoAndWait(); delay(30)
                            mainHandler.post { playGif(nextImage.uri, currentScaleMode) }
                        }
                        else -> {
                            stopVideoAndWait(); delay(30)
                            val bitmap = loadBitmap(nextImage.uri)
                            if (bitmap != null) mainHandler.post { showBitmap(bitmap, currentScaleMode) }
                        }
                    }
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
                        candidate?.also { shuffleShownIds.add(it.id) }
                    }
                }
            }
        }

        private fun drawCurrentImage() {
            if (!surfaceReady || !isVisible) return
            if (isSwitching.get()) return
            if (videoPlaying && mediaCodecJob?.isActive == true) return

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
                            "VIDEO" -> { startVideoDecoder(image.uri, currentScaleMode); return@launch }
                            "GIF" -> { mainHandler.post { playGif(image.uri, currentScaleMode) }; return@launch }
                            else -> {
                                val bitmap = loadBitmap(image.uri)
                                if (bitmap != null) { mainHandler.post { showBitmap(bitmap, currentScaleMode) }; return@launch }
                            }
                        }
                    }
                    mainHandler.post { showDefault() }
                } catch (e: Exception) {
                    Log.e(TAG, "drawCurrentImage error", e)
                }
            }
        }

        // ======== Video: MediaMetadataRetriever → Bitmap → Canvas ========
        // Pure Canvas rendering. No EGL, no OpenGL, no MediaCodec.
        // MediaMetadataRetriever is universally compatible and reliable.

        private fun startVideoDecoder(uriStr: String, scaleMode: ScaleMode) {
            videoPlaying = true
            videoStopFlag = false
            mediaCodecJob = scope.launch {
                try {
                    videoRetriever?.release()
                    videoRetriever = MediaMetadataRetriever()
                    videoRetriever!!.setDataSource(applicationContext, Uri.parse(uriStr))
                    playVideoLoop(uriStr, scaleMode)
                } catch (e: Exception) {
                    Log.e(TAG, "Video error: ${e.message}")
                } finally {
                    videoPlaying = false
                    try { videoRetriever?.release() } catch (_: Exception) {}
                    videoRetriever = null
                }
            }
        }

        private suspend fun playVideoLoop(uriStr: String, scaleMode: ScaleMode) {
            val retriever = videoRetriever ?: return
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            Log.d(TAG, "Video: ${durationMs}ms")

            while (currentCoroutineContext().isActive && surfaceReady && !videoStopFlag) {
                if (!isVisible) { delay(100); continue }
                playVideoOnce(retriever, durationMs, scaleMode)
                if (!videoStopFlag && surfaceReady) delay(16)
            }
        }

        /**
         * Play video once by extracting frames as fast as possible.
         * Uses real-time pacing: extract frame, display, measure time, repeat.
         * No fixed FPS assumption - we extract and display as fast as we can.
         */
        private suspend fun playVideoOnce(
            retriever: MediaMetadataRetriever,
            durationMs: Long,
            scaleMode: ScaleMode
        ) {
            var timestampMs = 0L
            // Step through video at ~33ms intervals (30fps equivalent)
            // getFrameAtTime with OPTION_CLOSEST gives exact frame at timestamp
            val stepMs = 33L

            while (currentCoroutineContext().isActive && surfaceReady && !videoStopFlag && isVisible) {
                val startMs = System.currentTimeMillis()

                // Extract exact frame at this timestamp (not nearest keyframe)
                val frame = try {
                    if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getFrameAtTime(
                            timestampMs * 1000, // convert to microseconds
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )
                    } else {
                        retriever.getFrameAtTime(
                            timestampMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }
                } catch (_: Exception) { null }

                if (frame != null) {
                    showBitmapDirect(frame, scaleMode)
                    frame.recycle()
                }

                timestampMs += stepMs
                // Loop when reaching end
                if (timestampMs >= durationMs) timestampMs = 0L

                // Pace: sleep for remaining time in this frame's budget
                val elapsedMs = System.currentTimeMillis() - startMs
                val sleepMs = (stepMs - elapsedMs).coerceAtLeast(1)
                delay(sleepMs)
            }
        }

        // ======== GIF via Canvas ========

        private fun playGif(uriStr: String, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28) playGif28(uriStr, scaleMode)
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
                val dest = calcDestRect(bitmap.width.toFloat(), bitmap.height.toFloat(),
                    m.widthPixels.toFloat(), m.heightPixels.toFloat(), scaleMode)
                canvas.drawBitmap(bitmap, null, dest, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
        }

        private fun showBitmapDirect(bitmap: Bitmap, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val m = getMetrics()
                val dest = calcDestRect(bitmap.width.toFloat(), bitmap.height.toFloat(),
                    m.widthPixels.toFloat(), m.heightPixels.toFloat(), scaleMode)
                canvas.drawBitmap(bitmap, null, dest, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
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
