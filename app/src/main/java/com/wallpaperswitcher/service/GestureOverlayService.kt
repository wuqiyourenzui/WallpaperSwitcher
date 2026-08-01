package com.wallpaperswitcher.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.wallpaperswitcher.R
import com.wallpaperswitcher.WallpaperSwitcherApp
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.engine.WallpaperEngine
import com.wallpaperswitcher.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 手势覆盖层服务
 *
 * 使用 FLAG_NOT_TOUCHABLE + 独立 Looper 线程：
 * - 触摸事件完全穿透，不影响任何操作
 * - 通过 WindowManager 的触摸事件回调检测双击
 * - 切换壁纸在 IO 线程执行，不卡顿
 */
class GestureOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 双击检测状态
    private var lastTapTime = 0L
    private val doubleTapTimeout = 300L // ms

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 创建完全透明、不接收触摸的覆盖层
        overlayView = object : View(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // 检测双击（通过时间差判断）
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < doubleTapTimeout) {
                        lastTapTime = 0L
                        onDoubleTapDetected()
                    } else {
                        lastTapTime = now
                    }
                }
                return false // 不消费
            }
        }.apply {
            setBackgroundColor(0x00000000)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_TOUCHABLE: 触摸完全穿透
            // 这是关键！覆盖层不接收任何触摸事件
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "手势覆盖层已启动（不干扰触摸）")
        } catch (e: Exception) {
            Log.e(TAG, "启动手势覆盖层失败", e)
            stopSelf()
        }
    }

    private fun onDoubleTapDetected() {
        scope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val enabled = db.settingsDao()
                    .getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                if (enabled) {
                    val engine = WallpaperEngine(applicationContext)
                    engine.switchToNext()
                    Log.d(TAG, "双击切换壁纸成功")
                }
            } catch (e: Exception) {
                Log.e(TAG, "双击切换壁纸失败", e)
            }
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, WallpaperSwitcherApp.CHANNEL_ID)
            .setContentTitle("双击切换壁纸")
            .setContentText("双击屏幕即可切换壁纸")
            .setSmallIcon(R.drawable.ic_wallpaper_thumb)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "移除覆盖层失败", e)
        }
        overlayView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GestureOverlayService"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, GestureOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GestureOverlayService::class.java))
        }
    }
}
