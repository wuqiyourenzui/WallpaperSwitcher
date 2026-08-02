package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import com.wallpaperswitcher.data.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer

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

        // Video decoder
        private var videoDecoder: VideoFrameDecoder? = null
        private var videoRenderRunnable: Runnable? = null

        // GIF
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
            if (visible) {
                videoDecoder?.resume()
                drawCurrentImage()
            } else {
                videoDecoder?.pause()
                stopGif()
            }
        }

        override fun onDestroy() {
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            stopVideo(); stopGif()
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
                } catch (e: Exception) {
                    Log.e(TAG, "$source error", e)
                } finally {
                    isSwitching = false
                }
            }
        }

        private fun drawCurrentImage() {
            if (!surfaceReady || !isVisible) return
            if (videoDecoder?.isPlaying == true) return

            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    val scaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }
                    val image = if (imageId > 0) db.wallpaperImageDao().getImageById(imageId) else null
                    if (image != null) {
                        when (image.mediaType ?: "IMAGE") {
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

        // ======== Video playback (MediaCodec frame-by-frame) ========

        private fun playVideo(uriStr: String, scaleMode: ScaleMode = ScaleMode.FIT) {
            stopVideo()
            stopGif()
            if (!surfaceReady) return

            scope.launch {
                try {
                    val decoder = VideoFrameDecoder(applicationContext, uriStr)
                    if (!decoder.init()) {
                        Log.e(TAG, "VideoFrameDecoder init failed, falling back to first frame")
                        val frame = decoder.getFirstFrame()
                        if (frame != null) mainHandler.post { showBitmap(frame, scaleMode) }
                        decoder.release()
                        return@launch
                    }

                    videoDecoder = decoder
                    val fps = decoder.fps.coerceIn(1, 60)

                    mainHandler.post {
                        val runnable = object : Runnable {
                            override fun run() {
                                if (!surfaceReady || !isVisible || videoDecoder == null) return
                                val frame = videoDecoder?.decodeFrame() ?: return
                                try {
                                    val canvas = surfaceHolder.lockCanvas() ?: return
                                    canvas.drawColor(Color.BLACK)
                                    val m = getMetrics()
                                    val dest = calcDestRect(
                                        frame.width.toFloat(), frame.height.toFloat(),
                                        m.widthPixels.toFloat(), m.heightPixels.toFloat(), scaleMode
                                    )
                                    canvas.drawBitmap(frame, null, dest, null)
                                    surfaceHolder.unlockCanvasAndPost(canvas)
                                } catch (_: Exception) {}
                                mainHandler.postDelayed(this, (1000 / fps).toLong())
                            }
                        }
                        videoRenderRunnable = runnable
                        runnable.run()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "playVideo failed", e)
                }
            }
        }

        private fun stopVideo() {
            videoRenderRunnable?.let { mainHandler.removeCallbacks(it) }
            videoRenderRunnable = null
            videoDecoder?.release()
            videoDecoder = null
        }

        // ======== GIF playback ========

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
                drawable.repeatCount = -1
                drawable.start()
                val runnable = object : Runnable {
                    override fun run() {
                        if (!surfaceReady || !isVisible || gifDrawable == null) return
                        try {
                            val canvas = surfaceHolder.lockCanvas() ?: return
                            canvas.drawColor(Color.BLACK)
                            val m = getMetrics()
                            val dest = calcDestRect(
                                drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat(),
                                m.widthPixels.toFloat(), m.heightPixels.toFloat(), scaleMode
                            )
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

// ======== Video frame decoder using MediaCodec ========
// Decodes video frames one at a time, returns Bitmap for each frame

class VideoFrameDecoder(
    private val context: Context,
    private val uriStr: String
) {
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var videoTrackIndex = -1
    private var durationUs = 0L
    private var startTimeNs = 0L
    private var lastPtsUs = 0L
    private var isLooping = true

    var fps = 30
        private set
    var isPlaying = false
        private set
    private var paused = false



    fun init(): Boolean {
        try {
            val ext = MediaExtractor()
            ext.setDataSource(context, Uri.parse(uriStr), null)

            for (i in 0 until ext.trackCount) {
                val format = ext.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    ext.selectTrack(i)

                    durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    fps = format.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30)

                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)

                    val decoder = MediaCodec.createDecoderByType(mime)
                    decoder.configure(format, null, null, 0)
                    decoder.start()

                    extractor = ext
                    codec = decoder
                    startTimeNs = System.nanoTime()
                    isPlaying = true

                    Log.d(TAG, "VideoCodec init: ${width}x${height} ${fps}fps ${mime}")
                    return true
                }
            }
            ext.release()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "VideoCodec init failed", e)
            release()
            return false
        }
    }

    fun decodeFrame(): Bitmap? {
        val ext = extractor ?: return null
        val cdc = codec ?: return null
        if (paused) return null

        try {
            // Feed input
            val inputIndex = cdc.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer = cdc.getInputBuffer(inputIndex) ?: return null
                val sampleSize = ext.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    // End of stream - loop
                    if (isLooping) {
                        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        startTimeNs = System.nanoTime()
                        lastPtsUs = 0
                    }
                    cdc.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    cdc.queueInputBuffer(inputIndex, 0, sampleSize, ext.sampleTime, 0)
                    ext.advance()
                }
            }

            // Wait for frame timing
            val elapsedNs = System.nanoTime() - startTimeNs
            val elapsedUs = elapsedNs / 1000
            if (lastPtsUs > elapsedUs + 33_000) {
                // Frame is ahead of real time, wait
                Thread.sleep(10)
                return null
            }

            // Get output
            val info = MediaCodec.BufferInfo()
            val outputIndex = cdc.dequeueOutputBuffer(info, 10_000L)
            if (outputIndex >= 0) {
                lastPtsUs = info.presentationTimeUs
                val outputBuffer = cdc.getOutputBuffer(outputIndex) ?: return null
                val format = cdc.outputFormat
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)

                val bitmap = frameToBitmap(outputBuffer, format, width, height)
                cdc.releaseOutputBuffer(outputIndex, false)

                // Check end of stream
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    if (isLooping) {
                        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        startTimeNs = System.nanoTime()
                        lastPtsUs = 0
                    }
                }

                return bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeFrame error", e)
        }
        return null
    }

    private fun frameToBitmap(buffer: ByteBuffer, format: MediaFormat, width: Int, height: Int): Bitmap {
        val colorFormat = format.getIntegerOrDefault(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)

        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> {
                // NV21/NV12 → ARGB
                val yuv = ByteArray(buffer.remaining())
                buffer.get(yuv)
                return yuvToBitmap(yuv, width, height, colorFormat)
            }
            MediaCodecInfo.CodecCapabilities.COLOR_FormatRGBAFlexible,
            MediaCodecInfo.CodecCapabilities.COLOR_Format32bitARGB8888 -> {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
                return bitmap
            }
            else -> {
                // Try NV12 as fallback
                val yuv = ByteArray(buffer.remaining())
                buffer.get(yuv)
                return yuvToBitmap(yuv, width, height, colorFormat)
            }
        }
    }

    private fun yuvToBitmap(yuv: ByteArray, width: Int, height: Int, colorFormat: Int): Bitmap {
        val argb = IntArray(width * height)
        val frameSize = width * height

        // Y plane
        for (i in 0 until height) {
            for (j in 0 until width) {
                val y = (yuv[i * width + j].toInt() and 0xFF) - 16
                if (y < 0) y.also { /* clamp */ }
            }
        }

        // Full YUV420SP to ARGB conversion
        var yp = 0
        for (j in 0 until height) {
            for (i in 0 until width) {
                val y = (yuv[yp].toInt() and 0xFF) - 16
                val uvIndex = frameSize + (j shr 1) * width + (i and 0xFFFE)
                val u = (yuv[uvIndex].toInt() and 0xFF) - 128
                val v = (yuv[uvIndex + 1].toInt() and 0xFF) - 128

                var r = (1.164 * y + 1.596 * v).toInt()
                var g = (1.164 * y - 0.813 * v - 0.391 * u).toInt()
                var b = (1.164 * y + 2.018 * u).toInt()

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                argb[yp] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                yp++
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        return bitmap
    }

    fun getFirstFrame(): Bitmap? {
        val ext = extractor ?: return null
        ext.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        startTimeNs = System.nanoTime()
        lastPtsUs = 0
        for (i in 0..20) {
            val frame = decodeFrame()
            if (frame != null) return frame
        }
        return null
    }

    fun pause() { paused = true }
    fun resume() { paused = false; startTimeNs = System.nanoTime() - lastPtsUs * 1000 }

    fun release() {
        isPlaying = false
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return if (containsKey(key)) getInteger(key) else default
    }

    companion object {
        private const val TAG = "VideoFrameDecoder"
    }
}
