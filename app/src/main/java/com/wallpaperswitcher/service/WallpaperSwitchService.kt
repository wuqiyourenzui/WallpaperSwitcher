package com.wallpaperswitcher.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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

/**
 * Timed wallpaper switch foreground service.
 * Switches wallpapers: broadcasts ACTION_SWITCH to the live wallpaper engine
 * when it is running, otherwise applies a static wallpaper directly.
 */
class WallpaperSwitchService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var switchJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure foreground state first (required when started via startForegroundService)
        startForeground(NOTIFICATION_ID, createNotification())

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
                        // User can re-enable via toggle, which calls start() again.
                        Log.d(TAG, "No enabled groups, stopping service")
                        // Sync the toggle so the UI reflects the stopped state.
                        db.settingsDao().setBool(SettingsKeys.SERVICE_ENABLED, false)
                        stopSelf()
                        return@launch
                    }
                    // Get global interval
                    val interval = db.settingsDao().getLong(SettingsKeys.GLOBAL_INTERVAL_MS, 60_000L)
                        .coerceAtLeast(10_000L)
                    delay(interval)
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
        if (LiveWallpaperService.engineRunning) {
            sendSwitchBroadcast(source)
        } else {
            scope.launch {
                val ok = WallpaperApplier.applyNext(applicationContext)
                if (ok) {
                    Log.d(TAG, "Static wallpaper switched")
                } else {
                    Log.e(TAG, "Static wallpaper switch failed (no media?)")
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

        fun switchNow(context: Context) {
            if (LiveWallpaperService.engineRunning) {
                val intent = Intent(LiveWallpaperService.ACTION_SWITCH).apply {
                    putExtra(LiveWallpaperService.EXTRA_SOURCE, LiveWallpaperService.SOURCE_MANUAL)
                }
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    WallpaperApplier.applyNext(context)
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
                CoroutineScope(Dispatchers.IO).launch {
                    val image = AppDatabase.getInstance(context)
                        .wallpaperImageDao()
                        .getImageById(targetId) ?: return@launch
                    WallpaperApplier.apply(context, image)
                }
            }
        }
    }
}
