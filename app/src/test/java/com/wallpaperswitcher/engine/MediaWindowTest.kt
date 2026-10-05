package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大分组网格的窗口/翻页判定。 */
class MediaWindowTest {

    private val page = MediaWindow.PAGE

    @Test
    fun `a range inside the window needs nothing`() {
        val action = MediaWindow.plan(
            first = 100, last = 130, windowStart = 0, windowSize = page, total = 5000
        )
        assertEquals(MediaWindow.Action.None, action)
    }

    @Test
    fun `scrolling near the end appends the next page`() {
        val action = MediaWindow.plan(
            first = page - 10, last = page + 5, windowStart = 0, windowSize = page, total = 5000
        )
        assertEquals(MediaWindow.Action.Append, action)
    }

    @Test
    fun `an empty window starts from the top`() {
        assertEquals(
            MediaWindow.Action.Jump(0),
            MediaWindow.plan(first = 0, last = 10, windowStart = 0, windowSize = 0, total = 5000)
        )
    }

    @Test
    fun `scrolling back near the start prepends`() {
        val action = MediaWindow.plan(
            first = 210 - MediaWindow.PREFETCH, last = 215,
            windowStart = 200, windowSize = page, total = 5000
        )
        assertEquals(MediaWindow.Action.Prepend, action)
    }

    @Test
    fun `a far jump replaces the window with one page around the target`() {
        val action = MediaWindow.plan(
            first = 3000, last = 3030, windowStart = 0, windowSize = page, total = 5000
        )
        val jump = action as MediaWindow.Action.Jump
        // 目标必须落在新窗口里，且窗口不超过一页。
        assertTrue("start=${jump.start}", jump.start <= 3000)
        assertTrue("start=${jump.start}", jump.start + page > 3030)
        assertTrue(jump.start >= 0)
        assertTrue(jump.start + page <= 5000)
    }

    @Test
    fun `a jump near the top or bottom stays inside the group`() {
        val top = MediaWindow.jumpStart(5, 20, total = 5000)
        assertEquals(0, top)
        val bottom = MediaWindow.jumpStart(4990, 4999, total = 5000)
        assertEquals(5000 - page, bottom)
    }

    @Test
    fun `a group smaller than one page is always one window`() {
        val action = MediaWindow.plan(
            first = 0, last = 40, windowStart = 0, windowSize = 0, total = 45
        )
        assertEquals(MediaWindow.Action.Jump(0), action)
        // 总量不到一页时窗口起点只能是 0。
        assertEquals(0, MediaWindow.jumpStart(0, 40, total = 45))
    }

    @Test
    fun `an empty group needs nothing`() {
        assertEquals(
            MediaWindow.Action.None,
            MediaWindow.plan(first = 0, last = 10, windowStart = 0, windowSize = 0, total = 0)
        )
    }

    @Test
    fun `indexes past the end clamp to the last row`() {
        // 网格可能先于计数刷新请求一个越界下标（删除后总数变小）。
        val action = MediaWindow.plan(
            first = 42, last = 99, windowStart = 0, windowSize = page, total = 43
        )
        assertEquals(MediaWindow.Action.None, action)
    }

    @Test
    fun `appending keeps the order and the start`() {
        val current = MediaWindow.Window(start = 0, items = listOf(media(1), media(2)))
        val next = MediaWindow.applied(
            MediaWindow.Action.Append, current, listOf(media(3), media(4))
        )
        assertEquals(0, next?.start)
        assertEquals(listOf(1L, 2L, 3L, 4L), next?.items?.map { it.id })
    }

    @Test
    fun `prepending moves the start back by the page size`() {
        val current = MediaWindow.Window(start = 200, items = listOf(media(21), media(22)))
        val next = MediaWindow.applied(
            MediaWindow.Action.Prepend, current, listOf(media(19), media(20))
        )
        assertEquals(198, next?.start)
        assertEquals(listOf(19L, 20L, 21L, 22L), next?.items?.map { it.id })
    }

    @Test
    fun `a jump replaces the whole window`() {
        val current = MediaWindow.Window(start = 0, items = listOf(media(1), media(2)))
        val next = MediaWindow.applied(
            MediaWindow.Action.Jump(3000), current, listOf(media(3001), media(3002))
        )
        assertEquals(3000, next?.start)
        assertEquals(listOf(3001L, 3002L), next?.items?.map { it.id })
    }

    @Test
    fun `an empty page never changes the window`() {
        val current = MediaWindow.Window(start = 0, items = listOf(media(1)))
        assertNull(MediaWindow.applied(MediaWindow.Action.Append, current, emptyList()))
        assertNull(MediaWindow.applied(MediaWindow.Action.None, current, listOf(media(2))))
    }

    @Test
    fun `a range wider than one page starts at its beginning, then appends`() {
        // 一次可见 + 预取超过一页（大屏平板）：居中会永远够不着两端，必须从头覆盖。
        val jump = MediaWindow.plan(
            first = 1000, last = 1300, windowStart = 0, windowSize = page, total = 5000
        ) as MediaWindow.Action.Jump
        assertEquals(1000, jump.start)
        // 下一轮就该往后接，而不是再跳一次（否则补页循环出不来）。
        assertEquals(
            MediaWindow.Action.Append,
            MediaWindow.plan(
                first = 1000, last = 1300,
                windowStart = jump.start, windowSize = page, total = 5000,
            )
        )
    }

    private fun media(id: Long) = com.wallpaperswitcher.data.WallpaperImage(
        id = id,
        groupId = 1L,
        uri = "content://media/$id",
    )
}
