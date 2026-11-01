package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.RssSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入订阅源：同 URL 就地更新，不新增第二行。 */
class RssSourceImportTest {

    private fun source(
        id: Long = 0L,
        name: String = "名字",
        url: String = "https://a.example/feed",
        type: Int = 0,
        articleStyle: Int = 0,
        enabled: Boolean = true,
        rawJson: String = "",
        createdAt: Long = 0L,
    ) = RssSource(
        id = id,
        name = name,
        url = url,
        type = type,
        articleStyle = articleStyle,
        enabled = enabled,
        rawJson = rawJson,
        createdAt = createdAt,
    )

    @Test
    fun `a brand new url is inserted`() {
        val decision = RssSourceImport.decide(
            existing = emptyList(),
            incoming = listOf(source(url = "https://new.example/feed")),
        )
        assertEquals(1, decision.inserted.size)
        assertEquals(0, decision.updated.size)
        assertEquals("https://new.example/feed", decision.inserted.first().url)
    }

    @Test
    fun `the same url updates the existing row instead of adding one`() {
        val existing = source(id = 7L, name = "旧名字", type = 0, createdAt = 111L)
        val decision = RssSourceImport.decide(
            existing = listOf(existing),
            incoming = listOf(
                source(
                    name = "新名字",
                    type = 2,
                    rawJson = """{"ruleArticles":"class.item"}""",
                )
            ),
        )
        assertEquals(0, decision.inserted.size)
        assertEquals(1, decision.updated.size)
        val updated = decision.updated.first()
        // 内容跟随导入…
        assertEquals("新名字", updated.name)
        assertEquals(2, updated.type)
        assertEquals("""{"ruleArticles":"class.item"}""", updated.rawJson)
        // …身份与本地状态保留。
        assertEquals(7L, updated.id)
        assertEquals(111L, updated.createdAt)
    }

    @Test
    fun `a locally disabled source stays disabled after re-import`() {
        val existing = source(id = 3L, enabled = false)
        val decision = RssSourceImport.decide(
            existing = listOf(existing),
            incoming = listOf(source(enabled = true)),
        )
        assertEquals(false, decision.updated.first().enabled)
    }

    @Test
    fun `the same url twice in one batch collapses into one row`() {
        val decision = RssSourceImport.decide(
            existing = emptyList(),
            incoming = listOf(
                source(name = "first", url = "https://dup.example/feed"),
                source(name = "second", url = "https://dup.example/feed"),
            ),
        )
        assertEquals(1, decision.inserted.size)
        assertEquals("second", decision.inserted.first().name)
        assertEquals(0, decision.updated.size)
    }

    @Test
    fun `urls are matched after trimming whitespace`() {
        val decision = RssSourceImport.decide(
            existing = listOf(source(id = 9L, url = "https://a.example/feed")),
            incoming = listOf(source(url = "  https://a.example/feed  ")),
        )
        assertEquals(0, decision.inserted.size)
        assertEquals(1, decision.updated.size)
        assertEquals("https://a.example/feed", decision.updated.first().url)
    }

    @Test
    fun `a blank incoming name keeps the existing one`() {
        val decision = RssSourceImport.decide(
            existing = listOf(source(id = 5L, name = "保留我")),
            incoming = listOf(source(name = "   ")),
        )
        assertEquals("保留我", decision.updated.first().name)
    }

    @Test
    fun `items without a url are ignored`() {
        val decision = RssSourceImport.decide(
            existing = emptyList(),
            incoming = listOf(source(url = ""), source(url = "   ")),
        )
        assertTrue(decision.inserted.isEmpty())
        assertTrue(decision.updated.isEmpty())
        assertEquals(2, decision.ignored)
    }

    @Test
    fun `re-importing a source updates its article style`() {
        // 阅读 `articleStyle` 是源自己带的：2 = 两列网格。重新导入换排版后，
        // 列表要跟着改成网格，不能还留着旧的 0（单列）。
        val existing = source(id = 4L, name = "秀人集", articleStyle = 0)
        val decision = RssSourceImport.decide(
            existing = listOf(existing),
            incoming = listOf(source(name = "秀人集", articleStyle = 2)),
        )
        assertEquals(2, decision.updated.first().articleStyle)
        assertEquals(4L, decision.updated.first().id)
    }

    @Test
    fun `article style is read back from the source json`() {
        assertEquals(2, LegadoImport.articleStyleOf("""{"articleStyle":2,"ruleArticles":"x"}"""))
        assertEquals(4, LegadoImport.articleStyleOf("""[{"articleStyle":4}]"""))
        // 没有这个字段（手写源 / 老导出）按列表处理。
        assertEquals(0, LegadoImport.articleStyleOf("""{"ruleArticles":"x"}"""))
        assertEquals(0, LegadoImport.articleStyleOf(""))
        assertEquals(0, LegadoImport.articleStyleOf(null))
        // 只认 0..4：读到别的值按默认列表处理，不能猜成一个网格。
        assertEquals(0, LegadoImport.articleStyleOf("""{"articleStyle":9}"""))
        assertEquals(0, LegadoImport.articleStyleOf("""{"articleStyle":-1}"""))
        assertEquals(0, LegadoImport.articleStyleOf("""{"articleStyle":"2"}"""))
    }
}
