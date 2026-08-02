package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
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

        // Video playback
        private var mediaPlayer: MediaPlayer? = null
        private var isVideoPlaying = false

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
            if (visible) {
                drawCurrentImage()
            } else {
                stopVideo()
                stopGif()
            }
        }

        override fun onDestroy() {
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            stopVideo()
            stopGif()
            currentBitmap?.recycle(); currentBitmap = null
            scope.cancel()
            super.onDestroy()
        }

        // ======== Media type detection ========

        private fun getMediaType(uriStr: String): String {
            return try {
                val uri = Uri.parse(uriStr)
                val name = uri.lastPathSegment ?: ""
                val ext = name.lowercase().substringAfterLast('.', "")
                when (ext) {
                    "gif" -> "GIF"
                    "mp4", "mkv", "webm", "avi", "mov", "3gp" -> "VIDEO"
                    else -> "IMAGE"
                }
            } catch (_: Exception) { "IMAGE" }
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

                    val scaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }

                    val mediaType = nextImage.mediaType ?: getMediaType(nextImage.uri)

                    when (mediaType) {
                        "VIDEO" -> {
                            mainHandler.post { playVideo(nextImage.uri) }
                        }
                        "GIF" -> {
                            mainHandler.post { playGif(nextImage.uri, scaleMode) }
                        }
                        else -> {
                            val bitmap = loadBitmap(nextImage.uri)
                            if (bitmap != null) {
                                mainHandler.post { showBitmap(bitmap, scaleMode) }
                            }
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
                        val mediaType = image.mediaType ?: getMediaType(image.uri)
                        when (mediaType) {
                            "VIDEO" -> { mainHandler.post { playVideo(image.uri) }; return@launch }
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

        // ======== Video playback (MediaPlayer) ========

        private fun playVideo(uriStr: String) {
            stopVideo()
            stopGif()
            if (!surfaceReady) return
            try {
                val uri = Uri.parse(uriStr)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, uri)
                    isLooping = true
                    setSurface(surfaceHolder.surface)
                    setOnPreparedListener { mp ->
                        mp.start()
                        isVideoPlaying = true
                        Log.d(TAG, "Video playback started")
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        stopVideo()
                        showDefault()
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "playVideo failed", e)
                stopVideo()
            }
        }

        private fun stopVideo() {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
            mediaPlayer = null
            isVideoPlaying = false
        }

        // ======== GIF playback (ImageDecoder, API 28+) ========

        private fun playGif(uriStr: String, scaleMode: ScaleMode = ScaleMode.FIT) {
            stopGif()
            stopVideo()
            if (!surfaceReady) return
            try {
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    playGifImageDecoder(uriStr, scaleMode)
                } else {
                    val bitmap = loadBitmap(uriStr)
                    if (bitmap != null) showBitmap(bitmap, scaleMode)
                }
            } catch (e: Exception) {
                Log.e(TAG, "playGif failed", e)
                val bitmap = loadBitmap(uriStr)
                if (bitmap != null) showBitmap(bitmap, scaleMode)
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
                drawable.repeatCount = android.graphics.drawable.AnimatedImageDrawable.INFINITE
                drawable.start()

                val renderRunnable = object : Runnable {
                    override fun run() {
                        if (!surfaceReady || !isVisible || gifDrawable == null) return
                        try {
                            val canvas = surfaceHolder.lockCanvas() ?: return
                            canvas.drawColor(Color.BLACK)
                            val m = getMetrics()
                            val sw = m.widthPixels.toFloat()
                            val sh = m.heightPixels.toFloat()
                            val bw = drawable.intrinsicWidth.toFloat()
                            val bh = drawable.intrinsicHeight.toFloat()
                            val dw: Float
                            val dh: Float

                            when (scaleMode) {
                                ScaleMode.FIT -> {
                                    val ratio = bw / bh; val sr = sw / sh
                                    if (ratio > sr) { dw = sw; dh = dw / ratio } else { dh = sh; dw = dh * ratio }
                                }
                                ScaleMode.FILL -> {
                                    val ratio = bw / bh; val sr = sw / sh
                                    if (ratio < sr) { dw = sw; dh = dw / ratio } else { dh = sh; dw = dh * ratio }
                                }
                                ScaleMode.STRETCH -> { dw = sw; dh = sh }
                            }

                            val l = (sw - dw) / 2f; val t = (sh - dh) / 2f
                            drawable.setBounds(l.toInt(), t.toInt(), (l + dw).toInt(), (t + dh).toInt())
                            drawable.draw(canvas)
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {}
                        mainHandler.postDelayed(this, 33)
                    }
                }
                gifFrameRunnable = renderRunnable
                mainHandler.post(renderRunnable)
                Log.d(TAG, "GIF playback started")
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
                val sw = m.widthPixels.toFloat(); val sh = m.heightPixels.toFloat()
                val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()

                val dest: RectF = when (scaleMode) {
                    ScaleMode.FIT -> {
                        val ratio = bw / bh; val sr = sw / sh
                        val dw: Float; val dh: Float
                        if (ratio > sr) { dw = sw; dh = dw / ratio } else { dh = sh; dw = dh * ratio }
                        val l = (sw - dw) / 2f; val t = (sh - dh) / 2f
                        RectF(l, t, l + dw, t + dh)
                    }
                    ScaleMode.FILL -> {
                        val ratio = bw / bh; val sr = sw / sh
                        val dw: Float; val dh: Float
                        if (ratio < sr) { dw = sw; dh = dw / ratio } else { dh = sh; dw = dh * ratio }
                        val l = (sw - dw) / 2f; val t = (sh - dh) / 2f
                        RectF(l, t, l + dw, t + dh)
                    }
                    ScaleMode.STRETCH -> {
                        RectF(0f, 0f, sw, sh)
                    }
                }

                canvas.drawBitmap(bitmap, null, dest, null)
                surfaceHolder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) { Log.e(TAG, "showBitmap error", e) }
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