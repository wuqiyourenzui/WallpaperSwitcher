package com.wallpaperswitcher.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.engine.MediaTypes
import com.wallpaperswitcher.engine.WallpaperTarget
import com.wallpaperswitcher.ui.theme.HiEmptyState
import com.wallpaperswitcher.ui.theme.HiLoadingState
import com.wallpaperswitcher.ui.theme.HiMotion
import com.wallpaperswitcher.ui.theme.LocalAccentColor
import com.wallpaperswitcher.ui.theme.hiCardColor
import com.wallpaperswitcher.viewmodel.WallpaperViewModel
import kotlin.math.abs
import kotlin.math.hypot

// ---------------------------------------------------------------------------
// 纯逻辑：手势区域 / 双击判定 / 索引环绕 / 相邻分组。
//
// 这些函数不碰 Compose、不碰 Android，所以能直接单测（MediaBrowseLogicTest）。
// 组合层（下面的 MediaBrowseScreen）只负责把指针事件喂进来、把结果动作执行掉。
// ---------------------------------------------------------------------------

/** 一次落点落在哪块手势区域（见 [browseZoneOf]）。 */
internal enum class BrowseZone { Left, Center, Right }

/** 一次手势最终要做的事。 */
internal enum class BrowseAction {
    Previous,
    Next,
    ToggleFavorite,
    RequestDelete,
    PreviousGroup,
    NextGroup,
}

/**
 * 左右两侧"点按翻页"区各占的宽度比（中间 40% 留给双击收藏）。
 *
 * 为什么双击只认中间：左右点按要"抬手即翻页"，如果为了等一次可能的双击而
 * 把翻页延后一个双击时间窗（[BROWSE_DOUBLE_TAP_MS]），翻页就会明显发黏；
 * 反过来说，中间那块没有单击动作，双击判定就可以安心等第二个点按。
 */
internal const val BROWSE_SIDE_FRACTION = 0.30f

/** 双击的时间窗（毫秒）：两次点按相隔超过它就不算双击。 */
internal const val BROWSE_DOUBLE_TAP_MS = 280L

/**
 * 双击的位置容差（像素）：两次点按离得太远就当作两次独立点按
 * （用户想连点两下左边翻页，不该被当成收藏）。
 */
internal const val BROWSE_DOUBLE_TAP_SLOP_PX = 56f

/**
 * 落点分区：横坐标在 [width] 的左右各 [sideFraction] 之内算翻页区，其余算中间。
 * 宽高非正（测量尚未完成）时按中间处理 —— 中间区没有任何单击动作，最安全。
 */
internal fun browseZoneOf(
    x: Float,
    width: Float,
    sideFraction: Float = BROWSE_SIDE_FRACTION,
): BrowseZone {
    if (width <= 0f) return BrowseZone.Center
    val f = sideFraction.coerceIn(0f, 0.5f)
    return when {
        x < width * f -> BrowseZone.Left
        x > width * (1f - f) -> BrowseZone.Right
        else -> BrowseZone.Center
    }
}

/** 把下标夹进 `0 until count`；空列表固定为 0（列表刚被删空时不会越界）。 */
internal fun clampBrowseIndex(index: Int, count: Int): Int =
    if (count <= 0) 0 else index.coerceIn(0, count - 1)

/** 环绕取模：负数也能落到 `0 until count`；[count] <= 0 时返回 0。 */
internal fun wrapBrowseIndex(index: Int, count: Int): Int {
    if (count <= 0) return 0
    val m = index % count
    return if (m < 0) m + count else m
}

/** 下一张（最后一张之后回到第一张）。 */
internal fun nextBrowseIndex(current: Int, count: Int, step: Int = 1): Int =
    wrapBrowseIndex(clampBrowseIndex(current, count) + step, count)

/** 上一张（第一张之前回到最后一张）。 */
internal fun previousBrowseIndex(current: Int, count: Int, step: Int = 1): Int =
    wrapBrowseIndex(clampBrowseIndex(current, count) - step, count)

/**
 * 相邻分组：从 [current] 走 [step] 步并环绕；[isEmpty] 判为"没有任何素材"的分组
 * 会被跳过（空分组只会让人看到一片空白）。全都为空（或只剩当前分组）时原地不动。
 */
