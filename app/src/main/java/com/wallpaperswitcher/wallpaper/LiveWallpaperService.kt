package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.media.MediaMetadataRetriever
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
        private const val SWITCH_SETTLE_DELAY_MS = 100L
        private const val GIF_FRAME_INTERVAL_MS = 33L // ~30fps
        private const val SHUFFLE_MAX_ATTEMPTS = 10

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

        // Shuffle state — cached in memory, flushed to DB only on destroy
        private val shuffleShownIds = ConcurrentHashMap.newKeySet<Long>()
        @Volatile private var shuffleAllCount = 0
        @Volatile private var shuffleDirty = false

        // Video renderer (MediaCodec + SurfaceTexture + EGL on HandlerThread)
        private var videoRenderer: VideoRenderer? = null
        @Volatile private var videoMode = false

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
                // Unregister first to prevent duplicate registration on engine recreate
                try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
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
                if (videoMode) videoRenderer?.resume() else drawCurrentImage()
            } else {
                if (videoMode) videoRenderer?.pause()
                pauseGif()
            }
        }

        override fun onDestroy() {
            engineRunning = false
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            // Flush shuffle state to DB on destroy
            flushShuffleState()
            releaseAll()
            scope.cancel()
            super.onDestroy()
        }

        private fun flushShuffleState() {
            if (!shuffleDirty) return
            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val shuffleKey = "shuffle_ids" // simplified key
                    val countKey = "shuffle_count"
                    dao.setString(shuffleKey, shuffleShownIds.joinToString(","))
                    dao.setLong(countKey, shuffleAllCount.toLong())
                } catch (_: Exception) {}
            }
        }

        // ======== Resource lifecycle ========

        private fun releaseAll() {
            stopVideo()
            pauseGif()
            gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
            gifDrawable?.let {
                try { it.stop() } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= 28) {
                    try { (it as java.io.Closeable).close() } catch (_: Exception) {}
                }
            }
            gifDrawable = null
        }

        private fun stopVideo() {
            videoMode = false
            videoRenderer?.release()
            videoRenderer = null
        }

        private fun pauseGif() {
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
        }

        // ======== Switch logic ========

        /**
         * Determine which media type to switch to based on enabled groups.
         * Priority: IMAGE groups first. If no IMAGE groups, use VIDEO groups.
         * If both exist, only show IMAGE (video takes over screen, blocks image switching).
         */
        private suspend fun pickMediaType(): String {
            val imageGroups = db.wallpaperGroupDao().getEnabledGroupsByType("IMAGE")
            if (imageGroups.isNotEmpty()) return "IMAGE"
            return "VIDEO"
        }

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

                    val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                    if (groups.isEmpty()) return@launch

                    currentScaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }

                    val nextImage = if (targetId != null && targetId > 0) {
                        imageDao.getImageById(targetId)
                    } else {
                        val mediaType = pickMediaType()
                        val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                        val switchMode = try {
                            SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                        } catch (_: Exception) { SwitchMode.RANDOM }
                        pickNextImage(switchMode, imageDao, lastId, dao, mediaType)
                    }

                    if (nextImage == null) return@launch

                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
                    val mediaType = nextImage.mediaType ?: "IMAGE"
                    Log.d(TAG, "Switch to: ${nextImage.displayName} ($mediaType)")

                    // Stop everything before switching
                    stopVideo()
                    pauseGif()
                    delay(SWITCH_SETTLE_DELAY_MS)

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
            switchMode: SwitchMode, imageDao: WallpaperImageDao, lastId: Long, dao: SettingsDao, groupType: String
        ): WallpaperImage? {
            return when (switchMode) {
                SwitchMode.RANDOM -> {
                    imageDao.getRandomFromEnabledGroupsByTypeExcluding(groupType, lastId)
                        ?: imageDao.getRandomFromEnabledGroupsByType(groupType)
                }
                SwitchMode.SEQUENTIAL -> {
                    val count = imageDao.countByEnabledGroupsOfType(groupType)
                    if (count == 0) null else {
                        val key = if (groupType == "VIDEO") SettingsKeys.VIDEO_SEQ_INDEX else SettingsKeys.SEQUENTIAL_INDEX
                        val idx = dao.getLong(key).toInt()
                        val next = idx % count
                        val img = imageDao.getSequentialFromEnabledGroupsByType(groupType, next)
                        if (img != null) {
                            dao.setLong(key, (next + 1).toLong())
                            img
                        } else {
                            // Offset out of range (e.g. images deleted) — reset index
                            dao.setLong(key, 0L)
                            imageDao.getSequentialFromEnabledGroupsByType(groupType, 0)
                                ?: imageDao.getRandomFromEnabledGroupsByType(groupType)
                        }
                    }
                }
                SwitchMode.SHUFFLE -> {
                    val totalCount = imageDao.countByEnabledGroupsOfType(groupType)
                    if (totalCount == 0) null else {
                        // Load shuffle state from DB only if not yet loaded in memory
                        if (shuffleShownIds.isEmpty() && shuffleAllCount == 0) {
                            val shuffleKey = if (groupType == "VIDEO") "video_shuffle" else "image_shuffle"
                            val countKey = if (groupType == "VIDEO") "video_shuffle_count" else "image_shuffle_count"
                            val savedIds = dao.getString(shuffleKey, "")
                            val savedCount = dao.getLong(countKey, 0L).toInt()
                            if (savedIds.isNotEmpty()) {
                                savedIds.split(",").mapNotNull { it.toLongOrNull() }.forEach { shuffleShownIds.add(it) }
                            }
                            shuffleAllCount = savedCount
                        }
                        if (shuffleAllCount != totalCount || shuffleShownIds.size >= totalCount) {
                            shuffleShownIds.clear()
                        }
                        var attempts = 0; var candidate: WallpaperImage? = null
                        while (attempts < SHUFFLE_MAX_ATTEMPTS && candidate == null) {
                            val img = imageDao.getRandomFromEnabledGroupsByTypeExcluding(groupType, lastId)
                                ?: imageDao.getRandomFromEnabledGroupsByType(groupType)
                            if (img != null && img.id !in shuffleShownIds) candidate = img
                            else if (img != null && shuffleShownIds.size >= totalCount) {
                                shuffleShownIds.clear(); candidate = img
                            }
                            attempts++
                        }
                        candidate?.also {
                            shuffleShownIds.add(it.id)
                            shuffleAllCount = totalCount
                            shuffleDirty = true
                        }
                    }
                }
            }
        }

        private fun drawCurrentImage() {
            if (!surfaceReady || !isVisible) return
            if (isSwitching.get()) return
            if (videoMode) return

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

        // ======== Video via VideoRenderer (MediaCodec + EGL + SurfaceTexture) ========

        private fun startVideo(uriStr: String, scaleMode: ScaleMode) {
            videoMode = true
            val sw = cachedScreenW.takeIf { it > 0 } ?: getMetrics().widthPixels.toFloat()
            val sh = cachedScreenH.takeIf { it > 0 } ?: getMetrics().heightPixels.toFloat()
            val renderer = VideoRenderer(applicationContext, surfaceHolder, mainHandler)
            videoRenderer = renderer
            renderer.start(uriStr, scaleMode, sw, sh)
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
            val source = android.graphics.ImageDecoder.createSource(contentResolver, Uri.parse(uriStr))
            val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
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
                        mainHandler.postDelayed(this, GIF_FRAME_INTERVAL_MS)
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
                // Don't recycle old bitmap here — unlockCanvasAndPost is async and
                // the GPU may still be reading the old bitmap. Let GC handle it.
                currentBitmap = bitmap
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
