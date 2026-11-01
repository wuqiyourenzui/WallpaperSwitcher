package com.wallpaperswitcher.viewmodel

import com.wallpaperswitcher.viewmodel.RssController.RssArticleStyleCycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「切换布局」：`articleStyle` 在 **1→2→3→4→1** 之间循环。
 *
 * 渲染侧按 1=列表、2=两列网格、3=瀑布流、4=三列网格 实现（`RssArticleStyle`）。
 * 这里锁住循环本身：只在 1..4 里转，不出现 0，也不越界。
 */
class RssArticleStyleCycleTest {

    @Test
    fun `cycles 1 through 4 and wraps back to 1`() {
        assertEquals(2, RssArticleStyleCycle.next(1))
        assertEquals(3, RssArticleStyleCycle.next(2))
        assertEquals(4, RssArticleStyleCycle.next(3))
        assertEquals(1, RssArticleStyleCycle.next(4))
    }

    @Test
    fun `never produces zero or anything out of range`() {
        var value = 1
        repeat(12) {
            value = RssArticleStyleCycle.next(value)
            assertTrue("articleStyle=$value must be 1..4", value in 1..4)
        }
    }

    @Test
    fun `a source that was never set starts the cycle at 1`() {
        // 0 = 没设过（旧数据 / 新建源）。不该把 0 当成一档留在循环里。
        assertEquals(1, RssArticleStyleCycle.next(0))
        // 手改坏或将来加了新档：同样回到 1，不越滚越大。
        assertEquals(1, RssArticleStyleCycle.next(9))
        assertEquals(1, RssArticleStyleCycle.next(-1))
    }
}
