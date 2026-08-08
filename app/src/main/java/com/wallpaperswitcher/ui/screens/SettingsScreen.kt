package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.ui.theme.parseHexColor
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: WallpaperViewModel) {
    val serviceEnabled by viewModel.serviceEnabled.collectAsStateWithLifecycle()
    val doubleTapEnabled by viewModel.doubleTapEnabled.collectAsStateWithLifecycle()
    val unlockSwitchEnabled by viewModel.unlockSwitchEnabled.collectAsStateWithLifecycle()
    val globalIntervalMs by viewModel.globalIntervalMs.collectAsStateWithLifecycle()
    val globalSwitchMode by viewModel.globalSwitchMode.collectAsStateWithLifecycle()
    val globalScaleMode by viewModel.globalScaleMode.collectAsStateWithLifecycle()
    val themeColor by viewModel.themeColor.collectAsStateWithLifecycle()

    var showIntervalDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Global wallpaper settings
        SettingsSection(title = "壁纸设置") {
            // Interval
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showIntervalDialog = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("切换间隔", style = MaterialTheme.typography.bodyLarge)
                    Text(formatInterval(globalIntervalMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Switch mode
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shuffle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text("切换模式", modifier = Modifier.weight(1f))
                SwitchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = globalSwitchMode == mode,
                        onClick = { viewModel.setGlobalSwitchMode(mode) },
                        label = { Text(when (mode) { SwitchMode.RANDOM -> "随机"; SwitchMode.SEQUENTIAL -> "顺序"; SwitchMode.SHUFFLE -> "洗牌" }) },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Scale mode
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AspectRatio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text("缩放模式", modifier = Modifier.weight(1f))
                ScaleMode.entries.forEach { mode ->
                    FilterChip(
                        selected = globalScaleMode == mode,
                        onClick = { viewModel.setGlobalScaleMode(mode) },
                        label = { Text(when (mode) { ScaleMode.FILL -> "填充"; ScaleMode.FIT -> "适应"; ScaleMode.STRETCH -> "拉伸" }) },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Switch triggers: timer, double tap and unlock live in one section and
        // are fully independent - enabling one never disables another.
        SettingsSection(title = "切换方式") {
            SettingsSwitchItem(
                icon = Icons.Outlined.PlayCircle,
                title = "定时切换",
                subtitle = "按设定间隔自动切换壁纸",
                checked = serviceEnabled,
                onCheckedChange = { viewModel.toggleService(it) }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchItem(
                icon = Icons.Outlined.LockOpen,
                title = "解锁切换",
                subtitle = "每次解锁屏幕时自动切换壁纸",
                checked = unlockSwitchEnabled,
                onCheckedChange = { viewModel.toggleUnlockSwitch(it) }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchItem(
                icon = Icons.Outlined.TouchApp,
                title = "双击切换",
                subtitle = "双击屏幕切换壁纸（需设置动态壁纸）",
                checked = doubleTapEnabled,
                onCheckedChange = { viewModel.toggleDoubleTap(it) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Usage guide
        SettingsSection(title = "使用指南") {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = "如何使用",
                subtitle = buildString {
                    appendLine("1. 创建分组，添加图片或视频。")
                    appendLine("2. 长按图片/视频 → 设为壁纸。")
                    appendLine("3. 在「切换方式」中开启定时、双击或解锁切换。")
                }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsInfoItem(
                icon = Icons.Outlined.Battery1Bar,
                title = "电量消耗",
                subtitle = "使用协程调度，电量消耗极低。"
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Theme
        SettingsSection(title = "外观") {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showColorDialog = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("主题颜色", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (themeColor.isEmpty()) "跟随系统" else themeColor,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val previewColor = if (themeColor.isNotEmpty()) parseHexColor(themeColor)
                    else MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(previewColor ?: MaterialTheme.colorScheme.primary)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // About
        SettingsSection(title = "关于") {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = "壁纸切换 v1.0",
                subtitle = "轻量级壁纸自动切换工具，支持分组管理和多种切换模式。"
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }

    // Interval picker dialog
    if (showIntervalDialog) {
        IntervalPickerDialog(
            currentMs = globalIntervalMs,
            onDismiss = { showIntervalDialog = false },
            onSelect = { viewModel.setGlobalInterval(it); showIntervalDialog = false }
        )
    }

    if (showColorDialog) {
        ThemeColorPickerDialog(
            currentHex = themeColor,
            onDismiss = { showColorDialog = false },
            onSelect = { viewModel.setThemeColor(it); showColorDialog = false }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (checked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsInfoItem(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight
            )
        }
    }
}

@Composable
fun IntervalPickerDialog(
    currentMs: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit
) {
    val options = listOf(
        10_000L to "10 秒",
        30_000L to "30 秒",
        60_000L to "1 分钟",
        300_000L to "5 分钟",
        900_000L to "15 分钟",
        1800_000L to "30 分钟",
        3600_000L to "1 小时",
        7200_000L to "2 小时",
        21600_000L to "6 小时",
        43200_000L to "12 小时",
        86400_000L to "24 小时"
    )
    var customValue by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换间隔") },
        text = {
            Column(modifier = Modifier.verticalScroll(scrollState)) {
                options.forEach { (ms, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(ms) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = currentMs == ms, onClick = { onSelect(ms) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
                Divider(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                // Custom input
                Text(
                    "自定义时间",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customValue,
                        onValueChange = { customValue = it.filter { c -> c.isDigit() } },
                        label = { Text("秒数") },
                        placeholder = { Text("例如: 45") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val seconds = customValue.toLongOrNull() ?: 0L
                            if (seconds >= 10) {
                                onSelect(seconds * 1000L)
                            }
                        },
                        enabled = (customValue.toLongOrNull() ?: 0L) >= 10
                    ) {
                        Text("确定")
                    }
                }
                Text(
                    "最少 10 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun ThemeColorPickerDialog(
    currentHex: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    // Predefined theme colors
    val colors = listOf(
        "" to "跟随系统",
        "#6750A4" to "紫罗兰",
        "#006C51" to "翡翠绿",
        "#006E1C" to "翠绿",
        "#0061A4" to "海洋蓝",
        "#006874" to "青色",
        "#984061" to "玫瑰红",
        "#7D5260" to "棕色",
        "#B82E2E" to "红色",
        "#E65100" to "橙色",
        "#F9A825" to "琥珀",
        "#33691E" to "深绿",
        "#01579B" to "深蓝",
        "#4A148C" to "紫色",
        "#880E4F" to "玫红",
        "#BF360C" to "深橙",
        "#263238" to "蓝灰",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题颜色") },
        text = {
            Column {
                Text(
                    "选择主题颜色，重启后生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                // Grid of color circles
                val chunked = colors.chunked(4)
                chunked.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { (hex, name) ->
                            val isSelected = hex == currentHex
                            val color = if (hex.isEmpty()) MaterialTheme.colorScheme.primary
                                else parseHexColor(hex) ?: Color.Gray
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { onSelect(hex) }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .then(
                                            if (isSelected) Modifier.border(
                                                3.dp,
                                                MaterialTheme.colorScheme.onSurface,
                                                CircleShape
                                            ) else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                        }
                        // Fill empty slots in last row
                        repeat(4 - row.size) {
                            Spacer(modifier = Modifier.width(40.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