internal fun steppedGroupIndex(
    current: Int,
    step: Int,
    count: Int,
    isEmpty: (Int) -> Boolean = { false },
): Int {
    if (count <= 0) return 0
    val from = wrapBrowseIndex(current, count)
    var idx = from
    repeat(count - 1) {
        idx = wrapBrowseIndex(idx + step, count)
        if (!isEmpty(idx)) return idx
    }
    return from
}

/**
 * 双击判定。
 *
 * 只吃"已经完成的点按"（抬手时喂进来），不看按下那一刻：引擎里的
 * [com.wallpaperswitcher.engine.DoubleTapDetector] 是在第二次按下时就判双击的
 * （实时壁纸要抢时间），那个语义搬到这个页面会把"点一下之后马上上滑"误判成双击。
 *
 * 命中双击时内部状态会清空，所以三连击只算一次双击。
 */
internal class BrowseDoubleTapMatcher(
    private val windowMs: Long = BROWSE_DOUBLE_TAP_MS,
    private val slopPx: Float = BROWSE_DOUBLE_TAP_SLOP_PX,
) {
    // 用布尔量标记"有没有上一次点按"，不用 0L 当哨兵：uptimeMillis 为 0 的
    // 点按在真机上不会出现，但测试和时钟回拨都不该被这种细节坑到。
    private var hasLastTap = false
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** @return true 表示这次抬手构成了一次双击。 */
    fun onCompletedTap(x: Float, y: Float, timeMs: Long): Boolean {
        val elapsed = timeMs - lastTapAt
        val isDouble = hasLastTap &&
            elapsed in 0..windowMs &&
            hypot(x - lastTapX, y - lastTapY) <= slopPx
        if (isDouble) {
            // 清掉：再来一次点按就是新的一轮，不会连着触发第二次收藏。
            hasLastTap = false
            return true
        }
        hasLastTap = true
        lastTapAt = timeMs
        lastTapX = x
        lastTapY = y
        return false
    }

    /** 忘掉上一次点按（发生了滑动/长按，或者点了左右两侧的翻页区）。 */
    fun reset() {
        hasLastTap = false
    }
}

/** 点按容忍的最大位移（像素）：超过它就不算点按。 */
private const val BROWSE_TAP_SLOP_PX = 26f

/** 上滑翻页的触发距离（像素）。 */
private const val BROWSE_SWIPE_UP_PX = 96f

/** 横滑换分组的触发距离（像素）。 */
private const val BROWSE_GROUP_SWIPE_PX = 72f

/** 长按删除的等待时间（毫秒）。 */
private const val BROWSE_LONG_PRESS_MS = 480L

/** 底部提示最多显示几行（俄语/西班牙语的提示一行放不下）。 */
private const val BROWSE_HINT_MAX_LINES = 3

/**
 * 大图浏览（Stories 式）：点分组封面进来，一张一张地看，边看边收藏/删除/换组。
 *
 * 分组详情页的九宫格适合"整理"，不适合"挑一张好看的" —— 这个页面补的就是那个缺口。
 *
 * 手势（底部一行 [R.string.browse_hint] 也写着）：
 *  - 点按左右两侧 = 上一张 / 下一张（抬手即生效，不等双击时间窗）
 *  - 上滑 = 下一张；横向滑动（图片区或顶部标题行）= 切到相邻分组
 *  - 中间双击 = 收藏 / 取消收藏；长按（图片区）= 从分组里删除（先确认）
 *  - 顶部右上角的星标是同一个收藏动作的按钮入口 —— 双击手势对读屏用户不可用
 *
 * 数据：`viewModel.groups` + `viewModel.selectGroup(id)` → `viewModel.loadedImages`
 * （进页面/换组时自己 select，因为分组详情页退出时会把 selectedGroupId 清空）。
 * 收藏写 `viewModel.setFavorite(id, on)`，删除 `viewModel.deleteImage(image)`
 * （它内部会 refreshImages()，所以删完列表自己会少一张），
 * 应用 `viewModel.setImageAsWallpaper(image, slot)`。
 */
