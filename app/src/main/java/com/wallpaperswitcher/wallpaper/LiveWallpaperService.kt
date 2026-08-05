package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.Build
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

        // Video state — MediaCodec software decoder
        private var videoJob: Job? = null
        @Volatile private var videoPlaying = false
        @Volatile private var videoStopFlag = false
        @Volatile private var videoMode = false
        @Volatile private var videoDurationMs = 0L
        private var videoFps = 30

        // Reusable display objects to reduce GC pressure
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
            videoJob?.cancel()
            videoJob = null
        }

        private fun pauseVideo() {
            videoPlaying = false
        }

        private fun resumeVideo() {
            videoPlaying = true
        }

        private fun pauseGif() {
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

                    // Stop everything before switching
                    stopVideo()
                    pauseGif()
                    delay(50)

                    when (mediaType) {
                        "VIDEO" -> startVideo(nextImage.uri, currentScaleMode)
                        "GIF" -> mainHandler.post { playGif(nextImage.uri, currentScaleMode) }
                        else -> {
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
                        // Restore shuffle state from DB if needed (survives engine recreation)
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
                            // Persist shuffle state to survive engine recreation
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
                } catch (e: Exception) {
                    Log.e(TAG, "drawCurrentImage error", e)
                }
            }
        }

        // ======== Video: MediaCodec software decoder + Canvas ========
        // Uses Android's software codec (c2.android.avc.decoder) for frame-by-frame
        // decoding. Much faster than MediaMetadataRetriever.getFrameAtTime() which
        // has to seek+decode from keyframe for each frame.
        // Supports FIT, FILL, STRETCH scale modes.

        private fun startVideo(uriStr: String, scaleMode: ScaleMode) {
            videoMode = true
            videoPlaying = true
            videoStopFlag = false

            // Pre-cache screen metrics
            val metrics = getMetrics()
            cachedScreenW = metrics.widthPixels.toFloat()
            cachedScreenH = metrics.heightPixels.toFloat()

            videoJob = scope.launch {
                try {
                    decodeAndPlay(uriStr, scaleMode)
                } catch (e: Exception) {
                    Log.e(TAG, "Video error: ${e.message}")
                } finally {
                    videoPlaying = false
                }
            }
        }

        private suspend fun decodeAndPlay(uriStr: String, scaleMode: ScaleMode) = withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            extractor.setDataSource(applicationContext, Uri.parse(uriStr), null)

            // Find video track
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
            videoDurationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
            val frameRate = format.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30)
            videoFps = frameRate.coerceIn(15, 60)
            val frameIntervalUs = (1_000_000L / videoFps)

            Log.d(TAG, "Video decode: ${width}x${height} @ ${videoFps}fps, mime=$mime")

            // Create software decoder (prefer c2.android.avc.decoder for SW decode)
            val decoder = try {
                // Try software decoder first (works on all devices)
                MediaCodec.createDecoderByType(mime).also {
                    Log.d(TAG, "Decoder: ${it.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "No decoder for $mime: ${e.message}")
                extractor.release()
                return@withContext
            }

            // ImageReader to receive decoded frames from decoder
            val imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
            decoder.configure(format, imageReader.surface, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var loopCount = 0

            try {
                while (!outputDone && currentCoroutineContext().isActive && surfaceReady && !videoStopFlag) {
                    // Pause
                    if (!isVisible || !videoPlaying) {
                        delay(50)
                        continue
                    }

                    // Feed input
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex) ?: continue
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                // End of stream — loop
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Drain output
                    val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outputIndex >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            // End of stream — loop
                            decoder.releaseOutputBuffer(outputIndex, false)
                            loopCount++
                            if (loopCount < 3) { // Limit loops to prevent infinite
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                                inputDone = false
                            } else {
                                outputDone = true
                            }
                            continue
                        }

                        // Convert decoded frame to Bitmap and display
                        val image = imageReader.acquireLatestImage()
                        if (image != null) {
                            val bitmap = yuvToBitmap(image, width, height)
                            image.close()
                            if (bitmap != null) {
                                showVideoFrame(bitmap, scaleMode)
                                bitmap.recycle()
                            }
                        }

                        decoder.releaseOutputBuffer(outputIndex, false)

                        // Frame pacing
                        delay(frameIntervalUs / 1000)
                    }
                }
            } finally {
                try { decoder.stop() } catch (_: Exception) {}
                try { decoder.release() } catch (_: Exception) {}
                try { imageReader.close() } catch (_: Exception) {}
                try { extractor.release() } catch (_: Exception) {}
            }
        }

        /**
         * Convert YUV_420_888 Image to ARGB_8888 Bitmap.
         */
        private fun yuvToBitmap(image: Image, width: Int, height: Int): Bitmap? {
            return try {
                val yPlane = image.planes[0]
                val uPlane = image.planes[1]
                val vPlane = image.planes[2]

                val yBuffer = yPlane.buffer
                val uBuffer = uPlane.buffer
                val vBuffer = vPlane.buffer

                val yRowStride = yPlane.rowStride
                val uvRowStride = uPlane.rowStride
                val uvPixelStride = uPlane.pixelStride

                val argb = IntArray(width * height)

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val yVal = yBuffer.get(y * yRowStride + x).toInt() and 0xFF
                        val uvX = x / 2
                        val uvY = y / 2
                        val uVal = uBuffer.get(uvY * uvRowStride + uvX * uvPixelStride).toInt() and 0xFF
                        val vVal = vBuffer.get(uvY * uvRowStride + uvX * uvPixelStride).toInt() and 0xFF

                        // YUV to RGB
                        val r = (yVal + 1.370705f * (vVal - 128)).toInt().coerceIn(0, 255)
                        val g = (yVal - 0.337633f * (uVal - 128) - 0.698001f * (vVal - 128)).toInt().coerceIn(0, 255)
                        val b = (yVal + 1.732446f * (uVal - 128)).toInt().coerceIn(0, 255)

                        argb[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }

                Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
            } catch (e: Exception) {
                Log.e(TAG, "yuvToBitmap error: ${e.message}")
                null
            }
        }

        private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
            return try { if (containsKey(key)) getInteger(key) else default } catch (_: Exception) { default }
        }

        /**
         * Draw a video frame to the wallpaper canvas.
         * Uses pre-cached screen metrics and reusable destRect.
         */
        private fun showVideoFrame(bitmap: Bitmap, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.BLACK)
                val sw = cachedScreenW
                val sh = cachedScreenH
                calcDestRect(bitmap.width.toFloat(), bitmap.height.toFloat(), sw, sh, scaleMode)
                canvas.drawBitmap(bitmap, null, destRect, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {}
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

        // ======== Canvas rendering (images, GIF, video frames) ========

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

        /** Writes into reusable [destRect] to avoid per-frame allocation. */
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
