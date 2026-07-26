package com.wallpaperswitcher.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

@Composable
fun SettingsScreen(viewModel: WallpaperViewModel) {
    val serviceEnabled by viewModel.serviceEnabled.collectAsStateWithLifecycle()
    val doubleTapEnabled by viewModel.doubleTapEnabled.collectAsStateWithLifecycle()
    val unlockSwitchEnabled by viewModel.unlockSwitchEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 服务控制
        SettingsSection(title = "服务") {
            SettingsSwitchItem(
                icon = Icons.Outlined.PlayCircle,
                title = "自动切换服务",
                subtitle = "后台定时自动切换壁纸",
                checked = serviceEnabled,
                onCheckedChange = { viewModel.toggleService(it) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 触发方式
        SettingsSection(title = "触发方式") {
            SettingsSwitchItem(
                icon = Icons.Outlined.TouchApp,
                title = "双击切换",
                subtitle = "双击屏幕（动态壁纸模式下）切换壁纸",
                checked = doubleTapEnabled,
                onCheckedChange = { viewModel.toggleDoubleTap(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchItem(
                icon = Icons.Outlined.LockOpen,
                title = "解锁切换",
                subtitle = "每次解锁屏幕时自动切换壁纸",
                checked = unlockSwitchEnabled,
                onCheckedChange = { viewModel.toggleUnlockSwitch(it) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 壁纸模式说明
        SettingsSection(title = "使用说明") {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = "两种运行模式",
                subtitle = buildString {
                    appendLine("1. 后台服务模式：在应用内开启，通过前台服务定时切换壁纸。")
                    appendLine("2. 动态壁纸模式：设置 → 壁纸 → 动态壁纸 → 选择「动态壁纸切换」。")
                    appendLine("")
                    appendLine("双击切换功能仅在动态壁纸模式下可用。")
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsInfoItem(
                icon = Icons.Outlined.Battery1Bar,
                title = "省电提示",
                subtitle = "应用使用协程调度，不使用 AlarmManager，电量消耗极低。前台服务通知可在系统设置中关闭。"
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 关于
        SettingsSection(title = "关于") {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = "壁纸切换 v1.0",
                subtitle = "一款轻量级壁纸自动切换工具，支持分组管理、多种切换模式和图片适配方式。"
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
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
