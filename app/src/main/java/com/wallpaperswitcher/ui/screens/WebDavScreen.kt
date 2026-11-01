package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.OnlineSource
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

/**
 * 底栏「WebDAV」页：网盘来源的**唯一**入口。
 *
 * 从「设置 → 在线壁纸源」搬出来是因为它已经不是一个"开关列表"：能浏览目录、能看图片
 * 和视频、能批量下载并设为壁纸，够得上一个顶层入口。内置源（Bing/NASA/图站）仍留在
 * 在线壁纸源里，两者互不影响。
 *
 * 顶层 tab 不注册返回处理：按返回键直接退出应用（与「设置」「订阅」一致 —— 否则会
 * 出现"按返回回到首页"的假返回）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WebDavScreen(
    viewModel: WallpaperViewModel,
    modifier: Modifier = Modifier,
) {
    val sources by viewModel.customOnlineSources.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<OnlineSource?>(null) }
    var browsing by remember { mutableStateOf<OnlineSource?>(null) }

    val current = browsing
    if (current != null) {
        // 浏览网盘 = 本 Tab 的内容，不再套全屏 Dialog：系统底栏始终可见。
        OnlineBrowserDialog(
            viewModel = viewModel,
            source = current,
            onDismiss = { browsing = null },
            onEdit = {
                browsing = null
                editing = current
            },
            embedded = true,
        )
        return
    }


    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsSection(title = stringResource(R.string.online_netdisk_section)) {
            if (sources.isEmpty()) {
                Text(
                    text = stringResource(R.string.online_netdisk_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            sources.forEach { source ->
                SettingsClickableItem(
                    icon = Icons.Outlined.Cloud,
                    title = source.name.ifBlank { source.webdavUrl },
                    subtitle = stringResource(R.string.online_netdisk_browse_hint),
                    onClick = { browsing = source },
                )
                androidx.compose.material3.Divider(
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            SettingsClickableItem(
                icon = Icons.Outlined.Add,
                title = stringResource(R.string.online_netdisk_add),
                onClick = { adding = true },
            )
        }
        Text(
            text = stringResource(R.string.webdav_tip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }

    if (adding) {
        OnlineSourceEditDialog(viewModel = viewModel, source = null, onDismiss = { adding = false })
    }
    editing?.let { source ->
        OnlineSourceEditDialog(
            viewModel = viewModel,
            source = source,
            onDismiss = { editing = null },
        )
    }
}
