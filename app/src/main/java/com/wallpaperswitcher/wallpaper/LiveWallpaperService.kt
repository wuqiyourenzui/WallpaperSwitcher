package com.wallpaperswitcher.wallpaper

import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.engine.WallpaperEngine
import kotlinx.coroutines.*

/**
 * 动态壁纸服务
 * 支持双击切换壁纸，同时作为壁纸引擎的载体
 */
class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    inner class LiveWallpaperEngine : Engine() {

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private lateinit var engine: WallpaperEngine
        private lateinit var db: AppDatabase
        private var switchJob: Job? = null
        private val handler = Handler(Looper.getMainLooper())

        private val gestureDetector = GestureDetector(
            applicationContext,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    onDoubleTapDetected()
                    return true
                }
            }
        )

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            engine = WallpaperEngine(applicationContext)
            db = AppDatabase.getInstance(applicationContext)
            setTouchEventsEnabled(true)
            startSwitchLoop()
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                startSwitchLoop()
            } else {
                switchJob?.cancel()
            }
        }

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
                        val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                        if (groups.isEmpty()) {
                            delay(60_000L)
                            continue
                        }

                        val minInterval = groups.minOf { it.switchIntervalMs }
                            .coerceAtLeast(60_000L)

                        delay(minInterval)

                        val doubleTapEnabled = db.settingsDao()
                            .getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                        // 动态壁纸模式下自动切换由定时器驱动
                        engine.switchToNext()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "动态壁纸切换异常", e)
                        delay(10_000L)
                    }
                }
            }
        }

        private fun onDoubleTapDetected() {
            scope.launch {
                val enabled = db.settingsDao()
                    .getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                if (enabled) {
                    engine.switchToNext()
                }
            }
        }
    }

    companion object {
        private const val TAG = "LiveWallpaperService"
    }
}
