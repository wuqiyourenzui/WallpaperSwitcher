package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 去重的纯逻辑：dHash 的方向性、同组内"保留最大"、跨组不合并、宽高比和
 * 相同面积（非完全相同哈希）不误删。
 */
class MediaDedupeTest {

    private fun candidate(
        id: Long,
        groupId: Long = 1L,
        width: Int = 1000,
        height: Int = 500,
        hash: Long = 0L,
    ) = MediaDedupe.Candidate(
        id = id,
        groupId = groupId,
        uri = "file:///data/files/rss/1/$id.jpg",
        width = width,
        height = height,
        hash = hash,
    )

    @Test
    fun `dhash reads the left-to-right brightness direction`() {
        val w = MediaDedupe.HASH_WIDTH
        val h = MediaDedupe.HASH_HEIGHT
        val rising = IntArray(w * h) { index ->
            val x = index % w
            val v = (x * 255) / (w - 1)
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        val falling = IntArray(w * h) { index ->
            val x = index % w
            val v = 255 - (x * 255) / (w - 1)
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        assertEquals(0L, MediaDedupe.dHash(rising, w, h))
        assertEquals(-1L, MediaDedupe.dHash(falling, w, h))
    }

    @Test
    fun `the same image at two sizes keeps the larger one`() {
        val small = candidate(id = 1, width = 300, height = 200, hash = 0x1234L)
        val large = candidate(id = 2, width = 1200, height = 800, hash = 0x1234L)
        val drops = MediaDedupe.plan(listOf(small, large, candidate(id = 3, hash = 0xFFFFL)))
        assertEquals(listOf(1L), drops.map { it.id })
    }

    @Test
    fun `the same image in different groups is kept in both`() {
        val a = candidate(id = 1, groupId = 1L, width = 300, height = 200, hash = 0x1234L)
        val b = candidate(id = 2, groupId = 2L, width = 1200, height = 800, hash = 0x1234L)
        assertTrue(MediaDedupe.plan(listOf(a, b)).isEmpty())
    }

    @Test
    fun `similar hashes with different aspect ratios are not duplicates`() {
        val a = candidate(id = 1, width = 1000, height = 1000, hash = 0x1234L)
        val b = candidate(id = 2, width = 400, height = 100, hash = 0x1234L)
        assertTrue(MediaDedupe.plan(listOf(a, b)).isEmpty())
    }

    @Test
    fun `same size and similar but different hashes are not removed`() {
        val a = candidate(id = 1, width = 800, height = 600, hash = 0x1234L)
        val b = candidate(id = 2, width = 800, height = 600, hash = 0x1235L)
        assertTrue(MediaDedupe.plan(listOf(a, b)).isEmpty())
    }

    @Test
    fun `an exact hash duplicate at the same size is removed`() {
        val a = candidate(id = 1, width = 800, height = 600, hash = 0x1234L)
        val b = candidate(id = 2, width = 800, height = 600, hash = 0x1234L)
        assertEquals(listOf(2L), MediaDedupe.plan(listOf(a, b)).map { it.id })
    }

    @Test
    fun `no candidates means no drops`() {
        assertTrue(MediaDedupe.plan(emptyList()).isEmpty())
    }
}
