package com.wallpaperswitcher.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wallpaperswitcher.R
import com.wallpaperswitcher.WallpaperSwitcherApp
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.engine.WallpaperEngine
import com.wallpaperswitcher.ui.MainActivity
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * 壁纸自动切换前台服务
 * 使用协程 + delay 实现低功耗定时切换
 */
class WallpaperSwitchService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var switchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var engine: WallpaperEngine
    private lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        engine = WallpaperEngine(applicationContext)
        db = (applicationContext as WallpaperSwitcherApp).database
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SWITCH_NOW -> {
                scope.launch { engine.switchToNext() }
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
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * 启动壁纸切换循环
     */
    private fun startSwitchLoop() {
        switchJob?.cancel()
        switchJob = scope.launch {
            while (isActive) {
                try {
                    // 获取最短的切换间隔（从所有启用分组中取最小值）
                    val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                    if (groups.isEmpty()) {
                        delay(TimeUnit.MINUTES.toMillis(1))
                        continue
                    }

                    val minInterval = groups.minOf { it.switchIntervalMs }
                        .coerceAtLeast(MIN_INTERVAL_MS)

                    delay(minInterval)
                    engine.switchToNext()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "切换循环异常", e)
                    delay(TimeUnit.SECONDS.toMillis(10))
                }
            }
        }
    }

    /**
     * 解锁屏幕时触发切换
     */
    fun onScreenUnlocked() {
        scope.launch { engine.switchToNext() }
    }

    /**
     * 双击时触发切换
     */
    fun onDoubleTap() {
        scope.launch { engine.switchToNext() }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WallpaperSwitchService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, WallpaperSwitcherApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_wallpaper_thumb)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_wallpaper_thumb, "停止", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WallpaperSwitcher::SwitchLock"
        ).apply {
            acquire(TimeUnit.SECONDS.toMillis(30))
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "WallpaperSwitchService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_SWITCH_NOW = "com.wallpaperswitcher.SWITCH_NOW"
        const val ACTION_STOP = "com.wallpaperswitcher.STOP"
        val MIN_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1) // 最低1分钟

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
            val intent = Intent(context, WallpaperSwitchService::class.java).apply {
                action = ACTION_SWITCH_NOW
            }
            context.startService(intent)
        }
    }
}
