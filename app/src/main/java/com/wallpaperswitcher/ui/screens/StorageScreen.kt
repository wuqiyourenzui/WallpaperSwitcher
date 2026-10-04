package com.wallpaperswitcher.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wallpaperswitcher.R
import com.wallpaperswitcher.ui.theme.HiCard
import com.wallpaperswitcher.ui.theme.HiDims
import com.wallpaperswitcher.ui.theme.HiLoadingHint
import com.wallpaperswitcher.ui.theme.HiLoadingState
import com.wallpaperswitcher.ui.theme.LocalAccentColor
import com.wallpaperswitcher.viewmodel.StorageCleanResult
import com.wallpaperswitcher.viewmodel.StorageDirUsage
import com.wallpaperswitcher.viewmodel.StorageUsage
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// 纯逻辑：不碰 Compose / Android 框架，边界都能在单测里钉住（StorageFormatTest）。
// ---------------------------------------------------------------------------

/** 人类可读单位的档位，1024 进制（`B / KB / MB / GB / TB / PB`）。 */
private val STORAGE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

/** 每 1024 进一档。 */
private const val STORAGE_KB = 1024L

/** 一位小数 = 十分位；一档的十分位上限是 `1024.0 * 10`。 */
private const val TENTHS_PER_UNIT = 10_240L

/**
 * 把字节数变成人类可读的大小：`0 B` / `1023 B` / `1.0 KB` / `280.5 MB` / `1.0 GB`。
 *
 * 几个刻意的地方：
 *  - **1024 进制**：这是应用私有目录里文件的大小，KB/MB 就是 1024 的幂。不用
 *    `Formatter.formatFileSize` 是因为它按系统设置走十进制或二进制（"1.05 GB"），
 *    同一台机器上和系统设置里的数字对不上，也没法在单测里钉住。
 *  - **B 档不带小数**：`1023 B` 比 `1023.0 B` 干净，字节本来也没有小数可言。
 *  - **进位检查**：先按 1024 选档，再看四舍五入到一位小数会不会顶到 1024 ——
 *    1048575 B 是 1023.999 KB，直接格式化就是 `1024.0 KB`（看着像 bug，其实该显示
 *    `1.0 MB`）。所以顶到 1024 时必须再升一档。
 *  - **不用 `String.format("%.1f")`**：那会跟系统 Locale 走（德语 "1,0 KB"），
 *    7 个语言下数字形态不一致，单测也钉不住；这里手工拼小数点。
 */
internal fun formatStorageBytes(bytes: Long): String {
    // 负数只可能是脏数据（统计的是文件长度之和），当成 0 处理，别在界面上出现 "-1 B"。
    if (bytes <= 0L) return "0 B"
    if (bytes < STORAGE_KB) return "$bytes B"

    // 选档：value 落到 [1, 1024) 就停（最后一档除外，Long 最大也就 8192 PB）。
    var unit = 0
    var value = bytes.toDouble()
    while (value >= STORAGE_KB && unit < STORAGE_UNITS.lastIndex) {
        value /= STORAGE_KB
        unit++
    }

    var tenths = roundToTenths(value)
    if (tenths >= TENTHS_PER_UNIT && unit < STORAGE_UNITS.lastIndex) {
        // 顶到 1024 了：用**原始字节数**重算下一档，而不是在已经除过的值上再除一次 ——
        // 两次除法会累积浮点误差。
        unit++
        tenths = roundToTenths(bytes.toDouble() / powerOf1024(unit))
    }
    return "${tenths / 10}.${tenths % 10} ${STORAGE_UNITS[unit]}"
}

/** `1024` 的 [unit] 次方（0 -> `B` 档，1 -> `KB`，…）。 */
private fun powerOf1024(unit: Int): Double {
    var divisor = 1.0
    repeat(unit) { divisor *= STORAGE_KB }
    return divisor
}

/**
 * 四舍五入到一位小数，用"十分位整数"表示。
 *
 * 返回整数而不是 Double，是为了后面只做整数除法和取余 —— 既躲开了浮点比较，
 * 也躲开了 `String.format` 的 Locale 问题（见 [formatStorageBytes]）。
 * 用 `floor(x + 0.5)` 而不是 `round`：`round` 的 .5 往哪边靠是"看实现"的，
 * 这里要的是明确的四舍五入。
 */
private fun roundToTenths(value: Double): Long =
    kotlin.math.floor(value * 10.0 + 0.5).toLong()

/**
 * 一次清理之后该不该说"已清理 …"。
 *
 * 返回 null = 这一趟没清到东西，页面改用 `storage_nothing_orphan`。
 * 只看字节数不够：`files == 0` 而 `bytes > 0` 只可能是脏数据（字节数是文件长度之和，
 * 一个文件都没删却报"释放了 3.0 MB"，用户会当成页面在骗人）。
 */
internal fun cleanedStorageNote(result: StorageCleanResult): StorageCleanResult? =
    result.takeIf { it.files > 0 }

