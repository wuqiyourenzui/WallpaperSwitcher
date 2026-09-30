package com.wallpaperswitcher

import com.wallpaperswitcher.util.AppLog

import android.app.Application
import android.content.ComponentCallbacks2
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.setBool
import com.wallpaperswitcher.receiver.ScreenUnlockReceiver
import com.wallpaperswitcher.util.logCoroutineFailures
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WallpaperSwitcherApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    private var unlockReceiver: ScreenUnlockReceiver? = null

    override fun onCreate() {
        super.onCreate()
        // Start capturing runtime logs first so startup/engine logs are kept.
        AppLog.init(this)
        // None of the startup steps below may take the app down: a notification
        // channel, a receiver (OEM policy) or the image loader failing must
        // degrade that one feature, not crash every launch.
        runCatching { createNotificationChannel() }
            .onFailure { AppLog.e(TAG, "createNotificationChannel failed", it) }
        initDefaultSettings()
        runCatching { registerUnlockReceiver() }
            .onFailure { AppLog.e(TAG, "registerUnlockReceiver failed", it) }
        runCatching { initCoil() }
            .onFailure { AppLog.e(TAG, "initCoil failed", it) }
    }

    /**
     * When the system is running low on memory, drop the image cache so the
     * wallpaper engine (video decode buffers + GL textures) has the best
     * chance of surviving instead of being killed.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            trimThumbnailCache("trim level $level")
            // Also drop the wallpaper engine's prefetched next-image bitmap
            // (one screen-size ARGB) so the currently displayed media's decode
            // buffers + GL textures have the best chance of surviving. The
            // next switch simply decodes fresh.
            LiveWallpaperService.onTrimMemory(level)
            // ...and the static path's cached encoded wallpapers (a few MB).
            com.wallpaperswitcher.engine.WallpaperApplier.trimMemory()
        }
    }

    /**
     * The UI went to the background: drop the thumbnail memory cache after
     * [UI_CACHE_TRIM_DELAY_MS], unless the UI comes back first (the system
     * wallpaper picker, a quick app switch, a permission screen - all of them
     * return within seconds, and clearing eagerly would re-decode every visible
     * thumbnail).
     *
     * Measured on a 65k-media tablet with the launcher in front and our Activity
     * only sitting in the back stack: Coil's cache held 2662 bitmaps / 92-115MB,
     * the single biggest item of the process (PSS 284MB), and the system never
     * sent a trim callback to make us release it. Clearing it there is what takes
     * the process back to roughly the engine-only footprint (~180MB).
     */
    fun scheduleThumbnailCacheTrim() {
        uiCacheTrimHandler.removeCallbacks(uiCacheTrimRunnable)
        uiCacheTrimHandler.postDelayed(uiCacheTrimRunnable, UI_CACHE_TRIM_DELAY_MS)
    }

    /** The UI is visible again: keep the thumbnails. */
    fun cancelThumbnailCacheTrim() {
        uiCacheTrimHandler.removeCallbacks(uiCacheTrimRunnable)
    }

    /**
     * Release part of Coil's in-memory thumbnails (the disk cache is untouched).
     *
     * This used to be `memoryCache.clear()`, and the measured cost was the jank
     * the user reported: coming back from the launcher and scrolling a group grid
     * re-decoded every visible thumbnail - 262 frames with **23.3% janky**,
     * p50 32ms / p90 73ms / p99 150ms, versus 6.8% / 21ms / 44ms on the very next
     * scroll with a warm cache. Trimming to Coil's own UI-hidden level keeps about
     * half of the working set, so the first scroll after re-entry stays warm while
     * the biggest single process item (measured 92-115MB at 20% on a 65k-media
     * library) is still cut roughly in half.
     */
    private fun trimThumbnailCache(reason: String) {
        try {
            val cache = Coil.imageLoader(this).memoryCache ?: return
            val before = cache.size
            cache.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            AppLog.d(TAG, "Thumbnail memory cache trimmed ($reason): $before -> ${cache.size}")
        } catch (_: Exception) {
        }
    }

    private val uiCacheTrimHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiCacheTrimRunnable = Runnable { trimThumbnailCache("UI hidden") }

    private fun initCoil() {
        val imageLoader = ImageLoader.Builder(this)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    // 10% instead of 25%: the thumbnails are the biggest single
                    // item in a normal session (measured 92-115MB at 20% on a
                    // 65k-media library), and they compete with the wallpaper
                    // engine's video decode buffers + GL textures. The disk cache
                    // still makes a re-decode cheap, so the smaller cap only costs
                    // a few decodes while scrolling.
                    .maxSizePercent(0.10)
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
        CoroutineScope(Dispatchers.IO + logCoroutineFailures(TAG)).launch {
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
                // The lock timer is ON by default (product decision): write it
                // explicitly so the stored state matches what every reader's
                // getBool(..., true) fallback already assumes.
                if (dao.getValue(SettingsKeys.LOCK_TIMER_ENABLED) == null) {
                    dao.setBool(SettingsKeys.LOCK_TIMER_ENABLED, true)
                }
                // Housekeeping: the "swipe / page-flip switch" feature was
                // removed, and its toggle row was left behind in existing
                // installs. Nothing reads it any more, so drop it instead of
                // carrying a dead key forever.
                for (obsolete in OBSOLETE_KEYS) {
                    if (dao.getValue(obsolete) != null) {
                        val removed = dao.deleteKey(obsolete)
                        AppLog.d(TAG, "Removed obsolete setting '$obsolete' (rows=$removed)")
                    }
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "initDefaultSettings failed", e)
            }
        }
    }

    /**
     * ACTION_USER_PRESENT must be registered dynamically: it is an implicit
     * broadcast, so a manifest-declared receiver for it is silently never
     * delivered on Android 8+ (captured logs confirmed zero callbacks with a
     * manifest registration). The wallpaper engine keeps this process alive,
     * so the dynamic receiver is registered whenever the wallpaper is active.
     */
    private fun registerUnlockReceiver() {
        unlockReceiver = ScreenUnlockReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val flags = if (Build.VERSION.SDK_INT >= 33) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }
        registerReceiver(unlockReceiver, filter, flags)
    }

    // Note: onTerminate() is never called on real devices (only emulators).
    // The OS automatically cleans up registered receivers when the process dies.

    companion object {
        private const val TAG = "WallpaperSwitcherApp"
        const val CHANNEL_ID = "wallpaper_switch_service"
        /**
         * How long the UI has to stay hidden before its thumbnail cache is trimmed
         * to Coil's UI-hidden level - about half of it, see
         * [scheduleThumbnailCacheTrim]. Long enough to survive a trip through the
         * system wallpaper picker or a quick app switch, short enough that an app
         * left in the background stops holding its whole working set.
         *
         * This used to be memoryCache.clear(), which dropped everything; the
         * cost showed up as jank on the first scroll after coming back to the app
         * (measured: 20-26% janky frames / p50 31-36ms on a cold cache versus
         * 2.8% / 19ms with the working set kept, and 0.05% / 5ms once warm).
         */
        private const val UI_CACHE_TRIM_DELAY_MS = 60_000L
        /**
         * Setting keys whose feature no longer exists. Removed once on startup
         * so an upgraded install does not keep writing/reading dead state.
         */
        private val OBSOLETE_KEYS = listOf(
            "swipe_switch_enabled", // 滑动/翻页切换（已移除的功能）
            "shuffle_all_count_lock", // 锁屏洗牌计数（只写不读，已删除写入方）
            "static_when_image_only", // 仅图片时用静态壁纸（已撤销的功能）
            // "锁屏壁纸切换"开关（曾短暂取代锁屏定时，已按需求回退）
            "lock_switch_enabled"
        )
    }
}
