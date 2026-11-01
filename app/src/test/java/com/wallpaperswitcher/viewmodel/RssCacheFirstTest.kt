package com.wallpaperswitcher.viewmodel

import com.wallpaperswitcher.viewmodel.RssController.RssEnterPlan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 订阅源进入时的**缓存优先**判定（默认行为，和阅读一致）。
 *
 * 之前每次进入源、每次切分类都会先把该源的缓存删掉再联网抓，所以永远要等一次
 * 完整抓取；现在列表默认留在库里，进入时先用缓存立即出内容、刷新在后台跑，
 * 只有「这个分类一篇都没存过」时才等一次网络。
 */
class RssCacheFirstTest {

    @Test
    fun `a cached category shows immediately and refreshes in the background`() {
        assertEquals(RssEnterPlan.RenderFromCache, RssEnterPlan.decide(cachedCount = 100))
        assertEquals(RssEnterPlan.RenderFromCache, RssEnterPlan.decide(cachedCount = 1))
    }

    @Test
    fun `an empty category waits for the first fetch`() {
        // 没缓存还直接返回会白屏：这种情况必须等一次抓取。
        assertEquals(RssEnterPlan.WaitForFirstFetch, RssEnterPlan.decide(cachedCount = 0))
    }
}
