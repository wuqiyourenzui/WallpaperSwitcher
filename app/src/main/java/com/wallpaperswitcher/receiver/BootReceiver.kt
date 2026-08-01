package com.wallpaperswitcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.service.GestureOverlayService
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启动接收器
 * 启动定时切换服务和双击手势覆盖层（根据设置）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)

                    // 启动定时切换服务
                    val serviceEnabled = db.settingsDao()
                        .getBool(SettingsKeys.SERVICE_ENABLED, false)
                    if (serviceEnabled) {
                        WallpaperSwitchService.start(context)
                    }

                    // 启动双击手势覆盖层
                    val doubleTapEnabled = db.settingsDao()
                        .getBool(SettingsKeys.DOUBLE_TAP_ENABLED, true)
                    if (doubleTapEnabled) {
                        GestureOverlayService.start(context)
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "开机启动失败", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
