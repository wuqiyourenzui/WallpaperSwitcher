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
import com.wallpaperswitcher.ui.MainActivity
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import kotlinx.coroutines.*

/**
 * Timed wallpaper switch foreground service.
 * Sends ACTION_SWITCH broadcast to LiveWallpaperService.
 */
class WallpaperSwitchService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var switchJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure foreground state first (required when started via startForegroundService)
        startForeground(NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            ACTION_SWITCH_NOW -> {
                sendSwitchBroadcast()
            }
            ACTION_STOP -> {
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
        // Sync service_enabled to false so UI reflects stopped state.
        // Use runBlocking to ensure the DB write completes before scope cancellation.
        try {
            val db = AppDatabase.getInstance(applicationContext)
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                db.settingsDao().setBool(SettingsKeys.SERVICE_ENABLED, false)
            }
        } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    private fun startSwitchLoop() {
        switchJob?.cancel()
        switchJob = scope.launch {
            // First switch immediately on start
            sendSwitchBroadcast()
            while (isActive) {
                try {
                    val db = AppDatabase.getInstance(applicationContext)
                    val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                    if (groups.isEmpty()) {
                        // No enabled groups — stop service to save power.
                        // User can re-enable via toggle, which calls start() again.
                        Log.d(TAG, "No enabled groups, stopping service")
                        withContext(Dispatchers.Main) { stopSelf() }
                        return@launch
                    }
                    // Get global interval
                    val interval = db.settingsDao().getLong(SettingsKeys.GLOBAL_INTERVAL_MS, 60_000L)
                        .coerceAtLeast(10_000L)
                    delay(interval)
                    sendSwitchBroadcast()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    Log.e(TAG, "Switch loop error", e)
                    delay(10_000L)
                }
            }
        }
    }

    private fun sendSwitchBroadcast() {
        val intent = Intent(LiveWallpaperService.ACTION_SWITCH)
        intent.setPackage(applicationContext.packageName)
        applicationContext.sendBroadcast(intent)
        Log.d(TAG, "Switch broadcast sent")
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, WallpaperSwitcherApp.CHANNEL_ID)
            .setContentTitle("壁纸切换")
            .setContentText("壁纸自动切换中")
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
            val intent = Intent(LiveWallpaperService.ACTION_SWITCH)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        }

        fun switchToTarget(context: Context, targetId: Long) {
            val intent = Intent(LiveWallpaperService.ACTION_SWITCH).apply {
                setPackage(context.packageName)
                putExtra(LiveWallpaperService.EXTRA_TARGET_ID, targetId)
            }
            context.sendBroadcast(intent)
        }
    }
}
