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

        // Unified EGL renderer for both images and videos
        private var renderer: WallpaperRenderer? = null
        @Volatile private var videoMode = false
        // Pending target from broadcast, processed when current switch finishes
        @Volatile private var pendingTargetId: Long? = null
        // Track what's currently displayed to detect changes
        @Volatile private var lastDisplayedId = 0L

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
                    if (targetId > 0) {
                        // If currently switching, save as pending so it's not lost
                        if (isSwitching.get()) {
                            pendingTargetId = targetId
                        } else {
                            doSwitch("broadcast", targetId)
                        }
                    } else {
                        doSwitch("broadcast", null)
                    }
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
            // Initialize the unified EGL renderer
            val sw = cachedScreenW.takeIf { it > 0 } ?: getMetrics().widthPixels.toFloat()
            val sh = cachedScreenH.takeIf { it > 0 } ?: getMetrics().heightPixels.toFloat()
            renderer = WallpaperRenderer(applicationContext, surfaceHolder).also { it.initialize(sw, sh) }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            cachedScreenW = width.toFloat()
            cachedScreenH = height.toFloat()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            lastDisplayedId = 0L
            renderer?.release()
            renderer = null
            pauseGif()
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                scope.launch {
                    val dao = db.settingsDao()
                    val savedId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    if (savedId != lastDisplayedId || lastDisplayedId == 0L) {
                        stopVideo()
                        pauseGif()
                        videoMode = false
                        currentBitmap = null
                        drawCurrentImage()
                    } else if (videoMode) {
                        // Video auto-resumes via renderer
                    } else if (gifDrawable != null) {
                        // GIF auto-resumes via runnable
                    } else {
                        // Image already displayed, no action needed
                    }
                }
            } else {
                if (videoMode) renderer?.stopVideo()
                pauseGif()
            }
        }

        override fun onDestroy() {
            engineRunning = false
            lastDisplayedId = 0L
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
            renderer?.stopVideo()
            pauseGif()
            gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
            currentBitmap?.recycle(); currentBitmap = null
            lastDisplayedId = 0L
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
            renderer?.stopVideo()
        }

        private fun pauseGif() {
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
        }

        // ======== Switch logic ========

        /**
         * Determine which media type to switch to based on enabled groups.
         * If only one type has enabled groups, use that type.
         * If both exist, alternate based on current state.
         */
        private suspend fun pickMediaType(): String {
            val imageGroups = db.wallpaperGroupDao().getEnabledGroupsByType("IMAGE")
            val videoGroups = db.wallpaperGroupDao().getEnabledGroupsByType("VIDEO")
            return when {
                imageGroups.isEmpty() && videoGroups.isEmpty() -> "IMAGE"
                imageGroups.isEmpty() -> "VIDEO"
                videoGroups.isEmpty() -> "IMAGE"
                // Both exist: check what's currently displayed
                videoMode -> "IMAGE"  // Currently showing video → switch to image
                else -> "VIDEO"       // Currently showing image → switch to video
            }
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

                    var nextImage = if (targetId != null && targetId > 0) {
                        val img = imageDao.getImageById(targetId)
                        // Verify the image's group is enabled; if not, fall back
                        if (img != null) {
                            val group = db.wallpaperGroupDao().getGroupById(img.groupId)
                            if (group == null || !group.isEnabled) {
                                pickNextImage(SwitchMode.RANDOM, imageDao, 0L, dao, pickMediaType())
                            } else {
                                img
                            }
                        } else {
                            pickNextImage(SwitchMode.RANDOM, imageDao, 0L, dao, pickMediaType())
                        }
                    } else {
                        val mediaType = pickMediaType()
                        val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                        val switchMode = try {
                            SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                        } catch (_: Exception) { SwitchMode.RANDOM }
                        pickNextImage(switchMode, imageDao, lastId, dao, mediaType)
                    }

                    if (nextImage == null) {
                        // No enabled groups with images — try fallback
                        nextImage = imageDao.getFirstFromEnabledGroups()
                        if (nextImage == null) return@launch
                    }

                    dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
                    val mediaType = nextImage.mediaType ?: "IMAGE"
                    Log.d(TAG, "Switch to: ${nextImage.displayName} ($mediaType)")

                    // Stop everything before switching
                    stopVideo()
                    pauseGif()
                    delay(SWITCH_SETTLE_DELAY_MS)

                    // Only set lastDisplayedId AFTER media starts loading
                    when (mediaType) {
                        "VIDEO" -> {
                            currentBitmap = null
                            startVideo(nextImage.uri, currentScaleMode)
                            if (videoMode) lastDisplayedId = nextImage.id
                        }
                        "GIF" -> {
                            currentBitmap = null
                            videoMode = false
                            mainHandler.post { playGif(nextImage.uri, currentScaleMode) }
                            lastDisplayedId = nextImage.id
                        }
                        else -> {
                            videoMode = false
                            val bitmap = loadBitmap(nextImage.uri)
                            if (bitmap != null) {
                                currentBitmap = bitmap
                                renderer?.showImage(bitmap, currentScaleMode)
                                lastDisplayedId = nextImage.id
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "doSwitch error", e)
                } finally {
                    isSwitching.set(false)
                    // Process pending target if one was queued during this switch
                    val pending = pendingTargetId
                    if (pending != null) {
                        pendingTargetId = null
                        doSwitch("pending", pending)
                    }
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
            // Always reload from DB — use lastDisplayedId to skip if unchanged

            scope.launch {
                try {
                    val dao = db.settingsDao()
                    val imageDao = db.wallpaperImageDao()
                    var imageId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)

                    // Skip if already displaying this image AND media is actually active
                    if (imageId == lastDisplayedId && lastDisplayedId != 0L) {
                        if (videoMode && renderer?.isVideoPlaying == true) return@launch
                        if (!videoMode && currentBitmap != null && !currentBitmap!!.isRecycled) return@launch
                    }

                    currentScaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }
                    var image = if (imageId > 0) imageDao.getImageById(imageId) else null

                    // Fallback: if saved image was deleted or its group is disabled,
                    // pick any available image from enabled groups
                    if (image == null) {
                        image = imageDao.getFirstFromEnabledGroups()
                        if (image != null) {
                            dao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                        }
                    } else {
                        // Verify the image's group is still enabled
                        val group = db.wallpaperGroupDao().getGroupById(image.groupId)
                        if (group == null || !group.isEnabled) {
                            image = imageDao.getFirstFromEnabledGroups()
                            if (image != null) {
                                dao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                            }
                        }
                    }

                    // Stop current media before loading new
                    stopVideo()
                    pauseGif()
                    videoMode = false

                    if (image != null) {
                        lastDisplayedId = image.id
                        when (image.mediaType ?: "IMAGE") {
                            "VIDEO" -> {
                                currentBitmap = null
                                startVideo(image.uri, currentScaleMode)
                                return@launch
                            }
                            "GIF" -> {
                                currentBitmap = null
                                videoMode = false
                                mainHandler.post { playGif(image.uri, currentScaleMode) }
                                return@launch
                            }
                            else -> {
                                videoMode = false
                                val bitmap = loadBitmap(image.uri)
                                if (bitmap != null) {
                                    currentBitmap = bitmap
                                    renderer?.showImage(bitmap, currentScaleMode)
                                    return@launch
                                }
                            }
                        }
                    }
                    videoMode = false
                    renderer?.showImage(createDefaultBitmap(), currentScaleMode)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.e(TAG, "drawCurrentImage error", e)
                }
            }
        }

        // ======== Video via WallpaperRenderer (unified EGL) ========

        private fun startVideo(uriStr: String, scaleMode: ScaleMode) {
            if (!surfaceReady || !surfaceHolder.surface.isValid) {
                Log.w(TAG, "startVideo: surface not ready")
                return
            }
            videoMode = true
            Log.d(TAG, "startVideo: $uriStr")
            renderer?.startVideo(uriStr, scaleMode)
        }

        // ======== GIF via EGL ========

        private fun playGif(uriStr: String, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                if (Build.VERSION.SDK_INT >= 28) playGif28(uriStr, scaleMode)
                else loadBitmap(uriStr)?.let { renderer?.showImage(it, scaleMode) }
            } catch (e: Exception) {
                loadBitmap(uriStr)?.let { renderer?.showImage(it, scaleMode) }
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
                            renderer?.showImage(bmp, scaleMode)
                        } catch (_: Exception) {}
                        mainHandler.postDelayed(this, GIF_FRAME_INTERVAL_MS)
                    }
                }
                gifFrameRunnable = runnable
                mainHandler.post(runnable)
            }
        }

        // ======== Canvas rendering ========

        private fun createDefaultBitmap(): Bitmap {
            val m = getMetrics()
            val bmp = Bitmap.createBitmap(m.widthPixels, m.heightPixels, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.DKGRAY)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 48f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Wallpaper Switcher", m.widthPixels / 2f, m.heightPixels / 2f, p)
            return bmp
        }

        private fun loadBitmap(uriStr: String): Bitmap? {
            return com.wallpaperswitcher.engine.BitmapUtils.loadBitmap(applicationContext, uriStr)
        }

        private fun getMetrics(): android.util.DisplayMetrics {
            return com.wallpaperswitcher.engine.BitmapUtils.getScreenMetrics(applicationContext)
        }
    }
}
