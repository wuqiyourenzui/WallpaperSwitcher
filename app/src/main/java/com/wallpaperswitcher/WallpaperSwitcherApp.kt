package com.wallpaperswitcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.IntentFilter
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.setBool
import com.wallpaperswitcher.receiver.ScreenUnlockReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WallpaperSwitcherApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    private var unlockReceiver: ScreenUnlockReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initDefaultSettings()
        registerUnlockReceiver()
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

    private fun initDefaultSettings() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = database.settingsDao()
                if (dao.getValue(SettingsKeys.SERVICE_ENABLED) == null) {
                    dao.setBool(SettingsKeys.SERVICE_ENABLED, false)
                }
                if (dao.getValue(SettingsKeys.DOUBLE_TAP_ENABLED) == null) {
                    dao.setBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                }
                if (dao.getValue(SettingsKeys.UNLOCK_SWITCH_ENABLED) == null) {
                    dao.setBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Register unlock receiver programmatically (more reliable than manifest).
     */
    private fun registerUnlockReceiver() {
        unlockReceiver = ScreenUnlockReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        registerReceiver(unlockReceiver, filter)
    }

    override fun onTerminate() {
        unlockReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        super.onTerminate()
    }

    companion object {
        const val CHANNEL_ID = "wallpaper_switch_service"
    }
}
