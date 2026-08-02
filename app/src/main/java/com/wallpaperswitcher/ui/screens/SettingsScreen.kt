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
        SettingsSection(title = "Service") {
            SettingsSwitchItem(
                icon = Icons.Outlined.PlayCircle,
                title = "Auto Switch Service",
                subtitle = "Timed wallpaper switching in background",
                checked = serviceEnabled,
                onCheckedChange = { viewModel.toggleService(it) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Global wallpaper settings
        SettingsSection(title = "Wallpaper Settings") {
            // Interval
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showIntervalDialog = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Switch Interval", style = MaterialTheme.typography.bodyLarge)
                    Text(formatInterval(globalIntervalMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Switch mode
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Shuffle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text("Switch Mode", modifier = Modifier.weight(1f))
                SwitchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = globalSwitchMode == mode,
                        onClick = { viewModel.setGlobalSwitchMode(mode) },
                        label = { Text(when (mode) { SwitchMode.RANDOM -> "Random"; SwitchMode.SEQUENTIAL -> "Seq"; SwitchMode.SHUFFLE -> "Shuffle" }) },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Scale mode
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AspectRatio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text("Scale Mode", modifier = Modifier.weight(1f))
                ScaleMode.entries.forEach { mode ->
                    FilterChip(
                        selected = globalScaleMode == mode,
                        onClick = { viewModel.setGlobalScaleMode(mode) },
                        label = { Text(when (mode) { ScaleMode.FILL -> "Fill"; ScaleMode.FIT -> "Fit"; ScaleMode.STRETCH -> "Stretch" }) },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Trigger methods
        SettingsSection(title = "Trigger") {
            SettingsSwitchItem(
                icon = Icons.Outlined.LockOpen,
                title = "Switch on Unlock",
                subtitle = "Switch wallpaper every time you unlock the screen",
                checked = unlockSwitchEnabled,
                onCheckedChange = { viewModel.toggleUnlockSwitch(it) }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSwitchItem(
                icon = Icons.Outlined.TouchApp,
                title = "Double Tap Switch",
                subtitle = "Double tap screen to switch (needs Live Wallpaper)",
                checked = doubleTapEnabled,
                onCheckedChange = { viewModel.toggleDoubleTap(it) }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            // Set as Live Wallpaper button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        try {
                            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                                putExtra(
                                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    ComponentName(context, LiveWallpaperService::class.java)
                                )
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback: open wallpaper picker
                            try {
                                val intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
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
                    Text("Set as Live Wallpaper", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Required for double-tap to work",
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
        SettingsSection(title = "Guide") {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = "Two Modes",
                subtitle = buildString {
                    appendLine("1. Service Mode: Enable 'Auto Switch Service' and 'Switch on Unlock' for timed/unlock switching.")
                    appendLine("2. Live Wallpaper Mode: Tap 'Set as Live Wallpaper' above, then double-tap screen to switch.")
                    appendLine("")
                    appendLine("Both modes can work together.")
                }
            )

            Divider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsInfoItem(
                icon = Icons.Outlined.Battery1Bar,
                title = "Battery",
                subtitle = "Uses coroutines, very low battery usage."
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // About
        SettingsSection(title = "About") {
            SettingsInfoItem(
                icon = Icons.Outlined.Info,
                title = "Wallpaper Switcher v1.0",
                subtitle = "Lightweight wallpaper auto-switch tool with group management and multiple switch modes."
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
        60_000L to "1 min",
        300_000L to "5 min",
        900_000L to "15 min",
        1800_000L to "30 min",
        3600_000L to "1 hour",
        7200_000L to "2 hours",
        21600_000L to "6 hours",
        43200_000L to "12 hours",
        86400_000L to "24 hours"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch Interval") },
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
