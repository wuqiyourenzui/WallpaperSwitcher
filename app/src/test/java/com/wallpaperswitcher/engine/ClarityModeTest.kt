package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清晰度增强's stored values, including the one-way migration from the removed
 * 增强 ("strong") option into 画质增强（超分）("super").
 */
class ClarityModeTest {

    @Test
    fun `super is its own mode`() {
        assertEquals(ClarityMode.SUPER, ClarityMode.normalize(ClarityMode.SUPER))
        assertTrue(ClarityMode.boostsQuality(ClarityMode.SUPER))
    }

    @Test
    fun `the removed strong option migrates to super`() {
        assertEquals(ClarityMode.SUPER, ClarityMode.normalize("strong"))
        assertTrue(ClarityMode.boostsQuality("strong"))
    }

    @Test
    fun `auto and off keep their meaning`() {
        assertEquals(ClarityMode.AUTO, ClarityMode.normalize(ClarityMode.AUTO))
        assertEquals(ClarityMode.OFF, ClarityMode.normalize(ClarityMode.OFF))
        assertFalse(ClarityMode.boostsQuality(ClarityMode.AUTO))
        assertFalse(ClarityMode.boostsQuality(ClarityMode.OFF))
    }

    @Test
    fun `unknown values fall back to auto`() {
        assertEquals(ClarityMode.AUTO, ClarityMode.normalize(null))
        assertEquals(ClarityMode.AUTO, ClarityMode.normalize(""))
        assertEquals(ClarityMode.AUTO, ClarityMode.normalize("AUTO"))
        assertEquals(ClarityMode.AUTO, ClarityMode.normalize("high"))
    }

    @Test
    fun `only off disables the sharpening scale`() {
        assertEquals(0f, ClarityMode.sharpnessScale(ClarityMode.OFF), 0f)
        assertEquals(1.25f, ClarityMode.sharpnessScale(ClarityMode.AUTO), 0f)
        assertEquals(1.25f, ClarityMode.sharpnessScale(ClarityMode.SUPER), 0f)
        assertEquals(1.25f, ClarityMode.sharpnessScale("strong"), 0f)
    }
}
