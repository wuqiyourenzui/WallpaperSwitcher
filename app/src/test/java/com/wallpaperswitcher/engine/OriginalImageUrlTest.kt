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
}
