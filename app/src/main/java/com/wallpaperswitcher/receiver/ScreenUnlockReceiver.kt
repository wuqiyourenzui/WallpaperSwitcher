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
 */
class ScreenUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "收到广播: ${intent.action}")
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    val unlockEnabled = db.settingsDao()
                        .getBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)
                    val serviceEnabled = db.settingsDao()
                        .getBool(SettingsKeys.SERVICE_ENABLED, false)

                    Log.d(TAG, "unlockEnabled=$unlockEnabled, serviceEnabled=$serviceEnabled")

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
    }

    companion object {
        private const val TAG = "ScreenUnlockReceiver"
    }
}
