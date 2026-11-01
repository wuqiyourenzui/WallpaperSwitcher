package com.wallpaperswitcher.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读 `articleStyle` → 列表排版的映射（对照 legado-E
 * `ui/rss/article/RssArticlesFragment.initView`）：
 *
 * | articleStyle | 阅读 | 这里 |
 * |---|---|---|
 * | 0 / 1 | LinearLayoutManager（单列列表） | 单列列表 |
 * | 2 | GridLayoutManager(2) | 两列网格 |
 * | 3 | 竖屏 StaggeredGrid(2) / 横屏 (3) | 竖屏两列 / 横屏三列 |
 * | 4 | GridLayoutManager(3) | 三列网格 |
 */
class RssArticleStyleTest {

    @Test
    fun `styles that reading renders as a grid are grids here too`() {
        assertTrue(RssArticleStyle.isGrid(RssArticleStyle.GRID_TWO))
        assertTrue(RssArticleStyle.isGrid(RssArticleStyle.GRID_STAGGERED))
        assertTrue(RssArticleStyle.isGrid(RssArticleStyle.GRID_THREE))
    }

    @Test
    fun `the default list styles stay single column`() {
        assertFalse(RssArticleStyle.isGrid(0))
        assertFalse(RssArticleStyle.isGrid(1))
        // 没记录排版的源（老数据 / 手写源）按列表走，和阅读默认一致。
        assertFalse(RssArticleStyle.isGrid(null))
    }

    @Test
    fun `column count matches the reading layout managers`() {
        assertEquals(2, RssArticleStyle.gridColumns(RssArticleStyle.GRID_TWO))
        assertEquals(3, RssArticleStyle.gridColumns(RssArticleStyle.GRID_THREE))
        // 瀑布流：竖屏两列、横屏三列。
        assertEquals(2, RssArticleStyle.gridColumns(RssArticleStyle.GRID_STAGGERED, landscape = false))
        assertEquals(3, RssArticleStyle.gridColumns(RssArticleStyle.GRID_STAGGERED, landscape = true))
    }

    @Test
    fun `an unknown style still yields a usable grid`() {
        assertEquals(2, RssArticleStyle.gridColumns(7))
        assertEquals(2, RssArticleStyle.gridColumns(null))
    }
}
