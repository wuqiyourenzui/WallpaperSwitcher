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
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import com.wallpaperswitcher.service.WallpaperSwitchService
import com.wallpaperswitcher.ui.theme.WallpaperSwitcherTheme
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val vm: WallpaperViewModel = viewModel()
            val themeColor by vm.themeColor.collectAsStateWithLifecycle()

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
            WallpaperSwitcherTheme(themeColorHex = themeColor) {
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
        // Hide the floating double-tap button the moment the app opens, so it
        // never lingers over the UI during the window transition.
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
        // Leaving the UI: the wallpaper may be visible again (home screen), in
        // which case the audio resumes and the floating button comes back.
        LiveWallpaperService.setAppForeground(false)
        // The desktop timer idles after its one switch while the app is in the
        // foreground (see the app-foreground gate in WallpaperSwitchService):
        // wake it now so the desktop resumes its normal interval immediately
        // instead of waiting for the loop's fallback re-check.
        WallpaperSwitchService.poke(this)
    }
}
