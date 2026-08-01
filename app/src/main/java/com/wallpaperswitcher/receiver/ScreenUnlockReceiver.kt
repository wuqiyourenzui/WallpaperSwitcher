package com.wallpaperswitcher.receiver

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.engine.WallpaperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 解锁屏幕时切换壁纸
 *
 * 同时监听 SCREEN_ON 和 USER_PRESENT，确保各种解锁方式都能触发：
 * - 密码/图案解锁 → USER_PRESENT
 * - 指纹/面部解锁 → USER_PRESENT（部分机型）
 * - 直接亮屏（无锁屏）→ SCREEN_ON
 */
class ScreenUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "收到广播: $action")

        // USER_PRESENT 直接触发
        // SCREEN_ON 需要额外检查：屏幕已解锁（无锁屏或已解锁）
        val shouldSwitch = when (action) {
            Intent.ACTION_USER_PRESENT -> true
            Intent.ACTION_SCREEN_ON -> {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                !km.isKeyguardLocked // 无锁屏或已解锁
            }
            else -> return
        }

        if (!shouldSwitch) {
            Log.d(TAG, "屏幕锁定中，跳过")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val unlockEnabled = db.settingsDao()
                    .getBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)

                Log.d(TAG, "unlockEnabled=$unlockEnabled")

                if (unlockEnabled) {
                    val engine = WallpaperEngine(context)
                    val result = engine.switchToNext()
                    Log.d(TAG, "解锁切换壁纸结果: $result")
                }
            } catch (e: Exception) {
                Log.e(TAG, "解锁切换壁纸失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ScreenUnlockReceiver"
    }
}