// ---------------------------------------------------------------------------
// 页面
// ---------------------------------------------------------------------------

/**
 * 「存储与流量守门」：应用把订阅源 / 在线源的媒体下载在私有目录
 * （`files/rss/<源 id>/`、`files/online/<源 id>/`），删图片或换源时会留下没人引用的
 * 文件（真机实测积过 959 个文件 / 280MB）。这一页让你看清占了多少、能清多少，并一键清理。
 *
 * 数据（**都走 viewModel，不要直连 DAO / 文件系统**）：
 *  - `suspend fun viewModel.storageUsage(): StorageUsage` —— 目录总占用（挂起，IO 在内部）；
 *    `StorageUsage.rss` / `.online` 是 `StorageDirUsage(name, files, bytes)`，
 *    另有 `totalFiles` / `totalBytes`。
 *  - `suspend fun viewModel.measureOrphanMedia(): StorageCleanResult` —— **只统计不删除**的
 *    孤儿扫描。它和 [WallpaperViewModel.cleanOrphanMedia] 用的是同一套判断（数据库引用
 *    集合 + 10 分钟保护期），所以"其中 X 已没有引用"和点下去真正释放的量不会对不上。
 *  - `suspend fun viewModel.cleanOrphanMedia(): StorageCleanResult` —— 真删，返回释放量。
 *
 * 页面状态：
 *  - 进页面 / 点「重新统计」：顺序调上面两个统计（顺序而不是并发，见 [LaunchedEffect] 的注释）；
 *  - 「清理残留文件」只在真有残留时可用（`measureOrphanMedia` 的 `files > 0`）；
 *  - 清理中顶栏显示正在统计，清完行内留下"已清理 N 个文件，释放 X"，并**重新统计**刷新数字。
 *
 * 返回：顶栏（外层 Scaffold 的 TopAppBar）出标题，本页只提供返回箭头 + `BackHandler`。
 */
@Composable
fun StorageScreen(
    viewModel: WallpaperViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 本页父级是设置页。外层（WallpaperSwitcherApp）也给非顶层页注册了 BackHandler，
    // 但嵌套注册里最内层后注册的那个先收到事件 —— 这里自己注册，行为不依赖外层的分支表。
    BackHandler { onBack() }

    var usage by remember { mutableStateOf(StorageUsage()) }
    var orphans by remember { mutableStateOf(StorageCleanResult(0, 0L)) }
    // 还没拿到第一次统计：整屏走加载态，避免先闪一下"应用已占用 0 B / 0 个文件"。
    var loaded by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(true) }
    var cleaning by remember { mutableStateOf(false) }
    // 「重新统计」自增即触发下面那个 LaunchedEffect 再算一次。
    var scanToken by remember { mutableStateOf(0) }
    // 上次清理的结果。存成两个基本类型是为了能进 Bundle（旋转后那句"已清理 …"还在，
    // 而 StorageCleanResult 不是 Parcelable，为它套一个自定义 Saver 不值当）。
    var cleanedFiles by rememberSaveable { mutableStateOf(0) }
    var cleanedBytes by rememberSaveable { mutableStateOf(0L) }
    // 重建也走同一个纯函数，保证"files == 0 就不算清到东西"这条规则只有一处。
    val cleaned = remember(cleanedFiles, cleanedBytes) {
        cleanedStorageNote(StorageCleanResult(cleanedFiles, cleanedBytes))
    }

    val scope = rememberCoroutineScope()
    val busy = scanning || cleaning

    // 进页面算一次 + 每次「重新统计」再算一次。两次扫描都在 viewModel 内部的 IO 派发器上，
    // 这里**顺序**调而不是并发：两个统计各自要遍历目录 / 查引用集合，并发的话中间插进来的
    // 增删会让"占用了多少"和"其中多少能清"来自不同时刻，两个数字对不上。
    LaunchedEffect(scanToken) {
        scanning = true
        usage = viewModel.storageUsage()
        orphans = viewModel.measureOrphanMedia()
        scanning = false
        loaded = true
    }

    // 清理：真删 + 重新统计都在这一处，保证用户看到的新数字就是清理后的状态。
    val onClean: () -> Unit = {
        // 按钮的 enabled 已经要求"有残留且不忙"，这里再挡一次进行中的重复点击。
        if (!busy) {
            scope.launch {
                cleaning = true
                val note = cleanedStorageNote(viewModel.cleanOrphanMedia())
                cleanedFiles = note?.files ?: 0
                cleanedBytes = note?.bytes ?: 0L
                // 不重新统计的话，上面那张"应用已占用 X"还是清理前的数字，用户会以为白清了；
                // orphans 也要重算，让这一块回到"没有可清理的残留文件"。
                usage = viewModel.storageUsage()
                orphans = viewModel.measureOrphanMedia()
                cleaning = false
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StorageHeader(
                busy = busy,
                onBack = onBack,
                onRefresh = { if (!busy) scanToken++ },
            )

            if (!loaded) {
                HiLoadingState(
                    text = stringResource(R.string.storage_scanning),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                )
            } else {
                StorageContent(
                    usage = usage,
                    orphans = orphans,
                    cleaned = cleaned,
                    busy = busy,
                    onClean = onClean,
                )
            }
        }
    }
}

