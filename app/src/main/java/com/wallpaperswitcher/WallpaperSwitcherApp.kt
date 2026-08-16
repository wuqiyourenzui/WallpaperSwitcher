package com.wallpaperswitcher

import android.app.Application
import android.content.ComponentCallbacks2
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
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
        initCoil()
    }

    /**
     * When the system is running low on memory, drop the image cache so the
     * wallpaper engine (video decode buffers + GL textures) has the best
     * chance of surviving instead of being killed.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            try {
                Coil.imageLoader(this).memoryCache?.clear()
            } catch (_: Exception) {}
        }
    }

    private fun initCoil() {
        val imageLoader = ImageLoader.Builder(this)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    // 20% instead of 25%: leaves more headroom for the wallpaper
                    // engine's video decode buffers + GL textures on low-RAM
                    // devices while thumbnails stay cached.
                    .maxSizePercent(0.20)
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

    companion object {
        private const val TAG = "WallpaperSwitcherApp"
        const val CHANNEL_ID = "wallpaper_switch_service"
    }
}
