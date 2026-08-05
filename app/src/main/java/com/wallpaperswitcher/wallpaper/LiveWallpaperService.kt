package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.MediaPlayer
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

        // Current display state - the single source of truth
        private var displayedId = -1L
        private var mediaPlayer: MediaPlayer? = null
        @Volatile private var videoPlaying = false

        // GIF
        private var gifDrawable: android.graphics.drawable.AnimatedImageDrawable? = null
        private var gifFrameRunnable: Runnable? = null
        private var gifBitmapBuffer: Bitmap? = null

        private val switchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_SWITCH) {
                    val targetId = intent.getLongExtra(EXTRA_TARGET_ID, -1L)
                    Log.d(TAG, "Broadcast targetId=$targetId")
                    doSwitch(targetId)
                }
            }
        }

        private val gestureDetector = GestureDetector(
            applicationContext,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    doSwitch(null)
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
            Log.d(TAG, "Surface created")
            syncDisplay()
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
            Log.d(TAG, "Visibility: $visible")
            if (visible) {
                syncDisplay()
            } else {
                pauseCurrentMedia()
            }
        }

        override fun onDestroy() {
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            releaseAll()
            scope.cancel()
            super.onDestroy()
        }

        // ======== Core: Single source of truth - sync display with DB ========

        /**
         * Read DB, compare with what's displayed, switch if different.
         * This is the ONLY function that decides what to display.
         * Called on: surfaceCreated, visibilityChanged(true), after switch.
         */
        private fun syncDisplay() {
            if (!surfaceReady || !isVisible) return
            if (isSwitching.get()) return

            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    currentScaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }

                    // Skip if same content is already displayed and active
                    if (imageId == displayedId && isCurrentlyPlaying()) {
                        return@launch
                    }

                    val image = if (imageId > 0) db.wallpaperImageDao().getImageById(imageId) else null
                    if (image == null) {
                        mainHandler.post { showDefault() }
                        return@launch
                    }

                    Log.d(TAG, "syncDisplay: ${image.displayName} (${image.mediaType}), id=$imageId, was=$displayedId")
                    displayedId = imageId

                    // ALL media operations in ONE mainHandler.post - atomic, no race
                    mainHandler.post {
                        // 1. Release old media
                        try {
                            mediaPlayer?.let {
                                try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
                                it.release()
                            }
                        } catch (_: Exception) {}
                        mediaPlayer = null
                        videoPlaying = false
                        gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
                        gifFrameRunnable = null
                        try { gifDrawable?.stop() } catch (_: Exception) {}
                        gifDrawable = null

                        if (!surfaceReady) return@post

                        // 2. Start new content (NO resetSurface for video!)
                        when (image.mediaType ?: "IMAGE") {
                            "VIDEO" -> startVideoInternal(image.uri)
                            else -> {
                                // Only reset surface for Canvas-based content
                                try {
                                    val canvas = surfaceHolder.lockCanvas()
                                    if (canvas != null) {
                                        canvas.drawColor(Color.BLACK)
                                        surfaceHolder.unlockCanvasAndPost(canvas)
                                    }
                                } catch (_: Exception) {}

                                when (image.mediaType) {
                                    "GIF" -> playGif(image.uri, currentScaleMode)
                                    else -> {
                                        val bitmap = loadBitmap(image.uri)
                                        if (bitmap != null) showBitmap(bitmap, currentScaleMode)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "syncDisplay error", e)
                }
            }
        }

        /**
         * Check if current content is actively playing (video or GIF).
         */
        private fun isCurrentlyPlaying(): Boolean {
            // Video playing
            if (videoPlaying && mediaPlayer != null) return true
            // GIF playing
            if (gifDrawable != null && gifFrameRunnable != null) return true
            // Image is static - always "playing" if displayedId matches
            if (displayedId > 0 && mediaPlayer == null && gifDrawable == null) return true
            return false
        }

        /**
         * Switch to specific target (from broadcast) or next random (from double-tap).
         */
        private fun doSwitch(targetId: Long?) {
            if (isSwitching.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        val dao = db.settingsDao()
                        val imageDao = db.wallpaperImageDao()

                        val nextImage = if (targetId != null && targetId > 0) {
                            imageDao.getImageById(targetId)
                        } else {
                            val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                            val switchMode = try {
                                SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                            } catch (_: Exception) { SwitchMode.RANDOM }
                            pickNextImage(switchMode, imageDao, lastId, dao)
                        }

                        if (nextImage != null) {
                            Log.d(TAG, "doSwitch to: ${nextImage.displayName} (${nextImage.mediaType})")
                            dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
                            // syncDisplay will pick up the change
                            displayedId = -1 // Force re-display
                            mainHandler.post { syncDisplay() }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "doSwitch error", e)
                    } finally {
                        isSwitching.set(false)
                    }
                }
            }
        }

        private fun releaseCurrentMedia() {
            // Capture locally to avoid race with concurrent mediaPlayer assignment
            val oldPlayer = mediaPlayer
            mediaPlayer = null
            videoPlaying = false
            mainHandler.post {
                try {
                    oldPlayer?.let {
                        try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
                        it.release()
                    }
                } catch (_: Exception) {}
            }
            // Stop GIF
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
            gifFrameRunnable = null
            try { gifDrawable?.stop() } catch (_: Exception) {}
            gifDrawable = null
        }

        private fun pauseCurrentMedia() {
            mainHandler.post {
                try { mediaPlayer?.pause() } catch (_: Exception) {}
            }
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
        }

        private fun resetSurface() {
            mainHandler.post {
                var canvas: android.graphics.Canvas? = null
                try {
                    canvas = surfaceHolder.lockCanvas()
                    if (canvas != null) {
                        canvas.drawColor(Color.BLACK)
                    }
                } catch (_: Exception) {}
                finally {
                    if (canvas != null) {
                        try { surfaceHolder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                    }
                }
            }
        }

        // ======== Video via MediaPlayer (called on main thread) ========

        private fun startVideoInternal(uriStr: String) {
            Log.d(TAG, "startVideo: $uriStr")
            if (!surfaceReady) return
            val surface = surfaceHolder.surface
            if (surface == null || !surface.isValid) {
                Log.e(TAG, "startVideo: surface not valid")
                return
            }

            try {
                val mp = MediaPlayer()

                mp.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    videoPlaying = false
                    try { mp.release() } catch (_: Exception) {}
                    if (mediaPlayer === mp) mediaPlayer = null
                    false
                }

                mp.setOnPreparedListener { player ->
                    Log.d(TAG, "Video prepared: ${player.videoWidth}x${player.videoHeight}")
                    try {
                        player.isLooping = true
                        player.start()
                        videoPlaying = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Video start failed: ${e.message}")
                    }
                }

                mp.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        Log.d(TAG, "Video rendering started")
                        videoPlaying = true
                    }
                    false
                }

                mp.setDataSource(applicationContext, Uri.parse(uriStr))
                mp.setSurface(surface)
                mp.prepareAsync()

                mediaPlayer = mp
            } catch (e: Exception) {
                Log.e(TAG, "startVideo error: ${e.message}")
                videoPlaying = false
            }
        }

        // ======== pickNextImage ========

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

        // ======== GIF ========

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

        private fun releaseAll() {
            // Capture locally to avoid race with concurrent mediaPlayer assignment
            val oldPlayer = mediaPlayer
            mediaPlayer = null
            videoPlaying = false
            mainHandler.post {
                try {
                    oldPlayer?.let {
                        try { if (it.isPlaying) it.stop() } catch (_: Exception) {}
                        it.release()
                    }
                } catch (_: Exception) {}
            }
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
            gifFrameRunnable = null
            try { gifDrawable?.stop() } catch (_: Exception) {}
            gifDrawable = null
            gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
            displayedId = -1
        }

        private fun loadBitmap(uriStr: String): Bitmap? {
            return com.wallpaperswitcher.engine.BitmapUtils.loadBitmap(applicationContext, uriStr)
        }

        private fun getMetrics(): android.util.DisplayMetrics {
            return com.wallpaperswitcher.engine.BitmapUtils.getScreenMetrics(applicationContext)
        }
    }
}
