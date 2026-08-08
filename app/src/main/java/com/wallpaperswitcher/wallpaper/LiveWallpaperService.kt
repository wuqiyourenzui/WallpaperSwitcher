package com.wallpaperswitcher.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private data class SwitchRequest(val source: String, val targetId: Long? = null)

class LiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "LiveWallpaperService"
        const val ACTION_SWITCH = "com.wallpaperswitcher.ACTION_SWITCH"
        const val EXTRA_TARGET_ID = "target_id"
        const val EXTRA_SOURCE = "switch_source"
        const val SOURCE_TIMER = "timer"
        const val SOURCE_UNLOCK = "unlock"
        const val SOURCE_MANUAL = "manual"
        private const val SWITCH_SETTLE_DELAY_MS = 80L
        private const val GIF_FRAME_INTERVAL_MS = 33L
        private const val SHUFFLE_MAX_ATTEMPTS = 10

        @Volatile
        var engineRunning = false
            private set
        @Volatile
        private var activeEngine: LiveWallpaperEngine? = null
    }

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val mainHandler = Handler(Looper.getMainLooper())
        private lateinit var db: AppDatabase
        @Volatile private var surfaceReady = false
        // Assume visible until the system tells us otherwise. Some devices do
        // not deliver onVisibilityChanged reliably right after unlock, which
        // would otherwise make double-tap / unlock switching appear dead.
        @Volatile private var isVisible = true
        // All switch triggers (timer / double-tap / unlock / manual) are sent
        // through a single serialized queue. A switch in progress never blocks
        // or drops new triggers: they wait in the queue and run in order.
        private val switchChannel = Channel<SwitchRequest>(8)
        private val consumerStarted = AtomicBoolean(false)
        @Volatile private var switchInProgress = false
        @Volatile private var switchStartedAt = 0L
        private val redrawInProgress = AtomicBoolean(false)
        private var currentBitmap: Bitmap? = null
        private var currentScaleMode: ScaleMode = ScaleMode.FIT

        private val shuffleShownIds = ConcurrentHashMap.newKeySet<Long>()
        @Volatile private var shuffleAllCount = 0
        @Volatile private var shuffleDirty = false

        // Unified EGL renderer — lives across surface recreations
        private var renderer: WallpaperRenderer? = null
        private var rendererInitialized = false
        @Volatile private var videoMode = false
        @Volatile private var lastDisplayedId = 0L

        private val destRect = RectF()
        private var cachedScreenW = 0f
        private var cachedScreenH = 0f

        private var gifDrawable: android.graphics.drawable.AnimatedImageDrawable? = null
        private var gifFrameRunnable: Runnable? = null
        private var gifBitmapBuffer: Bitmap? = null

        private val switchReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Only the currently active engine handles switch broadcasts.
                // When the wallpaper is re-applied, a stale engine can still be
                // registered for a moment; without this guard one broadcast
                // would trigger two concurrent switches and corrupt the GL
                // renderer (this showed up as an engine crash right after a
                // timed switch in the logs).
                if (activeEngine !== this@LiveWallpaperEngine) return
                if (intent.action == ACTION_SWITCH) {
                    val targetId = intent.getLongExtra(EXTRA_TARGET_ID, -1L)
                    if (targetId > 0) {
                        requestSwitch("broadcast", targetId)
                    } else {
                        requestSwitch("broadcast", null)
                    }
                }
            }
        }

        private val gestureDetector = GestureDetector(
            applicationContext,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    scope.launch {
                        try {
                            val enabled = db.settingsDao().getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                            if (enabled) {
                                requestSwitch("double-tap")
                            }
                        } catch (_: Exception) {
                            // A double tap is an explicit user action: switch even
                            // if reading the setting fails.
                            requestSwitch("double-tap")
                        }
                    }
                    return true
                }
            }
        )

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            engineRunning = true
            activeEngine = this
            db = AppDatabase.getInstance(applicationContext)
            setTouchEventsEnabled(true)
            // Start the switch queue consumer up-front so triggers are always
            // processed immediately.
            ensureSwitchConsumer()
            val filter = IntentFilter(ACTION_SWITCH)
            try {
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
            if (holder == null) return
            if (!rendererInitialized) {
                val sw = cachedScreenW.takeIf { it > 0 } ?: getMetrics().widthPixels.toFloat()
                val sh = cachedScreenH.takeIf { it > 0 } ?: getMetrics().heightPixels.toFloat()
                renderer = WallpaperRenderer(applicationContext, holder).also { it.initialize(sw, sh) }
                rendererInitialized = true
            }
            // Ensure the EGL window surface exists for this SurfaceHolder. This
            // is posted to the render thread, so drawing must wait until the
            // renderer reports its EGL surface is ready.
            renderer?.surfaceCreated()
            if (isVisible) {
                redrawWhenSurfaceReady()
            }
        }

        /**
         * Draw the current media once the renderer's EGL surface is actually
         * ready. surfaceCreated() runs on the render thread asynchronously, so
         * an immediate draw could hit a not-yet-created EGL surface and leave
         * the wallpaper blank (which looks like switching stopped working).
         */
        private fun redrawWhenSurfaceReady() {
            if (!isVisible) return
            val r = renderer ?: return
            if (r.isSurfaceReady()) {
                drawCurrentImage()
                return
            }
            mainHandler.postDelayed({ redrawWhenSurfaceReady() }, 50L)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            cachedScreenW = width.toFloat()
            cachedScreenH = height.toFloat()
            renderer?.surfaceChanged(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            lastDisplayedId = 0L
            renderer?.surfaceDestroyed()
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
                    if (!surfaceReady || renderer == null) return@launch
                    val dao = db.settingsDao()
                    val savedId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                    if (savedId != lastDisplayedId || lastDisplayedId == 0L) {
                        videoMode = false
                        currentBitmap = null
                        drawCurrentImage()
                    }
                }
            }
            // When the wallpaper becomes invisible (e.g. an app is opened) the
            // playback intentionally continues: the video keeps running and is
            // not restarted or interrupted by opening apps / visibility changes.
        }

        override fun onDestroy() {
            engineRunning = false
            if (activeEngine === this) activeEngine = null
            lastDisplayedId = 0L
            switchInProgress = false
            switchStartedAt = 0L
            consumerStarted.set(false)
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            flushShuffleState()
            try { renderer?.release() } catch (_: Exception) {}
            renderer = null
            rendererInitialized = false
            pauseGif()
            gifBitmapBuffer?.recycle(); gifBitmapBuffer = null
            currentBitmap?.recycle(); currentBitmap = null
            gifDrawable?.let {
                try { it.stop() } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= 28) {
                    try { (it as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                }
            }
            gifDrawable = null
            scope.cancel()
            super.onDestroy()
        }

        private fun flushShuffleState() {
            if (!shuffleDirty) return
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                try {
                    val dao = db.settingsDao()
                    // Save shuffle state for both image and video types
                    // (the shown IDs set is shared across types in this engine instance)
                    dao.setString(SettingsKeys.SHUFFLE_SHOWN_IDS, shuffleShownIds.joinToString(","))
                    dao.setLong(SettingsKeys.SHUFFLE_ALL_COUNT, shuffleAllCount.toLong())
                } catch (_: Exception) {}
            }
        }

        private fun stopVideo() {
            videoMode = false
            renderer?.stopVideo()
            renderer?.waitForDecodeThread(500)
        }

        private fun pauseGif() {
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
        }

        // ======== Switch logic ========

        /**
         * Submit a switch request. Requests are processed one at a time by a
         * single consumer, so a busy engine queues new triggers instead of
         * dropping them, and a single stuck/failed switch can never disable
         * timer / double-tap / unlock switching permanently.
         */
        private fun requestSwitch(source: String, targetId: Long? = null) {
            ensureSwitchConsumer()
            // Watchdog: if the current switch has been stuck for >30s, start a
            // fresh consumer so queued triggers are still executed.
            if (switchInProgress && switchStartedAt != 0L &&
                SystemClock.elapsedRealtime() - switchStartedAt > 30_000L
            ) {
                Log.w(TAG, "Switch stuck >30s, starting fallback consumer")
                scope.launch { consumeSwitches() }
            }
            Log.d(TAG, "Switch requested: $source target=$targetId")
            // Guaranteed enqueue: send suspends until the queue has space, so a
            // trigger can never be silently dropped when the queue is full.
            scope.launch {
                switchChannel.send(SwitchRequest(source, targetId))
            }
        }

        private fun ensureSwitchConsumer() {
            if (consumerStarted.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        consumeSwitches()
                    } finally {
                        // Allow the consumer to be restarted if it ever dies.
                        consumerStarted.set(false)
                    }
                }
            }
        }

        private suspend fun consumeSwitches() {
            for (req in switchChannel) {
                switchInProgress = true
                switchStartedAt = SystemClock.elapsedRealtime()
                try {
                    Log.d(TAG, "Switch start: ${req.source}")
                    executeSwitch(req.source, req.targetId)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // Never let one bad switch kill the queue consumer.
                    Log.e(TAG, "Switch failed: ${req.source}", t)
                } finally {
                    switchInProgress = false
                    Log.d(TAG, "Switch done: ${req.source}")
                }
            }
        }

        private suspend fun executeSwitch(source: String, targetId: Long?) {
            Log.d(TAG, "doSwitch from $source, targetId=$targetId")
            val dao = db.settingsDao()
            val imageDao = db.wallpaperImageDao()

            val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
            if (groups.isEmpty()) return

            // If target is already playing, skip restart (avoids video pause on "apply")
            if (targetId != null && targetId > 0 && targetId == lastDisplayedId) {
                if (videoMode && renderer?.isVideoPlaying == true) {
                    Log.d(TAG, "Target $targetId already playing, skip")
                    return
                }
                if (!videoMode && currentBitmap != null && !currentBitmap!!.isRecycled) {
                    Log.d(TAG, "Target $targetId already showing, skip")
                    return
                }
            }

            currentScaleMode = try {
                ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
            } catch (_: Exception) { ScaleMode.FIT }

            var nextImage = if (targetId != null && targetId > 0) {
                val img = imageDao.getImageById(targetId)
                if (img != null) {
                    val group = db.wallpaperGroupDao().getGroupById(img.groupId)
                    if (group == null || !group.isEnabled) {
                        pickNextImage(SwitchMode.RANDOM, imageDao, 0L, dao)
                    } else {
                        img
                    }
                } else {
                    pickNextImage(SwitchMode.RANDOM, imageDao, 0L, dao)
                }
            } else {
                val lastId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)
                val switchMode = try {
                    SwitchMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SWITCH_MODE, SwitchMode.RANDOM.name))
                } catch (_: Exception) { SwitchMode.RANDOM }
                pickNextImage(switchMode, imageDao, lastId, dao)
            }

            if (nextImage == null) {
                nextImage = imageDao.getFirstFromEnabledGroups()
                if (nextImage == null) return
            }

            dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
            val mediaType = nextImage.mediaType
            Log.d(TAG, "Switch to: ${nextImage.displayName} ($mediaType)")

            pauseGif()

            when (mediaType) {
                "VIDEO" -> {
                    // Image/GIF → Video: stop old, delay for settle, start new
                    stopVideo()
                    delay(SWITCH_SETTLE_DELAY_MS)
                    currentBitmap = null
                    if (startVideo(nextImage.uri, currentScaleMode)) {
                        lastDisplayedId = nextImage.id
                    }
                }
                "GIF" -> {
                    // Any → GIF: stop video atomically (show nothing, GIF will overwrite)
                    stopVideo()
                    delay(SWITCH_SETTLE_DELAY_MS)
                    currentBitmap = null
                    videoMode = false
                    mainHandler.post { playGif(nextImage.uri, currentScaleMode) }
                    lastDisplayedId = nextImage.id
                }
                else -> {
                    // Any → Image: load bitmap FIRST, then stop video + render atomically
                    videoMode = false
                    Log.d(TAG, "Loading image bitmap: ${nextImage.uri}")
                    val bitmap = loadBitmap(nextImage.uri)
                    if (bitmap != null) {
                        Log.d(TAG, "Bitmap loaded: ${bitmap.width}x${bitmap.height}")
                        // Recycle old bitmap to avoid memory leak
                        val old = currentBitmap
                        currentBitmap = bitmap
                        if (old != null && old != bitmap && !old.isRecycled) {
                            old.recycle()
                        }
                        // Always use stopVideoAndRender for clean transition.
                        // Even if isVideoPlaying is false, the decode thread might
                        // still be running and its cleanup could interfere.
                        renderer?.stopVideoAndRender(bitmap, currentScaleMode)
                        lastDisplayedId = nextImage.id
                    } else {
                        Log.e(TAG, "Failed to load bitmap for: ${nextImage.displayName} uri=${nextImage.uri}")
                    }
                }
            }
        }

        private suspend fun pickNextImage(
            switchMode: SwitchMode, imageDao: WallpaperImageDao, lastId: Long, dao: SettingsDao
        ): WallpaperImage? {
            return when (switchMode) {
                SwitchMode.RANDOM -> {
                    imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                        ?: imageDao.getRandomImageFromEnabledGroups()
                }
                SwitchMode.SEQUENTIAL -> {
                    val count = imageDao.countByEnabledGroups()
                    if (count == 0) null else {
                        val idx = dao.getLong(SettingsKeys.SEQUENTIAL_INDEX).toInt()
                        val next = idx % count
                        val img = imageDao.getSequentialImageFromEnabledGroups(next)
                        if (img != null) {
                            dao.setLong(SettingsKeys.SEQUENTIAL_INDEX, (next + 1).toLong())
                            img
                        } else {
                            dao.setLong(SettingsKeys.SEQUENTIAL_INDEX, 0L)
                            imageDao.getSequentialImageFromEnabledGroups(0)
                                ?: imageDao.getRandomImageFromEnabledGroups()
                        }
                    }
                }
                SwitchMode.SHUFFLE -> {
                    val totalCount = imageDao.countByEnabledGroups()
                    if (totalCount == 0) null else {
                        if (shuffleShownIds.isEmpty() && shuffleAllCount == 0) {
                            val savedIds = dao.getString(SettingsKeys.SHUFFLE_SHOWN_IDS, "")
                            val savedCount = dao.getLong(SettingsKeys.SHUFFLE_ALL_COUNT, 0L).toInt()
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
                            val img = imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                                ?: imageDao.getRandomImageFromEnabledGroups()
                            if (img != null && img.id !in shuffleShownIds) candidate = img
                            else if (img != null && shuffleShownIds.size >= totalCount) {
                                shuffleShownIds.clear(); candidate = img
                            }
                            attempts++
                        }
                        if (candidate == null) {
                            // Random sampling failed to find an unseen item (e.g. only
                            // one media exists): reset the shown set and accept any media.
                            shuffleShownIds.clear()
                            candidate = imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                                ?: imageDao.getRandomImageFromEnabledGroups()
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
            val r = renderer ?: return
            if (switchInProgress) {
                // A switch is running right now; retry shortly instead of
                // racing with it (concurrent startVideo + stopVideoAndRender on
                // the GL renderer caused native crashes during timed switches).
                mainHandler.postDelayed({ drawCurrentImage() }, 100L)
                return
            }
            // Guard against concurrent redraws (e.g. a surface event and a
            // visibility change firing at the same time) - two concurrent
            // drawCurrentImage calls would start the same video twice.
            if (!redrawInProgress.compareAndSet(false, true)) return

            scope.launch {
                try {
                    if (switchInProgress) {
                        mainHandler.postDelayed({ drawCurrentImage() }, 100L)
                        return@launch
                    }
                    val dao = db.settingsDao()
                    val imageDao = db.wallpaperImageDao()
                    var imageId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)

                    if (imageId == lastDisplayedId && lastDisplayedId != 0L) {
                        if (videoMode && r.isVideoPlaying) return@launch
                        if (!videoMode && currentBitmap != null && !currentBitmap!!.isRecycled) return@launch
                    }

                    currentScaleMode = try {
                        ScaleMode.valueOf(dao.getString(SettingsKeys.GLOBAL_SCALE_MODE, ScaleMode.FIT.name))
                    } catch (_: Exception) { ScaleMode.FIT }
                    var image = if (imageId > 0) imageDao.getImageById(imageId) else null

                    if (image == null) {
                        image = imageDao.getFirstFromEnabledGroups()
                        if (image != null) dao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                    } else {
                        val group = db.wallpaperGroupDao().getGroupById(image.groupId)
                        if (group == null || !group.isEnabled) {
                            image = imageDao.getFirstFromEnabledGroups()
                            if (image != null) dao.setLong(SettingsKeys.LAST_IMAGE_ID, image.id)
                        }
                    }

                    pauseGif()
                    videoMode = false

                    if (image != null) {
                        when (image.mediaType) {
                            "VIDEO" -> {
                                currentBitmap = null
                                if (startVideo(image.uri, currentScaleMode)) {
                                    lastDisplayedId = image.id
                                } else {
                                    lastDisplayedId = 0L
                                }
                                return@launch
                            }
                            "GIF" -> {
                                currentBitmap = null
                                videoMode = false
                                mainHandler.post { playGif(image.uri, currentScaleMode) }
                                lastDisplayedId = image.id
                                return@launch
                            }
                            else -> {
                                videoMode = false
                                Log.d(TAG, "drawCurrentImage loading bitmap: ${image.uri}")
                                val bitmap = loadBitmap(image.uri)
                                if (bitmap != null) {
                                    Log.d(TAG, "drawCurrentImage bitmap loaded: ${bitmap.width}x${bitmap.height}")
                                    val old = currentBitmap
                                    currentBitmap = bitmap
                                    if (old != null && old != bitmap && !old.isRecycled) {
                                        old.recycle()
                                    }
                                    r.stopVideoAndRender(bitmap, currentScaleMode)
                                    lastDisplayedId = image.id
                                    return@launch
                                } else {
                                    Log.e(TAG, "drawCurrentImage failed to load bitmap: ${image.uri}")
                                    lastDisplayedId = 0L
                                }
                            }
                        }
                    }
                    videoMode = false
                    r.showImage(createDefaultBitmap(), currentScaleMode)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "drawCurrentImage error", t)
                } finally {
                    redrawInProgress.set(false)
                }
            }
        }

        private fun startVideo(uriStr: String, scaleMode: ScaleMode): Boolean {
            if (!surfaceReady) {
                Log.w(TAG, "startVideo: surface not ready")
                return false
            }
            val r = renderer
            if (r == null) {
                Log.w(TAG, "startVideo: renderer not ready")
                return false
            }
            videoMode = true
            Log.d(TAG, "startVideo: $uriStr")
            r.startVideo(uriStr, scaleMode)
            return true
        }

        private fun playGif(uriStr: String, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            try {
                if (Build.VERSION.SDK_INT >= 28) playGif28(uriStr, scaleMode)
                else loadBitmap(uriStr)?.let { renderer?.showImage(it, scaleMode) }
            } catch (t: Throwable) {
                Log.e(TAG, "playGif failed, falling back to static frame", t)
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
                // Release the previous GIF (if any) before replacing it.
                gifDrawable?.let { old ->
                    try { old.stop() } catch (_: Exception) {}
                    try { (old as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                }
                gifDrawable = drawable
                drawable.repeatCount = -1
                drawable.start()

                val frameW = drawable.intrinsicWidth.coerceAtLeast(1)
                val frameH = drawable.intrinsicHeight.coerceAtLeast(1)
                gifBitmapBuffer?.recycle()
                gifBitmapBuffer = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)

                val r = renderer
                val runnable = object : Runnable {
                    override fun run() {
                        if (!surfaceReady || gifDrawable == null) return
                        try {
                            val bmp = gifBitmapBuffer ?: return
                            bmp.eraseColor(Color.TRANSPARENT)
                            val cv = Canvas(bmp)
                            drawable.draw(cv)
                            r?.showImage(bmp, scaleMode)
                        } catch (t: Throwable) {
                            Log.e(TAG, "GIF frame draw failed", t)
                        }
                        mainHandler.postDelayed(this, GIF_FRAME_INTERVAL_MS)
                    }
                }
                gifFrameRunnable = runnable
                mainHandler.post(runnable)
            } else {
                // Decoder returned a non-animated drawable (e.g. single-frame GIF):
                // render its first frame so the screen is never left blank.
                try {
                    val frameW = drawable.intrinsicWidth.coerceAtLeast(1)
                    val frameH = drawable.intrinsicHeight.coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
                    drawable.draw(Canvas(bmp))
                    renderer?.showImage(bmp, scaleMode)
                } catch (t: Throwable) {
                    Log.e(TAG, "GIF static frame failed", t)
                }
                try { (drawable as java.lang.AutoCloseable).close() } catch (_: Exception) {}
            }
        }

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
