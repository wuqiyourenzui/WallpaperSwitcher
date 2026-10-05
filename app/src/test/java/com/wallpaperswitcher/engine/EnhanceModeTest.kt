package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** `enhance_algo` 设置 → 渲染端模式值，以及旧开关的迁移映射。 */
class EnhanceModeTest {

    @Test
    fun `the fsr1 key selects fsr1`() {
        assertEquals(EnhanceMode.FSR1, EnhanceMode.fromKey(EnhanceMode.FSR1_KEY))
    }

    @Test
    fun `the anime4k key selects anime4k`() {
        assertEquals(EnhanceMode.ANIME4K, EnhanceMode.fromKey(EnhanceMode.ANIME4K_KEY))
    }

    @Test
    fun `missing or unknown keys default to fsr1`() {
        assertEquals(EnhanceMode.FSR1, EnhanceMode.fromKey(null))
        assertEquals(EnhanceMode.FSR1, EnhanceMode.fromKey(""))
        assertEquals(EnhanceMode.FSR1, EnhanceMode.fromKey("bicubic"))
    }

    @Test
    fun `the legacy boolean switches map to the new key`() {
        assertEquals(EnhanceMode.FSR1_KEY, EnhanceMode.legacyKey(fsr1 = false, anime4k = false))
        assertEquals(EnhanceMode.FSR1_KEY, EnhanceMode.legacyKey(fsr1 = true, anime4k = false))
        assertEquals(EnhanceMode.ANIME4K_KEY, EnhanceMode.legacyKey(fsr1 = false, anime4k = true))
        assertEquals(EnhanceMode.ANIME4K_KEY, EnhanceMode.legacyKey(fsr1 = true, anime4k = true))
    }
}
