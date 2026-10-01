package com.wallpaperswitcher.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallpaperswitcher.util.AppLog
import com.wallpaperswitcher.wallpaper.FloatingSwitchButton
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.ui.theme.WallpaperSwitcherTheme
import com.wallpaperswitcher.ui.theme.ThemeMode
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val vm: WallpaperViewModel = viewModel()
            val themeColor by vm.themeColor.collectAsStateWithLifecycle()
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()

            // Ask for the notification permission on Android 13+: the timer is
            // a foreground service and its notification is the only visible
            // sign that automatic switching is running.
            val notificationPermissionLauncher =
                androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this@MainActivity, android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                }
            }

            // NOTE: the "engine is not running" warning is shown by the home
            // screen as a permanent card (it re-reads the engine state on every
            // ON_RESUME). A second Toast here only duplicated the same message
            // right on top of it.
            // "system" follows the phone, "light"/"dark" force one mode (see
            // ThemeMode). Anything else falls back to following the system.
            WallpaperSwitcherTheme(
                darkTheme = ThemeMode.from(themeMode).isDark(),
                themeColorHex = themeColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WallpaperSwitcherApp(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Android 15+ can stop long-running foreground services in the
        // background. Self-heal the timer whenever the app is brought back to
        // the foreground (and on every app launch).
        WallpaperSwitchService.ensureRunning(this)
        // Hide the floating button the moment the app opens, so it never lingers
        // over the UI during the window transition.
        //
        // Two paths on purpose: the direct one works even when no wallpaper
        // engine is alive (the button is process-wide and outlives an engine
        // restart, so asking the engine used to leave it on screen), and the
        // engine one also re-evaluates its own bookkeeping.
        FloatingSwitchButton.hideShared()
        LiveWallpaperService.dismissFloatingButtonIfAny()
        // Re-evaluate the floating button (e.g. right after the user granted
        // the overlay permission in system settings) so it appears without
        // having to leave and re-enter the desktop.
        LiveWallpaperService.refreshFloatingButtonIfAny()
    }

    override fun onStart() {
        super.onStart()
        // Back in front of the user: a pending "drop the thumbnails" timer (see
        // onStop) must not fire while the grid is on screen.
        (application as? com.wallpaperswitcher.WallpaperSwitcherApp)
            ?.cancelThumbnailCacheTrim()
        // Earliest chance to get the overlay out of the way: onStart runs before
        // the activity window is drawn, so hiding here removes the button before
        // the user ever sees it over the UI (the engine's visibility callback
        // only arrives after the window animation).
        AppLog.d(TAG, "onStart: hiding the floating button")
        FloatingSwitchButton.hideShared()
        // Tell the wallpaper engine our UI is in front RIGHT NOW: its own
        // visibility callback lags behind the window animation, so without this
        // the video's audio kept playing (and the floating button kept showing)
        // for a moment after the app opened. Also keeps the decode/render work
        // paused while the app covers the wallpaper.
        LiveWallpaperService.setAppForeground(true)
    }

    override fun onStop() {
        super.onStop()
        // The app keeps running in the background for the wallpaper timers, and
        // its decoded thumbnails used to stay cached the whole time (measured
        // 92-115MB with the launcher in front). Drop them a minute after the UI
        // disappears - cancelled in onStart() if the user comes straight back.
        (application as? com.wallpaperswitcher.WallpaperSwitcherApp)
            ?.scheduleThumbnailCacheTrim()
    }

    /**
     * The UI lost the foreground: the wallpaper is in front again as soon as the
     * next screen (the launcher) takes over, and that happens BEFORE [onStop].
     *
     * Measured on the Redmi tablet: `onStop` arrived **1104ms** after HOME (it
     * waits for the app's exit animation), so flipping the engine's
     * app-foreground flag there left the video frozen - or black on a freshly
     * created engine - for that whole second before playback resumed
     * (reports: 「设置视频为壁纸后，返回桌面要黑屏一会才开始播放」、
     * 「进入壁纸软件后，再退出，视频会卡一下再播放」). onPause fires with the
     * transition itself (~50ms), so the decode/audio resume together with the
     * launcher animation instead of after it.
     */
    override fun onPause() {
        super.onPause()
        AppLog.d(TAG, "onPause: UI left the foreground")
        LiveWallpaperService.setAppForeground(false)
        // The desktop timer idles after its one switch while the app is in the
        // foreground (see the app-foreground gate in WallpaperSwitchService):
        // wake it now so the desktop resumes its normal interval immediately
        // instead of waiting for the loop's fallback re-check.
        WallpaperSwitchService.poke(this)
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}
