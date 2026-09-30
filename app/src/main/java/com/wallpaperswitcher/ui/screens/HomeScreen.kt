package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.wallpaperswitcher.data.WallpaperGroup
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import com.wallpaperswitcher.wallpaper.LiveWallpaperService

/** How often the home screen re-checks whether the live wallpaper engine is alive. */
private const val ENGINE_STATE_POLL_MS = 5_000L

@Composable
fun HomeScreen(
    viewModel: WallpaperViewModel,
    onGroupClick: (Long) -> Unit
) {
    // Single combined state: entering the home screen subscribes to ONE flow
    // and recomposes once, instead of once per Room-backed flow (the media
    // count query + groups + service toggle used to emit separately, which
    // recomposed the whole screen several times during the tab transition).
    val homeUiState by viewModel.homeUiState.collectAsStateWithLifecycle()
    val groups = homeUiState.groups
    val mediaCounts = homeUiState.mediaCounts
    val serviceEnabled = homeUiState.serviceEnabled
    val lockTimerEnabled = homeUiState.lockTimerEnabled
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 服务总开关卡片
        ServiceControlCard(
            serviceEnabled = serviceEnabled,
            lockTimerEnabled = lockTimerEnabled,
            onToggle = { viewModel.toggleService(it) },
            onSwitchNow = { viewModel.switchNow() }
        )

        // The live wallpaper engine is what actually receives timer /
        // double-tap / unlock switches. If it is not running (wallpaper never
        // applied, or a different live wallpaper was selected), switching
        // appears dead. Surface that state here instead of silently failing.
        // NOTE the text is precise about what really needs the engine: the
        // timed switch also works WITHOUT it (static wallpapers); only the
        // double-tap gesture needs the live wallpaper window to receive touches.
        // Refresh on every ON_RESUME (e.g. after returning from the system
        // live-wallpaper picker) so the warning disappears once the engine
        // actually starts.
        val lifecycleOwner = LocalLifecycleOwner.current
        var engineRunning by remember { mutableStateOf(LiveWallpaperService.engineRunning) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    engineRunning = LiveWallpaperService.engineRunning
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        // The engine can also die (or come back) while the app stays in the
        // foreground - MIUI kills the wallpaper process in the background. A
        // slow poll while this screen is RESUMED keeps the warning card honest
        // instead of showing a stale state until the user leaves and returns.
        LaunchedEffect(lifecycleOwner, serviceEnabled, lockTimerEnabled) {
            if (!serviceEnabled && !lockTimerEnabled) return@LaunchedEffect
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val running = LiveWallpaperService.engineRunning
                    if (running != engineRunning) engineRunning = running
                    delay(ENGINE_STATE_POLL_MS)
                }
            }
        }
        if (serviceEnabled && !engineRunning) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "动态壁纸引擎未运行：定时切换仍可用（静态壁纸模式），但双击切换不可用；" +
                            "如需动态效果，请在系统壁纸设置中选中「壁纸切换」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // A group whose 应用位置 includes 桌面 is what feeds the home screen. If
        // none exists (every group is lock-only), the desktop shows the app's
        // placeholder bitmap - tell the user instead of leaving them puzzled.
        val noHomeGroup = groups.none {
            it.isEnabled && WallpaperTarget.fromName(it.target).includesHome
        }
        if (noHomeGroup && groups.isNotEmpty() && engineRunning) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "没有分组的「应用位置」包含桌面：桌面会显示占位图，" +
                            "请把至少一个分组设为「桌面」或「桌面和锁屏」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 分组列表标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "壁纸分组",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "共 ${groups.size} 个分组",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, "新建", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("新建分组")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (groups.isEmpty()) {
            EmptyGroupsHint()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(groups, key = { it.id }) { group ->
                    GroupCard(
                        group = group,
                        mediaCount = mediaCounts[group.id] ?: 0,
                        onClick = { onGroupClick(group.id) },
                        onToggle = { viewModel.toggleGroupEnabled(group.id, it) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                // Insert on the ViewModel scope so a rotation/recreation
                // mid-insert cannot cancel it and silently drop the group.
                viewModel.createGroupAndOpen(name) { groupId ->
                    onGroupClick(groupId)
                }
            }
        )
    }
}

@Composable
private fun ServiceControlCard(
    serviceEnabled: Boolean,
    lockTimerEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onSwitchNow: () -> Unit
) {
    // Running: colorful primary→secondary tonal gradient. Stopped: a flat,
    // even neutral surface with a thin border - clearly defined but quiet,
    // so the "off" card never looks faded or unfinished.
    val background = if (serviceEnabled) {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.secondaryContainer
            )
        )
    } else {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            )
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = if (serviceEnabled) 3.dp else 0.dp),
        border = if (!serviceEnabled)
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        else
            null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Status dot: filled green when running, hollow
                            // grey when stopped (standby look).
                            if (serviceEnabled) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF34A853))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .border(
                                            2.dp,
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            CircleShape
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                when {
                                    serviceEnabled && lockTimerEnabled -> "桌面 + 锁屏运行中"
                                    serviceEnabled -> "桌面运行中"
                                    lockTimerEnabled -> "锁屏运行中"
                                    else -> "已停止"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (serviceEnabled || lockTimerEnabled)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (serviceEnabled) "壁纸自动切换中，点击下方按钮立即换一张"
                            else if (lockTimerEnabled) "锁屏定时切换中（桌面定时未开启）"
                            else "点击开关，壁纸将自动轮换",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (serviceEnabled)
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                    Switch(
                        checked = serviceEnabled,
                        onCheckedChange = onToggle,
                        thumbContent = {
                            Icon(
                                if (serviceEnabled) Icons.Filled.PlayArrow else Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    )
                }

                // The manual button also works in static mode, and it is useful
                // whenever either timer runs.
                if (serviceEnabled || lockTimerEnabled) {
                    Spacer(modifier = Modifier.height(14.dp))
                    FilledTonalButton(
                        onClick = onSwitchNow,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, "切换", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("立即切换壁纸")
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: WallpaperGroup,
    mediaCount: Int,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (group.isEnabled)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (group.isEnabled)
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        else
            null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标：品牌渐变圆底
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                if (group.isEnabled)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant,
                                if (group.isEnabled)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    // The group "type" column is a legacy artifact (groups mix
                    // images/videos/GIFs now and nothing writes it any more), so
                    // the card icon is a single generic one.
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = if (group.isEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        color = if (group.isEnabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    if (mediaCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        // 媒体数小徽章
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "$mediaCount",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    if (mediaCount > 0) {
                        "共 $mediaCount 个媒体 · ${WallpaperTarget.fromName(group.target).shortLabel}"
                    } else {
                        "暂无媒体，点击添加"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = group.isEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun EmptyGroupsHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Image,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "还没有壁纸分组",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "点击「新建分组」开始添加壁纸",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建壁纸分组") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分组名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "分组内可混合添加图片和视频",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name) },
                enabled = name.isNotBlank()
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
