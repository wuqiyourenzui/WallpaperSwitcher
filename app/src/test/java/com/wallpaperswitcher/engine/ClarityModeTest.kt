package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清晰度增强现在是开/关：只有 "off" 是关，历史值（auto / super / strong）与键缺失
 * 都按"开"处理（老安装默认行为保留）；开启时统一 1.5 锐化档。
 */
class ClarityModeTest {

    @Test
    fun `only off is disabled`() {
        assertFalse(ClarityMode.isEnabled(ClarityMode.OFF))
        assertTrue(ClarityMode.isEnabled(ClarityMode.ON))
        assertTrue(ClarityMode.isEnabled("auto"))
        assertTrue(ClarityMode.isEnabled("super"))
        assertTrue(ClarityMode.isEnabled("strong"))
        assertTrue(ClarityMode.isEnabled(null))
        assertTrue(ClarityMode.isEnabled(""))
    }

    @Test
    fun `the scale is zero off and the enhance scale on`() {
        assertEquals(0f, ClarityMode.sharpnessScale(ClarityMode.OFF), 0f)
        assertEquals(1.5f, ClarityMode.sharpnessScale(ClarityMode.ON), 0f)
        assertEquals(1.5f, ClarityMode.sharpnessScale("auto"), 0f)
        assertEquals(1.5f, ClarityMode.sharpnessScale("super"), 0f)
    }

    @Test
    fun `normalize keeps on and off distinct`() {
        assertEquals(ClarityMode.OFF, ClarityMode.normalize(ClarityMode.OFF))
        assertEquals(ClarityMode.ON, ClarityMode.normalize(ClarityMode.ON))
        assertEquals(ClarityMode.ON, ClarityMode.normalize("garbage"))
    }

    @Test
    fun `the quality boost follows the switch`() {
        assertTrue(ClarityMode.boostsQuality(ClarityMode.ON))
        assertFalse(ClarityMode.boostsQuality(ClarityMode.OFF))
    }
}
