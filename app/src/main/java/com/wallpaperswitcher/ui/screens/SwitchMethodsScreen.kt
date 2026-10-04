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
import com.wallpaperswitcher.ui.theme.HiOptionPickerRow
import com.wallpaperswitcher.ui.theme.HiOptionSpec
import com.wallpaperswitcher.ui.theme.LocalAccentColor
import com.wallpaperswitcher.ui.theme.hasHiOption
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
    val switchTransition = state.switchTransition
    val videoSoundEnabled = state.videoSoundEnabled
    val videoPlayToEnd = state.videoPlayToEnd
    val floatingButtonEnabled = state.floatingButtonEnabled

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

                // 悬浮切换按钮：开关在这里（用户要求），外观在它自己的子页里。
                SettingsSwitchItem(
                    icon = Icons.Outlined.AdsClick,
                    title = stringResource(R.string.settings_floating_button),
                    subtitle = stringResource(R.string.settings_floating_button_hint),
                    checked = floatingButtonEnabled,
                    onCheckedChange = { viewModel.toggleFloatingButton(it) }
                )


                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // 过渡动画: fade / slide / zoom / none
                // (SettingsKeys.SWITCH_TRANSITION_*)。选 "无" 时 ViewModel 会顺手把
                // 旧的 switch_fade_enabled 兼容位写成 off，其它值写成 on —— 这套兼容
                // 逻辑在 viewModel.setSwitchTransition 里，这里只给出用户选的那个值。
                // 说明文字（settings_transition_hint）保留在这一行里，跟着标题一起显示。
                HiOptionPickerRow(
                    title = stringResource(R.string.settings_transition),
                    subtitle = stringResource(R.string.settings_transition_hint),
                    icon = Icons.Outlined.Animation,
                    options = transitionOptions(),
                    selectedKey = transitionKeyOf(switchTransition),
                    onSelect = { viewModel.setSwitchTransition(it) },
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

    SettingsSwitchItem(
                        icon = Icons.Outlined.Movie,
                        title = stringResource(R.string.settings_video_play_to_end),
                        subtitle = stringResource(R.string.settings_video_play_to_end_hint),
                        checked = videoPlayToEnd,
                        onCheckedChange = { viewModel.setVideoPlayToEnd(it) }
                    )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))


                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // 视频播完再切：定时切换不再打断长视频。

        Spacer(modifier = Modifier.height(8.dp))

        // 锁屏切换：与桌面的定时/双击/解锁完全独立（双屏思路）。只从「应用位置」
        // 含锁屏的分组里取图，并且有自己的间隔 —— 桌面锁屏各换各的。
        SettingsSection(title = stringResource(R.string.settings_section_lock)) {
            SettingsSwitchItem(
                icon = Icons.Outlined.Timer,
                title = stringResource(R.string.settings_lock_timer),
                subtitle = stringResource(R.string.settings_lock_timer_hint),
                checked = lockTimerEnabled,
                onCheckedChange = { viewModel.toggleLockTimer(it) }
            )

            AnimatedVisibility(
                visible = lockTimerEnabled,
                enter = fadeIn(HiMotion.enter()) + expandVertically(HiMotion.enter()),
                exit = fadeOut(HiMotion.exit()) + shrinkVertically(HiMotion.exit()),
            ) {
                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLockIntervalDialog = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_lock_interval))
                        Text(
                            formatInterval(lockIntervalMs),
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
        }
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

// --- 选项表（纯逻辑，单测见 ui/theme/HiOptionLogicTest.kt） -------------------

/** 过渡动画面板：key 就是存进 [SettingsKeys.SWITCH_TRANSITION] 的字符串。 */
internal fun transitionOptions(): List<HiOptionSpec> = listOf(
    HiOptionSpec(SettingsKeys.SWITCH_TRANSITION_FADE, R.string.transition_fade),
    HiOptionSpec(SettingsKeys.SWITCH_TRANSITION_SLIDE, R.string.transition_slide),
    HiOptionSpec(SettingsKeys.SWITCH_TRANSITION_ZOOM, R.string.transition_zoom),
    HiOptionSpec(SettingsKeys.SWITCH_TRANSITION_NONE, R.string.transition_none),
)

/**
 * 存的过渡动画 → 面板认得的 key。
 *
 * 渲染端只把 "fade"/"slide"/"zoom" 当成有动画，其余值（例如从备份文件里恢复进来的
 * 陌生字符串）都是**没有过渡**（见 WallpaperRenderer 对 transitionMode 的判断），
 * 所以未知值归一化到"无"：行右侧说的就是用户实际会看到的效果。
 */
internal fun transitionKeyOf(stored: String): String =
    if (hasHiOption(transitionOptions(), stored)) stored else SettingsKeys.SWITCH_TRANSITION_DEFAULT
