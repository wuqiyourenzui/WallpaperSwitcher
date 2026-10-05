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
 * settings_page_wallpaper：设置主界面的一个子页（外壳见 [SettingsPageScaffold]）。
 * 内容与原设置页的同一分区逐字一致，只是搬进了自己的页面。
 *
 * 每个选项都是图 1 的形态：行右侧显示**当前值**，点一下从下方弹出选项面板
 * （[HiOptionPickerRow]）。选项表和当前值归一化见本文件底部
 * （[switchModeOptions] / [clarityKeyOf] 等）。
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
    val kenBurnsEnabled = state.kenBurnsEnabled

    SettingsPageScaffold(onBack = onBack, modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_page_wallpaper)) {
                // 排版：每组设置都是「图标 + 标题 …… 当前值 + 下拉」一行，点一下从
                // 下方弹出选项面板（图 1 的形态，见 HiOptionPickerRow）。
                // 行高恒定，所以选项多少都不影响列表；选项表见本文件底部的纯函数
                // （switchModeOptions 等），"有哪些选项、当前值是哪一项"可以单测。
                HiOptionPickerRow(
                    title = stringResource(R.string.settings_switch_mode),
                    icon = Icons.Outlined.Shuffle,
                    options = switchModeOptions(),
                    selectedKey = globalSwitchMode.name,
                    // valueOf 的 key 来自上面的选项表本身，但这里仍然用 switchModeOf
                    // （未知 key → null）而不是 valueOf：一个拼错的 key 不应该在
                    // UI 线程抛 IllegalArgumentException 把应用崩掉。
                    onSelect = { key -> switchModeOf(key)?.let(viewModel::setGlobalSwitchMode) },
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                HiOptionPickerRow(
                    title = stringResource(R.string.settings_scale_mode),
                    icon = Icons.Outlined.AspectRatio,
                    options = scaleModeOptions(),
                    selectedKey = globalScaleMode.name,
                    onSelect = { key -> scaleModeOf(key)?.let(viewModel::setGlobalScaleMode) },
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // Clarity enhancement for low-res media (default "auto" keeps the
                // current behavior; the option lets users tune it on-device).
                HiOptionPickerRow(
                    title = stringResource(R.string.settings_clarity),
                    icon = Icons.Outlined.HighQuality,
                    options = clarityOptions(),
                    selectedKey = clarityKeyOf(clarityMode),
                    onSelect = { viewModel.setClarityMode(it) },
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsSwitchItem(
                    icon = Icons.AutoMirrored.Outlined.RotateRight,
                    title = stringResource(R.string.settings_auto_rotate),
                    subtitle = stringResource(R.string.settings_auto_rotate_hint),
                    checked = rotateMismatchEnabled,
                    onCheckedChange = { viewModel.toggleRotateMismatch(it) }
                )

                if (rotateMismatchEnabled) {
                    HiOptionPickerRow(
                        title = stringResource(R.string.settings_rotate_direction),
                        options = rotateDirectionOptions(),
                        selectedKey = rotateDirectionKey(rotateMismatchClockwise),
                        onSelect = { key ->
                            viewModel.setRotateMismatchClockwise(rotateDirectionClockwise(key))
                        },
                        // 子行：缩进一级（行自身已有 16dp 内边距），并保留原来的
                        // 底部留白，让它明显挂在上面那个开关下面。
                        modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                    )
                }

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                SettingsSwitchItem(
                    icon = Icons.Outlined.SlowMotionVideo,
                    title = stringResource(R.string.settings_ken_burns),
                    subtitle = stringResource(R.string.settings_ken_burns_hint),
                    checked = kenBurnsEnabled,
                    onCheckedChange = { viewModel.setKenBurnsEnabled(it) }
                )
        }
    }
}

// --- 选项表（纯逻辑，单测见 ui/theme/HiOptionLogicTest.kt） -------------------
//
// 这些函数只做"枚举/存储值 ↔ 面板选项"的映射，不碰 Compose、不碰 Room，
// 所以可以直接在 JVM 单测里断言：每个可选值都在面板里、每个 key 都能映射回
// 原来的枚举、文案资源没有串行。

/** 切换模式面板：key 用 [SwitchMode] 的名字，因为它就是存进设置里的值。 */
internal fun switchModeOptions(): List<HiOptionSpec> = SwitchMode.entries.map { mode ->
    HiOptionSpec(
        key = mode.name,
        labelRes = when (mode) {
            SwitchMode.RANDOM -> R.string.switch_mode_random
            SwitchMode.SEQUENTIAL -> R.string.switch_mode_sequential
            SwitchMode.SHUFFLE -> R.string.switch_mode_shuffle
        },
    )
}

/** 面板 key → [SwitchMode]；未知 key 返回 null（绝不 `valueOf` 抛出崩在 UI 线程）。 */
internal fun switchModeOf(key: String): SwitchMode? =
    SwitchMode.entries.firstOrNull { it.name == key }

/** 缩放模式面板：key 用 [ScaleMode] 的名字（同 [switchModeOptions]）。 */
internal fun scaleModeOptions(): List<HiOptionSpec> = ScaleMode.entries.map { mode ->
    HiOptionSpec(
        key = mode.name,
        labelRes = when (mode) {
            ScaleMode.FILL -> R.string.scale_mode_fill
            ScaleMode.FIT -> R.string.scale_mode_fit
            ScaleMode.STRETCH -> R.string.scale_mode_stretch
        },
    )
}

/** 面板 key → [ScaleMode]；未知 key 返回 null。 */
internal fun scaleModeOf(key: String): ScaleMode? =
    ScaleMode.entries.firstOrNull { it.name == key }

// 清晰度存的不是枚举而是字符串（SettingsKeys.CLARITY_MODE：auto/off/super）。
internal const val CLARITY_KEY_AUTO = "auto"
internal const val CLARITY_KEY_OFF = "off"
/** 画质增强（超分）: replaces the removed 增强 ("strong") option. */
internal const val CLARITY_KEY_SUPER = "super"

/** 清晰度面板：顺序 = 默认值在前。 */
internal fun clarityOptions(): List<HiOptionSpec> = listOf(
    HiOptionSpec(CLARITY_KEY_AUTO, R.string.clarity_auto),
    HiOptionSpec(CLARITY_KEY_OFF, R.string.clarity_off),
    HiOptionSpec(CLARITY_KEY_SUPER, R.string.settings_quality_enhance),
)

/**
 * 存的清晰度 → 面板认得的 key。
 *
 * 归一化交给渲染端同一份实现（[com.wallpaperswitcher.engine.ClarityMode]）：
 * 旧版本的「增强」("strong") 现在是「画质增强（超分）」("super")，未知值等同于
 * "自动"。面板与渲染端必须说同样的话，否则行右侧会显示一个用户选不到的值。
 */
internal fun clarityKeyOf(stored: String): String =
    com.wallpaperswitcher.engine.ClarityMode.normalize(stored)

// 旋转方向在设置里存的是布尔（SettingsKeys.ROTATE_MISMATCH_CW），面板需要两个 key。
internal const val ROTATE_KEY_CW = "cw"
internal const val ROTATE_KEY_CCW = "ccw"

/** 旋转方向面板：顺时针在前（默认值）。 */
internal fun rotateDirectionOptions(): List<HiOptionSpec> = listOf(
    HiOptionSpec(ROTATE_KEY_CW, R.string.rotate_clockwise),
    HiOptionSpec(ROTATE_KEY_CCW, R.string.rotate_counter_clockwise),
)

/** 当前是否顺时针 → 面板 key。 */
internal fun rotateDirectionKey(clockwise: Boolean): String =
    if (clockwise) ROTATE_KEY_CW else ROTATE_KEY_CCW

/** 面板 key → 要写进设置的布尔值。 */
internal fun rotateDirectionClockwise(key: String): Boolean = key == ROTATE_KEY_CW
