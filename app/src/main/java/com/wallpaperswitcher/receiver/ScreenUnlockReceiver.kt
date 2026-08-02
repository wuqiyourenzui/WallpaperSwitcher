package com.wallpaperswitcher.receiver

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Unlock screen receiver.
 * Sends ACTION_SWITCH broadcast to LiveWallpaperService.
 */
class ScreenUnlockReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenUnlockReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received: $action")

        if (action == Intent.ACTION_USER_PRESENT) {
            doSwitch(context)
        } else if (action == Intent.ACTION_SCREEN_ON) {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardLocked) {
                doSwitch(context)
            }
        }
    }

    private fun doSwitch(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val enabled = db.settingsDao().getBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)
                Log.d(TAG, "unlockEnabled=$enabled")
                if (enabled) {
                    val switchIntent = Intent(LiveWallpaperService.ACTION_SWITCH)
                    switchIntent.setPackage(context.packageName)
                    context.sendBroadcast(switchIntent)
                    Log.d(TAG, "Switch broadcast sent")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Switch failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