@Composable
fun MediaBrowseScreen(
    viewModel: WallpaperViewModel,
    startGroupId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val images by viewModel.loadedImages.collectAsStateWithLifecycle()
    val windowStart by viewModel.windowStart.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalImageCount.collectAsStateWithLifecycle()
    val mediaCounts by viewModel.mediaCounts.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val isLoadingImages by viewModel.isLoadingImages.collectAsStateWithLifecycle()

    // 系统返回键/手势退出（整屏页面自己处理，不能指望外层）。
    BackHandler { onBack() }

    // 当前分组是本地状态，不读 selectedGroupId：那个值由 selectGroup() 异步写入，
    // 切换过程中读它会拿到"上一组"，光标就跳。存成 saveable 是为了转屏/重建后
    // 还停在原来那一张，而不是回到第一张。
    var groupId by rememberSaveable { mutableStateOf(startGroupId) }
    var position by rememberSaveable { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<WallpaperImage?>(null) }

    // 收藏状态从 favorites（Room Flow，setFavorite/delete 之后立刻回来）算，
    // 不能读 loadedImages —— 那是一份快照，双击收藏后它不会变，星标会停在旧值。
    // favorites 已按 uri 去重，所以同一张图在不同分组里也是同一个收藏状态。
    val favoriteUris = remember(favorites) { favorites.mapTo(HashSet()) { it.uri } }

    // 进页面 / 换组：只发起这一件事，不等别的元数据（首帧就靠它）。
    // GroupDetail 退出时 onDispose 会把 selectedGroupId 清成 null，所以这里
    // 不能假设"上一个页面已经加载好了"。
    LaunchedEffect(groupId) {
        if (viewModel.selectedGroupId.value != groupId) {
            viewModel.selectGroup(groupId)
        } else if (viewModel.loadedImages.value.isEmpty() && !viewModel.isLoadingImages.value) {
            viewModel.refreshImages()
        }
    }

    // 分页窗口：images 只是已加载的一段，位置用整组里的绝对下标表示
    // （见 engine.MediaWindow）；总数还没到时退回已加载张数，避免空指针式白屏。
    val count = if (totalCount > 0) totalCount else images.size
    val index = clampBrowseIndex(position, count)
    val current = images.getOrNull(index - windowStart)
    // 翻到窗口边缘就补下一页；快速连翻也只排一个补页任务。
    LaunchedEffect(index, windowStart, count) {
        if (count > 0) viewModel.ensureMediaRange(index - 1, index + 1)
    }
    val groupName = groups.firstOrNull { it.id == groupId }?.name
        ?: stringResource(R.string.browse_title)
    val isFavorite = current != null && current.uri in favoriteUris

    fun showToast(resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

    fun toggleFavorite(image: WallpaperImage) {
        val favorite = image.uri !in favoriteUris
        viewModel.setFavoriteByUri(image.uri, favorite)
        showToast(
            if (favorite) R.string.browse_favorited else R.string.browse_unfavorited
        )
    }

    // 相邻分组：跳过"已知没有任何素材"的分组（空分组只是一片空白）。
    // mediaCounts 还没到时（空 map）不跳过任何分组，否则刚进页面横滑不动。
    fun stepGroup(step: Int) {
        val list = groups
        if (list.size < 2) return
        val from = list.indexOfFirst { it.id == groupId }
        if (from < 0) return
        val isEmpty: (Int) -> Boolean = { i ->
            mediaCounts.isNotEmpty() && (mediaCounts[list[i].id] ?: 0) == 0
        }
        val next = steppedGroupIndex(from, step, list.size, isEmpty)
        if (next != from) {
            groupId = list[next].id
            position = 0
        }
    }

    // 手势动作。经 rememberUpdatedState 交给手势层：lambda 每次重组都是新的，
    // 而手势层的 Modifier 只构造一次（重建 pointerInput 会掐断进行中的手势）。
    val onAction: (BrowseAction) -> Unit = { action ->
        // 每次都从当前状态重新取：同一帧里连点两下也要各自前进一张。
        val shown = images.getOrNull(clampBrowseIndex(position, count) - windowStart)
        when (action) {
            BrowseAction.Previous -> if (count > 0) {
                position = previousBrowseIndex(position, count)
            }
            BrowseAction.Next -> if (count > 0) {
                position = nextBrowseIndex(position, count)
            }
            BrowseAction.ToggleFavorite -> shown?.let { toggleFavorite(it) }
            BrowseAction.RequestDelete -> if (shown != null) pendingDelete = shown
            BrowseAction.NextGroup -> stepGroup(1)
            BrowseAction.PreviousGroup -> stepGroup(-1)
        }
    }
    val latestAction = rememberUpdatedState(onAction)
    val latestGroupStep = rememberUpdatedState<(Int) -> Unit> { step -> stepGroup(step) }
    val doubleTap = remember { BrowseDoubleTapMatcher() }

    // 换了一张就把"上一次点按"忘掉：否则"在中间点一下 + 上滑翻页 + 又点一下"
    // 会在新的一张上凑成一次双击（时间窗内、位置也接近），平白收藏错图。
    LaunchedEffect(current?.id) { doubleTap.reset() }

    // 两份手势层都只构造一次：重组时重建 pointerInput 会把进行中的手势掐断。
    // 它们读的都是 State 对象（身份稳定），所以不存在"捕获到旧值"的问题。
    val imageGestures = remember(doubleTap, latestAction) {
        Modifier.browseImageGestures(doubleTap, latestAction)
    }
    val headerGestures = remember(latestGroupStep) {
        Modifier.browseGroupSwipes(latestGroupStep)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            BrowseHeader(
                groupName = groupName,
                position = index,
                count = count,
                isFavorite = isFavorite,
                onBack = onBack,
                onToggleFavorite = { current?.let { toggleFavorite(it) } },
                modifier = headerGestures,
            )

            // 图片区：手势层只挂在这里。顶栏/底栏是普通文字和按钮，不消费触摸，
            // 所以图片区的点按/滑动不会被它们挡住（反过来，按钮自己会优先拿到点击）。
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(imageGestures),
                contentAlignment = Alignment.Center,
            ) {
                // 图片区的实际像素尺寸，交给 Coil 当解码目标：
                // "进页面只解码当前这一张（且只解到屏幕大小），左右滑动才解码相邻那张"
                // 就是靠这里 —— 相邻的图不会被提前解码，翻到它时才按需解码。
                val density = androidx.compose.ui.platform.LocalDensity.current
                val targetW = with(density) { maxWidth.roundToPx() }
                val targetH = with(density) { maxHeight.roundToPx() }
                when {
                    current != null -> BrowseImage(
                        image = current,
                        targetWidth = targetW,
                        targetHeight = targetH,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 窗口还没盖到这一张（分页补页中）与首屏加载一样显示加载态，
                    // 而不是"没有素材"——否则快速翻页时会闪一下空状态。
                    isLoadingImages || count > 0 -> HiLoadingState(
                        text = stringResource(R.string.state_loading),
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> HiEmptyState(
                        title = stringResource(R.string.browse_no_media),
                        // 这里不再重复 browse_hint：底下那一行已经写着同一句提示。
                        icon = Icons.Outlined.Image,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }

            BrowseFooter(
                hasImage = current != null,
                onSetHome = {
                    current?.let { viewModel.setImageAsWallpaper(it, WallpaperTarget.SLOT_HOME) }
                },
                onSetLock = {
                    current?.let { viewModel.setImageAsWallpaper(it, WallpaperTarget.SLOT_LOCK) }
                },
                // 动态壁纸：走系统的实时壁纸预览确认流程（视频/GIF 只有实时引擎
                // 才能动起来；静态写入会把它们冻在第一帧）。
                onSetLive = {
                    current?.let { viewModel.setAsLiveWallpaper(it) }
                },
            )
        }
    }

    // 长按删除：先确认（名单可能是批量导入进来的，误删不可逆）。
    pendingDelete?.let { image ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.browse_delete_title)) },
            text = {
                Text(image.displayName.ifBlank { stringResource(R.string.item_untitled) })
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        // deleteImage() 内部会 refreshImages()，列表随即少一张，
                        // 光标位置不变（下一张顶上来），不需要在这里改 position。
                        viewModel.deleteImage(image)
                        showToast(R.string.browse_deleted)
                    }
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 手势层：把指针事件翻译成 [BrowseAction]（判定规则见文件顶部的 internal 函数）。
 *
 * 手势只在"一次完整的按下→抬起"里判定一次；被上滑/横滑/长按接管之后就不再
 * 当作点按（`consumed`）。`awaitEachGesture` 会在块返回后等所有手指抬起，
 * 所以中途 break 出去也不会让同一次手势触发两次。
 */
private fun Modifier.browseImageGestures(
    doubleTap: BrowseDoubleTapMatcher,
    action: State<(BrowseAction) -> Unit>,
): Modifier = pointerInput(doubleTap, action) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startX = down.position.x
        val startY = down.position.y
        val startTime = down.uptimeMillis
        val width = size.width.toFloat()
        val zone = browseZoneOf(startX, width)

        var lastX = startX
        var lastY = startY
        var lastTime = startTime
        var consumed = false

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val x = change.position.x
            val y = change.position.y
            val dx = x - startX
            val dy = y - startY
            val elapsed = change.uptimeMillis - startTime

            if (abs(dx) >= BROWSE_GROUP_SWIPE_PX && abs(dx) > abs(dy)) {
                // 横滑 = 换相邻分组（向左滑 = 下一组）。
                change.consume()
                action.value(
                    if (dx < 0f) BrowseAction.NextGroup else BrowseAction.PreviousGroup
                )
                consumed = true
                break
            }
            if (dy <= -BROWSE_SWIPE_UP_PX && abs(dy) > abs(dx)) {
                // 上滑 = 下一张。
                change.consume()
                action.value(BrowseAction.Next)
                consumed = true
                break
            }
            if (elapsed >= BROWSE_LONG_PRESS_MS && hypot(dx, dy) <= BROWSE_TAP_SLOP_PX) {
                // 长按：手指还没抬就弹确认框（和系统长按一致）。
                consumed = true
                action.value(BrowseAction.RequestDelete)
                break
            }

            lastX = x
            lastY = y
            lastTime = change.uptimeMillis
            if (!change.pressed) break
        }

        if (consumed) {
            // 上滑/横滑/长按已经接管了这次手势：顺手忘掉待定的点按，
            // 别让"点一下 + 滑一下 + 再点一下"在任何页面上凑成一次双击。
            doubleTap.reset()
            return@awaitEachGesture
        }
        if (hypot(lastX - startX, lastY - startY) > BROWSE_TAP_SLOP_PX) {
            // 移动过但没到翻页/换组阈值（慢慢拖）：不算点按，顺手清掉双击状态，
            // 免得和上一次点按凑成一次"双击"。
            doubleTap.reset()
            return@awaitEachGesture
        }
        when (zone) {
            // 两侧抬手即翻页，不参与双击判定。
            BrowseZone.Left -> {
                doubleTap.reset()
                action.value(BrowseAction.Previous)
            }
            BrowseZone.Right -> {
                doubleTap.reset()
                action.value(BrowseAction.Next)
            }
            // 中间没有单击动作，所以可以安心等第二个点按。
            BrowseZone.Center ->
                if (doubleTap.onCompletedTap(startX, startY, lastTime)) {
                    action.value(BrowseAction.ToggleFavorite)
                }
        }
    }
}

