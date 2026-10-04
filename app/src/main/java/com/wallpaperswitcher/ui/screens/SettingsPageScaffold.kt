package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

/**
 * 设置子页的统一外壳。
 *
 * 设置主界面（[SettingsScreen]）现在只是一个**入口列表**：每一项点进去是自己的页面，
 * 页面里才是真正的设置项。这样做的好处是主界面一眼能看完（不再是一条 1800 行的
 * 长滚动），而且每一项都有独立的返回栈位置。
 *
 * 这个 Composable 负责所有子页共有的部分：返回键、滚动、内边距、卡片间距。
 * 标题交给外层 TopAppBar（见 WallpaperSwitcherApp 的标题表），页内不重复写。
 *
 * **注意**：不要把 `viewModel.settingsUiState` 的收集放进这里 —— 每个子页只关心
 * 自己那几个字段，整页收集会让任一设置变化都重组这一页（历史上设置页就是被
 * 13 个 flow 拖慢的，见 SettingsScreen 顶部注释）。子页各自 collect。
 */
@Composable
fun SettingsPageScaffold(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.activity.compose.BackHandler { onBack() }
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}
