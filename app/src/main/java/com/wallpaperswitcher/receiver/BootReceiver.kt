package com.wallpaperswitcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.service.WallpaperSwitchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Boot completed receiver.
 * Starts the wallpaper switch service if enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    val serviceEnabled = db.settingsDao()
                        .getBool(SettingsKeys.SERVICE_ENABLED, false)
                    if (serviceEnabled) {
                        WallpaperSwitchService.start(context)
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Boot start failed", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
