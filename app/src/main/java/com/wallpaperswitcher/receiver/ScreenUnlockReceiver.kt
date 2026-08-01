package com.wallpaperswitcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 解锁屏幕时切换壁纸
 */
class ScreenUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) {
            val db = AppDatabase.getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                val unlockEnabled = db.settingsDao()
                    .getBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)
                val serviceEnabled = db.settingsDao()
                    .getBool(SettingsKeys.SERVICE_ENABLED, false)

                if (unlockEnabled && serviceEnabled) {
                    WallpaperSwitchService.switchNow(context)
                }
            }
        }
    }
}