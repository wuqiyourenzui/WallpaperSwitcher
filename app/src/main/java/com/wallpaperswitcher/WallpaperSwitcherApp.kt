package com.wallpaperswitcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
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
        initCoil()
    }

    private fun initCoil() {
        val imageLoader = ImageLoader.Builder(this)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // Use 25% of available memory for cache
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .allowHardware(false) // Software bitmaps for compatibility
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565) // 16-bit for thumbnails (less memory)
            .build()
        Coil.setImageLoader(imageLoader)
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
            } catch (e: Exception) {
                Log.e(TAG, "initDefaultSettings failed", e)
            }
        }
    }

    /**
     * Register unlock receiver programmatically (more reliable than manifest).
     */
    private fun registerUnlockReceiver() {
        unlockReceiver = ScreenUnlockReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }
        registerReceiver(unlockReceiver, filter, flags)
    }

    override fun onTerminate() {
        unlockReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister receiver", e)
            }
        }
        super.onTerminate()
    }

    companion object {
        private const val TAG = "WallpaperSwitcherApp"
        const val CHANNEL_ID = "wallpaper_switch_service"
    }
}
