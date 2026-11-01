package com.wallpaperswitcher.engine.legado

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ruleArticles` 写成 `@js:` 并 `return` 一个**数组**时，数组必须被摊平成 N 个条目
 * —— 阅读就是这么用的（`@js:(function(){… out.push(JSON.stringify({…})); return out;})()`
 * 这种源很常见，本仓库的 91porn 源就是）。
 *
 * 这里锁住这条：一旦 JS 的数组被当成"一个对象"，列表只剩 1 条，后续 `@json:$.t`
 * 之类的逐项规则全部取不到东西，列表就会**空着**（用户反馈「订阅源打开无内容」
 * 的实际形态）。
 */
class JsArrayArticlesTest {

    private fun engine() = LegadoRuleEngine(baseUrl = "https://example.com/")

    @Test
    fun `a js rule returning an array yields one entry per element`() {
        val engine = engine()
        val rule = """@js:(function(){
            var out = [];
            out.push(JSON.stringify({t:"one", l:"https://e.com/1"}));
            out.push(JSON.stringify({t:"two", l:"https://e.com/2"}));
            out.push(JSON.stringify({t:"three", l:"https://e.com/3"}));
            return out;
        })()"""
        val items = engine.getElements(rule, "<html></html>")
        assertEquals("js array must flatten to one entry per element", 3, items.size)
    }

    @Test
    fun `each flattened entry still answers the per-item json rules`() {
        val engine = engine()
        val rule = """@js:(function(){
            var out = [];
            out.push(JSON.stringify({t:"one", l:"https://e.com/1"}));
            out.push(JSON.stringify({t:"two", l:"https://e.com/2"}));
            return out;
        })()"""
        val items = engine.getElements(rule, "<html></html>")
        assertEquals(2, items.size)
        // 源里 ruleTitle / ruleLink 用 @json:$.t / @json:$.l 取每一条的字段。
        assertEquals("one", engine.getString("@json:$.t", items[0]))
        assertEquals("two", engine.getString("@json:$.t", items[1]))
        assertEquals("https://e.com/2", engine.getString("@json:$.l", items[1], isUrl = true))
    }

    @Test
    fun `a js rule returning a single json object still yields one entry`() {
        val engine = engine()
        val rule = """@js:(function(){ return JSON.stringify({t:"only", l:"https://e.com/1"}); })()"""
        val items = engine.getElements(rule, "<html></html>")
        assertEquals(1, items.size)
        assertEquals("only", engine.getString("@json:$.t", items[0]))
    }
}
