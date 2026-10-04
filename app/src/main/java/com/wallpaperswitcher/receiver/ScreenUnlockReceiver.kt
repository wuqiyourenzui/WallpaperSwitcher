package com.wallpaperswitcher.receiver

import com.wallpaperswitcher.util.AppLog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.getLong
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import com.wallpaperswitcher.util.logCoroutineFailures
import kotlinx.coroutines.CancellationException
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
        AppLog.d(TAG, "Received: $action")

        // USER_PRESENT fires after any unlock: fingerprint, face, PIN, swipe
        // No need for SCREEN_ON - it causes double-switch on fingerprint unlock
        if (action == Intent.ACTION_USER_PRESENT) {
            doSwitch(context)
        }
    }

    private fun doSwitch(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + logCoroutineFailures(TAG)).launch {
            try {
                // goAsync() has ~10s timeout; wrap with safety margin
                withTimeout(8_000L) {
                    val db = AppDatabase.getInstance(context)
                    val enabled = db.settingsDao().getBool(SettingsKeys.UNLOCK_SWITCH_ENABLED, false)
                    AppLog.d(TAG, "unlockEnabled=$enabled")
                    // 一键暂停 (稍后切换) holds the AUTOMATIC triggers too: while
                    // the user asked the wallpaper to stay put, an unlock must
                    // not switch it. A manual tap on the floating button still
                    // works - that is an explicit action.
                    val paused = db.settingsDao()
                        .getLong(SettingsKeys.PAUSE_UNTIL, 0L) > System.currentTimeMillis()
                    if (paused) {
                        AppLog.d(TAG, "Paused, skipping unlock switch")
                    }
                    if (enabled && !paused) {
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
                            // Static wallpaper mode: there is no engine to
                            // broadcast to, so switch the home wallpaper
                            // directly (the timer's static path does the same).
                            AppLog.d(TAG, "Engine not running, switching statically (unlock)")
                            // Respect the per-group rhythm/mode (0 = screen-wide).
                            val groupId = try {
                                com.wallpaperswitcher.engine.GroupSchedulePlan
                                    .nextHomeGroupId(context)
                            } catch (_: Exception) {
                                0L
                            }
                            val applied = WallpaperSwitchService.applyStaticTickNow(
                                context,
                                com.wallpaperswitcher.engine.WallpaperTarget.SLOT_HOME,
                                android.app.WallpaperManager.FLAG_SYSTEM,
                                groupId
                            )
                            AppLog.d(TAG, "Static unlock switch applied=$applied")
                            // Only claim the unlock when something was actually
                            // written: otherwise the timer would treat this tick
                            // as handled and skip its own catch-up, leaving the
                            // wallpaper unchanged.
                            if (applied) {
                                WallpaperSwitchService.notifyUnlockSwitchDispatched(context)
                            }
                            return@withTimeout
                        }
                        // `engineRunning` can be stale (the engine process was
                        // killed in the background). Handing the switch to the
                        // engine instance directly returns whether a REAL engine
                        // accepted it, so a dead flag can no longer make the
                        // timer skip a switch that never happened.
                        // Same group the timer would pick: an unlock switch must
                        // follow the group's own mode too (0 = screen-wide).
                        val groupId = try {
                            com.wallpaperswitcher.engine.GroupSchedulePlan
                                .nextHomeGroupId(context)
                        } catch (_: Exception) {
                            0L
                        }
                        if (LiveWallpaperService.requestSwitchFromOutside(
                                LiveWallpaperService.SOURCE_UNLOCK, groupId
                            )
                        ) {
                            // Tell the timer that "解锁切换" handled this unlock,
                            // so it neither switches a second time nor fires
                            // moments after the unlock switch.
                            WallpaperSwitchService.notifyUnlockSwitchDispatched(context)
                            AppLog.d(TAG, "Switch handed to the engine (unlock)")
                            return@withTimeout
                        }
                        // No live engine instance accepted it: keep the broadcast
                        // path so an engine that is still starting up (or being
                        // recreated) receives the switch as before.
                        AppLog.w(TAG, "Engine flag was stale, falling back to the broadcast")
                        val switchIntent = Intent(LiveWallpaperService.ACTION_SWITCH).apply {
                            putExtra(LiveWallpaperService.EXTRA_SOURCE, LiveWallpaperService.SOURCE_UNLOCK)
                        }
                        switchIntent.setPackage(context.packageName)
                        context.sendBroadcast(switchIntent)
                        // Deliberately do NOT claim the unlock here: this path
                        // only runs because no live engine instance accepted the
                        // switch, so nothing has confirmed the broadcast was
                        // received. Leaving the timer unclaimed lets its own
                        // catch-up cover the unlock if the broadcast was dropped.
                        AppLog.d(TAG, "Switch broadcast sent (unlock, unconfirmed)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Switch failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
