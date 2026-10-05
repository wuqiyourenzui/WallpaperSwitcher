package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.WallpaperImage

// 大分组网格的分页窗口计算（纯逻辑，调用方是
// WallpaperViewModel.ensureMediaRange）。
//
// 分组详情原来一次性把整组查进内存（getImagesByGroupSync）：几百上千张时
// 光元数据（uri / 显示名 / 文件夹路径）就是好几 MB，超大分组开页明显变慢。
// 现在只保留一段「窗口」（PAGE 张），网格按整组总数渲染，没加载到的格子先画
// 占位；滚动到窗口边缘就接着取下一页，快速滚动条跳到远处就直接换成以目标为中心
// 的一页。
object MediaWindow {

    /** 一页多少张（一条 SQL 的 LIMIT）。 */
    const val PAGE = 200

    /** 距窗口边缘多少格开始预取：格子可见后才取会让滚动出现空档。 */
    const val PREFETCH = 40

    sealed interface Action {
        /** 请求的范围已经在窗口里，什么都不用做。 */
        data object None : Action

        /** 往后接一页（正常向下滚动 / 翻到下一张）。 */
        data object Append : Action

        /** 往前接一页（往回滚动，窗口是滑动的）。 */
        data object Prepend : Action

        /** 离窗口太远（拖快速滚动条）：直接换成以目标为中心的一页窗口。 */
        data class Jump(val start: Int) : Action
    }

    /** 当前窗口：第一张在整组里的下标 + 已加载的那一段（顺序与整组一致）。 */
    data class Window(val start: Int, val items: List<WallpaperImage>)

    /**
     * 把刚查到的一页拼进窗口。返回 null 表示这一页是空的（没进展，调用方停止）。
     *
     * 拼接只在这里发生，`WallpaperViewModel` 只负责执行 SQL，顺序/边界（尤其是
     * 前插时的新起点、跳转时整段替换）都能单独测。
     */
    fun applied(action: Action, current: Window, page: List<WallpaperImage>): Window? {
        if (action == Action.None || page.isEmpty()) return null
        return when (action) {
            Action.Append -> Window(current.start, current.items + page)
            Action.Prepend -> Window(
                start = (current.start - page.size).coerceAtLeast(0),
                items = page + current.items,
            )
            is Action.Jump -> Window(action.start, page)
            Action.None -> null
        }
    }

    /**
     * 决定为了覆盖 `[first, last]` 这一段索引（0 基，按分组的全量顺序）需要做什么。
     *
     * @param windowStart 当前窗口第一张在全量列表里的下标
     * @param windowSize 当前窗口的张数（0 = 还没加载）
     */
    fun plan(first: Int, last: Int, windowStart: Int, windowSize: Int, total: Int): Action {
        if (total <= 0) return Action.None
        val lo = first.coerceIn(0, total - 1)
        val hi = last.coerceIn(lo, total - 1)
        val size = windowSize.coerceAtLeast(0)
        val start = windowStart.coerceIn(0, (total - 1).coerceAtLeast(0))
        if (size == 0) return Action.Jump(0)
        val end = (start + size).coerceAtMost(total)

        if (lo < start) {
            return if (start - lo <= PREFETCH) Action.Prepend
            else Action.Jump(jumpStart(lo, hi, total))
        }
        if (lo >= end || hi >= end) {
            return if (lo - end < PREFETCH) Action.Append
            else Action.Jump(jumpStart(lo, hi, total))
        }
        return Action.None
    }

    /**
     * 以 `[lo, hi]` 为中心、长度为 [PAGE] 的窗口起点（夹在 0..total-PAGE 之间）。
     *
     * 请求范围本身就比一页宽时（例如平板上一次可见上百格 + 预取），居中会让窗口
     * 一头一尾都够不着、下一次判定又要跳——直接从头覆盖，后面的页由 Append 接着补，
     * 保证每跳一次都有进展。
     */
    fun jumpStart(lo: Int, hi: Int, total: Int): Int {
        if (total <= 0) return 0
        val maxStart = (total - PAGE).coerceAtLeast(0)
        if (hi - lo + 1 >= PAGE) return lo.coerceIn(0, maxStart)
        val center = (lo + hi) / 2
        return (center - PAGE / 2).coerceIn(0, maxStart)
    }
}
