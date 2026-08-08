package com.wallpaperswitcher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wallpaperswitcher.wallpaper.LiveWallpaperService
import com.wallpaperswitcher.ui.theme.WallpaperSwitcherTheme
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled in UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestStoragePermission()

        setContent {
            val vm: WallpaperViewModel = viewModel()
            val themeColor by vm.themeColor.collectAsStateWithLifecycle()
            val serviceEnabled by vm.serviceEnabled.collectAsStateWithLifecycle()

            // If the timer switch is enabled but the live wallpaper engine is
            // not running, timer/double-tap/unlock switching cannot work (the
            // engine receives none of the triggers). Tell the user to re-apply
            // the live wallpaper instead of silently staying in a broken state.
            androidx.compose.runtime.LaunchedEffect(serviceEnabled) {
                if (serviceEnabled && !LiveWallpaperService.engineRunning) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "动态壁纸引擎未运行，定时/双击/解锁切换无法生效，请重新设置动态壁纸",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }

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

    private fun requestStoragePermission() {
        // Combine every missing permission into ONE request.
        // Launching the same launcher twice in a row (as the old code did on
        // Android 13+) throws "Only one request can be launched at a time".
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }
}
