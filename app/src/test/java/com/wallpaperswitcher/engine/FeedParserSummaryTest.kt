package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表行摘要：源规则自己拼的"抓不到"占位提示要被丢掉。
 *
 * 夹具是**真机数据库里的原文**（源 `悄悄的看2019`，id=40）：规则没抓到图片时
 * 把这句话写进了 `description` 和 `content`，一屏几十行都挂同一句
 * 「未识别到图片，点此打开原页面」，把标题淹了（用户要求删掉）。
 */
class FeedParserSummaryTest {

    private val realPlaceholder =
        """<div style="padding:12px 0"><a href="https://qqdk2019.net/" """ +
            """style="color:#e60000">未识别到图片，点此打开原页面</a></div>"""

    @Test
    fun `the real rule-generated placeholder is dropped`() {
        assertEquals("", FeedParser.summaryText(realPlaceholder, ""))
        // 也可能落在 content 上（description 为空时用它）
        assertEquals("", FeedParser.summaryText("", realPlaceholder))
    }

    @Test
    fun `other wordings of the same placeholder are dropped too`() {
        // 不同源措辞不一样，但都同时含"没拿到"+"去原站"
        assertEquals("", FeedParser.summaryText("未解析到图片，点击打开原页面", ""))
        assertEquals("", FeedParser.summaryText("图片识别失败，点此打开原文", ""))
        assertEquals("", FeedParser.summaryText("<a href='#'>未找到内容，点击这里</a>", ""))
    }

    @Test
    fun `a real description is kept`() {
        val real = "这是一组很长的写真描述，讲的是拍摄现场的花絮和模特的状态，" +
            "完全不该被当成占位提示丢掉，因为它根本不含那些指引词。"
        assertEquals(real, FeedParser.summaryText(real, ""))
        assertEquals("正文摘要", FeedParser.summaryText("<p>正文摘要</p>", ""))
    }

    @Test
    fun `a long article is never treated as a placeholder`() {
        // 正文里**恰好**出现"解析失败"和"点此"也不该被删：一整段长文本是内容。
        val long = "这篇正文很长，" + "内容".repeat(60) + "，其中提到解析失败与点此打开原页面。"
        assertEquals(long, FeedParser.summaryText(long, ""))
    }

    @Test
    fun `html is still stripped for a normal description`() {
        assertEquals(
            "标题 正文",
            FeedParser.summaryText("<b>标题</b><script>x=1</script><p>正文</p>", ""),
        )
    }
}
