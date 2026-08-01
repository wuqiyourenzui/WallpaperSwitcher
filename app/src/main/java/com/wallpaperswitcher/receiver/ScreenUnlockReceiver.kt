package com.wallpaperswitcher.receiver

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
 * 直接调用引擎切换，不依赖前台服务是否运行
 * 仅检查 UNLOCK_SWITCH_ENABLED，不受定时服务开关影响
 */
class ScreenUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    val unlockEnabled = db.settingsDao()
                        .getBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)

                    if (unlockEnabled) {
                        val engine = WallpaperEngine(context)
                        engine.switchToNext()
                        Log.d("ScreenUnlockReceiver", "解锁切换壁纸成功")
                    }
                } catch (e: Exception) {
                    Log.e("ScreenUnlockReceiver", "解锁切换壁纸失败", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
