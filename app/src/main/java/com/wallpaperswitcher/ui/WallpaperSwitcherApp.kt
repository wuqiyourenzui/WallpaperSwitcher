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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.ui.screens.*
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperSwitcherApp(viewModel: WallpaperViewModel) {
    val context = LocalContext.current
    // Save the current screen so opening the system live-wallpaper picker
    // (or any activity recreation) returns to the same page instead of
    // falling back to the home/group list.
    var currentScreen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.Home)
    }

    // Toast 消息
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Long instructions (e.g. which button to tap in the system live-wallpaper
    // dialog). A normal toast is gone in ~2s - and the system dialog covers the
    // app immediately afterwards - so these are shown as a non-touchable
    // floating bubble for several seconds, with a repeated toast as fallback
    // when the overlay permission is not granted.
    LaunchedEffect(Unit) {
        viewModel.hintMessage.collectLatest { msg ->
            if (!com.wallpaperswitcher.wallpaper.HintOverlay.show(context, msg)) {
                com.wallpaperswitcher.wallpaper.HintOverlay.showLongToast(context, msg)
            }
        }
    }

    // Drop the hint the moment we come back to the foreground: leaving the
    // system live-wallpaper dialog (after tapping 设为壁纸, or by cancelling)
    // resumes this activity, and the instruction is no longer useful then.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                com.wallpaperswitcher.wallpaper.HintOverlay.dismiss()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Show the actual group name in the detail page's top bar instead of a
    // generic "分组详情" label.
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when (currentScreen) {
                            is Screen.Home -> "壁纸切换"
                            is Screen.GroupDetail -> selectedGroup?.name ?: "分组详情"
                            is Screen.Settings -> "设置"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    if (currentScreen !is Screen.Home) {
                        IconButton(onClick = { currentScreen = Screen.Home }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                tonalElevation = 0.dp
            ) {
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NavigationBarItem(
                    selected = currentScreen is Screen.Home,
                    onClick = { currentScreen = Screen.Home },
                    icon = {
                        Icon(
                            if (currentScreen is Screen.Home) Icons.Filled.Home
                            else Icons.Outlined.Home, "首页"
                        )
                    },
                    label = { Text("首页") },
                    colors = navColors
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
                    label = { Text("设置") },
                    colors = navColors
                )
            }
        }
    ) { padding ->
        // Handle system back button for non-home screens
        if (currentScreen !is Screen.Home) {
            BackHandler { currentScreen = Screen.Home }
        }

        Box(modifier = Modifier.padding(padding)) {
            // No transition animation at all: switching screens (tab flips AND
            // group navigation) swaps instantly. The animated crossfade/slide
            // kept both full screens composed and animated during the switch,
            // which stuttered on some devices; an instant swap renders only
            // the target screen for the first frame.
            when (val screen = currentScreen) {
                is Screen.Home -> HomeScreen(
                    viewModel = viewModel,
                    onGroupClick = { groupId ->
                        viewModel.selectGroup(groupId)
                        currentScreen = Screen.GroupDetail(groupId)
                    }
                )
                is Screen.GroupDetail -> {
                    // Leaving the group screen releases its full media list: the
                    // detail screen loads EVERY media of the group (no paging, by
                    // design - the fast scroller needs the whole list), and until
                    // now that list stayed in memory until the process died or
                    // another group was opened.
                    DisposableEffect(screen.groupId) {
                        onDispose { viewModel.selectGroup(null) }
                    }
                    GroupDetailScreen(
                        viewModel = viewModel,
                        groupId = screen.groupId,
                        onBack = { currentScreen = Screen.Home }
                    )
                }
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

private val ScreenSaver = Saver<Screen, String>(
    save = { screen ->
        when (screen) {
            is Screen.Home -> "home"
            is Screen.GroupDetail -> "group:${screen.groupId}"
            is Screen.Settings -> "settings"
        }
    },
    restore = { value ->
        when {
            value == "home" -> Screen.Home
            value == "settings" -> Screen.Settings
            value.startsWith("group:") ->
                value.removePrefix("group:").toLongOrNull()?.let { Screen.GroupDetail(it) }
                    ?: Screen.Home
            else -> Screen.Home
        }
    }
)
