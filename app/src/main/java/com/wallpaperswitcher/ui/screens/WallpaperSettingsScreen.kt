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
 * （[switchModeOptions] / [enhanceAlgoOptions] 等）。
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
    val switchTransition = state.switchTransition
    val videoSoundEnabled = state.videoSoundEnabled
    val videoPlayToEnd = state.videoPlayToEnd
    val rotateMismatchEnabled = state.rotateMismatchEnabled
    val rotateMismatchClockwise = state.rotateMismatchClockwise
    val kenBurnsEnabled = state.kenBurnsEnabled
    val clarityEnabled by viewModel.clarityEnabled.collectAsStateWithLifecycle()
    val enhanceAlgo by viewModel.enhanceAlgo.collectAsStateWithLifecycle()

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

                // Clarity enhancement for low-res media (default "auto" keeps the
                // current behavior; the option lets users tune it on-device).
                // 清晰度增强：只有开/关；打开后下面出现算法二选一（FSR1 / Anime4K）。
                SettingsSwitchItem(
                    icon = Icons.Outlined.HighQuality,
                    title = stringResource(R.string.settings_clarity),
                    subtitle = stringResource(R.string.settings_clarity_hint),
                    checked = clarityEnabled,
                    onCheckedChange = { viewModel.setClarityEnabled(it) }
                )

                if (clarityEnabled) {
                    HiOptionPickerRow(
                        title = stringResource(R.string.settings_enhance_algo),
                        subtitle = stringResource(R.string.settings_enhance_algo_hint),
                        options = enhanceAlgoOptions(),
                        selectedKey = enhanceAlgoOf(enhanceAlgo),
                        onSelect = { viewModel.setEnhanceAlgo(it) },
                        // 子行：缩进一级，明确挂在开关下面（和旋转方向同一形态）。
                        modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                    )
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

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // 视频壁纸播放声音：只在桌面可见时出声（见 4.9.x「视频壁纸声音」）。
                SettingsSwitchItem(
                    icon = Icons.Outlined.MusicNote,
                    title = stringResource(R.string.settings_video_sound),
                    subtitle = stringResource(R.string.settings_video_sound_hint),
                    checked = videoSoundEnabled,
                    onCheckedChange = { viewModel.setVideoSoundEnabled(it) }
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // 视频播完再切：定时切换不再打断长视频。
                SettingsSwitchItem(
                    icon = Icons.Outlined.Movie,
                    title = stringResource(R.string.settings_video_play_to_end),
                    subtitle = stringResource(R.string.settings_video_play_to_end_hint),
                    checked = videoPlayToEnd,
                    onCheckedChange = { viewModel.setVideoPlayToEnd(it) }
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

// 过渡动画面板：key 就是存进 SettingsKeys.SWITCH_TRANSITION 的字符串。
// （跟着控件从「切换方式」页搬过来：设置项和它的选项表放在同一个文件里。）

/** 过渡动画面板。 */
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

// 超分算法：key 就是设置里存的值（SettingsKeys.ENHANCE_ALGO / engine.EnhanceMode）。

/** 超分算法面板：FSR1 在前（默认值）。 */
internal fun enhanceAlgoOptions(): List<HiOptionSpec> = listOf(
    HiOptionSpec(com.wallpaperswitcher.engine.EnhanceMode.FSR1_KEY, R.string.settings_fsr1),
    HiOptionSpec(com.wallpaperswitcher.engine.EnhanceMode.ANIME4K_KEY, R.string.settings_anime4k),
)

/** 存的算法 → 面板认得的 key；未知值归一到 FSR1（与 EnhanceMode.fromKey 同口径）。 */
internal fun enhanceAlgoOf(stored: String): String =
    if (stored == com.wallpaperswitcher.engine.EnhanceMode.ANIME4K_KEY) {
        com.wallpaperswitcher.engine.EnhanceMode.ANIME4K_KEY
    } else {
        com.wallpaperswitcher.engine.EnhanceMode.FSR1_KEY
    }

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
