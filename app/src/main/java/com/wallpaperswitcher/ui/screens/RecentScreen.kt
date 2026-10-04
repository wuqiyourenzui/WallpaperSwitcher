package com.wallpaperswitcher.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.RecentShownEntry
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.ui.theme.HiCard
import com.wallpaperswitcher.ui.theme.HiDims
import com.wallpaperswitcher.ui.theme.HiEmptyState
import com.wallpaperswitcher.ui.theme.HiLoadingState
import com.wallpaperswitcher.ui.theme.HiMotion
import com.wallpaperswitcher.ui.theme.LocalAccentColor
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlinx.coroutines.delay

/** 页面一次显示多少行。 */
internal const val RECENT_LIST_LIMIT = 60

/**
 * 从库里拉多少行：**比显示的多一倍**。
 *
 * 两屏合并时同一张图会有两行（"桌面显示过"和"锁屏显示过"各一行，`recent_shown`
 * 的主键是 (slot, mediaId)），[mergeRecentEntries] 去重之后只剩一行 —— 如果按
 * [RECENT_LIST_LIMIT] 去拉，一个两面都轮换的分组会把列表吃掉一半。
 */
internal const val RECENT_FETCH_LIMIT = RECENT_LIST_LIMIT * 2

/**
 * 缩略图解码尺寸。
 *
 * 单元格 64dp，3x 密度下约 192px；有意解码得比它小一点，换取更小的解码与位图
 * 内存（和分组九宫格同一个理由，见 GroupDetailScreen 的同名常量）。
 */
private const val RECENT_THUMBNAIL_DECODE_SIZE = 176

/** 缩略图边长。 */
private val RECENT_THUMBNAIL_SIZE = 64.dp

/** 相对时间的刷新节奏：文案最小单位是分钟，30s 一跳足够。 */
private const val RECENT_CLOCK_TICK_MS = 30_000L

// ---------------------------------------------------------------------------
// 纯逻辑：不碰 Compose / Room / Android 框架，所以每个边界都能在单测里钉住
// （RecentRollbackLogicTest）。
// ---------------------------------------------------------------------------

/**
 * 推回时写给哪块屏。
 *
 * 锁屏项必须显式传 [WallpaperTarget.SLOT_LOCK]：ViewModel 里那条分支保证即使分组
 * 目标是 BOTH 也只写锁屏，用户点一下就能把锁屏单独换回去，桌面不动 —— 这正是
 * "手滑切走一张好图"最常发生的地方。
 *
 * 桌面项返回 null（= 按分组自己的目标写）。ViewModel 只特判 LOCK，传 SLOT_HOME
 * 和传 null 走的是同一条路径，所以这里选 null：让"桌面项照分组目标来"这件事在
 * 代码里是明确的，而不是靠一个恰好等价的参数。
 */
internal fun recentForceSlot(slot: String): String? =
    if (slot == WallpaperTarget.SLOT_LOCK) WallpaperTarget.SLOT_LOCK else null

/**
 * 把 `recent_shown` 的行整理成页面要显示的列表：按媒体去重（保留最新那行）
 * → 按时间倒序 → 截断到 [keep]。
 *
 * 去重是这里最关键的一步，也是本页的一个**取舍**：同一张图在桌面和锁屏各显示过
 * 一次就是两行，不去重的话列表里会出现同一张缩略图两次（用户要找的是"那张图"，
 * 不是"某一次显示"，而且 60 条上限会被同几张图占满）。保留最新那行意味着这一行
 * 标注的是它**最后一次**显示在哪块屏，点下去就回到那块屏；代价是一张图如果在
 * 两块屏上都显示过，用户没法在这一页指定回另一块屏（那块屏等轮换、或在分组里
 * 重新选一张）。
 *
 * 排序与截断放在去重之后做：SQL 已经 ORDER BY shownAt DESC，但去重会把行挪走，
 * 调用方不该依赖输入顺序。
 */
