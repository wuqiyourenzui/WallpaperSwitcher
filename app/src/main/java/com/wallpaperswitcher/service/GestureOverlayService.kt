package com.wallpaperswitcher.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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
 * 在屏幕上放置一个透明的全屏 View，用于检测双击手势
 * 不依赖动态壁纸模式，在任何壁纸模式下都能工作
 */
class GestureOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        // 先启动前台（Android 8+ 必须在 5 秒内调用 startForeground）
        startForeground(NOTIFICATION_ID, createNotification())

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 创建透明全屏 View
        overlayView = View(this).apply {
            setBackgroundColor(0x00000000) // 完全透明
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    onDoubleTapDetected()
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    return false
                }
            }
        )

        overlayView?.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // 不消费事件，让下层也能接收触摸
        }

        try {
            windowManager?.addView(overlayView, params)
            Log.d(TAG, "手势覆盖层已启动")
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
