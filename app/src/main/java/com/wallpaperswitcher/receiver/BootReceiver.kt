package com.wallpaperswitcher.receiver

import com.wallpaperswitcher.util.AppLog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.wallpaperswitcher.data.AppDatabase
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.getBool
import com.wallpaperswitcher.data.setLong
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.util.logCoroutineFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Boot completed receiver.
 * Starts the wallpaper switch service if enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED after a reboot, MY_PACKAGE_REPLACED after an app
        // update: both leave the timer enabled but dead until it is restarted.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO + logCoroutineFailures("BootReceiver")).launch {
                try {
                    // goAsync() gives a ~10s budget (which can shrink on a slow
                    // first boot while the DB migrates); bound our own work with
                    // a safety margin so finish() is always called in time.
                    //
                    // The timeout is caught by its own branch on purpose:
                    // TimeoutCancellationException IS a CancellationException, so
                    // the rethrow branch below used to swallow it - boot then
                    // failed completely silently (no log, no service, and the
                    // timer stayed dead until the app was opened by hand).
                    val startAt = SystemClock.elapsedRealtime()
                    var serviceStarted = false
                    try {
                        withTimeout(8_000L) {
                            serviceStarted = bootStart(context)
                        }
                    } catch (e: TimeoutCancellationException) {
                        AppLog.w(
                            "BootReceiver",
                            "Boot start timed out after ${SystemClock.elapsedRealtime() - startAt}ms; " +
                                "starting the service without the schedule pre-check",
                            e
                        )
                        // The slow part was the settings pre-check / anchor write.
                        // Starting the service is cheap and it self-checks
                        // SERVICE_ENABLED itself, so falling back to a blind start
                        // is better than leaving the timer dead after a reboot.
                        serviceStarted = true
                        context.startForegroundService(
                            Intent(context, WallpaperSwitchService::class.java)
                        )
                    }
                    AppLog.d(
                        "BootReceiver",
                        "Boot start done in ${SystemClock.elapsedRealtime() - startAt}ms (started=$serviceStarted)"
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e("BootReceiver", "Boot start failed", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    /**
     * The boot-time work on the happy path: re-anchor both schedules, then start
     * the service when either timer is enabled.
     *
     * @return true when [Context.startForegroundService] was called.
     */
    private suspend fun bootStart(context: Context): Boolean {
        val db = AppDatabase.getInstance(context)
        val settings = db.settingsDao()
        // The lock timer is independent: the service must also come back when
        // only the lock timer is enabled.
        val needed = settings.getBool(SettingsKeys.SERVICE_ENABLED, false) ||
            settings.getBool(SettingsKeys.LOCK_TIMER_ENABLED, true)
        if (!needed) return false
        // A fresh boot starts a fresh interval: a persisted anchor from before
        // the reboot would otherwise fire an immediate catch-up switch right
        // after boot.
        val now = System.currentTimeMillis()
        settings.setLong(SettingsKeys.TIMER_LAST_SWITCH_WALL_MS, now)
        settings.setLong(SettingsKeys.LOCK_TIMER_LAST_SWITCH_WALL_MS, now)
        // Use explicit component; minSdk 26 = Android 8.0, so
        // startForegroundService() is always available.
        context.startForegroundService(Intent(context, WallpaperSwitchService::class.java))
        return true
    }
}