/**
 * 顶部只有返回箭头 + 右端的「重新统计」。
 *
 * 标题不在这里重复：外层 Scaffold 的 TopAppBar 已经按 `Screen.Storage` 显示
 * [R.string.storage_title]（和 RecentScreen / FavoritesScreen 一样）。
 * 忙碌时那个位置换成一句话，否则几百个文件的目录遍历期间按钮看着像"点了没反应"。
 */
@Composable
private fun StorageHeader(
    busy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (busy) {
            HiLoadingHint(
                text = stringResource(R.string.storage_scanning),
                iconSize = 14.dp,
            )
        } else {
            TextButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = stringResource(R.string.storage_refresh))
            }
        }
    }
}

/**
 * 三张数字卡 + 一张清理卡。
 *
 * 用可滚动 `Column` 而不是 LazyColumn：这里固定就四项，没有长列表的回收问题，
 * 而且整页高度很小（滚动只为"大字体 + 横屏"留余地）。
 */
@Composable
private fun StorageContent(
    usage: StorageUsage,
    orphans: StorageCleanResult,
    cleaned: StorageCleanResult?,
    busy: Boolean,
    onClean: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = HiDims.PageHorizontal,
                end = HiDims.PageHorizontal,
                top = 4.dp,
                bottom = HiDims.PageBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(HiDims.CardSpacing),
    ) {
        StorageTotalCard(usage = usage)
            // 「在线壁纸下载」那一栏已去掉：在线壁纸功能已经删除，这一页只统计
            // 应用自己的下载（订阅源）。（早期下载残留的文件仍会被下面的
            // 「清理残留文件」当成孤儿一起清掉。）
        StorageCleanCard(
            orphans = orphans,
            cleaned = cleaned,
            busy = busy,
            onClean = onClean,
        )
    }
}

/** 总览：`storage_total`（总占用，人类可读）+ 一行 `storage_files`（文件数）。 */
@Composable
private fun StorageTotalCard(usage: StorageUsage) {
    val accent = hiAccent()
    HiCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HiDims.RowHorizontal, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.SdStorage,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(26.dp),
            )
            Spacer(modifier = Modifier.width(HiDims.IconGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.storage_total,
                        formatStorageBytes(usage.totalBytes),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.storage_files, usage.totalFiles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 一张分项卡（订阅源下载 / 在线壁纸下载）：名称 + 文件数，右侧是大小。
 *
 * 两张分开而不是塞进一张卡的两行：这两个目录的清理方式确实不同（订阅源还有"用户自选
 * 下载目录"那条路径），分开显示才对得上"钱花在哪"。
 */
@Composable
private fun StorageDirCard(
    name: String,
    icon: ImageVector,
    dir: StorageDirUsage,
) {
    val accent = hiAccent()
    HiCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HiDims.RowHorizontal, vertical = HiDims.RowVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(HiDims.IconGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.storage_files, dir.files),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = formatStorageBytes(dir.bytes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
            )
        }
    }
}

/**
 * 「可清理」块。
 *
 * 数字来源是 [WallpaperViewModel.measureOrphanMedia]（只统计不删除），所以进页面就能
 * 说清"其中 X 已没有引用"，而**不是**拿目录总占用冒充孤儿量 —— 那两件事差着一个数量级
 * （实测 280MB 里只有一部分是孤儿），说错了用户会觉得清理在骗人。
 *
 * 没有残留时按钮是禁用的（`enabled = hasOrphans`）：让"能不能点"本身就是答案。
 */
@Composable
private fun StorageCleanCard(
    orphans: StorageCleanResult,
    cleaned: StorageCleanResult?,
    busy: Boolean,
    onClean: () -> Unit,
) {
    val accent = hiAccent()
    val hasOrphans = orphans.files > 0
    HiCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HiDims.RowHorizontal, vertical = HiDims.RowVertical),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.DeleteSweep,
                    contentDescription = null,
                    tint = if (hasOrphans) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(HiDims.IconGap))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasOrphans) {
                            stringResource(
                                R.string.storage_orphan_hint,
                                formatStorageBytes(orphans.bytes),
                            )
                        } else {
                            stringResource(R.string.storage_nothing_orphan)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (cleaned != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.storage_cleaned,
                                cleaned.files,
                                formatStorageBytes(cleaned.bytes),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(HiDims.CardSpacing))
            FilledTonalButton(
                onClick = onClean,
                enabled = hasOrphans && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.storage_clean))
            }
        }
    }
}

/** 主题强调色：跟随用户选的颜色，没设时退回 M3 primary（同 RecentScreen 的角标）。 */
@Composable
private fun hiAccent(): Color =
    LocalAccentColor.current.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
