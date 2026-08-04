package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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

        // Video state - pure Canvas, no EGL/OpenGL
        private var mediaCodecJob: Job? = null
        @Volatile private var videoPlaying = false
        @Volatile private var videoStopFlag = false

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

        // ======== Video: MediaCodec → ImageReader → Bitmap → Canvas ========
        // Hardware decoding via MediaCodec, frame capture via ImageReader.
        // Pure Canvas rendering. No EGL, no OpenGL.

        private fun startVideoDecoder(uriStr: String, scaleMode: ScaleMode) {
            videoPlaying = true
            videoStopFlag = false
            mediaCodecJob = scope.launch {
                try {
                    playVideoLoop(uriStr, scaleMode)
                } catch (e: Exception) {
                    Log.e(TAG, "Video error: ${e.message}")
                } finally {
                    videoPlaying = false
                }
            }
        }

        private suspend fun playVideoLoop(uriStr: String, scaleMode: ScaleMode) {
            while (currentCoroutineContext().isActive && surfaceReady && !videoStopFlag) {
                if (!isVisible) { delay(100); continue }
                playVideoOnce(uriStr, scaleMode)
                if (!videoStopFlag && surfaceReady) delay(16)
            }
        }

        /**
         * Play video using MediaCodec for hardware decoding.
         * Uses ImageReader(YUV_420_888) to capture decoded frames.
         * YUV→RGB conversion via integer math, then Canvas draw.
         */
        private suspend fun playVideoOnce(uriStr: String, scaleMode: ScaleMode) {
            if (!surfaceReady) return

            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            var imageReader: ImageReader? = null

            try {
                val uri = Uri.parse(uriStr)
                extractor = MediaExtractor()
                extractor.setDataSource(applicationContext, uri, null)

                var trackIndex = -1; var mime = ""
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val m = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/")) { trackIndex = i; mime = m; break }
                }
                if (trackIndex < 0) { Log.e(TAG, "No video track"); return }

                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val width = format.getIntegerOrDefault(MediaFormat.KEY_WIDTH, 1280)
                val height = format.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, 720)
                val fps = format.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30)
                val frameIntervalMs = 1000L / fps.coerceIn(15, 60)

                // ImageReader with YUV_420_888 - universally supported by all decoders
                imageReader = ImageReader.newInstance(width, height, android.graphics.ImageFormat.YUV_420_888, 3)

                // Reusable bitmap for decoded frames
                val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, imageReader.surface, null, 0)
                codec.start()

                Log.d(TAG, "Video: $mime ${width}x${height} ${fps}fps")

                val info = MediaCodec.BufferInfo()
                var inputDone = false

                while (currentCoroutineContext().isActive && surfaceReady && !videoStopFlag && isVisible) {
                    // Feed compressed data
                    if (!inputDone) {
                        val inputIdx = codec.dequeueInputBuffer(10000L)
                        if (inputIdx >= 0) {
                            val buf = codec.getInputBuffer(inputIdx) ?: continue
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Get decoded frame from ImageReader
                    val image = imageReader.acquireLatestImage()
                    if (image != null) {
                        try {
                            yuvToBitmap(image, frameBitmap)
                            showBitmapDirect(frameBitmap, scaleMode)
                        } finally {
                            image.close()
                        }
                        delay(frameIntervalMs)
                    } else {
                        // Check for EOS
                        val outputIdx = codec.dequeueOutputBuffer(info, 5000L)
                        if (outputIdx >= 0) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                codec.releaseOutputBuffer(outputIdx, false)
                                return
                            }
                            codec.releaseOutputBuffer(outputIdx, true)
                        }
                        delay(1)
                    }
                }
                frameBitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "playVideoOnce error: ${e.message}")
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                try { extractor?.release() } catch (_: Exception) {}
                try { imageReader?.close() } catch (_: Exception) {}
            }
        }

        /**
         * Convert YUV_420_888 Image to ARGB_8888 Bitmap.
         * Uses fast integer BT.601 conversion.
         */
        private fun yuvToBitmap(image: Image, dst: Bitmap) {
            val w = image.width; val h = image.height
            val planes = image.planes

            val yBuf = planes[0].buffer
            val uBuf = planes[1].buffer
            val vBuf = planes[2].buffer
            val yStride = planes[0].rowStride
            val uvStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride

            val pixels = IntArray(w * h)
            var idx = 0
            for (row in 0 until h) {
                val uvRow = row shr 1
                for (col in 0 until w) {
                    val y = (yBuf.get(row * yStride + col).toInt() and 0xFF) - 16
                    val uvIdx = uvRow * uvStride + (col shr 1) * uvPixelStride
                    val u = (uBuf.get(uvIdx).toInt() and 0xFF) - 128
                    val v = (vBuf.get(uvIdx).toInt() and 0xFF) - 128

                    var r = (298 * y + 409 * v + 128) shr 8
                    var g = (298 * y - 100 * u - 208 * v + 128) shr 8
                    var b = (298 * y + 516 * u + 128) shr 8

                    pixels[idx++] = -0x1000000 or
                            (r.coerceIn(0, 255) shl 16) or
                            (g.coerceIn(0, 255) shl 8) or
                            b.coerceIn(0, 255)
                }
            }
            dst.setPixels(pixels, 0, w, 0, 0, w, h)
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

        private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
            return if (containsKey(key)) getInteger(key) else default
        }

    }
}
