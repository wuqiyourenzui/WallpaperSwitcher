package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 两个互斥开关 → 渲染端模式值。 */
class EnhanceModeTest {

    @Test
    fun `both off is the built-in bicubic path`() {
        assertEquals(EnhanceMode.BUILT_IN, EnhanceMode.of(fsr1 = false, anime4k = false))
    }

    @Test
    fun `fsr1 selects the fsr1 path`() {
        assertEquals(EnhanceMode.FSR1, EnhanceMode.of(fsr1 = true, anime4k = false))
    }

    @Test
    fun `anime4k selects the anime4k path`() {
        assertEquals(EnhanceMode.ANIME4K, EnhanceMode.of(fsr1 = false, anime4k = true))
    }

    @Test
    fun `if both are somehow on anime4k wins`() {
        assertEquals(EnhanceMode.ANIME4K, EnhanceMode.of(fsr1 = true, anime4k = true))
    }
}
