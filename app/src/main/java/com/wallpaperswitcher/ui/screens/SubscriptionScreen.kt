package com.wallpaperswitcher.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.RssArticle
import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.engine.FeedParser
import com.wallpaperswitcher.engine.OnlineSourceRules
import com.wallpaperswitcher.ui.theme.HiEmptyState
import com.wallpaperswitcher.ui.theme.HiLoadingHint
import com.wallpaperswitcher.ui.theme.HiMotion
import com.wallpaperswitcher.ui.theme.LocalAccentColor
import com.wallpaperswitcher.ui.theme.hiCardColor
import com.wallpaperswitcher.viewmodel.WallpaperViewModel

/** How many article images to warm into Coil's cache (≈ first two screens). */
private const val IMAGE_PREFETCH_LIMIT = 12

/** Tile spacing of the article picker grid. */
private val GRID_SPACING = 8.dp

/**
 * Up to this many pictures the picker uses plain rows (nothing to scroll); a
 * longer gallery switches to the lazy grid, which only downloads the tiles that
 * are actually on screen instead of the whole article at once.
 */
private const val PICKER_GRID_MIN_LAZY = 9

/**
 * 阅读订阅源 (Legado-compatible): the source list. Add a feed manually or
 * import 阅读 (Legado) subscription sources (JSON / share link), then open a
 * source to read its articles.
 */
