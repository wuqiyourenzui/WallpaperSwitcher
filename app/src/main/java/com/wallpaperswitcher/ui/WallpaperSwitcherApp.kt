package com.wallpaperswitcher.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wallpaperswitcher.ui.screens.*
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperSwitcherApp(viewModel: WallpaperViewModel) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    // Toast 消息
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentScreen) {
                            is Screen.Home -> "壁纸切换"
                            is Screen.GroupDetail -> "分组详情"
                            is Screen.Settings -> "设置"
                        }
                    )
                },
                navigationIcon = {
                    if (currentScreen !is Screen.Home) {
                        IconButton(onClick = { currentScreen = Screen.Home }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentScreen is Screen.Home,
                    onClick = { currentScreen = Screen.Home },
                    icon = {
                        Icon(
                            if (currentScreen is Screen.Home) Icons.Filled.Home
                            else Icons.Outlined.Home, "首页"
                        )
                    },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = currentScreen is Screen.Settings,
                    onClick = { currentScreen = Screen.Settings },
                    icon = {
                        Icon(
                            if (currentScreen is Screen.Settings) Icons.Filled.Settings
                            else Icons.Outlined.Settings, "设置"
                        )
                    },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        // Handle system back button for non-home screens
        if (currentScreen !is Screen.Home) {
            BackHandler { currentScreen = Screen.Home }
        }

        Box(modifier = Modifier.padding(padding)) {
            when (val screen = currentScreen) {
                is Screen.Home -> HomeScreen(
                    viewModel = viewModel,
                    onGroupClick = { groupId ->
                        viewModel.selectGroup(groupId)
                        currentScreen = Screen.GroupDetail(groupId)
                    }
                )
                is Screen.GroupDetail -> GroupDetailScreen(
                    viewModel = viewModel,
                    groupId = screen.groupId,
                    onBack = { currentScreen = Screen.Home }
                )
                is Screen.Settings -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

sealed class Screen {
    data object Home : Screen()
    data class GroupDetail(val groupId: Long) : Screen()
    data object Settings : Screen()
}
