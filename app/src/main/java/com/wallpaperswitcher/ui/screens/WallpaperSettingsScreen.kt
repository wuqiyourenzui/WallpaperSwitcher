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
 * settings_page_wallpaper：设置主界面的一个子页（外壳见 [SettingsPageScaffold]）。
 * 内容与原设置页的同一分区逐字一致，只是搬进了自己的页面。
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun WallpaperSettingsScreen(
    viewModel: WallpaperViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val globalSwitchMode = state.globalSwitchMode
    val globalScaleMode = state.globalScaleMode
    val clarityMode = state.clarityMode
    val rotateMismatchEnabled = state.rotateMismatchEnabled
    val rotateMismatchClockwise = state.rotateMismatchClockwise

    SettingsPageScaffold(onBack = onBack, modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_page_wallpaper)) {
                // 排版：选项组统一"标签 + 选项同一行"（与下方stringResource(R.string.settings_rotate_direction)一致）。
                // 之前标签独占一行、选项另起一行，每组多花约 90px，设置页要滚很久。
                // 宽度不够时选项换到第二行（见 SettingsChoiceRow），否则长语言会把
                // 标签挤成 0 宽。
                SettingsChoiceRow(
                    icon = Icons.Outlined.Shuffle,
                    label = stringResource(R.string.settings_switch_mode)
                ) {
                    // Emitted straight into the row's FlowRow (no wrapping Row):
                    // a wrapping Row would hand the FlowRow ONE item, so an
                    // over-long option would be squeezed into its own chip instead
                    // of moving to the next line.
                    SwitchMode.entries.forEach { mode ->
                        SettingsOptionChip(
                            selected = globalSwitchMode == mode,
                            onClick = { viewModel.setGlobalSwitchMode(mode) },
                            label = when (mode) { SwitchMode.RANDOM -> stringResource(R.string.switch_mode_random); SwitchMode.SEQUENTIAL -> stringResource(R.string.switch_mode_sequential); SwitchMode.SHUFFLE -> stringResource(R.string.switch_mode_shuffle) }
                        )
                    }
                }

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // Scale mode (same "label + options in one row" layout as 切换模式).
                SettingsChoiceRow(
                    icon = Icons.Outlined.AspectRatio,
                    label = stringResource(R.string.settings_scale_mode)
                ) {
                    ScaleMode.entries.forEach { mode ->
                        SettingsOptionChip(
                            selected = globalScaleMode == mode,
                            onClick = { viewModel.setGlobalScaleMode(mode) },
                            label = when (mode) { ScaleMode.FILL -> stringResource(R.string.scale_mode_fill); ScaleMode.FIT -> stringResource(R.string.scale_mode_fit); ScaleMode.STRETCH -> stringResource(R.string.scale_mode_stretch) }
                        )
                    }
                }

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // Clarity enhancement for low-res media (default "auto" keeps the
                // current behavior; the option lets users tune it on-device).
                SettingsChoiceRow(
                    icon = Icons.Outlined.HighQuality,
                    label = stringResource(R.string.settings_clarity)
                ) {
                    listOf("auto" to stringResource(R.string.clarity_auto), "off" to stringResource(R.string.clarity_off), "strong" to stringResource(R.string.clarity_strong)).forEach { (mode, label) ->
                        SettingsOptionChip(
                            selected = clarityMode == mode,
                            onClick = { viewModel.setClarityMode(mode) },
                            label = label
                        )
                    }
                }

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsSwitchItem(
                    icon = Icons.AutoMirrored.Outlined.RotateRight,
                    title = stringResource(R.string.settings_auto_rotate),
                    subtitle = stringResource(R.string.settings_auto_rotate_hint),
                    checked = rotateMismatchEnabled,
                    onCheckedChange = { viewModel.toggleRotateMismatch(it) }
                )

                if (rotateMismatchEnabled) {
                    SettingsChoiceRow(
                        label = stringResource(R.string.settings_rotate_direction),
                        labelStyle = MaterialTheme.typography.bodyMedium,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    ) {
                        SettingsOptionChip(
                            selected = rotateMismatchClockwise,
                            onClick = { viewModel.setRotateMismatchClockwise(true) },
                            label = stringResource(R.string.rotate_clockwise)
                        )
                        SettingsOptionChip(
                            selected = !rotateMismatchClockwise,
                            onClick = { viewModel.setRotateMismatchClockwise(false) },
                            label = stringResource(R.string.rotate_counter_clockwise)
                        )
                    }
                }
        }
    }
}
