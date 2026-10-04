package com.wallpaperswitcher.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.ui.AppLocale
import com.wallpaperswitcher.ui.theme.HiMotion
import com.wallpaperswitcher.ui.theme.LocalAccentColor
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

/**
 * settings_page_scan：设置主界面的一个子页（外壳见 [SettingsPageScaffold]）。
 * 内容与原设置页的同一分区逐字一致，只是搬进了自己的页面。
 */
@Composable
fun FolderScanScreen(
    viewModel: WallpaperViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val autoScanEnabled = state.autoScanEnabled
    val autoScanIntervalMs = state.autoScanIntervalMs
    val autoScanLastRunAt = state.autoScanLastRunAt

    var showAutoScanIntervalDialog by remember { mutableStateOf(false) }

    SettingsPageScaffold(onBack = onBack, modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_page_scan)) {
                SettingsSwitchItem(
                    icon = Icons.Outlined.Sync,
                    title = stringResource(R.string.settings_auto_scan),
                    subtitle = buildString {
                        append(stringResource(R.string.settings_auto_scan_hint))
                        if (autoScanEnabled) {
                            // Direct answer to "is it actually running?" - the
                            // periodic job can be deferred by aggressive ROMs, and
                            // opening the app now catches up (see the ViewModel).
                            append(
                                stringResource(
                                    R.string.settings_auto_scan_last_run,
                                    formatAgo(autoScanLastRunAt)
                                )
                            )
                        }
                    },
                    checked = autoScanEnabled,
                    onCheckedChange = { viewModel.toggleAutoScan(it, autoScanIntervalMs) }
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                Row(
                    modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAutoScanIntervalDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_scan_interval), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            formatInterval(autoScanIntervalMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Same trailing affordance as the other interval rows (切换间隔 /
                    // 锁屏切换间隔): without it this row looked like plain text while
                    // its two siblings showed 修改, which is what made the column of
                    // values look uneven.
                    Text(
                        stringResource(R.string.action_modify),
                        style = MaterialTheme.typography.labelLarge,
                        color = LocalAccentColor.current
                    )
                }
        }
    }

    if (showAutoScanIntervalDialog) {
        AutoScanIntervalDialog(
            currentMs = autoScanIntervalMs,
            onDismiss = { showAutoScanIntervalDialog = false },
            onSelect = { ms ->
                viewModel.toggleAutoScan(autoScanEnabled, ms)
                showAutoScanIntervalDialog = false
            }
        )
    }
}
