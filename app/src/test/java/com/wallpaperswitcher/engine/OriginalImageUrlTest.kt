package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** 原图优先 URL 升级：只动明确的缩略图特征，其它 URL 原样保留。 */
class OriginalImageUrlTest {

    @Test
    fun `wordpress size suffixes are removed`() {
        assertEquals(
            "https://site.com/wp/2024/photo.jpg",
            OriginalImageUrl.upgrade("https://site.com/wp/2024/photo-300x200.jpg"),
        )
        assertEquals(
            "https://site.com/a/pic.png",
            OriginalImageUrl.upgrade("https://site.com/a/pic-150x150.png"),
        )
    }

    @Test
    fun `names that are not a size suffix stay untouched`() {
        // 一位数字不是尺寸后缀。
        assertEquals(
            "https://site.com/shot-4x4.png",
            OriginalImageUrl.upgrade("https://site.com/shot-4x4.png"),
        )
        assertEquals(
            "https://site.com/photo.jpg",
            OriginalImageUrl.upgrade("https://site.com/photo.jpg"),
        )
    }

    @Test
    fun `resize query params are dropped`() {
        assertEquals(
            "https://cdn.com/a.jpg",
            OriginalImageUrl.upgrade("https://cdn.com/a.jpg?w=300&h=200&quality=70"),
        )
        assertEquals(
            "https://cdn.com/a.jpg?v=2",
            OriginalImageUrl.upgrade("https://cdn.com/a.jpg?w=300&v=2"),
        )
        assertEquals(
            "https://cdn.com/a.jpg",
            OriginalImageUrl.upgrade("https://cdn.com/a.jpg?x-oss-process=image/resize,w_300"),
        )
    }

    @Test
    fun `unrelated query params and fragments are preserved`() {
        assertEquals(
            "https://cdn.com/a.jpg?token=abc",
            OriginalImageUrl.upgrade("https://cdn.com/a.jpg?token=abc"),
        )
        // 尺寸参数去掉，但片段保留。
        assertEquals(
            "https://cdn.com/a.jpg#frag",
            OriginalImageUrl.upgrade("https://cdn.com/a.jpg?w=300#frag"),
        )
    }

    @Test
    fun `empty and blank urls are returned unchanged`() {
        assertEquals("", OriginalImageUrl.upgrade(""))
        assertEquals("   ", OriginalImageUrl.upgrade("   "))
    }

    @Test
    fun `wordpress underscore suffix and cropped variant are removed`() {
        assertEquals(
            "https://site.com/wp/photo.jpg",
            OriginalImageUrl.upgrade("https://site.com/wp/photo_300x200.jpg"),
        )
        // WP 的 "裁剪版" 变体：-1024x683-c.jpg
        assertEquals(
            "https://site.com/wp/photo.png",
            OriginalImageUrl.upgrade("https://site.com/wp/photo-1024x683-c.png"),
        )
    }

    @Test
    fun `largest srcset entry wins over the display sized one`() {
        assertEquals(
            "https://x/large.jpg",
            OriginalImageUrl.largestFromSrcset(
                "https://x/small.jpg 300w, https://x/mid.jpg 800w, https://x/large.jpg 1600w",
            ),
        )
        // 2x 倍率 = 更大的一张。
        assertEquals(
            "https://x/2x.jpg",
            OriginalImageUrl.largestFromSrcset("https://x/1x.jpg 1x, https://x/2x.jpg 2x"),
        )
        // 无描述符的一项按 1x 处理，仍然大于 300w 缩略图。
        assertEquals(
            "https://x/orig.jpg",
            OriginalImageUrl.largestFromSrcset("https://x/thumb.jpg 300w, https://x/orig.jpg"),
        )
    }

    @Test
    fun `srcset without usable entries yields null`() {
        assertEquals(null, OriginalImageUrl.largestFromSrcset(null))
        assertEquals(null, OriginalImageUrl.largestFromSrcset(""))
        assertEquals(null, OriginalImageUrl.largestFromSrcset("data:image/gif;base64,AAA"))
    }
}
