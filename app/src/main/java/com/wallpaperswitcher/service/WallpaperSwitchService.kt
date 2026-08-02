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
        when (intent?.action) {
            ACTION_SWITCH_NOW -> {
                sendSwitchBroadcast()
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification())
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
                    val db = AppDatabase.getInstance(applicationContext)
                    val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                    if (groups.isEmpty()) {
                        delay(60_000L)
                        continue
                    }
                    // Get global interval
                    val interval = db.settingsDao().getLong(SettingsKeys.GLOBAL_INTERVAL_MS, 60_000L)
                        .coerceAtLeast(60_000L)
                    delay(interval.toLong())
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
            .setContentTitle("Wallpaper Switcher")
            .setContentText("Auto-switching wallpaper")
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
    }
}
