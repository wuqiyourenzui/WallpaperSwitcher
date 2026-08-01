package com.wallpaperswitcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.os.Build
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
 * 通过 AccessibilityService 监听全局触摸事件，
 * 检测双击手势触发壁纸切换。
 * 完全不影响正常触摸操作。
 */
class DoubleTapAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastTapTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理无障碍事件，只用 touchExploration 的手势回调
    }

    override fun onInterrupt() {}

    /**
     * 手势检测回调（API 24+）
     * 系统检测到全局手势时调用
     */
    override fun onGesture(gestureId: Int): Boolean {
        if (gestureId == GESTURE_DOUBLE_TAP) {
            onDoubleTap()
            return true
        }
        return false
    }

    /**
     * 备用双击检测：通过触摸探索事件的时间差
     */
    override fun onTouchExplorationGesture(event: AccessibilityEvent?) {
        // 备用方案
    }

    private fun onDoubleTap() {
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "双击无障碍服务已连接")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DoubleTapA11y"
        private const val GESTURE_DOUBLE_TAP = 17 // GESTURE_DOUBLE_TAP

        var instance: DoubleTapAccessibilityService? = null
            private set

        /**
         * 检查无障碍服务是否已启用
         */
        fun isEnabled(context: Context): Boolean {
            val serviceName = "${context.packageName}/${DoubleTapAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(serviceName)
        }

        /**
         * 打开无障碍设置页面
         */
        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
