package com.wallpaperswitcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.OnlineSource
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.OnlineBrowser
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.launch

/**
 * 网盘浏览页：文件夹一行行、媒体三列缩略图网格。
 *
 * 三种用法：
 *  - **点媒体**：在线观看（不下载，见 OnlineMediaViewerDialog）；
 *  - **勾选媒体** + 底部「下载所选」：批量下载进壁纸库；
 *  - **文件夹右侧的 ▶**：递归下载整个文件夹（有层数/条数上限）。
 *
 * 缩略图走 Coil + Basic 认证头在线加载（磁盘缓存命中就不重复拉），**不写入壁纸库**；
 * 视频没有先帧解码器（Coil 未注册 VideoFrameDecoder），所以视频格子显示摄像机图标 +
 * 尺寸，点开才真正播放。
 */
@Composable
internal fun OnlineBrowserDialog(
    viewModel: WallpaperViewModel,
    source: OnlineSource,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    /** true = 直接作为页面内容渲染（WebDAV 页内联，系统底栏保持可见）。 */
    embedded: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val viewMode by viewModel.webdavViewMode.collectAsStateWithLifecycle()
    // 面包屑从来源配置的目录开始，别让用户从服务器根目录点下来。
    val configSegments = remember(source.id) {
        source.webdavPath.trim('/').split('/').filter { it.isNotBlank() }
    }
    val pathStack = remember(source.id) { mutableStateListOf(*configSegments.toTypedArray()) }
    // 「返回」最多退到配置目录这一层：退过头会让后续请求丢掉 /夸克 前缀（实测 404）。
    val floor = configSegments.size
    val path = pathStack.joinToString("/")
    var entries by remember { mutableStateOf<List<OnlineBrowser.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    // 「多选」开关：开启后文件夹与媒体格子都出现勾选框，可一起"添加到分组"。
    var selecting by remember { mutableStateOf(false) }
    // 下载全部走后台（viewModelScope）：页面里不再保留"正在下载"状态，
    // 以前它一旦没被复位就会把文件夹点击全部挡掉（用户报的"返回后点文件夹没反应"）。
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var currentName by remember { mutableStateOf("") }
    var busyName by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    /** 缩略图/查看器共用的认证头（WebDAV Basic）。 */
    var authHeaders by remember(source.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    /** 查看器打开的媒体列表（null = 没打开）与起始下标。 */
    var viewing by remember { mutableStateOf<List<OnlineBrowser.Entry>?>(null) }
    var viewStart by remember { mutableStateOf(0) }
    var viewHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    /** 正在为哪些项选目标分组（null = 没在选）；单选/多选共用这一条路径。 */
    var pickEntries by remember { mutableStateOf<List<OnlineBrowser.Entry>?>(null) }
    var applying by remember { mutableStateOf(false) }
    /** 正在添加到分组的那一项 + 下载进度（字节, 总字节）。 */
    /** 分组选择器里「新建分组」的输入框状态。 */
    var creatingGroup by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    /**
     * 网格一次先渲染多少项：WebDAV 一层可能有上千项，一次性建 2000 个 Coil 请求会让
     * 首屏很卡。滚到底点「加载更多」再放一批（这不是服务端分页，只控制渲染量）。
     */
    var showLimit by remember(path, query) { mutableStateOf(GRID_PAGE) }
    /** 子目录搜索结果（null = 没搜过；空列表 = 搜了没找到）。 */
    var deepHits by remember(query) { mutableStateOf<List<OnlineBrowser.Entry>?>(null) }
    var deepSearching by remember { mutableStateOf(false) }

    // 目录滚动位置：返回上一级时回到原来的位置，而不是跳回顶部。
    //
    // 只在 path 变化时 scrollToItem 是**不够的**：加载分支（"正在读取目录…"）期间没有
    // 网格，此时 scrollToItem 打到空列表上会被夹到 0；目录命中缓存时 loading 的
    // true→false 又可能被合并，之后不会再有重试。所以改成"数据到位后再恢复一次"。
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // 位置按「目录 + 视图模式」分开存：网格 3 列与列表 1 列的行高/条目结构不同，
    // 混用同一下标会让列表模式回不到原位；同时记录格内偏移，避免"停在原位置上方"。
    val posKey = "$path|$viewMode"
    val scrollPositions = remember(source.id) { mutableMapOf<String, Pair<Int, Int>>() }
    // 滚动中持续记录当前目录的位置（比在 dispose 时再取更可靠）。
    LaunchedEffect(posKey, gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }
            .collect { scrollPositions[posKey] = it }
    }

    LaunchedEffect(source.id) {
        authHeaders = viewModel.onlineMediaHeaders(source.id)
    }
    LaunchedEffect(path) {
        loading = true
        error = null
        val result = viewModel.browseOnlineSource(source.id, "/" + path)
        if (result.isSuccess) {
            entries = result.getOrDefault(emptyList())
        } else {
            entries = emptyList()
            error = result.exceptionOrNull()?.message ?: "network"
        }
        loading = false
    }

    /** 在线观看：打开当前目录的媒体列表（像 OpenList 那样可左右滑）。只取认证头，不下载。 */
    val visible = entries.filter { e ->
        query.isBlank() || e.displayName.contains(query.trim(), ignoreCase = true)
    }
    val folders = visible.filter { it.isDir }

    // 数据到位后再恢复滚动位置（加载期间没有网格，早滚会被夹到 0）。
    var restorePath by remember(source.id) { mutableStateOf<String?>(null) }
    // 换目录：标一次"待恢复"，等列表真有数据再滚（否则会被夹到 0）。
    LaunchedEffect(posKey) { restorePath = posKey }
    LaunchedEffect(posKey, loading, visible.size) {
        if (restorePath == posKey && !loading && visible.isNotEmpty()) {
            val (index, offset) = scrollPositions[posKey] ?: (0 to 0)
            if (index > 0 || offset > 0) gridState.scrollToItem(index, offset)
            restorePath = null
        }
    }
    val media = visible.filter { !it.isDir }

    fun openViewer(entry: OnlineBrowser.Entry) {
        // 下载已改后台（viewModelScope），这里不再因为"正在下载"而挡住打开操作。
        message = null
        // 深搜命中的条目不在当前目录的 media 里：用深搜结果当翻页数据源，
        // 否则 indexOfFirst 返回 -1 → 会打开当前目录的第一张（真 bug）。
        val hits = deepHits.orEmpty()
        val list = when {
            media.any { it.path == entry.path } -> media
            hits.any { it.path == entry.path } -> hits
            else -> listOf(entry)
        }
        viewStart = list.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        scope.launch {
            viewHeaders = viewModel.onlineMediaHeaders(source.id)
            viewing = list
        }
    }

    /** 「添加到分组」（查看器单选 / 列表多选共用）：逐项下载入库到所选分组。 */
    fun addToGroup(targets: List<OnlineBrowser.Entry>, groupId: Long) {
        if (applying || targets.isEmpty()) return
        // 立刻收起查看器：进度框盖在浏览页上，加完就停在"当前目录"。
        viewing = null
        // 交给 ViewModel 跑：协程挂在 viewModelScope 上，退出浏览页/切 Tab 也不会
        // 被取消（以前用 rememberCoroutineScope，一关页面下载就断）。
        viewModel.importOnlineToGroup(source.id, targets, groupId)
        // 加完立刻退出「全选」模式、清空勾选（用户要求）：回来后是干净的浏览态。
        selecting = false
        selected.clear()
    }

    /** 批量下载一组条目（多选 / 整个文件夹）：全在后台跑，进度显示在分组详情页。 */
    fun downloadAll(targets: List<OnlineBrowser.Entry>) {
        if (targets.isEmpty()) return
        message = null
        // 不再用页面自己的 scope：改用 viewModelScope 的入口，离开浏览页也不会中断，
        // 进度条由「分组详情 → 添加壁纸下面」那条显示（见 GroupDetailScreen）。
        viewModel.importOnlineEntriesBackground(source.id, targets)
    }


    fun thumbRequest(entry: OnlineBrowser.Entry): ImageRequest? {
        // 视频给 null：Coil 的 VideoFrameDecoder 要读完整个文件才能解帧，网盘视频动辄
        // 几百 MB；改由 OnlineVideoThumbs 走 Range 取远端首帧。
        if (entry.mediaType == MediaTypes.VIDEO) return null
        val url = entry.url ?: return null
        return ImageRequest.Builder(context)
            .data(url)
            .apply { authHeaders.forEach { (k, v) -> addHeader(k, v) } }
            // 按格子尺寸解码（3 列 ≈700px）：原图动辄 3000×4000，全尺寸解码是网格
            // 滚动卡顿的主因，缩到 512 后内存与解码时间都降一个量级。
            .size(THUMB_PX)
            .crossfade(true)
            .build()
    }

    // 内容本体：embedded=true 时由 WebDAV 页直接内联渲染（底栏还在），否则套一层 Dialog。
    val content: @Composable () -> Unit = {
        // 返回键注册在 Dialog 里面（叠层内），并且只在"没有更上层 UI"时生效：
        // 查看器、分组选择器、新建分组、进度框各自处理自己的返回，
        // 否则按一下会把底下的浏览页也退掉。
        androidx.activity.compose.BackHandler(
            enabled = viewing == null && pickEntries == null &&
                !creatingGroup && !applying
        ) {
            if (pathStack.size > floor) {
                pathStack.removeAt(pathStack.lastIndex)
            } else {
                onDismiss()
            }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶栏：返回一层 / 路径 / 编辑来源
                Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (pathStack.size > floor) {
                                pathStack.removeAt(pathStack.lastIndex)
                            } else {
                                onDismiss()
                            }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                    Text(
                        text = source.name.ifBlank { "/" } +
                            (if (pathStack.size <= floor) "" else
                                " / " + pathStack.drop(floor).joinToString(" / ") {
                                    decodeForDisplay(it)
                                }),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 「全选」：一键勾选当前目录里的全部内容（文件夹 + 媒体，含深搜命中），
                    // 再点一次=取消；逐个勾选/取消仍可在格子上点。
                    TextButton(
                        enabled = viewing == null && pickEntries == null,
                        onClick = {
                            if (selecting) {
                                selecting = false
                                selected.clear()
                            } else {
                                selecting = true
                                selected.clear()
                                // 全选：当前目录的全部条目（含子目录与深搜命中），去重后勾上。
                                (visible + deepHits.orEmpty()).distinctBy { it.path }
                                    .forEach { selected.add(it.path) }
                            }
                        },
                    ) {
                        Text(
                            text = if (selecting) stringResource(R.string.action_cancel) else "全选",
                            color = if (selecting) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        // 视图切换（网格/列表）属于"这个源的浏览习惯"，放在源自己的浏览页顶栏。
                        viewModel.setWebdavViewMode(if (viewMode == "grid") "list" else "grid")
                    }) {
                        Text(
                            text = if (viewMode == "grid") "☰" else "▦",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    TextButton(onClick = onEdit) {
                        Text(stringResource(R.string.online_browse_edit))
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.online_browse_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
                Divider()
                when {
                    loading -> Text(
                        stringResource(R.string.online_browse_loading),
                        modifier = Modifier.padding(16.dp),
                    )

                    error != null -> Text(
                        // 把内部错误码翻译成人话：超时/不可达/不是 WebDAV 目录。
                        text = when (error) {
                            "timeout" -> stringResource(R.string.online_browse_err_timeout)
                            "network" -> stringResource(R.string.online_browse_err_network)
                            "parse" -> stringResource(R.string.online_browse_err_parse)
                            "auth" -> stringResource(R.string.online_browse_err_auth)
                            else -> if (error!!.startsWith("http:")) {
                                // 服务器返回的状态码别再裸奔：http:404 这种直接给一句人话。
                                stringResource(
                                    R.string.online_browse_err_http,
                                    error!!.removePrefix("http:"),
                                )
                            } else {
                                stringResource(R.string.online_browse_error, error!!)
                            }
                        },
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )

                    visible.isEmpty() -> Column(modifier = Modifier.padding(16.dp)) {
                        // 区分两种"空"：目录本来就空 / 只是当前关键字没有匹配。
                        Text(
                            text = if (query.isBlank()) {
                                stringResource(R.string.online_browse_empty)
                            } else {
                                stringResource(R.string.online_browse_no_match, query.trim())
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (query.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    enabled = !deepSearching,
                                    onClick = {
                                        deepSearching = true
                                        deepHits = null
                                        scope.launch {
                                            val result = viewModel.searchOnlineFolder(
                                                source.id, "/" + path, query
                                            )
                                            deepSearching = false
                                            deepHits = result.getOrDefault(emptyList())
                                        }
                                    },
                                ) {
                                    Text(
                                        if (deepSearching) {
                                            stringResource(R.string.online_browse_searching)
                                        } else {
                                            stringResource(R.string.online_browse_search_deep)
                                        }
                                    )
                                }
                            }
                            deepHits?.let { hits ->
                                if (hits.isEmpty()) {
                                    Text(
                                        stringResource(R.string.online_browse_no_match_deep),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(
                                        stringResource(R.string.online_browse_deep_hits, hits.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(GRID_COLUMNS),
                                        modifier = Modifier.heightIn(max = 900.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        items(hits, key = { "deep:${it.path}" }) { entry ->
                                            val isSelected = entry.path in selected
                                            MediaTile(
                                                entry = entry,
                                                request = thumbRequest(entry),
                                                selected = isSelected,
                                                busy = busyName == entry.displayName,
                                                authHeaders = authHeaders,
                                                onOpen = { openViewer(entry) },
                                                onToggle = {
                                                    if (isSelected) selected.remove(entry.path)
                                                    else selected.add(entry.path)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> LazyVerticalGrid(
                        state = gridState,
                        // 列表模式用 1 列：每行占满宽度，否则列表行会被挤进 1/3 宽的格子里
                        // （真机表现就是"只剩一行/一行里挤三个"）。
                        columns = GridCells.Fixed(if (viewMode == "list") 1 else GRID_COLUMNS),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 「上一级」：返回上一个目录，而不是让用户点到"同名母目录"再吃 404。
                        if (pathStack.size > floor) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                                pathStack.removeAt(pathStack.lastIndex)
                                            }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.online_browse_parent),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                Divider(modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        }
                        if (folders.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = stringResource(R.string.online_browse_folders),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        start = 8.dp, top = 4.dp, bottom = 4.dp
                                    ),
                                )
                            }
                            if (viewMode == "grid") {
                                // 文件夹也用网格卡片（与媒体同一套格子），右侧小图标=下载整个文件夹。
                                items(folders, key = { "dir:${it.path}" }) { entry ->
                                    FolderTile(
                                        name = entry.displayName,
                                        enabled = true,
                                        selecting = selecting,
                                        checked = entry.path in selected,
                                        onToggle = {
                                            if (entry.path in selected) selected.remove(entry.path)
                                            else selected.add(entry.path)
                                        },
                                        onOpen = {
                                        pathStack.add(entry.name)
                                        },
                                    )
                                }
                            } else {
                                // 列表模式的文件夹**每个一行、各自是独立的 lazy item**：
                                // 之前把它们塞进一个 item 的 Column 里，整列算作一个 item，
                                // firstVisibleItemIndex 恒为 0/1 —— 返回时"位置恢复"，
                                // 记录的永远是同一个值，看起来就是回到别的位置。
                                items(folders, key = { "dir:${it.path}" }, span = { GridItemSpan(maxLineSpan) }) { entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (selecting) {
                                                    if (entry.path in selected) selected.remove(entry.path)
                                                    else selected.add(entry.path)
                                                } else {
                                                    pathStack.add(entry.name)
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        if (selecting) {
                                            Checkbox(
                                                checked = entry.path in selected,
                                                onCheckedChange = {
                                                    if (entry.path in selected) selected.remove(entry.path)
                                                    else selected.add(entry.path)
                                                },
                                            )
                                        }
                                        Icon(
                                            Icons.Outlined.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            entry.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    // 不再逐行画分隔线：上百个目录时会堆成"细线墙"，
                                    // 靠行高与图标对齐已经够分辨（用户要求）。
                                }
                            }
                        }
                        // 目录与媒体之间保留一条分隔线（行内不再逐条画线）。
                        if (folders.isNotEmpty() && media.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Divider(modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        }
                        items(media.take(showLimit), key = { it.path }) { entry ->
                            val isSelected = entry.path in selected
                            if (viewMode == "list") {
                                // 列表视图：一行一条，缩略图 + 名字 + 大小（大目录更好扫）。
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { openViewer(entry) }
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = {
                                            if (isSelected) selected.remove(entry.path)
                                            else selected.add(entry.path)
                                        },
                                    )
                                    val req = thumbRequest(entry)
                                    if (req != null) {
                                        AsyncImage(
                                            model = req,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                        )
                                    } else {
                                        Icon(
                                            Icons.Outlined.Videocam,
                                            contentDescription = null,
                                            modifier = Modifier.size(56.dp),
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (entry.size > 0L) {
                                            Text(
                                                text = formatSize(entry.size),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            } else {
                                MediaTile(
                                    entry = entry,
                                    request = thumbRequest(entry),
                                    selected = isSelected,
                                    busy = busyName == entry.displayName,
                                    authHeaders = authHeaders,
                                    onOpen = { openViewer(entry) },
                                    onToggle = {
                                        if (isSelected) selected.remove(entry.path)
                                        else selected.add(entry.path)
                                    },
                                )
                            }
                        }
                        if (media.size > showLimit) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    TextButton(onClick = { showLimit += GRID_PAGE }) {
                                        Text(
                                            stringResource(
                                                R.string.online_browse_load_more,
                                                media.size - showLimit,
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 底部批量条：多选模式下常驻（便于看到"已选 0"与取消），有选择时提供动作。
                if (selecting || selected.isNotEmpty()) {
                    Divider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.online_browse_selected, selected.size),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { selected.clear() }) {
                            Text(stringResource(R.string.online_browse_clear))
                        }
                        TextButton(
                            enabled = !applying && selected.isNotEmpty(),
                            onClick = {
                                // 勾选里可能既有媒体也有文件夹：
                                // 媒体直接用；文件夹按"该目录下的全部媒体"展开（上限 200/个），
                                // 再一起交给分组选择器。
                                scope.launch {
                                    applying = true
                                    try {
                                        val mediaTargets = (entries + deepHits.orEmpty())
                                            .distinctBy { it.path }
                                            .filter { it.path in selected }
                                        val folderPaths = (folders + deepHits.orEmpty())
                                            .filter { it.isDir && it.path in selected }
                                            .map { it.path }
                                            .distinct()
                                        val expanded = ArrayList<OnlineBrowser.Entry>(mediaTargets)
                                        for (dir in folderPaths) {
                                            viewModel.collectOnlineFolder(source.id, dir)
                                                .getOrNull()
                                                ?.let { expanded.addAll(it) }
                                        }
                                        val targets = expanded.distinctBy { it.path }
                                        selected.clear()
                                        if (targets.isEmpty()) {
                                            message = "folder_empty"
                                        } else {
                                            pickEntries = targets
                                        }
                                    } finally {
                                        applying = false
                                    }
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    R.string.online_viewer_add_to_group_count, selected.size
                                )
                            )
                        }
                    }
                }
                message?.let { raw ->
                    val text = when {
                        raw == "folder_empty" -> stringResource(R.string.online_browse_empty)
                        raw == "download_failed" ->
                            stringResource(R.string.online_browse_download_failed)
                        raw == "added" -> stringResource(R.string.online_browse_added)
                        raw.startsWith("added:") -> {
                            // "added:ok" / "added:ok:failed" —— 必须自己解析，
                            // 否则会把内部串（added:3:1）原样显示给用户。
                            val parts = raw.split(':')
                            val ok = parts.getOrNull(1)?.toIntOrNull() ?: 0
                            val failed = parts.getOrNull(2)?.toIntOrNull() ?: 0
                            if (failed == 0) {
                                stringResource(R.string.online_browse_added_count, ok)
                            } else {
                                stringResource(R.string.online_browse_added_partial, ok, failed)
                            }
                        }
                        raw == "view_failed" ->
                            stringResource(R.string.online_viewer_failed)
                        raw.startsWith("ok:") -> {
                            val parts = raw.split(':')
                            val ok = parts.getOrNull(1)?.toIntOrNull() ?: 0
                            val failed = parts.getOrNull(2)?.toIntOrNull() ?: 0
                            if (failed == 0) {
                                stringResource(R.string.online_browse_batch_ok, ok)
                            } else {
                                stringResource(R.string.online_browse_batch_partial, ok, failed)
                            }
                        }

                        else -> raw
                    }
                    Text(
                        text = text,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    viewing?.let { list ->
        OnlineMediaViewerDialog(
            entries = list,
            startIndex = viewStart,
            headers = viewHeaders,
            applying = applying,
            onDismiss = { viewing = null },
            onAddToGroup = { entry -> pickEntries = listOf(entry) },
        )
    }
    // 「添加到分组」：先选分组，再下载入库（选已有分组时不会覆盖同步分组）。
    // 「添加到分组」：先选分组，再下载入库（选已有分组时不会覆盖同步分组）。
    pickEntries?.let { targets ->
        val groups by viewModel.groups.collectAsStateWithLifecycle()
        GroupPickerDialog(
            groups = groups,
            onDismiss = { pickEntries = null },
            onPick = { groupId ->
                pickEntries = null
                addToGroup(targets, groupId)
            },
            onCreateGroup = { creatingGroup = true },
        )
    }
    // 新建分组 → 建完立刻把这批下进去。
    if (creatingGroup) {
        CreateGroupDialog(
            name = newGroupName,
            onNameChange = { newGroupName = it },
            onConfirm = {
                val name = newGroupName.trim()
                val batch = pickEntries
                creatingGroup = false
                newGroupName = ""
                pickEntries = null
                if (batch != null && name.isNotEmpty()) {
                    viewModel.createGroupAndOpen(name) { newId ->
                        if (newId > 0L) addToGroup(batch, newId)
                    }
                }
            },
            onDismiss = {
                creatingGroup = false
                newGroupName = ""
            },
        )
    }
    // 进度只在**页面顶部**显示（不再弹模态进度框）：下载本身在 viewModelScope 上跑，
    // 弹窗既挡操作、又在离开页面时显得"中断"。见下面 content() 里的顶部条。
    // 「已添加 N 项」的底部提示已按用户要求去掉：进度条结束就代表完成。
    // 结果码仍然要取走，否则下一次会立刻又被当成新结果。
    LaunchedEffect(Unit) {
        viewModel.onlineImportResult.collect { viewModel.clearOnlineImportResult() }
    }
    if (embedded) {
        content()
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) { content() }
    }
}
/** 网格模式下的文件夹卡片：图标 + 名称 + 右下角"下载整个文件夹"。 */
@Composable
private fun FolderTile(
    name: String,
    selecting: Boolean = false,
    checked: Boolean = false,
    onToggle: () -> Unit = {},
    enabled: Boolean = true,
    onOpen: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable { if (selecting) onToggle() else onOpen() },
    ) {
        Icon(
            Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).size(44.dp),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, bottom = 4.dp),
        )
    }
}
/** 一格媒体：缩略图 + 名称 + 勾选框；视频用远端首帧（见 OnlineVideoThumbs）。 */
@Composable
private fun MediaTile(
    entry: OnlineBrowser.Entry,
    request: ImageRequest?,
    selected: Boolean,
    busy: Boolean,
    authHeaders: Map<String, String>,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    /** 勾选框常显（用户要求）：不进入选择态也能直接点格子勾选。 */
    showCheck: Boolean = true,
) {
    val isVideo = entry.mediaType == MediaTypes.VIDEO
    val context = LocalContext.current
    // 视频不走 Coil：Coil 的 VideoFrameDecoder 需要读完整个文件，网盘视频几百 MB。
    var videoFrame by remember(entry.path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(entry.path) {
        if (isVideo && entry.url != null) {
            videoFrame = com.wallpaperswitcher.engine.OnlineVideoThumbs.load(
                context, entry.url!!, authHeaders
            )
        }
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onOpen),
    ) {
        val frame = videoFrame
        when {
            isVideo && frame != null -> Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = entry.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            !isVideo && request != null -> AsyncImage(
                model = request,
                contentDescription = entry.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            else -> Icon(
                imageVector = if (isVideo) Icons.Outlined.Videocam
                else Icons.Outlined.ImageNotSupported,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // 视频角标（不再盖在画面正中）：右下角一个播放标记。
        if (isVideo) {
            Icon(
                Icons.Outlined.PlayCircleOutline,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 22.dp)
                    .size(22.dp),
            )
        }
        // 底部名称条
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        if (busy) {
            Text(
                text = stringResource(R.string.online_browse_downloading),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (showCheck) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.align(Alignment.TopEnd),
        )
        }
    }
}

private const val GRID_COLUMNS = 3

/** 缩略图解码尺寸（px）：网格 3 列，512 足够清晰且省内存。 */
private const val THUMB_PX = 512

/** 「加载更多」每次追加的媒体数量（3 列的整数倍）。 */
private const val GRID_PAGE = 60

/** 1.2 MB / 345 KB 这种人类可读大小。 */

/** 顶栏展示用：把 percent-encoded 的路径段还原成可读文字。 */
private fun decodeForDisplay(segment: String): String = try {
    if (segment.contains('%')) java.net.URLDecoder.decode(segment, "UTF-8") else segment
} catch (_: Throwable) {
    segment
}
