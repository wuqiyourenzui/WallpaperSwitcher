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
import android.os.PowerManager
import android.provider.Settings
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.*
import com.wallpaperswitcher.service.WallpaperSwitchService
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
        // 20fps cap instead of 30fps: GIF wallpapers look identical (most GIFs
        // are <=15fps) but cost ~1/3 less CPU/GPU for the frame upload + swap.
        private const val GIF_FRAME_INTERVAL_MS = 50L
        // Image/GIF bitmap loads get the same timeout as video opens, so a
        // stuck cloud/SAF provider can never freeze the switch queue forever.
        private const val BITMAP_LOAD_TIMEOUT_MS = 15_000L
        private const val SHUFFLE_MAX_ATTEMPTS = 10
        // Double-tap detection window and slop. Kept slightly more generous
        // than ViewConfiguration's defaults because some launchers compress or
        // jitter the events they forward to the wallpaper window.
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        private const val DOUBLE_TAP_SLOP_DP = 40f

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
        private val powerManager: PowerManager by lazy {
            applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        }
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
        // Media ids that recently failed to start (e.g. a video with no video
        // track). Recovery switches skip these so one broken file can never
        // freeze the wallpaper until the next timer tick.
        private val failedMediaIds = ConcurrentHashMap.newKeySet<Long>()

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
        // GIF uri whose decode is in flight; a newer switch clears it so a
        // stale decoded drawable can never start after newer media.
        @Volatile private var pendingGifUri: String? = null
        // Detects a decoder that stopped producing frames (e.g. a cloud file
        // whose stream read blocks forever) and triggers automatic recovery.
        private var videoHealthJob: kotlinx.coroutines.Job? = null
        // Consecutive recovery failures: stops the 1.5s auto-recovery loop
        // when every candidate media is broken, instead of switching forever
        // and burning battery. Reset once a video actually plays.
        @Volatile private var recoveryFailCount = 0

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

        // Screen on/off fallback: some devices do not deliver a visibility
        // change when the screen turns off/on, so the engine would otherwise
        // keep decoding video at full speed in the dark.
        private val screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> setPowerSave(true, "screen-off")
                    // Only resume full speed if the wallpaper is actually
                    // visible: while the keyguard or another app still covers
                    // it (screen on), keep the low-power throttle active.
                    Intent.ACTION_SCREEN_ON -> if (isVisible) setPowerSave(false, "screen-on")
                }
            }
        }

        // Manual double-tap detector. GestureDetector only reports a double
        // tap on the second ACTION_DOWN and silently fails when the launcher /
        // system delivers an incomplete event stream (Android 16/17 devices and
        // some OEM launchers consume or drop one of the two taps). Tracking
        // both DOWN and UP pairs lets the engine recognize a double tap from
        // whatever subset of events actually reaches the wallpaper window.
        private var lastTapUpTime = 0L
        private var lastTapUpX = 0f
        private var lastTapUpY = 0f
        private var lastDownTime = 0L
        private var lastDownX = 0f
        private var lastDownY = 0f
        private var lastDownWasDouble = false
        // Launcher-independent double-tap fallback (some Android 16/17
        // launchers stop forwarding touches to the wallpaper window).
        private var floatingButton: FloatingSwitchButton? = null
        // Throttle the timer self-heal to once every 30s to avoid churn.
        private var lastTimerSelfHealAt = 0L
        private val doubleTapSlopPx: Float by lazy {
            applicationContext.resources.displayMetrics.density * DOUBLE_TAP_SLOP_DP
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            engineRunning = true
            activeEngine = this
            db = AppDatabase.getInstance(applicationContext)
            setTouchEventsEnabled(true)
            Log.d(TAG, "Engine created (build 20260816-b1), touch events enabled")
            // Apply the clarity setting live: toggling it in Settings updates
            // the currently displayed wallpaper immediately instead of waiting
            // for the next switch. The per-switch applyClarityMode() below
            // remains as a belt-and-suspenders for the very first render.
            scope.launch {
                try {
                    db.settingsDao().getValueFlow(SettingsKeys.CLARITY_MODE).collect { mode ->
                        renderer?.sharpnessScale = when (mode) {
                            "off" -> 0f
                            "strong" -> 1.6f
                            else -> 1f
                        }
                    }
                } catch (_: Exception) {}
            }
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
            try {
                try { applicationContext.unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
                val screenFilter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                applicationContext.registerReceiver(screenStateReceiver, screenFilter)
            } catch (_: Exception) {}
            // The wallpaper engine is alive whenever the wallpaper is applied,
            // which makes it the most reliable watchdog for the timer service
            // (Android 15+ can kill long-running foreground services) and for
            // the floating double-tap button.
            selfHealTimerService()
            updateFloatingButton()
            scope.launch {
                try {
                    db.settingsDao().getValueFlow(SettingsKeys.FLOATING_BUTTON_ENABLED).collect {
                        updateFloatingButton()
                    }
                } catch (_: Exception) {}
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            surfaceReady = true
            if (holder == null) return
            if (!rendererInitialized) {
                val sw = cachedScreenW.takeIf { it > 0 } ?: getMetrics().widthPixels.toFloat()
                val sh = cachedScreenH.takeIf { it > 0 } ?: getMetrics().heightPixels.toFloat()
                renderer = WallpaperRenderer(applicationContext, holder).also {
                    it.initialize(sw, sh)
                    it.onVideoStartFailed = { onVideoStartFailed() }
                }
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
        private fun redrawWhenSurfaceReady(attempts: Int = 0) {
            if (!isVisible) return
            val r = renderer ?: return
            if (r.isSurfaceReady()) {
                drawCurrentImage()
                return
            }
            if (attempts >= 60) {
                // The EGL surface never became ready (e.g. initialization keeps
                // failing). Stop polling: endless 50ms wakeups would waste
                // battery. A later surfaceCreated()/visibility event retries.
                Log.w(TAG, "Surface never became ready after ${attempts * 50L}ms; stopping redraw polling")
                return
            }
            mainHandler.postDelayed({ redrawWhenSurfaceReady(attempts + 1) }, 50L)
        }

        /**
         * Called (on the decode thread) when a video failed to start - e.g. it
         * was superseded by an even newer switch, or the GL resources were torn
         * down concurrently. lastDisplayedId must be reset, otherwise the
         * engine thinks the new media is already on screen and the previous
         * video's last frame stays frozen forever. A delayed redraw retries the
         * current media if nothing else started playing.
         */
        private fun onVideoStartFailed() {
            Log.w(TAG, "Video failed to start; scheduling recovery switch")
            if (lastDisplayedId > 0L) failedMediaIds.add(lastDisplayedId)
            videoMode = false
            lastDisplayedId = 0L
            recoveryFailCount++
            if (recoveryFailCount > 5) {
                Log.w(TAG, "Too many consecutive video failures; pausing auto-recovery")
                return
            }
            mainHandler.postDelayed({
                if (isVisible && surfaceReady && !switchInProgress && renderer?.isVideoPlaying != true) {
                    // Switch to a DIFFERENT media instead of retrying the same
                    // broken file: RANDOM pick excludes the failed LAST_IMAGE_ID
                    // and the failedMediaIds blocklist covers sequential/shuffle.
                    requestSwitch("recovery")
                }
            }, 1500L)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            cachedScreenW = width.toFloat()
            cachedScreenH = height.toFloat()
            renderer?.surfaceChanged(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            surfaceReady = false
            lastDisplayedId = 0L
            // The surface is gone: the renderer stops presenting, so a video
            // that was playing can no longer produce frames. Cancel the health
            // monitor instead of letting it trigger a spurious recovery switch
            // while the surface is destroyed.
            videoMode = false
            videoHealthJob?.cancel()
            videoHealthJob = null
            renderer?.surfaceDestroyed()
            pauseGif()
        }

        override fun onTouchEvent(event: MotionEvent) {
            handleTouchEvent(event)
        }

        /**
         * Robust double-tap detection that fires from either the second
         * ACTION_DOWN (the classic case) or the second ACTION_UP (fallback for
         * launchers that swallow one of the DOWN events). Every DOWN/UP is
         * logged so a captured log can prove whether the system is delivering
         * touches to the wallpaper window at all.
         */
        private fun handleTouchEvent(event: MotionEvent) {
            val action = event.actionMasked
            val x = event.x
            val y = event.y
            val now = SystemClock.uptimeMillis()
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    val withinTime = lastTapUpTime != 0L && now - lastTapUpTime <= DOUBLE_TAP_TIMEOUT_MS
                    val withinSlop = lastTapUpTime != 0L &&
                        kotlin.math.hypot(x - lastTapUpX, y - lastTapUpY) <= doubleTapSlopPx
                    lastDownWasDouble = withinTime && withinSlop
                    if (lastDownWasDouble) {
                        // Second tap recognized on the DOWN event. Clear the UP
                        // marker so the UP fallback below cannot fire a second
                        // switch for the same gesture.
                        lastTapUpTime = 0L
                        Log.d(TAG, "Touch DOWN ($x, $y): double-tap from DOWN")
                        onDoubleTapDetected()
                    } else {
                        lastDownTime = now
                        lastDownX = x
                        lastDownY = y
                        Log.d(TAG, "Touch DOWN ($x, $y)")
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (lastDownWasDouble) {
                        // Already switched on the DOWN of this tap.
                        lastDownWasDouble = false
                        lastTapUpTime = 0L
                    } else {
                        val withinUpTime = lastTapUpTime != 0L && now - lastTapUpTime <= DOUBLE_TAP_TIMEOUT_MS
                        val withinUpSlop = lastTapUpTime != 0L &&
                            kotlin.math.hypot(x - lastTapUpX, y - lastTapUpY) <= doubleTapSlopPx
                        if (withinUpTime && withinUpSlop) {
                            // The launcher consumed one of the DOWN events but
                            // still forwarded both UPs: count it as a double tap.
                            lastTapUpTime = 0L
                            Log.d(TAG, "Touch UP ($x, $y): double-tap from UP fallback")
                            onDoubleTapDetected()
                        } else {
                            lastTapUpTime = now
                            lastTapUpX = x
                            lastTapUpY = y
                            Log.d(TAG, "Touch UP ($x, $y)")
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    lastDownWasDouble = false
                    lastTapUpTime = 0L
                    Log.d(TAG, "Touch CANCEL")
                }
            }
            super.onTouchEvent(event)
        }

        private fun onDoubleTapDetected() {
            // A double tap is an explicit user action: switch even if reading
            // the setting fails.
            scope.launch {
                try {
                    val enabled = db.settingsDao().getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                    if (enabled) {
                        requestSwitch("double-tap")
                    }
                } catch (_: Exception) {
                    requestSwitch("double-tap")
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                selfHealTimerService()
                updateFloatingButton()
                setPowerSave(false, "visibility")
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
            } else {
                // The wallpaper is not visible: either the screen is off or
                // another app covers it. Throttle decode/render so the engine
                // stops burning power behind other apps. Playback never stops
                // or restarts - it resumes full speed when visible again.
                setPowerSave(true, "visibility")
            }
        }

        override fun onDestroy() {
            // Only the active engine owns the static engineRunning flag. When
            // the wallpaper is re-applied, the OLD engine may receive its
            // onDestroy AFTER the new engine already started; clearing the flag
            // unconditionally would make the timer fall back to the static path
            // and drop unlock/double-tap while the new engine is alive (exactly
            // what the captured logs showed).
            if (activeEngine === this) {
                activeEngine = null
                engineRunning = false
            }
            lastDisplayedId = 0L
            switchInProgress = false
            switchStartedAt = 0L
            consumerStarted.set(false)
            floatingButton?.dismiss()
            floatingButton = null
            // Remove any pending delayed recovery / redraw callbacks so they
            // can never fire after the engine is destroyed.
            mainHandler.removeCallbacksAndMessages(null)
            try { applicationContext.unregisterReceiver(switchReceiver) } catch (_: Exception) {}
            try { applicationContext.unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
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
            videoHealthJob?.cancel()
            videoHealthJob = null
            scope.cancel()
            super.onDestroy()
        }

        /**
         * Android 15+ can stop a long-running foreground service while the app
         * is in the background. Whenever the wallpaper becomes (or is kept)
         * visible, restart the timer if it is enabled but dead.
         */
        private fun selfHealTimerService() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTimerSelfHealAt < 30_000L) return
            lastTimerSelfHealAt = now
            scope.launch {
                try {
                    if (WallpaperSwitchService.running) return@launch
                    val enabled = db.settingsDao().getBool(SettingsKeys.SERVICE_ENABLED, false)
                    if (enabled) {
                        Log.d(TAG, "Timer enabled but service not running, restarting (engine watchdog)")
                        WallpaperSwitchService.start(applicationContext)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Timer self-heal blocked (background FGS start?)", e)
                }
            }
        }

        /**
         * Show/hide the floating double-tap button according to the setting.
         * WindowManager calls must run on the main thread, so the DB read
         * happens off-main and the window ops are posted to the main handler.
         */
        private fun updateFloatingButton() {
            scope.launch {
                val enabled = try {
                    db.settingsDao().getBool(SettingsKeys.FLOATING_BUTTON_ENABLED, false)
                } catch (_: Exception) {
                    false
                }
                val canOverlay = try {
                    Settings.canDrawOverlays(applicationContext)
                } catch (_: Exception) {
                    false
                }
                mainHandler.post {
                    if (enabled && canOverlay) {
                        if (floatingButton == null) {
                            floatingButton = FloatingSwitchButton(applicationContext).also { it.show() }
                        }
                    } else {
                        floatingButton?.dismiss()
                        floatingButton = null
                    }
                }
            }
        }

        private fun flushShuffleState() {
            if (!shuffleDirty) return
            // onDestroy runs on the main thread: bound the DB write so a busy
            // Room executor (e.g. during a large folder import) can never block
            // the main thread long enough to trigger an ANR. Shuffle state is
            // only a hint - losing it just resets the deck on the next use.
            try {
                kotlinx.coroutines.runBlocking {
                    withTimeoutOrNull(1500L) {
                        withContext(Dispatchers.IO) {
                            val dao = db.settingsDao()
                            // Save shuffle state for both image and video types
                            // (the shown IDs set is shared across types in this
                            // engine instance).
                            dao.setString(SettingsKeys.SHUFFLE_SHOWN_IDS, shuffleShownIds.joinToString(","))
                            dao.setLong(SettingsKeys.SHUFFLE_ALL_COUNT, shuffleAllCount.toLong())
                        }
                    }
                }
            } catch (_: Exception) {
                // Timeout or cancellation: skip persisting this time.
            }
        }

        private fun stopVideo() {
            videoMode = false
            videoHealthJob?.cancel()
            videoHealthJob = null
            renderer?.stopVideo()
            renderer?.waitForDecodeThread(500)
        }

        private fun pauseGif() {
            gifFrameRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingGifUri = null
        }

        /**
         * Toggle the renderer's screen-off power-save mode. This only throttles
         * the video decode rate and GIF redraws - it never stops or restarts
         * playback, and switching/timer/unlock behavior is untouched.
         */
        private fun setPowerSave(enabled: Boolean, reason: String) {
            if (renderer?.powerSaveMode == enabled) return
            renderer?.powerSaveMode = enabled
            Log.d(TAG, "Power save ${if (enabled) "ON" else "OFF"} ($reason)")
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
            // Only skip when the SCREEN is actually off: starting a decode in
            // the dark wastes battery, and the switch runs on the next tick
            // after screen-on. When another app merely covers the wallpaper
            // (screen still on), timer switches still execute - the new media
            // simply plays in the throttled power-save mode until visible.
            if (!powerManager.isInteractive()) {
                Log.d(TAG, "Skip $source switch while screen is off (power save)")
                return
            }
            val dao = db.settingsDao()
            val imageDao = db.wallpaperImageDao()
            applyClarityMode()

            val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
            if (groups.isEmpty()) return

            // If target is already playing, skip restart (avoids video pause on "apply")
            if (targetId != null && targetId > 0 && targetId == lastDisplayedId) {
                if (videoMode && renderer?.isVideoPlaying == true) {
                    Log.d(TAG, "Target $targetId already playing, skip")
                    return
                }
                val shownBmp = currentBitmap
                if (!videoMode && shownBmp != null && !shownBmp.isRecycled) {
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

            // Skip media that recently failed to start (broken video files) so
            // recovery switches never re-pick the same broken item.
            var media: WallpaperImage = nextImage
            if (media.id in failedMediaIds) {
                var attempts = 0
                while (attempts < 5) {
                    val alt = imageDao.getRandomImageFromEnabledGroupsExcluding(media.id)
                        ?: imageDao.getRandomImageFromEnabledGroups()
                    if (alt == null || alt.id !in failedMediaIds) {
                        if (alt != null) media = alt
                        break
                    }
                    attempts++
                }
            }
            nextImage = media

            dao.setLong(SettingsKeys.LAST_IMAGE_ID, nextImage.id)
            val mediaType = nextImage.mediaType
            // Include the id: multiple files can share the same display name
            // (logs showed '1 (7).jpg' twice with different ids), which made
            // duplicate/order investigations ambiguous.
            Log.d(TAG, "Switch to: ${nextImage.displayName} ($mediaType) id=${nextImage.id}")

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
                    val bitmap = loadBitmapWithTimeout(nextImage.uri)
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
                        // A broken/deleted file must not leave the wallpaper
                        // black/stuck until the next timer tick: blocklist the
                        // id and schedule a recovery switch to a different
                        // media (bounded like the video recovery path).
                        failedMediaIds.add(nextImage.id)
                        lastDisplayedId = 0L
                        recoveryFailCount++
                        if (recoveryFailCount <= 5) {
                            requestSwitch("recovery")
                        }
                    }
                }
            }
            // A healthy media was applied: clear the failure blocklist so a
            // previously-broken file can be retried later.
            if (lastDisplayedId == nextImage.id && nextImage.id !in failedMediaIds) {
                failedMediaIds.clear()
                recoveryFailCount = 0
            }
            if (lastDisplayedId == nextImage.id) maybeFade()
        }

        private suspend fun pickNextImage(
            switchMode: SwitchMode, imageDao: WallpaperImageDao, lastId: Long, dao: SettingsDao
        ): WallpaperImage? {
            return when (switchMode) {
                SwitchMode.RANDOM -> {
                    randomEnabledImage(imageDao, lastId)
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
                            val img = randomEnabledImage(imageDao, lastId)
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
            if (renderer?.powerSaveMode == true) return
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
                    applyClarityMode()
                    var imageId = dao.getLong(SettingsKeys.LAST_IMAGE_ID)

                    if (imageId == lastDisplayedId && lastDisplayedId != 0L) {
                        if (videoMode && r.isVideoPlaying) return@launch
                        val shownBmp = currentBitmap
                        if (!videoMode && shownBmp != null && !shownBmp.isRecycled) return@launch
                        // A GIF that is already rendering must not be re-decoded
                        // and restarted by a coincidental redraw (e.g. a
                        // visibility retry while a switch is settling).
                        if (!videoMode && gifDrawable != null && gifFrameRunnable != null) return@launch
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
                                    maybeFade()
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
                                maybeFade()
                                return@launch
                            }
                            else -> {
                                videoMode = false
                                Log.d(TAG, "drawCurrentImage loading bitmap: ${image.uri}")
                                val bitmap = loadBitmapWithTimeout(image.uri)
                                if (bitmap != null) {
                                    Log.d(TAG, "drawCurrentImage bitmap loaded: ${bitmap.width}x${bitmap.height}")
                                    val old = currentBitmap
                                    currentBitmap = bitmap
                                    if (old != null && old != bitmap && !old.isRecycled) {
                                        old.recycle()
                                    }
                                    r.stopVideoAndRender(bitmap, currentScaleMode)
                                    lastDisplayedId = image.id
                                    maybeFade()
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

        /**
         * Random pick without ORDER BY RANDOM(): count + random OFFSET is
         * orders of magnitude faster on large libraries (thousands of rows).
         */
        private suspend fun randomEnabledImage(
            imageDao: WallpaperImageDao,
            lastId: Long
        ): WallpaperImage? {
            val count = imageDao.countByEnabledGroups()
            if (count == 0) return null
            if (count == 1) return imageDao.getRandomImageFromEnabledGroups()
            val offset = kotlin.random.Random.nextInt(count)
            return imageDao.getRandomImageFromEnabledGroupsExcludingAt(lastId, offset)
                ?: imageDao.getRandomImageFromEnabledGroupsExcluding(lastId)
                ?: imageDao.getRandomImageFromEnabledGroups()
        }

        /**
         * Sync the clarity-enhancement setting into the renderer. Called before
         * every switch/redraw so the user's choice applies to the next media.
         */
        private suspend fun applyClarityMode() {
            val mode = try {
                db.settingsDao().getString(SettingsKeys.CLARITY_MODE, "auto")
            } catch (_: Exception) {
                "auto"
            }
            renderer?.sharpnessScale = when (mode) {
                "off" -> 0f
                "strong" -> 1.6f
                else -> 1f
            }
        }

        /**
         * Start the fade-in transition after a switch when the setting is
         * enabled and the wallpaper is actually visible (skip it while covered
         * or the screen is off - nobody can see it then).
         */
        private suspend fun maybeFade() {
            if (renderer?.powerSaveMode == true) return
            if (renderer?.isSurfaceReady() != true) return
            val enabled = try {
                db.settingsDao().getBool(SettingsKeys.SWITCH_FADE_ENABLED, true)
            } catch (_: Exception) {
                true
            }
            if (enabled) renderer?.requestFade()
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
            startVideoHealthMonitor()
            return true
        }

        /**
         * Watchdog for a stalled video: if the renderer stops presenting
         * frames for 8s while the engine still believes it is playing, report
         * the failure so the switch queue recovers with a different media.
         */
        private fun startVideoHealthMonitor() {
            videoHealthJob?.cancel()
            val startAt = SystemClock.elapsedRealtime()
            videoHealthJob = scope.launch {
                while (isActive) {
                    delay(10_000L)
                    if (!videoMode || renderer?.isVideoPlaying != true) return@launch
                    val now = SystemClock.elapsedRealtime()
                    val last = renderer?.lastVideoFrameAt ?: 0L
                    if (last < startAt) {
                        // No frame since this video started. Slow/cloud sources
                        // can legitimately take a while for the first frame, so
                        // only recover after a generous grace period.
                        if (now - startAt > 15_000L) {
                            Log.w(TAG, "Video never presented a frame in ${(now - startAt) / 1000}s; recovering (last=$last)")
                            onVideoStartFailed()
                            return@launch
                        }
                    } else if (now - last > 12_000L) {
                        Log.w(TAG, "Video stalled: no frame for ${(now - last) / 1000}s; recovering")
                        onVideoStartFailed()
                        return@launch
                    } else {
                        // Playing healthily: any previous recovery failures are
                        // stale, so auto-recovery can kick in again if needed.
                        if (recoveryFailCount > 0) recoveryFailCount = 0
                    }
                }
            }
        }

        private fun playGif(uriStr: String, scaleMode: ScaleMode) {
            if (!surfaceReady) return
            pendingGifUri = uriStr
            // Decode off the main thread: ImageDecoder's first pass for a large
            // GIF can take tens of ms and would jank the UI on the main looper.
            // The drawable (or fallback bitmap) is handed back to the main
            // thread for the actual animation, guarded so a stale decode that
            // lost the race against a newer switch is discarded.
            scope.launch {
                try {
                    if (Build.VERSION.SDK_INT >= 28) {
                        val source = android.graphics.ImageDecoder.createSource(contentResolver, Uri.parse(uriStr))
                        val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        mainHandler.post {
                            if (pendingGifUri != uriStr || !surfaceReady) {
                                try { (drawable as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                                return@post
                            }
                            pendingGifUri = null
                            playGif28(drawable, scaleMode)
                        }
                    } else {
                        val bmp = loadBitmapWithTimeout(uriStr)
                        mainHandler.post {
                            if (pendingGifUri != uriStr || !surfaceReady) {
                                if (bmp != null && !bmp.isRecycled) bmp.recycle()
                                return@post
                            }
                            pendingGifUri = null
                            bmp?.let { renderer?.showImage(it, scaleMode) }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "playGif failed, falling back to static frame", t)
                    val bmp = loadBitmapWithTimeout(uriStr)
                    mainHandler.post {
                        if (pendingGifUri != uriStr || !surfaceReady) {
                            if (bmp != null && !bmp.isRecycled) bmp.recycle()
                            return@post
                        }
                        pendingGifUri = null
                        bmp?.let { renderer?.showImage(it, scaleMode) }
                    }
                }
            }
        }

        @android.annotation.TargetApi(28)
        private fun playGif28(drawable: android.graphics.drawable.Drawable, scaleMode: ScaleMode) {
            if (drawable is android.graphics.drawable.AnimatedImageDrawable) {
                // Release the previous GIF (if any) before replacing it.
                gifDrawable?.let { old ->
                    try { old.stop() } catch (_: Exception) {}
                    try { (old as java.lang.AutoCloseable).close() } catch (_: Exception) {}
                }
                gifDrawable = drawable
                drawable.repeatCount = -1
                drawable.start()

                // Render into a buffer at most the screen size: a large GIF
                // would otherwise upload its full intrinsic resolution to the
                // GPU ~30 times per second, a major power drain. The wallpaper
                // displays at screen resolution anyway, so the visible quality
                // is identical to the original file.
                val intrinsicW = drawable.intrinsicWidth.coerceAtLeast(1)
                val intrinsicH = drawable.intrinsicHeight.coerceAtLeast(1)
                val screenMax = maxOf(getMetrics().widthPixels, getMetrics().heightPixels)
                // FIT never enlarges; FILL/STRETCH magnify the GIF, so keep a
                // higher buffer ceiling in those modes to avoid softening large
                // GIFs when they are upscaled.
                val gifCap = when (scaleMode) {
                    ScaleMode.FILL, ScaleMode.STRETCH ->
                        minOf(screenMax * 2, 4096).coerceAtLeast(2560)
                    else -> screenMax
                }
                val gifScale = if (maxOf(intrinsicW, intrinsicH) > gifCap) {
                    gifCap.toFloat() / maxOf(intrinsicW, intrinsicH)
                } else {
                    1f
                }
                val frameW = (intrinsicW * gifScale).toInt().coerceAtLeast(1)
                val frameH = (intrinsicH * gifScale).toInt().coerceAtLeast(1)
                gifBitmapBuffer?.recycle()
                gifBitmapBuffer = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)

                val r = renderer
                val drawScale = gifScale
                val runnable = object : Runnable {
                    override fun run() {
                        // Stopped/closed (engine destroyed or replaced):
                        // stop ticking forever.
                        if (gifDrawable == null) return
                        // Screen off: redraw at ~2fps instead of 20fps. The
                        // drawable keeps animating internally and resumes at
                        // full rate on screen-on - no restart, no jump.
                        if (renderer?.powerSaveMode == true) {
                            mainHandler.postDelayed(this, 500L)
                            return
                        }
                        // Keep ticking even when the surface is temporarily
                        // unavailable, so the animation resumes automatically
                        // once the surface comes back (previously the runnable
                        // returned without rescheduling and the GIF froze).
                        if (surfaceReady) {
                            try {
                                val bmp = gifBitmapBuffer ?: return
                                bmp.eraseColor(Color.TRANSPARENT)
                                val cv = Canvas(bmp)
                                if (drawScale < 1f) {
                                    cv.scale(drawScale, drawScale)
                                }
                                drawable.draw(cv)
                                r?.showGifFrame(bmp, scaleMode)
                            } catch (t: Throwable) {
                                Log.e(TAG, "GIF frame draw failed", t)
                            }
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
            return com.wallpaperswitcher.engine.BitmapUtils.loadBitmap(
                applicationContext, uriStr, currentScaleMode
            )
        }

        /**
         * Load a bitmap with a hard timeout. BitmapFactory/ContentResolver
         * calls cannot be interrupted by coroutine cancellation (a plain
         * withTimeout would keep waiting for the blocking call), so the decode
         * runs on a helper thread and the caller abandons it after [timeoutMs].
         * If the abandoned thread ever finishes, its bitmap is recycled.
         */
        private fun loadBitmapWithTimeout(
            uriStr: String,
            timeoutMs: Long = BITMAP_LOAD_TIMEOUT_MS
        ): Bitmap? {
            val result = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
            val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
            val thread = Thread({
                try {
                    val bmp = loadBitmap(uriStr)
                    if (abandoned.get()) {
                        if (bmp != null && !bmp.isRecycled) bmp.recycle()
                    } else {
                        result.set(bmp)
                    }
                } catch (_: Throwable) {}
            }, "BitmapLoad").apply {
                // Never keep the process alive because a provider is stuck.
                isDaemon = true
                start()
            }
            try {
                thread.join(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                abandoned.set(true)
                thread.interrupt()
                return null
            }
            val bmp = result.get()
            if (bmp != null && bmp.isRecycled) return null
            return bmp
        }

        private fun getMetrics(): android.util.DisplayMetrics {
            return com.wallpaperswitcher.engine.BitmapUtils.getScreenMetrics(applicationContext)
        }
    }
}
