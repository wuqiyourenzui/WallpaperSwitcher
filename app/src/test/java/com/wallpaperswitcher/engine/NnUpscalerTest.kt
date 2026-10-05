package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分块起点：最后一块必须贴边，不能写出界。 */
class NnUpscalerTest {

    @Test
    fun `a tiny image is one clamped tile`() {
        assertEquals(listOf(0), NnUpscaler.tileOrigins(30, 50))
        assertEquals(listOf(0), NnUpscaler.tileOrigins(50, 50))
    }

    @Test
    fun `an exact multiple has no overlap`() {
        assertEquals(listOf(0, 50, 100), NnUpscaler.tileOrigins(150, 50))
    }

    @Test
    fun `the last partial tile is aligned to the edge`() {
        // 640 = 12*50 余 40：最后一块从 600 挪到 590，覆盖 590..639。
        val origins = NnUpscaler.tileOrigins(640, 50)
        assertEquals(590, origins.last())
        assertEquals(0, origins.first())
        assertEquals(13, origins.size)
    }

    @Test
    fun `every tile stays inside the image`() {
        for (size in intArrayOf(1, 49, 50, 51, 199, 360, 640, 721)) {
            val origins = NnUpscaler.tileOrigins(size, 50)
            origins.forEach { origin ->
                assertTrue("size=$size origin=$origin", origin >= 0)
                assertTrue("size=$size origin=$origin", origin + 50 <= size || size < 50)
            }
            assertTrue(origins.zipWithNext().all { (a, b) -> b > a })
        }
    }
}
