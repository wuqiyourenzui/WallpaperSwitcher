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
import android.os.Build

import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.ScaleMode
import com.wallpaperswitcher.data.SettingsKeys
import com.wallpaperswitcher.data.SwitchMode
import com.wallpaperswitcher.ui.AppLocale
import com.wallpaperswitcher.ui.theme.HiMotion
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
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    val localeTag = com.wallpaperswitcher.ui.currentLocaleTag(LocalContext.current)
    // 语言切换要立刻重建 Activity，所以需要 Activity 上下文。
    val localContext = LocalContext.current

    SettingsPageScaffold(onBack = onBack, modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_page_appearance)) {
                // 语言：跟随系统 / 简体中文 / English … (only locales with a
                // translation are listed - see SettingsKeys.TRANSLATED_LOCALES).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLanguageDialog = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Language,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        localeDisplayName(localeTag),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                // 浅色/深色：跟随系统 or 强制其中一种
                SettingsChoiceRow(
                    icon = Icons.Outlined.Brightness4,
                    label = stringResource(R.string.settings_theme_mode)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        SettingsOptionChip(
                            selected = ThemeMode.from(themeMode) == mode,
                            onClick = { viewModel.setThemeMode(mode.value) },
                            label = stringResource(mode.labelRes)
                        )
                    }
                }

                Divider(modifier = Modifier.padding(horizontal = 16.dp))

                Row(
                    modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showColorDialog = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Palette, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_theme_color), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                themeColor.isNotEmpty() -> themeColor
                                // Android 12+ paints the whole UI from the wallpaper's
                                // palette (Monet); older versions fall back to the
                                // built-in scheme. Say which one is in effect.
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> stringResource(R.string.theme_color_system)
                                else -> stringResource(R.string.theme_color_system)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val previewColor = if (themeColor.isNotEmpty()) parseHexColor(themeColor)
                    else MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(previewColor ?: MaterialTheme.colorScheme.primary)
                    )
                }
        }
    }

    if (showLanguageDialog) {
        LanguagePickerDialog(
            currentTag = localeTag,
            onDismiss = { showLanguageDialog = false },
            onSelect = { tag ->
                // 先同步镜像：Activity 马上要重建，它的 attachBaseContext 必须已经
                // 看到新的 tag（数据库写入是异步的）。
                AppLocale.store(localContext, tag)
                viewModel.setLocale(tag)
                showLanguageDialog = false
                (localContext as? android.app.Activity)?.recreate()
            }
        )
    }
    if (showColorDialog) {
        ThemeColorPickerDialog(
            currentHex = themeColor,
            onDismiss = { showColorDialog = false },
            onSelect = { hex -> viewModel.setThemeColor(hex); showColorDialog = false }
        )
    }
}
