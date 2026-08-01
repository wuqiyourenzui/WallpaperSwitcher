package com.wallpaperswitcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.setBool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WallpaperSwitcherApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initDefaultSettings()
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
     * 初始化默认设置，确保数据库中有值
     */
    private fun initDefaultSettings() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = database.settingsDao()
                // 只在 key 不存在时写入默认值
                if (dao.getValue(SettingsKeys.SERVICE_ENABLED) == null) {
                    dao.setBool(SettingsKeys.SERVICE_ENABLED, false)
                }
                if (dao.getValue(SettingsKeys.DOUBLE_TAP_ENABLED) == null) {
                    dao.setBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                }
                if (dao.getValue(SettingsKeys.UNLOCK_SWITCH_ENABLED) == null) {
                    dao.setBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)
                }
            } catch (e: Exception) {
                // 忽略初始化错误
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "wallpaper_switch_service"
    }
}
