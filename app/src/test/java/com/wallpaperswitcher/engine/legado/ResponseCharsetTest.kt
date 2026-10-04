package com.wallpaperswitcher.engine.legado

import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResponseCharsetTest {

    @Test
    fun headerCharsetWins() {
        val bytes = "中文".toByteArray(charset("GBK"))
        assertEquals(
            "中文",
            ResponseCharset.decode(bytes, "text/html; charset=GBK".toMediaType()),
        )
    }

    @Test
    fun metaCharsetIsSniffedWhenHeaderHasNone() {
        val html = "<html><head><meta charset=\"gbk\"></head><body>妖神记</body></html>"
        val bytes = html.toByteArray(charset("GBK"))
        assertEquals(html, ResponseCharset.decode(bytes, "text/html".toMediaType()))
        assertEquals(html, ResponseCharset.decode(bytes, null))
    }

    @Test
    fun httpEquivContentTypeIsSniffed() {
        val html = "<html><head><meta http-equiv=\"Content-Type\" " +
            "content=\"text/html; charset=gb2312\"></head><body>目录</body></html>"
        assertEquals(
            html,
            ResponseCharset.decode(html.toByteArray(charset("GBK")), null),
        )
    }

    @Test
    fun utf8IsTheFallback() {
        val html = "<html><body>没有声明编码</body></html>"
        assertEquals(html, ResponseCharset.decode(html.toByteArray(Charsets.UTF_8), null))
        assertNull(ResponseCharset.charsetOf(ByteArray(0), null))
    }

    @Test
    fun unknownCharsetFallsBackToUtf8() {
        val html = "<meta charset=\"x-unknown-enc\"><p>ok</p>"
        assertEquals(html, ResponseCharset.decode(html.toByteArray(Charsets.UTF_8), null))
    }
}
