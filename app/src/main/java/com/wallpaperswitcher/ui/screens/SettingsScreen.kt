package com.wallpaperswitcher.ui.screens

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import com.wallpaperswitcher.wallpaper.LiveWallpaperService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: WallpaperViewModel) {
    val context = LocalContext.current
    val serviceEnabled by viewModel.serviceEnabled.collectAsStateWithLifecycle()
    val doubleTapEnabled by viewModel.doubleTapEnabled.collectAsStateWithLifecycle()
    val unlockSwitchEnabled by viewModel.unlockSwitchEnabled.collectAsStateWithLifecycle()
    val globalIntervalMs by viewModel.globalIntervalMs.collectAsStateWithLifecycle()
    val globalSwitchMode by viewModel.globalSwitchMode.collectAsStateWithLifecycle()
    val globalScaleMode by viewModel.globalScaleMode.collectAsStateWithLifecycle()

    var showIntervalDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Service control
        SettingsSection(title = "服务") {
            SettingsSwitchItem(
                icon = Icons.Outlined.PlayCircle,
                title = "自动切换服务",
                subtitle = "后台定时切换壁纸",
                checked = serviceEnabled,
                onCheckedChange = { viewModel.toggleService(it) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

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

        // Trigger methods
        SettingsSection(title = "触发方式") {
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

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

                        // Set as Live Wallpaper button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        var success = false
                        try {
                            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                                putExtra(
                                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    ComponentName(context, LiveWallpaperService::class.java)
                                )
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            success = true
                        } catch (_: Exception) {}
                        if (!success) {
                            try {
                                val intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                                success = true
                            } catch (_: Exception) {}
                        }
                        if (!success) {
                            try {
                                val intent = Intent(Intent.ACTION_SET_WALLPAPER)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                android.widget.Toast.makeText(context, "请手动设置壁纸：设置 > 壁纸 > 动态壁纸", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Wallpaper,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("设置为动态壁纸", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "双击切换功能需要此设置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Usage guide
        SettingsSection(title = "使用指南") {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = "两种模式",
                subtitle = buildString {
                    appendLine("1. 服务模式：开启「自动切换服务」和「解锁切换」，实现定时/解锁切换。")
                    appendLine("2. 动态壁纸模式：点击上方「设置为动态壁纸」，双击屏幕即可切换。")
                    appendLine("")
                    appendLine("两种模式可以同时使用。")
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换间隔") },
        text = {
            Column {
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
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
