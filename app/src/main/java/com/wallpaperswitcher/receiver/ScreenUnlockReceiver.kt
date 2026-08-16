package com.wallpaperswitcher.receiver

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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

        // USER_PRESENT fires after any unlock: fingerprint, face, PIN, swipe
        // No need for SCREEN_ON - it causes double-switch on fingerprint unlock
        if (action == Intent.ACTION_USER_PRESENT) {
            doSwitch(context)
        }
    }

    private fun doSwitch(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // goAsync() has ~10s timeout; wrap with safety margin
                withTimeout(8_000L) {
                    val db = AppDatabase.getInstance(context)
                    val enabled = db.settingsDao().getBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)
                    Log.d(TAG, "unlockEnabled=$enabled")
                    if (enabled) {
                        // USER_PRESENT fires while the keyguard is still clearing.
                        // Wait a moment so the wallpaper becomes visible again;
                        // otherwise the engine skips the switch as "not visible".
                        // Also retry: right after a process restart the engine
                        // may not have re-created itself yet.
                        var attempts = 0
                        while (!LiveWallpaperService.engineRunning && attempts < 3) {
                            delay(400L)
                            attempts++
                        }
                        if (!LiveWallpaperService.engineRunning) {
                            Log.d(TAG, "Engine not running after retries, skip unlock switch")
                            return@withTimeout
                        }
                        val switchIntent = Intent(LiveWallpaperService.ACTION_SWITCH).apply {
                            putExtra(LiveWallpaperService.EXTRA_SOURCE, LiveWallpaperService.SOURCE_UNLOCK)
                        }
                        switchIntent.setPackage(context.packageName)
                        context.sendBroadcast(switchIntent)
                        Log.d(TAG, "Switch broadcast sent (unlock)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Switch failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
