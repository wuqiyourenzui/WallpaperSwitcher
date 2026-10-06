package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.R
import com.wallpaperswitcher.engine.OnlineBuiltins
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

/**
 * 设置 → 在线壁纸源：内置源的开关与「立即更新」。
 *
 * 全部下载到自动创建的「在线壁纸」分组并参与轮换；开关打开后注册每天一次的
 * Wi-Fi 后台更新（见 OnlineSourceScheduler）。
 */
@Composable
fun OnlineSourcesScreen(
    viewModel: WallpaperViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources by viewModel.onlineSources.collectAsStateWithLifecycle()

    SettingsPageScaffold(onBack = onBack, modifier = modifier) {
        SettingsSection(title = stringResource(R.string.settings_online_sources)) {
            OnlineBuiltins.PROVIDERS.forEachIndexed { index, provider ->
                val source = sources.firstOrNull { it.type == provider.type }
                SettingsSwitchItem(
                    icon = Icons.Outlined.CloudDownload,
                    title = provider.label,
                    subtitle = onlineStatusText(source),
                    checked = source?.enabled == true,
                    onCheckedChange = { viewModel.setOnlineSourceEnabled(provider.type, it) },
                )
                if (index != OnlineBuiltins.PROVIDERS.lastIndex) {
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsClickableItem(
                icon = Icons.Outlined.Refresh,
                title = stringResource(R.string.settings_online_refresh_now),
                subtitle = stringResource(R.string.settings_online_sources_hint),
                onClick = { viewModel.refreshOnlineSources() },
            )
        }
    }
}

/** 内置在线源的状态文案（结果码 → 本地化文案，见 OnlineSourceRules.ResultInfo）。 */
@Composable
private fun onlineStatusText(source: com.wallpaperswitcher.data.OnlineSource?): String {
    val raw = source?.lastResult.orEmpty()
    if (raw.isBlank()) return stringResource(R.string.online_never_updated)
    val info = com.wallpaperswitcher.engine.OnlineSourceRules.decodeResult(raw)
        ?: return raw
    if (info.isError) {
        return stringResource(R.string.online_status_error, onlineErrorText(info.reason))
    }
    return if (info.added > 0) {
        stringResource(R.string.online_status_ok_added, info.added)
    } else {
        stringResource(R.string.online_status_ok_none)
    }
}
