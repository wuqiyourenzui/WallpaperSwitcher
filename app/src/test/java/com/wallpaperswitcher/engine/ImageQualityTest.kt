package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降噪分支的检测核心：平坦区域的杂讯要识别出来，干净图 / 硬边 / 渐变 / 纹理
 * 不能被误判成"脏图"（误判会把清晰内容糊掉）。
 */
class ImageQualityTest {

    private val w = 32
    private val h = 32

    private fun gray(v: Int) = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v

    @Test
    fun `a clean flat image needs no denoise`() {
        val pixels = IntArray(w * h) { gray(128) }
        assertEquals(0f, ImageQuality.denoiseStrength(pixels, w, h), 0f)
    }

    @Test
    fun `a flat image with pixel noise needs denoise`() {
        val pixels = IntArray(w * h) { index ->
            // 真实压缩噪点的量级（±15），应被识别成平坦区域的杂讯。
            val noise = if ((index + index / w) % 2 == 0) 15 else -15
            gray((128 + noise).coerceIn(0, 255))
        }
        assertTrue(ImageQuality.denoiseStrength(pixels, w, h) > 0.2f)
    }

    @Test
    fun `a hard edge is content and is not denoised`() {
        val pixels = IntArray(w * h) { index ->
            val x = index % w
            gray(if (x < w / 2) 20 else 235)
        }
        val strength = ImageQuality.denoiseStrength(pixels, w, h)
        assertTrue("edge strength=$strength", strength <= 0.05f)
    }

    @Test
    fun `a smooth gradient is not denoised`() {
        val pixels = IntArray(w * h) { index ->
            val x = index % w
            gray((20 + x * 6).coerceAtMost(255))
        }
        assertTrue(ImageQuality.denoiseStrength(pixels, w, h) <= 0.05f)
    }

    @Test
    fun `a soft low-contrast texture stays below the noise floor`() {
        // 低对比度的细织纹（±6）：邻居差 12，强度应很低。
        val pixels = IntArray(w * h) { index ->
            val x = index % w
            val y = index / w
            gray(if ((x + y) % 2 == 0) 134 else 122)
        }
        assertTrue(ImageQuality.denoiseStrength(pixels, w, h) <= 0.2f)
    }

    @Test
    fun `a hard fine texture raises the floor but the shader weights protect it`() {
        // 4px 黑白棋盘：CPU 会判成"高频很多"（强度高）；真正是否磨掉像素由 shader
        // 的双边权重决定——大色差像素权重极低，纹理几乎不动。检测偏保守不会糊内容。
        val pixels = IntArray(w * h) { index ->
            val x = index % w
            val y = index / w
            gray(if ((x / 4 + y / 4) % 2 == 0) 30 else 225)
        }
        assertTrue(ImageQuality.denoiseStrength(pixels, w, h) > 0.3f)
    }

    @Test
    fun `the strength is capped`() {
        val pixels = IntArray(w * h) { index ->
            val noise = if ((index * 7) % 3 == 0) 20 else -20
            gray((128 + noise).coerceIn(0, 255))
        }
        val strength = ImageQuality.denoiseStrength(pixels, w, h)
        assertTrue("strength=$strength", strength in 0f..0.6f)
    }

    @Test
    fun `tiny images are left alone`() {
        assertEquals(0f, ImageQuality.denoiseStrength(IntArray(4), 2, 2), 0f)
    }
}
