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

        // Video
        private var mediaPlayer: MediaPlayer? = null
        private var videoPlaying = false

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
            // If a video was playing, restart it on the new surface
            if (videoPlaying && mediaPlayer != null) {
                try {
                    mediaPlayer!!.setDisplay(holder)
                    if (!mediaPlayer!!.isPlaying) mediaPlayer!!.start()
                } catch (e: Exception) {
                    Log.e(TAG, "setDisplay on surfaceCreated failed", e)
                }
            }
            drawCurrentImage()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            if (videoPlaying && mediaPlayer != null) {
                try { mediaPlayer!!.setDisplay(holder) } catch (_: Exception) {}
            }
            drawCurrentImage()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            // Don't stop video here - surface may be recreated
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                if (videoPlaying && mediaPlayer != null) {
                    try {
                        mediaPlayer!!.setDisplay(surfaceHolder)
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
            // If video is playing, don't override it
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

        // ======== Video playback ========

        private fun playVideo(uriStr: String, scaleMode: ScaleMode = ScaleMode.FIT) {
            releaseVideo()
            stopGif()
            if (!surfaceReady) return

            val uri = Uri.parse(uriStr)
            Log.d(TAG, "playVideo: $uriStr")

            // Step 1: Extract first frame as bitmap (guaranteed to show something)
            scope.launch {
                val firstFrame = extractVideoFrame(uriStr)
                if (firstFrame != null) {
                    mainHandler.post { showBitmap(firstFrame, scaleMode) }
                }

                // Step 2: Start MediaPlayer for continuous playback
                mainHandler.post { startMediaPlayer(uri, scaleMode) }
            }
        }

        private fun startMediaPlayer(uri: Uri, scaleMode: ScaleMode) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, uri)
                    isLooping = true

                    setOnPreparedListener { mp ->
                        Log.d(TAG, "MediaPlayer prepared: ${mp.videoWidth}x${mp.videoHeight}")
                        try {
                            mp.setDisplay(surfaceHolder)
                            mp.start()
                            videoPlaying = true
                            Log.d(TAG, "Video playback started")
                        } catch (e: Exception) {
                            Log.e(TAG, "setDisplay/start failed", e)
                            // First frame bitmap already shown as fallback
                        }
                    }

                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        videoPlaying = false
                        // First frame bitmap already shown as fallback
                        true
                    }

                    setOnInfoListener { _, what, extra ->
                        Log.d(TAG, "MediaPlayer info: what=$what extra=$extra")
                        false
                    }

                    // Use synchronous prepare on IO thread to catch errors immediately
                    try {
                        prepare()
                        Log.d(TAG, "MediaPlayer sync prepare OK")
                        setDisplay(surfaceHolder)
                        start()
                        videoPlaying = true
                        Log.d(TAG, "Video started (sync)")
                    } catch (e: Exception) {
                        Log.e(TAG, "sync prepare failed, trying async", e)
                        try { prepareAsync() } catch (e2: Exception) {
                            Log.e(TAG, "async prepare also failed", e2)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startMediaPlayer failed", e)
            }
        }

        private fun extractVideoFrame(uriStr: String): Bitmap? {
            return try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, Uri.parse(uriStr))
                val frame = retriever.frameAtTime
                retriever.release()
                if (frame != null) {
                    // Scale down if too large
                    val m = getMetrics()
                    val maxW = m.widthPixels * 2
                    val maxH = m.heightPixels * 2
                    if (frame.width > maxW || frame.height > maxH) {
                        val scaled = Bitmap.createScaledBitmap(frame,
                            frame.width * maxW / maxOf(frame.width, maxW),
                            frame.height * maxH / maxOf(frame.height, maxH), true)
                        if (scaled !== frame) frame.recycle()
                        scaled
                    } else frame
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "extractVideoFrame failed", e)
                null
            }
        }

        private fun releaseVideo() {
            videoPlaying = false
            try {
                mediaPlayer?.let {
                    try { it.setDisplay(null) } catch (_: Exception) {}
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
            mediaPlayer = null
        }

        // ======== GIF playback (ImageDecoder, API 28+) ========

        private fun playGif(uriStr: String, scaleMode: ScaleMode = ScaleMode.FIT) {
            stopGif()
            releaseVideo()
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