internal fun mergeRecentEntries(
    rows: List<RecentShownEntry>,
    keep: Int = RECENT_LIST_LIMIT,
): List<RecentShownEntry> {
    if (rows.isEmpty() || keep <= 0) return emptyList()
    // 按媒体 id 去重（INNER JOIN 出来的行必然是已落库的媒体，id > 0）。
    // 并列时保留先出现的那行 —— 输入一般是 shownAt DESC，也就是时间更近的那行。
    val newestById = LinkedHashMap<Long, RecentShownEntry>(rows.size)
    for (row in rows) {
        val kept = newestById[row.image.id]
        if (kept == null || row.shownAt > kept.shownAt) newestById[row.image.id] = row
    }
    return newestById.values.sortedByDescending { it.shownAt }.take(keep)
}

/**
 * 推回成功之后把这一项挪到最前，并把它的时间改成"刚刚"。
 *
 * 为什么是就地提升、而不是重新查一次库：手动推回并**不保证**往 `recent_shown`
 * 写一行新的（写入点在切换循环里，见 PickOptions.recordShown 的调用方），立刻
 * 回查经常读到的还是旧顺序、旧时间 —— 那看起来就是"点了没反应"。本地提升是立刻
 * 的，而且它描述的正是刚刚发生的事。
 */
internal fun promoteRecentEntry(
    entries: List<RecentShownEntry>,
    mediaId: Long,
    nowMs: Long,
): List<RecentShownEntry> {
    val promoted = entries.firstOrNull { it.image.id == mediaId } ?: return entries
    return buildList(entries.size) {
        add(promoted.copy(shownAt = nowMs))
        entries.forEach { if (it.image.id != mediaId) add(it) }
    }
}

/**
 * 一行的时间文案：资源 id + 要填进 `%1$d` 的数字。
 *
 * 拆成"资源 + 数字"而不是直接给字符串，是因为格式化要能单测，而 `stringResource`
 * 只能在 Compose 里调用。
 */
internal data class RecentAgoLabel(@StringRes val res: Int, val value: Long)

/**
 * 这一行的相对时间（"刚刚 / 5 分钟前 / 2 小时前 / 1 天前"）。
 *
 * 算术复用设置页那条 [agoParts]（"上次扫描：3 分钟前"），两处不会漂移；包一层是
 * 为了把**本页**要的边界钉死在单测里：0 秒和 59 秒都算"刚刚"、60 秒整进位到
 * 分钟、未来时间（时钟回拨 / 时区跳变）夹到 0 —— 列表里出现"-3 分钟前"是最容易
 * 被用户当成 bug 的一种。
 */
internal fun recentAgoLabel(shownAtMs: Long, nowMs: Long): RecentAgoLabel =
    when (val parts = agoParts(shownAtMs, nowMs)) {
        AgoParts.Never -> RecentAgoLabel(R.string.ago_never, 0L)
        AgoParts.JustNow -> RecentAgoLabel(R.string.ago_just_now, 0L)
        is AgoParts.Count -> RecentAgoLabel(parts.unit.agoLabelRes, parts.value)
    }

// ---------------------------------------------------------------------------
// 页面
// ---------------------------------------------------------------------------

/**
 * 「最近显示」回滚页：这一屏（或两屏合并）最近显示过的壁纸，新的在前，点一下就推回。
 *
 * 壁纸应用最容易踩的痛点就是"手滑切走一张好图"—— 数据其实一直都在
 * （`recent_shown` 表本来就是给"最近 N 张不重复"用的），这里把它变成可操作的历史。
 *
 * 数据（**都走 viewModel，不要直连 DAO**）：
 *  - `viewModel.recentShownRows(limit, slot = null)` —— 挂起函数，`LaunchedEffect`
 *    里取；`slot` 传 null = 两屏合并，传 `WallpaperTarget.SLOT_HOME`/`SLOT_LOCK`
 *    只看一块屏。行里同时带着 `slot` 和 `shownAt`，这两样 `WallpaperImage` 上没有，
 *    而本页两件事都要用：**哪块屏**决定推回时 forceSlot 传什么，**多久以前**是每行
 *    的第二行文案。
 *  - 推回：`viewModel.setImageAsWallpaper(image, forceSlot)`，`forceSlot` 传
 *    `WallpaperTarget.SLOT_LOCK` 就只写锁屏，否则按分组自己的目标写。
 *
 * 返回：顶部返回箭头 + 系统返回键都要能退出（`BackHandler { onBack() }`）。
 */