/**
 * 顶栏那一行的横滑换组：作用范围和图片区一样（"顶部左右滑动 = 切到相邻分组"），
 * 分组名就在这一行，滑动它换组最直观。
 */
private fun Modifier.browseGroupSwipes(onStep: State<(Int) -> Unit>): Modifier =
    pointerInput(onStep) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startX = down.position.x
            val startY = down.position.y
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val dx = change.position.x - startX
                val dy = change.position.y - startY
                if (abs(dx) >= BROWSE_GROUP_SWIPE_PX && abs(dx) > abs(dy)) {
                    change.consume()
                    onStep.value(if (dx < 0f) 1 else -1)
                    break
                }
                if (!change.pressed) break
            }
        }
    }

/**
 * 当前这一张：Coil **按实际显示尺寸**解码（[targetWidth] / [targetHeight] 由
 * 图片区的布局尺寸给出），视频同分组网格一样用 [VideoFrameDecoder] 取首帧。
 *
 * 为什么必须给尺寸：不给的话 Coil 按原图解码 —— 一张 4000×3000 的壁纸就是
 * ~48MB 的一次解码，进页面第一张会明显卡一下，来回滑动时更是反复分配大块内存。
 * 预览只需要屏幕那么大，解码到屏幕尺寸既快又省，看画质也看不出差别
 * （真正的壁纸写入走的是原始 uri，与这里的预览解码无关）。
 *
 * 视频/GIF 取不到帧时 Coil 会走 error 分支（不抛异常），onError 里记下失败，
 * 由 [BrowseImage] 画一个"无法预览"的占位图标，而不是留一块空白。
 */
