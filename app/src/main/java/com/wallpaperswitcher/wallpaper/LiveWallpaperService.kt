package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
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
        private var currentMediaType: String = "IMAGE"

        // Video
        private var mediaPlayer: MediaPlayer? = null
        private var videoPlaying = false
        private var lastVideoUri: String = ""

        // GIF
        private var gifDrawable: android.graphics.drawable.AnimatedImageDrawable? = null
        private var gifFrameRunnable: Runnable? = null

        // Debug
        private var debugMsg: String = ""

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
            Log.d(TAG, "Surface created")
            if (videoPlaying && mediaPlayer != null) {
                try {
                    mediaPlayer!!.setSurface(holder?.surface)
                    if (!mediaPlayer!!.isPlaying) mediaPlayer!!.start()
                    Log.d(TAG, "Video resumed on new surface")
                } catch (e: Exception) {
                    Log.e(TAG, "Resume video failed", e)
                }
            }
            drawCurrentImage()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            Log.d(TAG, "Surface changed: ${width}x${height}")
            if (videoPlaying && mediaPlayer != null) {
                try { mediaPlayer!!.setSurface(holder?.surface) } catch (_: Exception) {}
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            Log.d(TAG, "Surface destroyed")
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            Log.d(TAG, "Visibility: $visible, videoPlaying=$videoPlaying")
            if (visible) {
                if (videoPlaying && mediaPlayer != null) {
                    try {
                        mediaPlayer!!.setSurface(surfaceHolder?.surface)
                        if (!mediaPlayer!!.isPlaying) mediaPlayer!!.start()
                    } catch (_: Exception) {}
                }
                drawCurrentImage()
            } else {
                if (videoPlaying && mediaPlayer != null) {
                    try { if (mediaPlayer!!.isPlaying) mediaPlayer!!.pause() } catch (_: Exception) {}
                }
                stopGif()
            }
        }

        override fun onDestroy() {
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            releaseVideo(); stopGif()
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
                    Log.d(TAG, "$source: ${nextImage.displayName} type=$mediaType uri=${nextImage.uri.take(50)}")

                    when (mediaType) {
                        "VIDEO" -> {
                            if (videoPlaying && mediaPlayer?.isPlaying == true && nextImage.uri == lastVideoUri) {
                                Log.d(TAG, "Same video playing, skip")
                            } else {
                                mainHandler.post { playVideo(nextImage.uri, scaleMode) }
                            }
                        }
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
            if (videoPlaying && mediaPlayer?.isPlaying == true) return

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

        // ======== Video playback ========

        private fun playVideo(uriStr: String, scaleMode: ScaleMode = ScaleMode.FIT) {
            releaseVideo()
            stopGif()
            if (!surfaceReady) return

            val uri = Uri.parse(uriStr)
            Log.d(TAG, "playVideo: $uriStr")

            // Show first frame immediately, then try continuous playback
            scope.launch {
                // Always show first frame as fallback
                val firstFrame = extractFirstFrame(uri)
                if (firstFrame != null) {
                    mainHandler.post { showBitmap(firstFrame, scaleMode) }
                }

                // Try MediaPlayer first (works on most devices)
                val mpWorks = tryMediaPlayer(uri, scaleMode)
                if (!mpWorks) {
                    Log.d(TAG, "MediaPlayer failed, trying MediaCodec")
                    // Fallback: use MediaCodec to decode frames
                    tryMediaCodec(uri, scaleMode)
                }
            }
        }

        private fun tryMediaPlayer(uri: Uri, scaleMode: ScaleMode): Boolean {
            return try {
                val surface = surfaceHolder?.surface ?: return false
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return false

                mediaPlayer = MediaPlayer().apply {
                    setDataSource(pfd.fileDescriptor)
                    pfd.close()
                    setSurface(surface)
                    isLooping = true

                    setOnPreparedListener { mp ->
                        mp.start()
                        videoPlaying = true
                        Log.d(TAG, "MediaPlayer OK: ${mp.videoWidth}x${mp.videoHeight}")
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        videoPlaying = false
                        false
                    }
                    prepare()
                    start()
                    videoPlaying = true
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "tryMediaPlayer failed: ${e.message}")
                false
            }
        }

        private fun tryMediaCodec(uri: Uri, scaleMode: ScaleMode) {
            try {
                val extractor = android.media.MediaExtractor()
                extractor.setDataSource(applicationContext, uri, null)

                var trackIndex = -1
                var mime = ""
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val m = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                    if (m.startsWith("video/")) {
                        trackIndex = i
                        mime = m
                        break
                    }
                }

                if (trackIndex < 0) {
                    Log.e(TAG, "No video track found")
                    return
                }

                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val width = format.getInteger(android.media.MediaFormat.KEY_WIDTH)
                val height = format.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                val fps = format.getIntegerOrDefault(android.media.MediaFormat.KEY_FRAME_RATE, 30)
                Log.d(TAG, "MediaCodec: ${width}x${height} $fps fps $mime")

                val codec = android.media.MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val bufferInfo = android.media.MediaCodec.BufferInfo()
                var startTimeNs = System.nanoTime()
                var isDecoding = true

                // Decode loop on background thread
                scope.launch {
                    while (isDecoding && surfaceReady) {
                        if (!isVisible) { delay(100); continue }

                        // Feed input
                        val inputIndex = codec.dequeueInputBuffer(10000L)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                                startTimeNs = System.nanoTime()
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }

                        // Get output
                        val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                        if (outputIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                val bitmap = yuvToBitmap(outputBuffer, format, width, height)
                                if (bitmap != null) {
                                    mainHandler.post { showBitmap(bitmap, scaleMode) }
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)

                            // Frame timing
                            val elapsedNs = System.nanoTime() - startTimeNs
                            val targetNs = bufferInfo.presentationTimeUs * 1000
                            val sleepMs = ((targetNs - elapsedNs) / 1_000_000).coerceIn(0, 100)
                            if (sleepMs > 0) delay(sleepMs)
                        } else if (outputIndex == android.media.MediaCodec.INFO_TRY_AGAIN_LATER) {
                            delay(5)
                        }

                        // End of stream
                        if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            startTimeNs = System.nanoTime()
                        }
                    }
                    codec.stop()
                    codec.release()
                    extractor.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "tryMediaCodec failed: ${e.message}")
            }
        }

        private fun yuvToBitmap(buffer: java.nio.ByteBuffer, format: android.media.MediaFormat, width: Int, height: Int): Bitmap? {
            return try {
                val yuv = ByteArray(buffer.remaining())
                buffer.get(yuv)

                // MediaCodec outputs NV12 (UV), YuvImage needs NV21 (VU) - swap UV bytes
                val frameSize = width * height
                var i = frameSize
                while (i < yuv.size - 1) {
                    val tmp = yuv[i]
                    yuv[i] = yuv[i + 1]
                    yuv[i + 1] = tmp
                    i += 2
                }

                val yuvImage = android.graphics.YuvImage(yuv, android.graphics.ImageFormat.NV21, width, height, null)
                val out = java.io.ByteArrayOutputStream()
                yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 85, out)
                val bytes = out.toByteArray()
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                Log.e(TAG, "yuvToBitmap error: ${e.message}")
                null
            }
        }

        private fun android.media.MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
            return if (containsKey(key)) getInteger(key) else default
        }

        // startVideoPlayback replaced by tryMediaPlayer + tryMediaCodec
        private fun startVideoPlayback_unused(uri: Uri) {
            try {
                val surface = surfaceHolder?.surface
                if (surface == null || !surface.isValid) {
                    Log.e(TAG, "Surface is null or invalid")
                    debugMsg = "Surface invalid"
                    showDebug()
                    return
                }

                Log.d(TAG, "Creating MediaPlayer, surface=${surface.hashCode()}")
                mediaPlayer = MediaPlayer().apply {
                    // Use FileDescriptor to avoid MEDIA_ERROR_IO (-38)
                    val pfd = try { contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
                    if (pfd == null) {
                        Log.e(TAG, "openFileDescriptor returned null for: $uri")
                        debugMsg = "Cannot open video file"
                        showDebug()
                        return
                    }
                    setDataSource(pfd.fileDescriptor)
                    pfd.close()

                    setOnPreparedListener { mp ->
                        Log.d(TAG, "MediaPlayer prepared: ${mp.videoWidth}x${mp.videoHeight}")
                        try {
                            setSurface(surface)
                            mp.start()
                            videoPlaying = true
                            debugMsg = ""
                            Log.d(TAG, "Video playing!")
                        } catch (e: Exception) {
                            Log.e(TAG, "setSurface/start failed", e)
                            debugMsg = "Play error: ${e.message?.take(40)}"
                            showDebug()
                        }
                    }

                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        videoPlaying = false
                        debugMsg = "Error: what=$what extra=$extra"
                        showDebug()
                        true
                    }

                    setOnCompletionListener {
                        Log.d(TAG, "Video completed, looping")
                        // isLooping handles this
                    }

                    isLooping = true

                    try {
                        Log.d(TAG, "MediaPlayer.prepare() sync...")
                        prepare()
                        Log.d(TAG, "MediaPlayer prepared OK (sync)")
                        setSurface(surface)
                        start()
                        videoPlaying = true
                        debugMsg = ""
                        Log.d(TAG, "Video started (sync path)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Sync prepare failed: ${e.message}")
                        debugMsg = "Prepare error: ${e.message?.take(40)}"
                        showDebug()
                        try {
                            Log.d(TAG, "Trying prepareAsync...")
                            prepareAsync()
                        } catch (e2: Exception) {
                            Log.e(TAG, "Async prepare also failed: ${e2.message}")
                            debugMsg = "Async error: ${e2.message?.take(40)}"
                            showDebug()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startVideoPlayback failed", e)
                debugMsg = "Init error: ${e.message?.take(40)}"
                showDebug()
            }
        }

        private fun extractFirstFrame(uri: Uri): Bitmap? {
            return try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                val frame = retriever.frameAtTime
                retriever.release()
                frame
            } catch (e: Exception) {
                Log.e(TAG, "extractFirstFrame failed", e)
                null
            }
        }

        private fun releaseVideo() {
            videoPlaying = false
            try {
                mediaPlayer?.let {
                    try { it.setSurface(null) } catch (_: Exception) {}
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
            mediaPlayer = null
        }

        // ======== GIF playback ========

        private fun playGif(uriStr: String, scaleMode: ScaleMode = ScaleMode.FIT) {
            stopGif()
            releaseVideo()
            if (!surfaceReady) return
            currentMediaType = "GIF"
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

        private fun showDebug() {
            if (!surfaceReady || debugMsg.isEmpty()) return
            try {
                val canvas = surfaceHolder.lockCanvas() ?: return
                canvas.drawColor(Color.DKGRAY)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE; textSize = 32f; textAlign = Paint.Align.CENTER
                }
                val m = getMetrics()
                canvas.drawText(debugMsg, m.widthPixels / 2f, m.heightPixels / 2f, p)
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

        private fun getMetrics(): DisplayMetrics {
            val wm = applicationContext.getSystemService(WINDOW_SERVICE) as WindowManager
            return DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }
        }
    }
}
