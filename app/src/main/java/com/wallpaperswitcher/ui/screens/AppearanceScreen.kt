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
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
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
import com.wallpaperswitcher.ui.theme.HiColorRow
import com.wallpaperswitcher.ui.theme.HiMotion
import com.wallpaperswitcher.ui.theme.HiOptionPickerRow
import com.wallpaperswitcher.ui.theme.HiOptionPickerRowOf
import com.wallpaperswitcher.ui.theme.HiOption
import com.wallpaperswitcher.ui.theme.HiOptionSpec
import com.wallpaperswitcher.ui.theme.parseHexColor
import com.wallpaperswitcher.ui.theme.ThemeMode
import com.wallpaperswitcher.ui.theme.LocalAccentColor
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

/**
 * settings_page_appearance：设置主界面的一个子页（外壳见 [SettingsPageScaffold]）。
 * 内容与原设置页的同一分区逐字一致，只是搬进了自己的页面。
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun AppearanceScreen(
    viewModel: WallpaperViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val themeMode = state.themeMode
    val themeColor = state.themeColor
    var showColorDialog by remember { mutableStateOf(false) }
    val localeTag = com.wallpaperswitcher.ui.currentLocaleTag(LocalContext.current)
    // 语言切换要立刻重建 Activity，所以需要 Activity 上下文。
    val localContext = LocalContext.current

    SettingsPageScaffold(onBack = onBack, modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_page_appearance)) {
                // 语言：跟随系统 / 简体中文 / English … (only locales with a
                // translation are listed - see SettingsKeys.TRANSLATED_LOCALES).
                // 与「主题模式」同一种形态：右侧当前值 + 下拉，选项在行下面展开。
                val languageSpecs = languageOptions()
                HiOptionPickerRowOf(
                    title = stringResource(R.string.settings_language),
                    icon = Icons.Outlined.Language,
                    options = languageSpecs,
                    // 存的 tag 不在表里（老版本/手改过的库）时按「跟随系统」显示，
                    // 面板里总能选中它，用户不会看不到自己当前的档位。
                    selectedKey = languageSpecs.firstOrNull { it.key == localeTag }?.key
                        ?: SettingsKeys.LOCALE_SYSTEM,
                    onSelect = { tag ->
                        // 先同步镜像：Activity 马上要重建，它的 attachBaseContext 必须
                        // 已经看到新的 tag（数据库写入是异步的）。
                        AppLocale.store(localContext, tag)
                        viewModel.setLocale(tag)
                        (localContext as? android.app.Activity)?.recreate()
                    },
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // 浅色/深色：跟随系统 or 强制其中一种。行右侧显示当前模式，
                // 点一下弹出「跟随系统 / 浅色 / 深色」面板。
                HiOptionPickerRow(
                    title = stringResource(R.string.settings_theme_mode),
                    icon = Icons.Outlined.Brightness4,
                    options = themeModeOptions(),
                    selectedKey = themeModeKeyOf(themeMode),
                    onSelect = { viewModel.setThemeMode(it) },
                )

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // 主题色：右侧是当前颜色的色点 + 色值（空 = 跟随系统），点一下打开
                // 取色器；「删除」把它清回跟随系统，不用再进一次取色器。
                val customThemeColor = themeColor.isNotEmpty()
                HiColorRow(
                    title = stringResource(R.string.settings_theme_color),
                    hex = if (customThemeColor) themeColor else stringResource(R.string.theme_color_system),
                    color = (if (customThemeColor) parseHexColor(themeColor) else null)
                        ?: MaterialTheme.colorScheme.primary,
                    // 没有自定义颜色时不给「删除」：清空 = 跟随系统，此时它已经
                    // 是跟随系统了，摆一个按了没变化的按钮只会让人以为坏了。
                    onClear = if (customThemeColor) ({ viewModel.setThemeColor("") }) else null,
                    icon = Icons.Outlined.Palette,
                    onClick = { showColorDialog = true },
                )
        }
    }

    if (showColorDialog) {
        ThemeColorPickerDialog(
            currentHex = themeColor,
            onDismiss = { showColorDialog = false },
            onSelect = { hex -> viewModel.setThemeColor(hex); showColorDialog = false }
        )
    }
}

// --- 选项表（纯逻辑，单测见 ui/theme/HiOptionLogicTest.kt） -------------------

/** 浅色/深色面板：key 就是 `ThemeMode.value`，即存进设置里的那个字符串。 */
internal fun themeModeOptions(): List<HiOptionSpec> =
    ThemeMode.entries.map { HiOptionSpec(it.value, it.labelRes) }

/**
 * 存的主题模式 → 面板认得的 key。
 *
 * 复用 [ThemeMode.from]：未知值（老版本、手改过的库、备份恢复）等同于"跟随系统"，
 * 和主题实际生效的方式一致 —— 面板里必须能选中它，否则用户看不到自己现在是哪一档。
 */
internal fun themeModeKeyOf(stored: String): String = ThemeMode.from(stored).value

/**
 * 语言面板：跟随系统 + 每个真的带翻译的 locale（[SettingsKeys.TRANSLATED_LOCALES]）。
 * 语言名来自系统 Locale，不是 string 资源，所以这里直接给 [HiOption]。
 */
@Composable
internal fun languageOptions(): List<HiOption> = buildList {
    add(HiOption(SettingsKeys.LOCALE_SYSTEM, stringResource(R.string.language_system)))
    SettingsKeys.TRANSLATED_LOCALES.forEach { tag ->
        add(HiOption(tag, localeDisplayName(tag)))
    }
}