private fun browseImageRequest(
    context: Context,
    image: WallpaperImage,
    targetWidth: Int,
    targetHeight: Int,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(Uri.parse(image.uri))
        .size(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        // 不给 crossfade：淡入会让"翻页"看起来慢半拍，硬切反而更像翻页。
        .crossfade(0)
        .allowHardware(true)
        .apply {
            if (image.mediaType == MediaTypes.VIDEO) {
                decoderFactory(VideoFrameDecoder.Factory())
            }
        }
        .build()

@Composable
private fun BrowseImage(
    image: WallpaperImage,
    targetWidth: Int,
    targetHeight: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 只按"当前这一张 + 当前尺寸"记忆：换张/转屏都会重新构造请求，
    // 不会把上一个尺寸的解码结果当成这个尺寸的缓存。
    val request = remember(image.uri, image.mediaType, context, targetWidth, targetHeight) {
        browseImageRequest(context, image, targetWidth, targetHeight)
    }
    // 图片底色用卡片色（与九宫格同一套观感），失败时也是同一块底色。
    val stageColor = hiCardColor()
    val stagePainter = remember(stageColor) { ColorPainter(stageColor) }
    var failed by remember(image.uri) { mutableStateOf(false) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = image.displayName.ifBlank { null },
            contentScale = ContentScale.Fit,
            placeholder = stagePainter,
            error = stagePainter,
            // NOTE: 这里只能用 onError，不能换成 onState —— Coil 2.5 的两个 AsyncImage
            // 重载是互斥的：带 placeholder/error 的那个没有 onState（另一个重载
            // 有 transform/onState，但没有 placeholder/error）。传了 onState 会选中
            // 另一个重载，placeholder/error 立刻变成"找不到参数"（编译错误）。
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
        if (failed) {
            Icon(
                Icons.Outlined.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(56.dp),
            )
        }
        // 视频/GIF 角标：拿不到帧时它就是那块空白的说明（"这是视频，不是加载失败"）。
        if (MediaTypes.isMotion(image.mediaType)) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (image.mediaType == MediaTypes.VIDEO) Icons.Filled.Videocam else Icons.Filled.Gif,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    if (image.mediaType == MediaTypes.VIDEO) {
                        stringResource(R.string.media_type_video)
                    } else {
                        "GIF"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

/** 顶栏：返回 + 分组名 + "第几张 / 共几张" + 收藏按钮，行下一条细进度条。 */
@Composable
private fun BrowseHeader(
    groupName: String,
    position: Int,
    count: Int,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    // 调用方把"横滑换组"的手势挂在整块顶栏上（含进度条那一行）。
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.action_back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // 纯数字计数器，与语言无关（这里没有对应的字符串资源）。
                    text = if (count > 0) "${position + 1} / $count" else "0 / 0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // 收藏按钮：双击手势的等价入口（读屏/大字号下双击很难可靠触发）。
            val starScale by animateFloatAsState(
                targetValue = if (isFavorite) 1f else 0.86f,
                animationSpec = HiMotion.standard(HiMotion.ShortMs),
                label = "browseStar",
            )
            val accent = LocalAccentColor.current.takeIf { it != Color.Unspecified }
                ?: MaterialTheme.colorScheme.primary
            IconButton(onClick = onToggleFavorite, enabled = count > 0) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.media_favorite_remove
                        else R.string.media_favorite_add
                    ),
                    tint = if (isFavorite) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = starScale
                            scaleY = starScale
                        },
                )
            }
        }
        BrowseProgressBar(position = position, count = count)
    }
}