@Composable
fun SubscriptionScreen(
    viewModel: WallpaperViewModel,
    onOpenArticles: (Long) -> Unit,
    /** 单 URL / 网页型源：直接用全屏浏览器打开（不做订阅源解析）。 */
    onOpenBrowser: (RssSource) -> Unit = {},
    /**
     * Bumped by the top bar's 多选 button. The multi-select entry used to sit in
     * its own full-width row above the list, which left a blank band under the
     * top bar (user report: 「订阅与文章间有空白」); it now lives in the top bar.
     */
    selectionRequest: Int = 0,
    /** Called after [selectionRequest] has been consumed: the caller resets the
     *  counter so re-entering the list does not re-open multi-select. */
    onSelectionRequestHandled: () -> Unit = {},
    /** 分享入库: a URL / 阅读 share link to prefill the import dialog with. */
    prefillImport: String? = null,
    /** Called once [prefillImport] has been shown, so it does not re-open. */
    onPrefillConsumed: () -> Unit = {},
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    val sources by viewModel.rssSources.collectAsStateWithLifecycle()
    val gridView by viewModel.rssGridView.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    LaunchedEffect(prefillImport) {
        if (prefillImport != null) showImport = true
    }
    var deleteTarget by remember { mutableStateOf<RssSource?>(null) }
    // 订阅源多选：批量启用 / 停用 / 删除（与分组列表同一套交互）。
    val selectedIds = remember {
        androidx.compose.runtime.mutableStateMapOf<Long, Boolean>()
    }
    var selectionMode by remember { mutableStateOf(false) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectionRequest) {
        if (selectionRequest > 0) {
            selectedIds.clear()
            selectionMode = true
            onSelectionRequestHandled()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (sources.isNotEmpty()) {
            // 多选栏展开 / 收起（与首页分组多选、设置页子选项同一套动效）。
            AnimatedVisibility(
                visible = selectionMode,
                enter = fadeIn(HiMotion.enter()) + expandVertically(HiMotion.enter()),
                exit = fadeOut(HiMotion.exit()) + shrinkVertically(HiMotion.exit()),
            ) {
                MultiSelectActionsBar(
                    selectedMap = selectedIds,
                    allIds = sources.map { it.id },
                    onExit = {
                        selectedIds.clear()
                        selectionMode = false
                    },
                    // 订阅源多选只做批量删除（不做批量启用/停用）。
                    onDelete = { confirmBatchDelete = true },
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
        if (sources.isEmpty()) {
            // 与首页、分组详情共用同一套容器式空态（见 HiUi.HiEmptyState）。
            HiEmptyState(
                title = stringResource(R.string.rss_empty),
                icon = Icons.Outlined.MenuBook,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        } else {
            val onOpen: (RssSource) -> Unit = { source ->
                if (selectionMode) {
                    if (selectedIds.containsKey(source.id)) {
                        selectedIds.remove(source.id)
                    } else {
                        selectedIds[source.id] = true
                    }
                } else {
                    // 网页型源直接全屏浏览器打开：单 URL 源、以及
                    // 规则要运行时从远程 jsLib 解出来的 JS 源
                    //（我们跑不了，以前点进去只能看到「不支持的 JS 源」）。
                    if (com.wallpaperswitcher.engine.legado.LegadoRss.isBrowseOnly(source)) {
                        onOpenBrowser(source)
                    } else {
                        onOpenArticles(source.id)
                    }
                }
            }
            val onLongClick: (RssSource) -> Unit = { source ->
                if (!selectionMode) {
                    selectedIds.clear()
                    selectedIds[source.id] = true
                    selectionMode = true
                }
            }
            if (gridView) {
                // 缩略图网格：站点图标 + 名称（订阅源不缓存文章，见 engine.RssIcons）。
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
                    verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
                ) {
                    items(sources, key = { it.id }) { source ->
                        RssSourceTile(
                            source = source,
                            selectionMode = selectionMode,
                            isSelected = selectedIds.containsKey(source.id),
                            onOpen = { onOpen(source) },
                            onLongClick = { onLongClick(source) },
                            onDelete = { deleteTarget = source },
                            onOpenInBrowser = { onOpenBrowser(source) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Owned by the caller so entering a source (which swaps this
                    // screen out) does not scroll the card list back to the top.
                    state = listState,
                    contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(sources, key = { it.id }) { source ->
                        RssSourceCard(
                            source = source,
                            selectionMode = selectionMode,
                            isSelected = selectedIds.containsKey(source.id),
                            onOpen = { onOpen(source) },
                            onLongClick = { onLongClick(source) },
                            onDelete = { deleteTarget = source },
                            // 浏览器打开：复用"网页型源"那条路径（同一屏全屏浏览器，
                            // 带源的 header/cookie），不新增导航目标。
                            onOpenInBrowser = { onOpenBrowser(source) },
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (!selectionMode) {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.rss_add)) }
            )
            }
        }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.rss_delete_title)) },
            text = { Text(stringResource(R.string.rss_delete_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRssSource(target)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (confirmBatchDelete) {
        val count = selectedIds.size
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text(stringResource(R.string.rss_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_sources_message, count)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRssSources(selectedIds.keys.toSet())
                    selectedIds.clear()
                    selectionMode = false
                    confirmBatchDelete = false
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showAdd) {
        RssAddDialog(
            onDismiss = { showAdd = false },
            onSave = { name, url ->
                viewModel.addRssSource(name, url)
                showAdd = false
            },
            onImport = {
                showAdd = false
                showImport = true
            }
        )
    }

    if (showImport) {
        RssImportDialog(
            initialText = prefillImport.orEmpty(),
            onDismiss = {
                showImport = false
                onPrefillConsumed()
            },
            onImport = { text ->
                viewModel.importLegadoSources(text)
                showImport = false
                onPrefillConsumed()
            }
        )
    }

}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun RssSourceCard(
    source: RssSource,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onOpen: () -> Unit,
    onLongClick: () -> Unit = {},
    onDelete: () -> Unit,
    /** 直接用应用内浏览器打开这个源的站点（不经过规则抓取）。 */
    onOpenInBrowser: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick),
        shape = RoundedCornerShape(com.wallpaperswitcher.ui.theme.HiDims.CardCorner),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = com.wallpaperswitcher.ui.theme.HiDims.RowHorizontal,
                    vertical = com.wallpaperswitcher.ui.theme.HiDims.RowVertical,
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Icon(
                        if (isSelected) Icons.Filled.CheckCircle
                        else Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = if (isSelected) LocalAccentColor.current
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        source.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        OnlineSourceRules.logSafeHost(source.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!selectionMode) {
                    // 有些源（需要登录 / 规则跑不动 / 临时想看站点本身）用浏览器
                    // 打开比走规则更直接；这里给一个显式入口，不用去别处找。
                    TextButton(onClick = onOpenInBrowser) {
                        Icon(
                            Icons.Outlined.OpenInNew,
                            contentDescription = stringResource(R.string.rss_open_in_browser),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                    }
                }
            }
            Text(
                stringResource(R.string.rss_last_update) + " · " +
                    if (source.lastFetchAt > 0L) {
                        formatAgo(source.lastFetchAt)
                    } else {
                        stringResource(R.string.online_never_updated)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                rssStatusText(source),
                style = MaterialTheme.typography.bodySmall,
                // 网页型源不抓取：即使它以前留下过错误时间戳，也别用红色报错。
                color = if (source.lastErrorAt > 0L &&
                    !com.wallpaperswitcher.engine.legado.LegadoRss.isBrowseOnly(source)
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * 缩略图网格里的一格：站点图标 + 名称 + 状态。
 *
 * 订阅源按设计不在本地缓存文章（进源实时加载、退出即清空），所以这里能显示的
 * 图像只有站点自己的 `/favicon.ico`（见 [com.wallpaperswitcher.engine.RssIcons]）；
 * 解析不出主机（`legado://` 分享链接等）或图标取不到时退回占位图标。
 *
 * 交互与列表卡片一致：点开、长按进多选；列表卡片上的「浏览器打开 / 删除」在网格
 * 里收进右上角的 ⋮ 菜单（与分组网格的媒体卡片同一形态），选中态用描边 + 勾号表达。
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun RssSourceTile(
    source: RssSource,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onOpen: () -> Unit,
    onLongClick: () -> Unit = {},
    onDelete: () -> Unit,
    onOpenInBrowser: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val iconUrl = remember(source.url) {
        com.wallpaperswitcher.engine.RssIcons.iconUrl(source.url)
    }
    val browserOnly = remember(source.id, source.rawJson) {
        com.wallpaperswitcher.engine.legado.LegadoRss.isBrowseOnly(source)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .then(
                if (isSelected) {
                    Modifier.border(
                        2.dp,
                        LocalAccentColor.current,
                        RoundedCornerShape(com.wallpaperswitcher.ui.theme.HiDims.CardCorner),
                    )
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(com.wallpaperswitcher.ui.theme.HiDims.CardCorner),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                if (iconUrl != null) {
                    AsyncImage(
                        model = imageRequest(context, iconUrl, null),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    )
                } else {
                    Icon(
                        Icons.Outlined.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(34.dp),
                    )
                }
                if (selectionMode) {
                    Icon(
                        if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = null,
                        tint = if (isSelected) LocalAccentColor.current
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(20.dp),
                    )
                } else {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.cd_more),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.rss_open_in_browser)) },
                                onClick = {
                                    showMenu = false
                                    onOpenInBrowser()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (browserOnly) OnlineSourceRules.logSafeHost(source.url)
                    else rssStatusText(source),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (source.lastErrorAt > 0L && !browserOnly) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RssAddDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onImport: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rss_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rss_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.rss_url)) },
                    placeholder = { Text(stringResource(R.string.rss_url_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onImport) {
                    Text(stringResource(R.string.rss_import_legado))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { onSave(name, url) }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun RssImportDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    .orEmpty()
            } catch (_: Exception) {
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rss_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.rss_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 与其它输入框统一成填充式（无描边、14dp 圆角）。
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.rss_import_paste)) },
                    minLines = 5,
                    maxLines = 10,
                    shape = RoundedCornerShape(14.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = hiCardColor(),
                        unfocusedContainerColor = hiCardColor(),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = {
                        filePicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                ) {
                    Text(stringResource(R.string.rss_import_file))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onImport(text) }
            ) {
                Text(stringResource(R.string.rss_import_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/** Articles of one subscription, newest first. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionArticlesScreen(
    viewModel: WallpaperViewModel,
    sourceId: Long,
    onOpenWeb: (RssArticle) -> Unit = {},
    /** 文章本身是视频（规则输出一条媒体地址）时走这条路：全屏播放页。 */
    onOpenVideo: (RssArticle) -> Unit = onOpenWeb,
) {
    val sources by viewModel.rssSources.collectAsStateWithLifecycle()
    val source = sources.firstOrNull { it.id == sourceId }
    var categories by remember(sourceId) { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember(sourceId) { mutableStateOf(0) }
    var sortName by remember(sourceId) { mutableStateOf<String?>(null) }
    var loading by remember(sourceId) { mutableStateOf(true) }
    var hasMore by remember(sourceId) { mutableStateOf(false) }
    var loadingMore by remember(sourceId) { mutableStateOf(false) }
    // 加载失败后的「重试」：把计数塞进 LaunchedEffect 的 key，重新走一遍抓取。
    var reloadKey by remember(sourceId) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // 点击进去就加载: pick up the remembered category and refresh once.
    LaunchedEffect(sourceId, source?.rawJson, reloadKey) {
        val current = source ?: return@LaunchedEffect
        // Entering a source must NOT evaluate its `<js>` sortUrl: only the
        // persisted category names are read (a plain DB read), so the list
        // starts immediately. The real parse happens when the user taps 分类.
        // Cache-first, and when nothing is cached yet compute once so the chips
        // are never missing (the list itself is already on screen).
        val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val only = viewModel.rssCachedCategoriesOnly(current)
            if (only.isNotEmpty()) only else viewModel.rssRefreshCategories(current)
        }
        categories = cached
        val index = viewModel.rssSelectedCategory(sourceId)
            .coerceIn(0, maxOf(0, cached.lastIndex))
        selected = index
        val sort = cached.getOrNull(index)
        sortName = sort
        hasMore = viewModel.rssHasMore(sourceId)
        // 订阅源不缓存正文/列表：每次进入都实时抓当前分类（订阅内容不占存储），
        // 离开这个界面时再把这次抓到的行删掉（见下面的 DisposableEffect）。
        loading = true
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.rssSelectCategory(current, index)
        }
        if (cached.isEmpty()) {
            // First visit: the chip row appears as soon as the source's
            // `sortUrl` has been parsed.
            val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                viewModel.rssRefreshCategories(current)
            }
            if (fresh.isNotEmpty()) categories = fresh
        }
        hasMore = viewModel.rssHasMore(sourceId)
        loading = false
    }

    // 离开源（返回源列表、切标签页）即丢弃本次抓取的列表与正文缓存，
    // 只保留用户主动加入分组的壁纸文件。
    androidx.compose.runtime.DisposableEffect(sourceId) {
        onDispose { viewModel.clearRssSourceCache(sourceId) }
    }

    val articles by remember(sourceId, sortName) {
        // No cached category yet: show every stored article of the source
        // instead of filtering by an unknown name.
        val sort = sortName
        if (sort == null) viewModel.rssArticles(sourceId)
        else viewModel.rssArticlesOfSort(sourceId, sort)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    // 最近一次抓取失败的原因（源上的 err:<reason>）：列表为空时显示完整错误态，
    // 已有内容时只显示一行「刷新失败」提示 —— 用的是订阅卡片同一套映射。
    val loadFailure = source?.let { OnlineSourceRules.decodeResult(it.lastResult) }
        ?.takeIf { it.isError }
    // Source headers (Referer/UA) for image requests, like 阅读's GlideHeaders.
    val imageHeaders = remember(source?.rawJson) {
        source?.let {
            com.wallpaperswitcher.engine.legado.LegadoRss.parseHeaderMap(
                com.wallpaperswitcher.engine.legado.RssSourceEditor.fieldValue(it.rawJson, "header"),
                it.id,
                it.url,
            )
        }.orEmpty()
    }
    var detail by remember { mutableStateOf<RssArticle?>(null) }
    var pendingArticle by remember { mutableStateOf<RssArticle?>(null) }
    var browserImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var browserHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var browserSelected by remember { mutableStateOf<List<String>>(emptyList()) }

    // The full-screen browser hands its collected images back here: open the
    // picker for that article with exactly those URLs selected.
    LaunchedEffect(sourceId) {
        viewModel.rssBrowserResult.collect { urls ->
            if (urls != null) {
                viewModel.consumeRssBrowserResult()
                browserImages = if (source?.type == 2) {
                    // A video source contributes videos only (no stray posters/icons).
                    urls.filter { looksLikeVideoStream(it) }
                        .ifEmpty { urls }
                } else {
                    // The browser collects EVERY <img> the page loaded, site chrome
                    // included (logo, ads, avatars, related-video thumbnails), so
                    // the picker showed pictures that were not in the article.
                    // Same filter as the in-dialog browser: the source's own
                    // script filter when it has one, else the generic gallery
                    // heuristics; if that keeps nothing, fall back to the raw list.
                    val ruleContent = source?.let {
                        com.wallpaperswitcher.engine.legado.RssSourceEditor
                            .fieldValue(it.rawJson, "ruleContent")
                    }
                    urls.distinct()
                        .filter { com.wallpaperswitcher.engine.legado.LegadoRss
                            .keepCollectedImage(ruleContent, it) }
                        .ifEmpty { urls.distinct() }
                }
                browserHeaders = viewModel.rssBrowserHeaders
                browserSelected = viewModel.rssBrowserSelected
                viewModel.rssBrowserSelected = emptyList()
                (pendingArticle ?: viewModel.rssBrowserArticle)?.let {
                    pendingArticle = it
                    detail = it
                }
                viewModel.rssBrowserArticle = null
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 登录 / 编辑 已经移到顶栏（见 WallpaperSwitcherApp 的 TopAppBar
        // actions）：原来它们是列表上方独立的一整行，和分类 chip 一起在
        // 顶栏下方留出一大块空白（用户反馈「订阅与文章间有空白」）。
        if (categories.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEachIndexed { index, name ->
                    FilterChip(
                        selected = index == selected,
                        onClick = {
                            if (index == selected) return@FilterChip
                            selected = index
                            sortName = name
                            scope.launch {
                                loading = true
                                source?.let { viewModel.rssSelectCategory(it, index) }
                                loading = false
                                hasMore = viewModel.rssHasMore(sourceId)
                            }
                        },
                        label = { Text(name) }
                    )
                }
            }
        }
        // 列表为空时由下面的居中加载态负责显示；这里只在「已经有内容、又在
        // 重新拉取」时补一条行内提示，避免同屏出现两个「正在加载」（用户反馈）。
        if (loading && articles.isNotEmpty()) {
            // Unified loading hint (static icon + text; no spinner — see
            // HiLoadingHint).
            HiLoadingHint(
                text = sortName?.let { stringResource(R.string.rss_loading_category, it) }
                    ?: stringResource(R.string.online_picker_loading),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else if (!loading && articles.isNotEmpty() && loadFailure != null) {
            // 旧内容还在、但这次刷新失败：给一行原因 + 重试，不打断阅读。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        R.string.rss_refresh_failed,
                        onlineErrorText(loadFailure.reason),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { reloadKey++ }) {
                    Text(stringResource(R.string.rss_reload))
                }
            }
        }
        if (articles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) {
                    HiLoadingHint(
                        text = sortName?.let { stringResource(R.string.rss_loading_category, it) }
                            ?: stringResource(R.string.online_picker_loading),
                        iconSize = 18.dp,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (loadFailure != null) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(hiCardColor()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(
                                R.string.rss_refresh_failed,
                                onlineErrorText(loadFailure.reason),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        FilledTonalButton(
                            onClick = { reloadKey++ },
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.rss_reload))
                        }
                        // 单 URL / 网页型源（阅读里就是直接当网页打开的，例如
                        // Pixiv 书源卡片、兽展日历）：抓不到列表也不必卡在错误里，
                        // 给一个「用浏览器打开」的兜底 —— 走应用内置的全屏浏览器。
                        val browseSource = source
                        if (browseSource != null &&
                            (loadFailure.reason == "parse" ||
                                loadFailure.reason == "unsupported_js")
                        ) {
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = {
                                onOpenWeb(
                                    RssArticle(
                                        sourceId = browseSource.id,
                                        guid = "browse:${browseSource.id}",
                                        title = browseSource.name,
                                        link = browseSource.url,
                                        isRead = true,
                                    )
                                )
                            }) {
                                Icon(
                                    Icons.Outlined.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.rss_open_in_browser))
                            }
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.rss_no_articles),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(articles, key = { it.sourceId to it.guid }) { article ->
                    RssArticleRow(article, imageHeaders) {
                        viewModel.markRssArticleRead(sourceId, article.guid)
                        pendingArticle = article
                        viewModel.rssBrowserArticle = article
                        // 视频还是图片不在这里判：文章打开后由正文内容决定
                        // （见 RssArticleScreen）。这里只是"打开这篇文章"。
                        onOpenVideo(article)
                    }
                }
                item(key = "load_more") {
                    // 阅读 loads pages lazily; composing the footer means the
                    // user reached the end, so continue from the cursor.
                    LaunchedEffect(hasMore, articles.size) {
                        if (hasMore && !loadingMore) {
                            // Debounce: only load once the user has actually
                            // rested at the bottom, not on every recomposition.
                            kotlinx.coroutines.delay(400)
                            if (hasMore && !loadingMore) {
                                loadingMore = true
                                // Runs in the ViewModel scope: scrolling the
                                // footer away must not cancel the fetch.
                                viewModel.requestRssLoadMore(sourceId) { more ->
                                    hasMore = more
                                    loadingMore = false
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when {
                            loadingMore -> HiLoadingHint(
                                text = stringResource(R.string.rss_content_loading)
                            )
                            hasMore -> TextButton(onClick = {
                                loadingMore = true
                                viewModel.requestRssLoadMore(sourceId) { more ->
                                    hasMore = more
                                    loadingMore = false
                                }
                            }) {
                                Text(stringResource(R.string.rss_load_more))
                            }
                            else -> Text(
                                stringResource(R.string.rss_no_more),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { article ->
        RssArticleDetailDialog(
            viewModel = viewModel,
            article = article,
            imageHeaders = imageHeaders,
            source = source,
            initialImages = browserImages,
            importHeaders = browserHeaders,
            initialSelected = browserSelected,
            onDismiss = { detail = null },
        )
    }

}

@Composable
private fun RssArticleRow(
    article: RssArticle,
    imageHeaders: Map<String, String>,
    onClick: () -> Unit,
) {
    // 文章链接选项里的请求头（阅读 `URL,{headers:…}`）也用于封面抓取：
    // 一些图床要求带 Referer，否则 403 只剩占位图。
    val headers = remember(article.requestHeaders, imageHeaders) {
        if (article.requestHeaders.isBlank()) {
            imageHeaders
        } else {
            imageHeaders + com.wallpaperswitcher.engine.legado.LegadoRss.requestHeaders(article)
        }
    }
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(com.wallpaperswitcher.ui.theme.HiDims.CardCorner),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (article.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = imageRequest(context, article.imageUrl, null, headers),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 88.dp, height = 88.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    article.title.ifBlank { article.link },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (article.isRead) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (article.publishedAt > 0L) {
                    Text(
                        formatAgo(article.publishedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val preview = FeedParser.stripHtml(
                    article.description.ifBlank { article.content }
                )
                if (preview.isNotBlank()) {
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RssArticleDetailDialog(
    viewModel: WallpaperViewModel,
    article: RssArticle,
    imageHeaders: Map<String, String>,
    source: RssSource?,
    initialImages: List<String> = emptyList(),
    importHeaders: Map<String, String> = emptyMap(),
    initialSelected: List<String> = emptyList(),
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var loading by remember { mutableStateOf(true) }
    var html by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    var galleryLoading by remember { mutableStateOf(false) }
    var retryKey by remember { mutableStateOf(0) }
    var showBrowser by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showGroupPicker by remember { mutableStateOf(false) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // 文章链接选项里的请求头也参与图片加载与下载（封面 / 图床常要 Referer）。
    val articleHeaders = remember(article.requestHeaders) {
        if (article.requestHeaders.isBlank()) {
            emptyMap()
        } else {
            com.wallpaperswitcher.engine.legado.LegadoRss.requestHeaders(article)
        }
    }
    val loadHeaders = remember(imageHeaders, articleHeaders) { imageHeaders + articleHeaders }
    val downloadHeaders = remember(importHeaders, loadHeaders) { loadHeaders + importHeaders }

    LaunchedEffect(article.sourceId, article.guid, retryKey) {
        loading = true
        var pageHtml = ""
        try {
            // A manual retry bypasses the cached body so the whole gallery is
            // fetched again (previous versions could have cached a partial set).
            val content = viewModel.loadRssArticleContent(article, force = retryKey > 0)
            html = content.html
            images = content.images
            pageHtml = content.pageHtml
        } catch (_: Exception) {
        }
        loading = false
        if (source?.type == 2) {
            // A video source's grid offers videos only (no posters/placeholders).
            images = images.filter { looksLikeVideoStream(it) }
        }
        if (initialImages.isNotEmpty()) {
            // 和阅读一致：选择界面里就是全屏页显示的那几张图。静态解析的结果
            // （site 推荐位、图集封面等）**不能并进来** —— 之前 `initialImages +
            // images` 会把与当前文章无关的封面混进网格，看起来就是"别的杂图"。
            images = initialImages.distinct()
            // Pre-select exactly the stream the player was showing (falling
            // back to everything collected) so 加入分组 grabs the right one.
            selected = initialSelected.filter { it in images }
                .ifEmpty { initialImages }
                .toSet()
            com.wallpaperswitcher.util.AppLog.d(
                "RssPick",
                "browser images=${initialImages.size} selected=${initialSelected.size} " +
                    "-> checked=${selected.size} grid=${images.size}",
            )
            showBrowser = false
        }
        // 点击进文章直接是浏览器模式: open it once the rule HTML is ready.
        // (Bare media URLs are wrapped into a tiny <video> player by the
        // browser dialog itself.)
        if (!showBrowser && initialImages.isEmpty()) showBrowser = true
        // Script-driven galleries: show the first page's images immediately and
        // stream the sibling pages in afterwards.
        if (pageHtml.isNotBlank()) {
            galleryLoading = true
            try {
                val extra = viewModel.loadRssGalleryImages(article, pageHtml, html)
                if (extra.isNotEmpty()) images = (images + extra).distinct()
            } catch (_: Exception) {
            }
            galleryLoading = false
        }
    }
    val referer = remember(article.link) { com.wallpaperswitcher.engine.OnlineSourceRules.originOf(article.link) }
    // Picker grid height: roughly half the screen, clamped so it works on a
    // phone and does not become a wall of tiles on a tablet. The exact height
    // is derived from the measured cell size below (a two-item video source
    // stays compact, a long gallery fills the cap and scrolls).
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    // 选择器的主体就是图片：给网格更大的高度上限（原来是 46% 屏高 / 最高 420dp，
    // 手机上只能看到两行），同时缩掉标题与表头占的高度。
    val gridMaxHeight = (screenHeightDp * 0.52f).dp.coerceIn(260.dp, 480.dp)
    // Prefetch the NEXT screenful (阅读's `preload`), after the tiles that are
    // actually on screen have started: prefetching the visible ones just
    // competed with them for the same bandwidth, which made the grid feel slow.
    LaunchedEffect(images) {
        if (images.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        val loader = context.imageLoader
        for (url in images.drop(IMAGE_PREFETCH_LIMIT).take(IMAGE_PREFETCH_LIMIT)) {
            try {
                loader.enqueue(imageRequest(context, url, referer, loadHeaders))
            } catch (_: Throwable) {
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                article.title.ifBlank { stringResource(R.string.rss_article) },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                // The grid below gets a DEFINITE height: a lazy grid inside an
                // unbounded/parent-sized column expands to its full content and
                // stops being lazy (that is how the picker ended up firing ~80
                // full-size downloads at once). Everything else stays compact.
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (loading) {
                    HiLoadingHint(
                        text = stringResource(R.string.rss_content_loading),
                        iconSize = 18.dp,
                    )
                }
                if (images.isNotEmpty()) {
                    // Count and 全选/清空 share one row: the picker gets a tighter
                    // header and the buttons sit next to what they act on.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.rss_article_images, images.size),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (galleryLoading) {
                            Icon(
                                Icons.Outlined.HourglassEmpty,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        // 「重新抓取」从底部移到表头（图标）：底部操作行只剩
                        // 「确定 + 加入分组 (N)」，窄屏（用户手机 393dp）不会再
                        // 被 FlowRow 折成上下两行。
                        if (article.link.isNotBlank()) {
                            IconButton(
                                onClick = { retryKey++ },
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = stringResource(R.string.rss_reload),
                                    tint = LocalAccentColor.current,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        // 全选 / 清空合并成一个按钮（已全选时显示「清空」）。原来两个
                        // 按钮加上「已选 N 张」药丸，把「文章图片 (N)」挤成了两行。
                        val allSelected = images.isNotEmpty() && selected.size == images.size
                        TextButton(
                            onClick = {
                                selected = if (allSelected) emptySet() else images.toSet()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                stringResource(
                                    if (allSelected) R.string.rss_clear_selection
                                    else R.string.rss_select_all
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    if (images.size <= PICKER_GRID_MIN_LAZY) {
                        // A short list (a video source's one or two streams, a
                        // small gallery) has nothing to scroll: plain rows keep
                        // the dialog compact.
                        images.chunked(3).forEach { rowImages ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(GRID_SPACING)
                            ) {
                                rowImages.forEach { url ->
                                    RssPickerTile(
                                        url = url,
                                        selected = url in selected,
                                        referer = referer,
                                        headers = imageHeaders,
                                        onClick = {
                                            selected = if (url in selected) {
                                                selected - url
                                            } else {
                                                selected + url
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(3 - rowImages.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            // Definite height: a lazy grid only skips off-screen
                            // tiles when its own height is bounded.
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(gridMaxHeight),
                            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
                            verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
                            // 底部留一点空隙：最后一行被裁切时不会紧贴对话框边缘。
                            contentPadding = PaddingValues(bottom = 8.dp),
                        ) {
                            items(images, key = { it }) { url ->
                                RssPickerTile(
                                    url = url,
                                    selected = url in selected,
                                    referer = referer,
                                    headers = imageHeaders,
                                    onClick = {
                                        selected = if (url in selected) {
                                            selected - url
                                        } else {
                                            selected + url
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                // 选择器只负责选图：正文不再显示（原来网格下方会展开一大段
                // 文章正文，用户反馈"图片下面有文字信息"）。
                if (!loading && images.isEmpty()) {
                    // The "no content" hint only matters when the picker is
                    // empty; under a full grid it was just noise.
                    Text(
                        stringResource(R.string.rss_content_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            // 「确定」放在整行最右边（用户要求）。M3 的 AlertDialog 里
            // dismissButton 在左、confirmButton 在右，所以这里把「确定」放进
            // 右槽；「加入分组 (N)」作为主操作放在左槽（原来它在 confirmButton
            // 里和「重新抓取」挤成一行，窄屏会被折成上下两排）。
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            if (images.isNotEmpty()) {
                FilledTonalButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { showGroupPicker = true },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.rss_add_to_group_count, selected.size))
                }
            }
        }
    )

    if (showBrowser) {
        RssWebArticleDialog(
            source = source,
            url = article.link,
            ruleHtml = html,
            onDismiss = { showBrowser = false },
            onCollect = { collected ->
                showBrowser = false
                // Hand the page's session cookies to the importer: video/image
                // hosts commonly 403 a download that has no session cookie.
                try {
                    val cookie = android.webkit.CookieManager.getInstance()
                        .getCookie(article.link)
                    com.wallpaperswitcher.engine.RssCookieStore
                        .injectCookieHeader(article.link, cookie)
                } catch (_: Throwable) {
                }
                // The picker shows exactly what the browser rendered - the
                // statically parsed list is replaced, not merged - and the
                // source's own filter drops thumbnails/site chrome so the
                // wallpaper matches what was on screen.
                val ruleContent = source?.let {
                    com.wallpaperswitcher.engine.legado.RssSourceEditor
                        .fieldValue(it.rawJson, "ruleContent")
                }
                images = collected
                    .distinct()
                    .filter { com.wallpaperswitcher.engine.legado.LegadoRss
                        .keepCollectedImage(ruleContent, it) }
                    .ifEmpty { collected.distinct() }
                selected = images.toSet()
            }
        )
    }


    if (showGroupPicker) {
        AlertDialog(
            onDismissRequest = { showGroupPicker = false },
            title = { Text(stringResource(R.string.rss_pick_group)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.online_group_auto),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addRssImagesToGroup(
                                    article, selected.toList(), 0L, downloadHeaders
                                )
                                showGroupPicker = false
                            }
                            .padding(vertical = 12.dp)
                    )
                    groups.forEach { group ->
                        Text(
                            group.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.addRssImagesToGroup(
                                        article, selected.toList(), group.id, downloadHeaders
                                    )
                                    showGroupPicker = false
                                }
                                .padding(vertical = 12.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGroupPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun rssStatusText(source: RssSource): String {
    // 单 URL / 网页型源不抓取，只提示点开用浏览器。
    if (com.wallpaperswitcher.engine.legado.LegadoRss.isBrowseOnly(source)) {
        return stringResource(R.string.rss_browse_source)
    }
    if (source.lastFetchAt <= 0L) return stringResource(R.string.online_never_updated)
    val info = OnlineSourceRules.decodeResult(source.lastResult)
        ?: return stringResource(R.string.online_never_updated)
    return if (info.isError) {
        stringResource(R.string.online_status_error, onlineErrorText(info.reason))
    } else if (info.added > 0) {
        stringResource(R.string.rss_status_ok, info.added)
    } else {
        stringResource(R.string.online_status_ok_none)
    }
}

/**
 * `_TPL_.mp4` is the tube sites' poster file: it has a video extension but is
 * a still image, so it must not be offered (or downloaded) as a wallpaper.
 */
private fun looksLikePlayerPlaceholder(url: String): Boolean =
    url.substringBefore('?').lowercase().contains("_tpl_")

/** One selectable picture in the article picker. */
@Composable
private fun RssPickerTile(
    url: String,
    selected: Boolean,
    referer: String?,
    headers: Map<String, String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            // 0.75 → 0.8：格子略矮一点，同样的高度能多显示一行。
            .aspectRatio(0.8f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selected) Modifier.border(
                    2.5.dp,
                    LocalAccentColor.current,
                    RoundedCornerShape(12.dp),
                ) else Modifier
            )
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = imageRequest(context, url, referer, headers),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (selected) {
            // 选中：轻压暗 + 强调色实心角标（与分组网格、美人图选择器同一套）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(LocalAccentColor.current),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

/**
 * Video-source picker filter: real streams only (mp4/webm/mov/mkv **and** HLS
 * playlists - the importer merges HLS, and the player streams it). Stills such
 * as `_TPL_.mp4` posters and HLS segments are excluded.
 */
private val VIDEO_STREAM_EXT =
    Regex("""\.(mp4|webm|mov|m4v|mkv|m3u8)(\?|$)""")

private fun looksLikeVideoStream(url: String): Boolean {
    val lower = url.lowercase()
    if (looksLikePlayerPlaceholder(lower)) return false
    val path = lower.substringBefore('?')
    if (path.endsWith(".ts") || path.endsWith(".m4s")) return false
    return VIDEO_STREAM_EXT.containsMatchIn(lower)
}
