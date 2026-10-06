package com.wallpaperswitcher.viewmodel

import android.app.Application
import com.wallpaperswitcher.R
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.WallpaperImageDao
import com.wallpaperswitcher.engine.MediaWindow
import com.wallpaperswitcher.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 分组详情网格的分页窗口状态机，从 `WallpaperViewModel` 拆分出来（逻辑逐字搬移）。
 *
 * 分组网格不再把整组读进内存，这里只保留一段 `[windowStart, windowStart + size)`
 * 的窗口，滚动/跳转时按需前插、后接或整体换页（判定见 [MediaWindow.plan]）。
 */
internal class MediaWindowController(
    private val app: Application,
    private val imageDao: WallpaperImageDao,
    private val scope: CoroutineScope,
    private val currentGroupId: () -> Long?,
    private val onToast: suspend (String) -> Unit,
) {

    private val tag = "MediaWindow"

    private val _loadedImages = MutableStateFlow<List<WallpaperImage>>(emptyList())
    val loadedImages: StateFlow<List<WallpaperImage>> = _loadedImages
    /** 窗口第一张在全量列表（addedAt DESC, id DESC）里的下标。 */
    private val _windowStart = MutableStateFlow(0)
    val windowStart: StateFlow<Int> = _windowStart
    private val _totalImageCount = MutableStateFlow(0)
    val totalImageCount: StateFlow<Int> = _totalImageCount
    private val _isLoadingImages = MutableStateFlow(false)
    val isLoadingImages: StateFlow<Boolean> = _isLoadingImages

    // In-flight page load; a newer load cancels it so a stale query can never
    // block a group switch or publish late results.
    private var loadImagesJob: Job? = null
    // Monotonic load generation: a cancelled job's finally must never clear
    // the flag of the job that superseded it (that race caused two concurrent
    // page loads appending the same offset).
    private var loadImagesGeneration = 0
    // 窗口补页：同一时刻只跑一个，期间到达的请求合并成一个范围（见 ensureRange）。
    private var mediaWindowJob: Job? = null
    private var pendingMediaFirst = -1
    private var pendingMediaLast = -1

    /** 清空窗口（切换分组/退出详情页）。 */
    fun reset() {
        _loadedImages.value = emptyList()
        _windowStart.value = 0
        _totalImageCount.value = 0
    }

    /** 外部（增删媒体后）刷新总数，不重载列表。 */
    fun setTotal(count: Int) {
        _totalImageCount.value = count
    }

    /**
     * Load the FIRST page of the group (see [MediaWindow]).
     *
     * 原来的实现一次把整组读进内存（"开页就要全部拿到"）；几百上千张的分组里
     * 光是元数据就是好几 MB，开页与每次 refresh 都要重查一遍。现在只取首页，
     * 其余交给 [ensureRange] 在滚动到边缘/跳转时按需补。
     */
    fun load(groupId: Long) {
        // A new load supersedes any in-flight one: switching groups quickly
        // must never publish a stale group's list. The stale job is also
        // guarded by the selectedGroupId check below.
        loadImagesJob?.cancel()
        mediaWindowJob?.cancel()
        pendingMediaFirst = -1
        pendingMediaLast = -1
        val gen = ++loadImagesGeneration
        loadImagesJob = scope.launch {
            _isLoadingImages.value = true
            try {
                // Only publish results for the group that is still selected:
                // a slow query for a previously-opened group must never
                // overwrite the list of the group the user switched to.
                if (currentGroupId() == groupId) {
                    _totalImageCount.value = imageDao.getImageCountByGroup(groupId)
                    _windowStart.value = loadStart()
                    _loadedImages.value = imageDao.getImagesByGroupPage(
                        groupId,
                        MediaWindow.PAGE,
                        _windowStart.value,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLog.e(tag, "loadAllImages failed", e)
                onToast(app.getString(R.string.toast_load_images_failed, e.message.orEmpty()))
            } finally {
                // Only the current generation owns the flag: a cancelled older
                // load must not clear the new load's isLoadingImages.
                if (gen == loadImagesGeneration) {
                    _isLoadingImages.value = false
                }
            }
        }
    }

    /**
     * 刷新时窗口从哪里开始：保持用户当前看的位置（删除一张后就地少一张），
     * 但总数变小后要夹回合法范围。
     */
    private fun loadStart(): Int {
        val total = _totalImageCount.value
        if (total <= 0) return 0
        val maxStart = (total - 1).coerceAtLeast(0)
        return _windowStart.value.coerceIn(0, maxStart)
    }

    /**
     * 保证 [firstIndex]..[lastIndex]（0 基、按整组顺序）这一段已经加载：
     * 靠近窗口尾部接一页、靠近头部前插一页、离得远就换成以目标为中心的一页
     * （快速滚动条跳转）。判定见 [MediaWindow.plan]。
     *
     * 请求会被合并：同一时刻只跑一个补页任务，滚动过程中连续到达的请求取并集，
     * 不会因为快速滑动排出一长串重复查询。
     */
    fun ensureRange(firstIndex: Int, lastIndex: Int) {
        val groupId = currentGroupId() ?: return
        if (_totalImageCount.value <= 0) return
        pendingMediaFirst =
            if (pendingMediaFirst < 0) firstIndex else minOf(pendingMediaFirst, firstIndex)
        pendingMediaLast =
            if (pendingMediaLast < 0) lastIndex else maxOf(pendingMediaLast, lastIndex)
        if (mediaWindowJob?.isActive == true) return
        mediaWindowJob = scope.launch {
            try {
                // 安全阀：补页之后窗口必须真的变了，否则收工（宁可留占位，也不能
                // 因为边界情况在这个循环里转不出去）。
                var lastWindow = _windowStart.value to _loadedImages.value.size
                while (true) {
                    val first = pendingMediaFirst
                    val last = pendingMediaLast
                    if (first < 0) break
                    pendingMediaFirst = -1
                    pendingMediaLast = -1
                    if (!applyWindowAction(groupId, first, last)) break
                    val window = _windowStart.value to _loadedImages.value.size
                    if (window == lastWindow) break
                    lastWindow = window
                    // 期间又有新请求（用户在滚动）就继续，否则收工。
                    if (pendingMediaFirst < 0) break
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLog.w(tag, "ensureMediaRange failed: ${e.javaClass.simpleName}")
            } finally {
                mediaWindowJob = null
            }
        }
    }

    /** 执行一次窗口动作；返回 false 表示"没进展"，调用方应停止循环。 */
    private suspend fun applyWindowAction(groupId: Long, first: Int, last: Int): Boolean {
        if (currentGroupId() != groupId) return false
        val page = MediaWindow.PAGE
        val action = MediaWindow.plan(
            first = first,
            last = last,
            windowStart = _windowStart.value,
            windowSize = _loadedImages.value.size,
            total = _totalImageCount.value,
        )
        if (action == MediaWindow.Action.None) return false
        val current = _loadedImages.value
        val window = MediaWindow.Window(
            start = _windowStart.value,
            items = current,
        )
        // 每个动作要查的范围：追加 = 窗口之后一页；前插 = 窗口之前的那一段
        // （不足一页时只补到 0）；跳转 = 目标窗口整段。
        val prependFrom = (window.start - page).coerceAtLeast(0)
        val (offset, limit) = when (action) {
            MediaWindow.Action.Append ->
                (window.start + current.size) to page
            MediaWindow.Action.Prepend ->
                prependFrom to (window.start - prependFrom)
            is MediaWindow.Action.Jump -> action.start to page
            MediaWindow.Action.None -> return false
        }
        if (limit <= 0) return false
        val fetched = imageDao.getImagesByGroupPage(groupId, limit, offset)
        val next = MediaWindow.applied(action, window, fetched) ?: return false
        if (currentGroupId() != groupId) return false
        _windowStart.value = next.start
        _loadedImages.value = next.items
        return true
    }
}
