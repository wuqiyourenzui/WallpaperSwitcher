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
        private const val COOLDOWN_MS = 2000L
    }

    @Volatile
    private var lastSwitchTimeMs = 0L

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received: $action")

        // Cooldown: ACTION_SCREEN_ON and ACTION_USER_PRESENT can fire in quick succession
        val now = System.currentTimeMillis()
        if (now - lastSwitchTimeMs < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown active, skip")
            return
        }

        if (action == Intent.ACTION_USER_PRESENT) {
            lastSwitchTimeMs = now
            doSwitch(context)
        } else if (action == Intent.ACTION_SCREEN_ON) {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardLocked) {
                lastSwitchTimeMs = now
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
