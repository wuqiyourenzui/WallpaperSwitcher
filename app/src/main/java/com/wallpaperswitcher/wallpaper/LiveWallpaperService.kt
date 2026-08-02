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
import android.view.WindowManager
import com.wallpaperswitcher.data.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class LiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "LiveWallpaperService"
        const val ACTION_SWITCH = "com.wallpaperswitcher.ACTION_SWITCH"
        private const val SWITCH_COOLDOWN_MS = 1500L // 1.5s cooldown between switches
    }

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val mainHandler = Handler(Looper.getMainLooper())
        private lateinit var db: AppDatabase
        private var surfaceReady = false
        private var isVisible = false
        private val isSwitching = AtomicBoolean(false)
        private var lastSwitchTimeMs = 0L
        private var currentBitmap: Bitmap? = null
        private var currentScaleMode: ScaleMode = ScaleMode.FIT

        // Shuffle tracking - keeps track of shown image IDs to avoid repeats
        private val shuffleShownIds = mutableSetOf<Long>()
        private var shuffleAllCount = 0

        // Media state
        private var mediaCodecJob: Job? = null
        private var videoPlaying = false
        private var videoStopFlag = false

        // GIF
        private var gifDrawable: android.graphics.drawable.AnimatedImageDrawable? = null
        private var gifFrameRunnable: Runnable? = null

        private val switchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_SWITCH) {
                    Log.d(TAG, "Broadcast received")
                    doSwitch("broadcast")
                }
            }
        }

        private val gestureDetector = GestureDetector(
            applicationContext,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    // Check double-tap setting asynchronously
                    scope.launch {
                        val enabled = db.settingsDao().getBool(
                            com.wallpaperswitcher.data.SettingsKeys.DOUBLE_TAP_ENABLED, true
                        )
                        if (enabled) {
                            doSwitch("double-tap")
                        } else {
                            Log.d(TAG, "Double-tap disabled, ignoring")
                        }
                    }
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
            Log.d(TAG, "Engine created")
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            surfaceReady = true
            Log.d(TAG, "Surface created")
            drawCurrentImage()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {}

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            Log.d(TAG, "Visibility: $visible")
            if (visible) {
                drawCurrentImage()
            } else {
                // Pause video/GIF but don't stop - resume when visible again
                pauseMedia()
            }
        }

        override fun onDestroy() {
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            releaseAll()
            currentBitmap?.recycle(); currentBitmap = null
            scope.cancel()
            super.onDestroy()
        }

        // ======== Media control ========

        private fun releaseAll() {
            videoPlaying = false
            videoStopFlag = true
            mediaCodecJob?.cancel()
            mediaCodecJob = null
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
            gifFrameRunnable = null
            try { gifDrawable?.stop() } catch (_: Exception) {}
            gifDrawable = null
        }

        private fun pauseMedia() {
            // Pause GIF rendering but keep video job alive (it checks isVisible)
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
        }

        private fun resumeMedia() {
            // Resume GIF rendering
            gifDrawable?.let {
                gifFrameRunnable?.let { runnable -> mainHandler.post(runnable) }
            }
        }

        // ======== Switch logic ========

        private fun doSwitch(source: String) {
            // Cooldown: prevent rapid successive switches from different triggers
            val now = System.currentTimeMillis()
            if (now - lastSwitchTimeMs < SWITCH_COOLDOWN_MS) {
                Log.d(TAG, "Cooldown active, skip ($source)")
                return
            }
            if (!isSwitching.compareAndSet(false, true)) {
                Log.d(TAG, "Already switching, skip ($source)")
                return
            }
            lastSwitchTimeMs = now
            Log.d(TAG, "doSwitch from $source")

            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageDao = db.wallpaperImageDao()
                    val groupDao = db.wallpaperGroupDao()

                    val groups = groupDao.getEnabledGroupsSync()
                    if (groups.isEmpty()) {
                        Log.d(TAG, "No enabled groups")
                        isSwitching.set(false)
                        return@launch
                    }

                    val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    val switchMode = try {
                        SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                    } catch (_: Exception) { SwitchMode.RANDOM }
                    currentScaleMode = try {
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
                                val next = idx % count
                                dao.setLong(SettingsKeys.SEQUENTIAL_INDEX, (next + 1).toLong())
                                imageDao.getSequentialImageFromEnabledGroups(next)
                                    ?: imageDao.getRandomImageFromEnabledGroups()
                            }
                        }
                        SwitchMode.SHUFFLE -> {
                            // True shuffle: track shown images, reset when all shown
                            val totalCount = imageDao.countByEnabledGroups()
                            if (totalCount == 0) {
                                null
                            } else {
                                // Reset if count changed or all shown
                                if (shuffleAllCount != totalCount || shuffleShownIds.size >= totalCount) {
                                    shuffleShownIds.clear()
                                    shuffleAllCount = totalCount
                                }
                                // Try to find an unshown image
                                var attempts = 0
                                var candidate: com.wallpaperswitcher.data.WallpaperImage? = null
                                while (attempts < 10 && candidate == null) {
                                    val img = imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                                        ?: imageDao.getRandomImageFromEnabledGroups()
                                    if (img != null && img.id !in shuffleShownIds) {
                                        candidate = img
                                    } else if (img != null && shuffleShownIds.size >= totalCount) {
                                        // All shown, reset and use this one
                                        shuffleShownIds.clear()
                                        candidate = img
                                    }
                                    attempts++
                                }
                                if (candidate != null) {
                                    shuffleShownIds.add(candidate.id)
                                }
                                candidate
                            }
                        }
                    }

                    if (nextImage == null) {
                        Log.d(TAG, "No next image")
                        isSwitching.set(false)
                        return@launch
                    }

                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
                    val mediaType = nextImage.mediaType ?: "IMAGE"
                    Log.d(TAG, "Switch to: ${nextImage.displayName} ($mediaType)")

                    // SMOOTH: prepare new content first, then stop old, then show new
                    when (mediaType) {
                        "VIDEO" -> {
                            val newUri = nextImage.uri
                            val scaleMode = currentScaleMode
                            // Extract first frame before stopping old
                            val firstFrame = extractFirstFrame(Uri.parse(newUri))
                            // Stop old content and show first frame immediately
                            mainHandler.post {
                                releaseAll()
                                if (firstFrame != null) showBitmap(firstFrame, scaleMode)
                                startVideoDecoder(newUri, scaleMode)
                            }
                        }
                        "GIF" -> {
                            val newUri = nextImage.uri
                            val scaleMode = currentScaleMode
                            mainHandler.post {
                                releaseAll()
                                playGif(newUri, scaleMode)
                            }
                        }
                        else -> {
                            val bitmap = loadBitmap(nextImage.uri)
                            val scaleMode = currentScaleMode
                            mainHandler.post {
                                releaseAll()
                                if (bitmap != null) showBitmap(bitmap, scaleMode)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "doSwitch error", e)
                } finally {
                    isSwitching.set(false)
                }
            }
        }

        private fun drawCurrentImage() {
            if (!surfaceReady || !isVisible) return
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
                            "VIDEO" -> {
                                mainHandler.post { startVideoDecoder(image.uri, currentScaleMode) }
                                return@launch
                            }
                            "GIF" -> {
                                mainHandler.post { playGif(image.uri, currentScaleMode) }
                                return@launch
                            }
                            else -> {
                                val bitmap = loadBitmap(image.uri)
                                if (bitmap != null) {
                                    mainHandler.post { showBitmap(bitmap, currentScaleMode) }
                                    return@launch
                                }
                            }
                        }
                    }
                    mainHandler.post { showDefault() }
                } catch (e: Exception) {
                    Log.e(TAG, "drawCurrentImage error", e)
                }
            }
        }

        // ======== Video via MediaCodec ========

        private fun startVideoDecoder(uriStr: String, scaleMode: ScaleMode) {
            val uri = Uri.parse(uriStr)
            mediaCodecJob = scope.launch {
                try {
                    val extractor = MediaExtractor()
                    extractor.setDataSource(applicationContext, uri, null)

                    var trackIndex = -1
                    var mime = ""
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val m = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (m.startsWith("video/")) {
                            trackIndex = i
                            mime = m
                            break
                        }
                    }

                    if (trackIndex < 0) { videoPlaying = false; return@launch }
                    extractor.selectTrack(trackIndex)
                    val format = extractor.getTrackFormat(trackIndex)
                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    val fps = format.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30)
                    val frameMs = (1000L / fps).coerceIn(16, 100)

                    val codec = MediaCodec.createDecoderByType(mime)
                    codec.configure(format, null, null, 0)
                    codec.start()

                    val info = MediaCodec.BufferInfo()
                    val startTimeNs = System.nanoTime()
                    videoPlaying = true

                    videoStopFlag = false
                    while (isActive && surfaceReady && !videoStopFlag) {
                        // When not visible, slow down but don't stop (keeps video looping)
                        if (!isVisible) {
                            delay(50)
                            // Still feed input/output to keep the pipeline moving
                        }

                        val inputIdx = codec.dequeueInputBuffer(10000L)
                        if (inputIdx >= 0) {
                            val buf = codec.getInputBuffer(inputIdx) ?: continue
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                                codec.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                codec.queueInputBuffer(inputIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }

                        val outputIdx = codec.dequeueOutputBuffer(info, 10000L)
                        if (outputIdx >= 0) {
                            val outBuf = codec.getOutputBuffer(outputIdx)
                            if (outBuf != null && isActive) {
                                val bmp = yuvToBitmap(outBuf, width, height)
                                if (bmp != null && isActive && isVisible) {
                                    mainHandler.post { showBitmap(bmp, scaleMode) }
                                }
                            }
                            codec.releaseOutputBuffer(outputIdx, false)

                            val elapsed = (System.nanoTime() - startTimeNs) / 1_000_000
                            val target = info.presentationTimeUs / 1000
                            val sleep = (target - elapsed).coerceIn(0, frameMs * 2)
                            if (sleep > 0) delay(sleep)
                        }

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            // Loop: seek back to start and flush codec
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            codec.flush()
                        }
                    }

                    codec.stop(); codec.release(); extractor.release()
                } catch (e: Exception) {
                    Log.e(TAG, "MediaCodec error: " + e.message)
                }
                videoPlaying = false
            }
        }

        private fun yuvToBitmap(buffer: ByteBuffer, width: Int, height: Int): Bitmap? {
            return try {
                val yuv = ByteArray(buffer.remaining())
                buffer.get(yuv)
                val frameSize = width * height
                var i = frameSize
                while (i < yuv.size - 1) {
                    val tmp = yuv[i]; yuv[i] = yuv[i + 1]; yuv[i + 1] = tmp; i += 2
                }
                val yuvImage = YuvImage(yuv, ImageFormat.NV21, width, height, null)
                val out = java.io.ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
                BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
            } catch (_: Exception) { null }
        }

        private fun extractFirstFrame(uri: Uri): Bitmap? {
            return try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                val frame = retriever.frameAtTime
                retriever.release()
                frame
            } catch (_: Exception) { null }
        }

        // ======== GIF ========

        private fun playGif(uriStr: String, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28) playGif28(uriStr, scaleMode)
                else { loadBitmap(uriStr)?.let { showBitmap(it, scaleMode) } }
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
                val runnable = object : Runnable {
                    override fun run() {
                        if (!surfaceReady || !isVisible || gifDrawable == null) return
                        try {
                            val canvas = surfaceHolder.lockCanvas() ?: return
                            canvas.drawColor(Color.BLACK)
                            val m = getMetrics()
                            val dest = calcDestRect(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat(),
                                m.widthPixels.toFloat(), m.heightPixels.toFloat(), scaleMode)
                            drawable.setBounds(dest.left.toInt(), dest.top.toInt(), dest.right.toInt(), dest.bottom.toInt())
                            drawable.draw(canvas)
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {}
                        mainHandler.postDelayed(this, 33)
                    }
                }
                gifFrameRunnable = runnable
                mainHandler.post(runnable)
            }
        }

        // ======== Image rendering ========

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

        private fun getMetrics(): android.util.DisplayMetrics {
            val wm = applicationContext.getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            return android.util.DisplayMetrics().also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = wm.currentWindowMetrics.bounds
                    it.widthPixels = bounds.width()
                    it.heightPixels = bounds.height()
                } else {
                    @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it)
                }
            }
        }

        private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
            return if (containsKey(key)) getInteger(key) else default
        }
    }
}
