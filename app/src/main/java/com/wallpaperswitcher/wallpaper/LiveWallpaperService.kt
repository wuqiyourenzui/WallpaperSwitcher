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
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.engine.WallpaperEngine
import kotlinx.coroutines.*

/**
 * 动态壁纸服务
 * 支持双击切换壁纸，同时作为壁纸引擎的载体
 *
 * 注意：双击切换仅检查 doubleTapEnabled 设置，
 * 不受 serviceEnabled（定时服务开关）影响，
 * 两种触发方式可同时生效。
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
                        // 检查定时切换是否启用
                        val serviceEnabled = db.settingsDao()
                            .getBool(SettingsKeys.SERVICE_ENABLED, false)
                        if (!serviceEnabled) {
                            // 定时切换未启用，等待后再检查
                            delay(30_000L)
                            continue
                        }

                        val groups = db.wallpaperGroupDao().getEnabledGroupsSync()
                        if (groups.isEmpty()) {
                            delay(60_000L)
                            continue
                        }

                        val minInterval = groups.minOf { it.switchIntervalMs }
                            .coerceAtLeast(60_000L)

                        delay(minInterval)

                        // 定时切换
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

        /**
         * 双击切换壁纸
         * 已由 GestureOverlayService 接管，此处不再处理
         */
        private fun onDoubleTapDetected() {
            // 由 GestureOverlayService 统一处理
        }
    }

    companion object {
        private const val TAG = "LiveWallpaperService"
    }
}