@Composable
fun RecentScreen(
    viewModel: WallpaperViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 这一页必须自己处理返回键：外层（WallpaperSwitcherApp）给所有非首页注册了一个
    // BackHandler，但它的分支表里 `Screen.Recent` 落到 `else -> Screen.Home` —— 那是
    // 分组详情这类页面的父级，而本页的父级是设置页。嵌套注册的 BackHandler 里，
    // 最内层后注册的那个先收到事件，所以这里的 onBack（回设置页）会赢。
    BackHandler { onBack() }

    var rows by remember { mutableStateOf<List<RecentShownEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    // 组名：行里只有 groupId，而第二行要显示"分组名 · 3 分钟前"，所以建一张
    // id -> name 的表（分组改名/删除后会自动跟着刷新）。
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val groupNames = remember(groups) { groups.associate { it.id to it.name } }

    // 进页面取一次。读失败和"没有历史"都返回空列表（recentShownRows 内部已经吞掉
    // 异常并记了日志），两者对用户都是"还没有记录"，不需要分开处理。
    LaunchedEffect(Unit) {
        isLoading = true
        rows = mergeRecentEntries(viewModel.recentShownRows(limit = RECENT_FETCH_LIMIT))
        isLoading = false
    }

    // 页面开着不动时，"刚刚"要能自己变成"1 分钟前"。最小单位是分钟，所以 30s 一跳
    // 就够；重组的代价只有可见的那几行（读 nowMs 的是行本身）。
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(RECENT_CLOCK_TICK_MS)
            nowMs = System.currentTimeMillis()
        }
    }

    // 一点就推回：写屏 + 就地提升都在这一处，避免"点了没反应"。
    //
    // 这里**不再自己弹 Toast**：`setImageAsWallpaper` 会按结果发自己的提示
    // （成功「已设为…」/ 失败的具体原因），再叠一句"已推回"在失败时就自相矛盾
    // （用户会先看到"已推回"、再看到"读不了这个文件"）。行本身滑到最前 + 时间
    // 变成"刚刚"已经是明确的成功反馈，比 Toast 更直接。
    val onPush: (RecentShownEntry) -> Unit = { row ->
        viewModel.setImageAsWallpaper(row.image, recentForceSlot(row.slot)) { ok ->
            if (ok) {
                val now = System.currentTimeMillis()
                rows = promoteRecentEntry(rows, row.image.id, now)
                nowMs = now
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            RecentHeader(onBack = onBack)

            when {
                isLoading -> HiLoadingState(
                    text = stringResource(R.string.state_loading),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                )

                rows.isEmpty() -> HiEmptyState(
                    title = stringResource(R.string.recent_empty),
                    icon = Icons.Outlined.History,
                    modifier = Modifier.padding(vertical = 60.dp),
                )

                else -> RecentList(
                    rows = rows,
                    groupNames = groupNames,
                    nowMs = nowMs,
                    onPush = onPush,
                )
            }
        }
    }
}

/**
 * 顶部只有返回箭头 + 一句"点一下会怎样"。
 *
 * 标题不在这里重复：外层 Scaffold 的 TopAppBar 已经按 `Screen.Recent` 显示
 * [R.string.recent_title]，而且这一页不在它的"子页面"名单里（那份名单只给分组
 * 详情、订阅文章等显示返回箭头），所以箭头由本页自己提供。
 */
