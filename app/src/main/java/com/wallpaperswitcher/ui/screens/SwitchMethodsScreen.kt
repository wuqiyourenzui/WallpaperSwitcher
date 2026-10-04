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
 * settings_page_switch：设置主界面的一个子页（外壳见 [SettingsPageScaffold]）。
 * 内容与原设置页的同一分区逐字一致，只是搬进了自己的页面。
 */
@Composable
fun SwitchMethodsScreen(
    viewModel: WallpaperViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val serviceEnabled = state.serviceEnabled
    val globalIntervalMs = state.globalIntervalMs
    val unlockSwitchEnabled = state.unlockSwitchEnabled
    val doubleTapEnabled = state.doubleTapEnabled
    val lockTimerEnabled = state.lockTimerEnabled
    val lockIntervalMs = state.lockIntervalMs
    val switchFadeEnabled = state.switchFadeEnabled
    val switchTransition = state.switchTransition
    val videoSoundEnabled = state.videoSoundEnabled
    val videoPlayToEnd = state.videoPlayToEnd

    var showIntervalDialog by remember { mutableStateOf(false) }
    var showLockIntervalDialog by remember { mutableStateOf(false) }

    SettingsPageScaffold(onBack = onBack, modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_page_switch)) {
                SettingsSwitchItem(
                    icon = Icons.Outlined.PlayCircle,
                    title = stringResource(R.string.settings_timed_switch),
                    subtitle = stringResource(R.string.settings_timed_switch_hint),
                    checked = serviceEnabled,
                    onCheckedChange = { viewModel.toggleService(it) }
                )

                if (serviceEnabled) {
                    // Only meaningful while the timer runs: hidden otherwise so the
                    // list stays short (the value is kept, turning the timer back on
                    // restores it).
                    Row(
                        modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showIntervalDialog = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Timer,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_switch_interval))
                            Text(
                                formatInterval(globalIntervalMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            stringResource(R.string.action_modify),
                            style = MaterialTheme.typography.labelLarge,
                            color = LocalAccentColor.current
                        )
                    }
                }

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsSwitchItem(
                    icon = Icons.Outlined.LockOpen,
                    title = stringResource(R.string.settings_unlock_switch),
                    subtitle = stringResource(R.string.settings_unlock_switch_hint),
                    checked = unlockSwitchEnabled,
                    onCheckedChange = { viewModel.toggleUnlockSwitch(it) }
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsSwitchItem(
                    icon = Icons.Outlined.TouchApp,
                    title = stringResource(R.string.settings_double_tap),
                    subtitle = stringResource(R.string.settings_double_tap_hint),
                    checked = doubleTapEnabled,
                    onCheckedChange = { viewModel.toggleDoubleTap(it) }
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsSwitchItem(
                    icon = Icons.Outlined.MusicNote,
                    title = stringResource(R.string.settings_video_sound),
                    subtitle = stringResource(R.string.settings_video_sound_hint),
                    checked = videoSoundEnabled,
                    onCheckedChange = { viewModel.setVideoSoundEnabled(it) }
                )
        }
    }

    if (showIntervalDialog) {
        IntervalPickerDialog(
            currentMs = globalIntervalMs,
            onDismiss = { showIntervalDialog = false },
            onSelect = { viewModel.setGlobalInterval(it); showIntervalDialog = false }
        )
    }
    if (showLockIntervalDialog) {
        IntervalPickerDialog(
            currentMs = lockIntervalMs,
            onDismiss = { showLockIntervalDialog = false },
            onSelect = { viewModel.setLockInterval(it); showLockIntervalDialog = false }
        )
    }
}
