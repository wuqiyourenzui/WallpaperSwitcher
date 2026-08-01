package com.wallpaperswitcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.service.GestureOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WallpaperSwitcherApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startGestureOverlayIfNeeded()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * 如果双击切换已启用，启动手势覆盖层服务
     */
    private fun startGestureOverlayIfNeeded() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = database.settingsDao()
                    .getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                if (enabled) {
                    GestureOverlayService.start(this@WallpaperSwitcherApp)
                }
            } catch (e: Exception) {
                // 数据库可能还没准备好，忽略
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "wallpaper_switch_service"
    }
}