@Composable
private fun RecentHeader(onBack: () -> Unit) {
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
        Text(
            text = stringResource(R.string.recent_push),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 历史列表。
 *
 * 用竖排列表而不是网格：每行要放"文件名 + 分组名 + 相对时间 + 是哪块屏"，网格
 * 单元格装不下（分组详情的九宫格是"选素材"，这里更像一条操作历史）。卡片沿用
 * [HiCard]（HyperOS 的圆角卡片 + 按压缩放），和设置页同一套观感。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentList(
    rows: List<RecentShownEntry>,
    groupNames: Map<Long, String>,
    nowMs: Long,
    onPush: (RecentShownEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = HiDims.PageHorizontal,
            end = HiDims.PageHorizontal,
            top = 4.dp,
            bottom = HiDims.PageBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(HiDims.CardSpacing),
    ) {
        items(
            items = rows,
            // 去重之后 id 唯一，可以作为稳定 key：推回置顶时 Compose 才能跟着
            // 移动同一张卡片，而不是重建两行。
            key = { it.image.id },
        ) { row ->
            RecentRow(
                row = row,
                groupName = groupNames[row.image.groupId],
                nowMs = nowMs,
                onPush = { onPush(row) },
                // 推回会把这一项提到最前（promoteRecentEntry）：让这次移动是滑上去，
                // 而不是瞬间跳位。这是 LazyItemScope 的 API，只能在 item 里调用。
                modifier = Modifier.animateItemPlacement(HiMotion.selection()),
            )
        }
    }
}

@Composable
private fun RecentRow(
    row: RecentShownEntry,
    groupName: String?,
    nowMs: Long,
    onPush: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val image = row.image
    // 单元格在滚动中会被复用：把请求记住，避免每次重组都重建（九宫格同理）。
    val request = remember(image.uri, image.mediaType, context) {
        buildRecentThumbnailRequest(context, image)
    }
    val placeholderPainter = remember { ColorPainter(Color(0xFFE0E0E0)) }
    val errorPainter = remember { ColorPainter(Color(0xFFBDBDBD)) }

    val ago = recentAgoLabel(row.shownAt, nowMs)
    val agoText = stringResource(ago.res, ago.value)
    val name = image.displayName.ifBlank { stringResource(R.string.item_untitled) }
    // 分组名只在真的取到时才拼进去（分组刚被删掉时就是 null）。
    val subtitle = groupName?.takeIf { it.isNotBlank() }?.let { "$it · $agoText" } ?: agoText

    HiCard(modifier = modifier, onClick = onPush) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(RECENT_THUMBNAIL_SIZE)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    // 视频/GIF 载不出来时给灰底方块，而不是留空或崩掉。
                    placeholder = placeholderPainter,
                    error = errorPainter,
                    modifier = Modifier.fillMaxSize(),
                )
                if (MediaTypes.isMotion(image.mediaType)) {
                    // 动图/视频在锁屏只能写第一帧（ViewModel 会提示），先标出来，
                    // 用户点之前就知道它不是一张静态图。
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = if (image.mediaType == MediaTypes.VIDEO) {
                                Icons.Filled.Videocam
                            } else {
                                Icons.Filled.Gif
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
            SlotBadge(slot = row.slot)
        }
    }
}

/**
 * 「哪块屏」标记：桌面 / 锁屏。
 *
 * 不只是装饰 —— 它决定的正是点下去写哪块屏（[recentForceSlot]），所以文字用的就是
 * 目标屏的名字；那枚恢复图标的无障碍说明是 [R.string.recent_push]，读屏用户听到的
 * 是"推回当前屏（锁屏）"。
 */
@Composable
private fun SlotBadge(slot: String, modifier: Modifier = Modifier) {
    val accent = LocalAccentColor.current.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Restore,
            contentDescription = stringResource(R.string.recent_push),
            tint = accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(
                if (slot == WallpaperTarget.SLOT_LOCK) {
                    R.string.recent_slot_lock
                } else {
                    R.string.recent_slot_home
                }
            ),
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 1,
        )
    }
}

/**
 * 列表缩略图的 Coil 请求。和分组九宫格同一套参数（那边的是 private，所以按同样的
 * 理由在这里重建一份）：
 *  - `.size(176, 176)`：有意低于单元格的 ~192px，换更小的解码/位图内存；
 *  - `.crossfade(0)`：列表滚动时每个新出现的单元格都会起一次淡入动画，纯浪费；
 *  - `.allowHardware(true)` + 显式 ARGB_8888：RGB_565 和硬件位图不兼容，否则缩略图
 *    会退回软件位图、每次绘制重新上传（九宫格实测 p90 44ms 的那个问题）；
 *  - 视频要挂 VideoFrameDecoder，否则 Coil 取不到帧、只能显示占位 —— "视频缩略图
 *    是灰块"那类反馈就来自这里。
 */
private fun buildRecentThumbnailRequest(context: Context, image: WallpaperImage): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(image.uri))
        .size(RECENT_THUMBNAIL_DECODE_SIZE, RECENT_THUMBNAIL_DECODE_SIZE)
        .crossfade(0)
        .allowHardware(true)
        .bitmapConfig(android.graphics.Bitmap.Config.ARGB_8888)
        .apply {
            if (image.mediaType == MediaTypes.VIDEO) {
                decoderFactory(VideoFrameDecoder.Factory())
            }
        }
        .build()