/** 顶栏下的细进度条（当前第几张 / 共几张）；不用 M3 的进度条组件（见 HiUi 的说明）。 */
@Composable
private fun BrowseProgressBar(position: Int, count: Int) {
    val target = if (count > 0) (position + 1).toFloat() / count else 0f
    // 弹簧/插值都可能越界，fillMaxWidth 的 fraction 必须先夹住。
    val fraction by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = HiMotion.standard(HiMotion.ShortMs),
        label = "browseProgress",
    )
    val accent = LocalAccentColor.current.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(accent)
        )
    }
}

/** 底栏：一行手势提示 + 应用入口（设为桌面 / 设为锁屏）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseFooter(
    hasImage: Boolean,
    onSetHome: () -> Unit,
    onSetLock: () -> Unit,
    onSetLive: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = stringResource(R.string.browse_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            maxLines = BROWSE_HINT_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        // 两个应用入口固定换行（窄屏 + 俄语/西班牙语的长标签放不进一行）。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onSetHome, enabled = hasImage) {
                Text(stringResource(R.string.browse_set_home))
            }
            TextButton(onClick = onSetLock, enabled = hasImage) {
                Text(stringResource(R.string.browse_set_lock))
            }
            TextButton(onClick = onSetLive, enabled = hasImage) {
                Text(stringResource(R.string.browse_set_live))
            }
        }
    }
}
