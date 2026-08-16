package com.wallpaperswitcher.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wallpaperswitcher.R
import com.wallpaperswitcher.WallpaperSwitcherApp
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.data.setBool
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.engine.WallpaperApplier
import com.wallpaperswitcher.ui.MainActivity
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Timed wallpaper switch foreground service.
 * Switches wallpapers: broadcasts ACTION_SWITCH to the live wallpaper engine
 * when it is running, otherwise applies a static wallpaper directly.
 */
class WallpaperSwitchService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var switchJob: Job? = null
    // Throttle the screen-off skip log to once per minute: at a 10s interval
    // the old code logged ~720 lines/hour while the screen was dark.
    private var lastScreenOffLogAt = 0L
    // A single transient empty read (e.g. while the DB is being migrated or a
    // folder import replaced groups) must never kill the timer and flip the
    // toggle off silently; only stop after several consecutive empty checks.
    private var consecutiveEmptyGroupChecks = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure foreground state first (required when started via startForegroundService)
        // Android 14+ (targetSdk 34) requires an explicit foreground service type:
        // pass FOREGROUND_SERVICE_TYPE_SPECIAL_USE on API 29+ instead of relying on
        // the manifest-declared type (avoids MissingForegroundServiceTypeException
        // on strict/OEM builds).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        running = true
        Log.d(TAG, "Service started (build 20260816-b1), action=${intent?.action}")

        when (intent?.action) {
            ACTION_SWITCH_NOW -> {
                sendSwitch(LiveWallpaperService.SOURCE_MANUAL)
            }
            ACTION_STOP -> {
                // Persist the disabled state BEFORE stopping. onDestroy() is not a
                // reliable place for this: it also runs when the system kills the
                // service (START_STICKY restart), which would corrupt the setting.
                try {
                    kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                        AppDatabase.getInstance(applicationContext)
                            .settingsDao()
                            .setBool(SettingsKeys.SERVICE_ENABLED, false)
                    }
                } catch (_: Exception) {}
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startSwitchLoop()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        switchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSwitchLoop() {
        switchJob?.cancel()
        switchJob = scope.launch {
            while (isActive) {
                try {
                    // First switch immediately, then every interval.
                    sendSwitch(LiveWallpaperService.SOURCE_TIMER)
                    val db = AppDatabase.getInstance(applicationContext)
                    val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                    if (groups.isEmpty()) {
                        consecutiveEmptyGroupChecks++
                        if (consecutiveEmptyGroupChecks >= EMPTY_GROUP_STOP_THRESHOLD) {
                            // User can re-enable via toggle, which calls start() again.
                            Log.d(TAG, "No enabled groups for $consecutiveEmptyGroupChecks checks, stopping service")
                            // Sync the toggle so the UI reflects the stopped state.
                            db.settingsDao().setBool(SettingsKeys.SERVICE_ENABLED, false)
                            stopSelf()
                            return@launch
                        }
                        Log.d(TAG, "No enabled groups (check $consecutiveEmptyGroupChecks/$EMPTY_GROUP_STOP_THRESHOLD), keeping timer alive")
                    } else {
                        consecutiveEmptyGroupChecks = 0
                    }
                    // Get global interval
                    val interval = db.settingsDao().getLong(SettingsKeys.GLOBAL_INTERVAL_MS, 60_000L)
                        .coerceAtLeast(10_000L)
                    // Screen off: recheck at max(interval, 60s) so short
                    // intervals (e.g. 10s) stop waking the service in the dark,
                    // while long intervals (e.g. 24h) are never checked MORE
                    // often than their own cadence. When the screen is back on,
                    // the normal interval is restored from the next check.
                    val interactive =
                        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
                    delay(if (interactive) interval else maxOf(interval, SCREEN_OFF_RECHECK_MS))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.e(TAG, "Switch loop error", e)
                    delay(10_000L)
                }
            }
        }
    }

    /**
     * Route a switch to wherever it can be rendered:
     * - Live wallpaper engine running -> broadcast to the engine.
     * - Otherwise -> apply a static wallpaper via WallpaperManager.
     */
    private fun sendSwitch(source: String) {
        // Screen off: nobody can see the result, and the live engine is in
        // power-save anyway. Skip the work so the timer never starts an
        // invisible decode (live engine) or a wasteful setBitmap (static
        // mode). The loop keeps its cadence; the next tick after the screen
        // comes back on switches normally.
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!pm.isInteractive) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastScreenOffLogAt > 60_000L) {
                lastScreenOffLogAt = now
                Log.d(TAG, "Screen off, skipping $source switch (power save)")
            }
            return
        }
        Log.d(TAG, "sendSwitch($source) engineRunning=${LiveWallpaperService.engineRunning}")
        if (LiveWallpaperService.engineRunning) {
            sendSwitchBroadcast(source)
        } else {
            // The timer loop does not wait for the static apply, so a slow
            // (e.g. cloud) bitmap decode must never overlap the next tick's
            // apply: two concurrent setBitmap calls could corrupt the wallpaper
            // and waste resources. Skip the tick if one is already running.
            if (!staticApplyInProgress.compareAndSet(false, true)) {
                Log.d(TAG, "Static wallpaper apply already in progress, skipping tick")
                return
            }
            scope.launch {
                try {
                    val ok = WallpaperApplier.applyNext(applicationContext)
                    if (ok) {
                        Log.d(TAG, "Static wallpaper switched")
                    } else {
                        Log.e(TAG, "Static wallpaper switch failed (no media?)")
                    }
                } finally {
                    staticApplyInProgress.set(false)
                }
            }
        }
    }

    private fun sendSwitchBroadcast(source: String) {
        val intent = Intent(LiveWallpaperService.ACTION_SWITCH)
        intent.setPackage(applicationContext.packageName)
        intent.putExtra(LiveWallpaperService.EXTRA_SOURCE, source)
        applicationContext.sendBroadcast(intent)
        Log.d(TAG, "Switch broadcast sent ($source)")
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, WallpaperSwitcherApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_wallpaper_thumb)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "WallpaperSwitchService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_SWITCH_NOW = "com.wallpaperswitcher.SWITCH_NOW"
        const val ACTION_STOP = "com.wallpaperswitcher.STOP"
        // Require several consecutive empty-group reads before stopping, so a
        // transient DB state can never kill the timer on its own.
        private const val EMPTY_GROUP_STOP_THRESHOLD = 3
        // While the screen is off, re-check every 60s instead of the configured
        // interval (which can be as low as 10s) to avoid useless wakeups.
        private const val SCREEN_OFF_RECHECK_MS = 60_000L
        // Shared guard so the timer loop and a manual "switch now" can never
        // apply two static wallpapers at the same time.
        private val staticApplyInProgress = AtomicBoolean(false)
        // True while this service is alive in the current process. Used by
        // ensureRunning() to self-heal after Android 15+ kills a long-running
        // foreground service in the background.
        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WallpaperSwitchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WallpaperSwitchService::class.java))
        }

        /**
         * Cheap self-heal: Android 15+ can stop long-running foreground
         * services while the app is in the background (6h timeout / OEM power
         * killers). Any time the app returns to the foreground, restart the
         * timer if it is enabled but no longer running.
         */
        fun ensureRunning(context: Context) {
            if (running) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val enabled = AppDatabase.getInstance(context)
                        .settingsDao()
                        .getBool(SettingsKeys.SERVICE_ENABLED, false)
                    if (enabled) {
                        Log.d(TAG, "Timer enabled but service not running, restarting")
                        start(context)
                    }
                } catch (_: Exception) {}
            }
        }

        fun switchNow(context: Context) {
            if (LiveWallpaperService.engineRunning) {
                val intent = Intent(LiveWallpaperService.ACTION_SWITCH).apply {
                    putExtra(LiveWallpaperService.EXTRA_SOURCE, LiveWallpaperService.SOURCE_MANUAL)
                }
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
            } else {
                if (!staticApplyInProgress.compareAndSet(false, true)) {
                    Log.d(TAG, "Static wallpaper apply already in progress, skipping switchNow")
                    return
                }
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        WallpaperApplier.applyNext(context)
                    } finally {
                        staticApplyInProgress.set(false)
                    }
                }
            }
        }

        fun switchToTarget(context: Context, targetId: Long) {
            if (LiveWallpaperService.engineRunning) {
                val intent = Intent(LiveWallpaperService.ACTION_SWITCH).apply {
                    setPackage(context.packageName)
                    putExtra(LiveWallpaperService.EXTRA_TARGET_ID, targetId)
                    putExtra(LiveWallpaperService.EXTRA_SOURCE, LiveWallpaperService.SOURCE_MANUAL)
                }
                context.sendBroadcast(intent)
            } else {
                // Same concurrency guard as switchNow(): a manual target switch
                // must never overlap a timed static apply (two concurrent
                // WallpaperManager.setBitmap calls can corrupt the wallpaper).
                if (!staticApplyInProgress.compareAndSet(false, true)) {
                    Log.d(TAG, "Static wallpaper apply already in progress, skipping switchToTarget")
                    return
                }
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val image = AppDatabase.getInstance(context)
                            .wallpaperImageDao()
                            .getImageById(targetId) ?: return@launch
                        WallpaperApplier.apply(context, image)
                    } finally {
                        staticApplyInProgress.set(false)
                    }
                }
            }
        }
    }
}
