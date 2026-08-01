package com.wallpaperswitcher.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.engine.WallpaperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 双击切换壁纸无障碍服务
 *
 * 两种检测方式（双保险）：
 * 1. onGesture(GESTURE_DOUBLE_TAP) — 系统手势回调
 * 2. TYPE_TOUCH_INTERACTION_START 事件的时间差检测 — 备用方案
 */
class DoubleTapAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastTapTime = 0L
    private val doubleTapWindow = 400L // ms

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 备用方案：通过触摸交互事件检测双击
        if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            checkDoubleTap()
        }
    }

    override fun onInterrupt() {}

    /**
     * 系统手势回调 — 主要检测方式
     */
    override fun onGesture(gestureId: Int): Boolean {
        Log.d(TAG, "onGesture: $gestureId")
        if (gestureId == GESTURE_DOUBLE_TAP) {
            triggerSwitch()
            return true
        }
        return false
    }

    /**
     * 备用双击检测
     */
    private fun checkDoubleTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapTime < doubleTapWindow) {
            lastTapTime = 0L
            triggerSwitch()
        } else {
            lastTapTime = now
        }
    }

    private fun triggerSwitch() {
        scope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val enabled = db.settingsDao()
                    .getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                Log.d(TAG, "双击触发, enabled=$enabled")
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "双击无障碍服务已连接")
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "双击无障碍服务已销毁")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DoubleTapA11y"
        private const val GESTURE_DOUBLE_TAP = 17

        var instance: DoubleTapAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val serviceName = "${context.packageName}/${DoubleTapAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(serviceName)
        }

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
