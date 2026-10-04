package com.wallpaperswitcher.engine.legado

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 阅读 `concurrentRate` 的解析与等待计算（ConcurrentRateLimiter）。 */
class ConcurrentRateTest {

    @Test
    fun onlyPositiveRatesLimit() {
        assertNull(ConcurrentRate.parse(null))
        assertNull(ConcurrentRate.parse(""))
        assertNull(ConcurrentRate.parse("0"))
        assertNull(ConcurrentRate.parse("abc"))
        assertEquals(ConcurrentRate.Rate(null, 1000), ConcurrentRate.parse("1000"))
        assertEquals(ConcurrentRate.Rate(3, 1000), ConcurrentRate.parse("3/1000"))
        assertEquals(ConcurrentRate.Rate(3, 1000), ConcurrentRate.parse(" 3 / 1000 "))
    }

    @Test
    fun intervalModeWaitsBetweenRequests() {
        val rate = ConcurrentRate.parse("1000")!!
        val window = ConcurrentRate.Window()
        // 第一次直接放行并占用。
        assertEquals(0L, ConcurrentRate.plan(rate, window, now = 10_000L))
        // 还在跑：等满一个间隔。
        assertEquals(1000L, ConcurrentRate.plan(rate, window, now = 10_000L))
        ConcurrentRate.release(rate, window)
        // 前一次开始后 500ms：再等 500ms。
        assertEquals(500L, ConcurrentRate.plan(rate, window, now = 10_500L))
        // 间隔已过：放行。
        assertEquals(0L, ConcurrentRate.plan(rate, window, now = 11_000L))
    }

    @Test
    fun frequencyModeAllowsTheConfiguredBurst() {
        val rate = ConcurrentRate.parse("3/1000")!!
        val window = ConcurrentRate.Window()
        assertEquals(0L, ConcurrentRate.plan(rate, window, now = 10_000L))
        assertEquals(0L, ConcurrentRate.plan(rate, window, now = 10_100L))
        assertEquals(0L, ConcurrentRate.plan(rate, window, now = 10_200L))
        // 第 4 次超出窗口配额：等到窗口结束。
        assertEquals(800L, ConcurrentRate.plan(rate, window, now = 10_200L))
        // 新窗口重新开始。
        assertEquals(0L, ConcurrentRate.plan(rate, window, now = 11_000L))
    }
}
