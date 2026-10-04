package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读 的「JS 源 / 加密源」（yckceo 上的 XH发布页 就是这种）：`jsLib` 指向一份
 * 远程混淆 JS，规则由它在运行时生成/解密（header 里的 `getJs()`），JSON 本身
 * 没有 `ruleArticles`。本应用没有这套运行时，必须识别出来并报「暂不支持」，
 * 而不是当普通源抓完后报「返回内容无法解析」。
 */
class LegadoJsSourceTest {

    private fun source(raw: String) = RssSource(
        id = 1L,
        name = "XH发布页",
        url = "https://qyyuapi.com",
        type = 0,
        rawJson = raw,
    )

    @Test
    fun jsLibraryWithoutStaticRulesNeedsTheUnsupportedRuntime() {
        val raw = """
            {"sourceName":"XH发布页","sourceUrl":"https://qyyuapi.com","type":0,
             "singleUrl":true,
             "header":"<js>\neval(String(getJs()));\n</js>",
             "jsLib":"{\"XH发布页\":\"https://qyyuapi.com/dy/js/XH发布页/jsLib.js\"}"}
        """.trimIndent()
        assertTrue(LegadoRss.requiresJsRuntime(source(raw)))
    }

    @Test
    fun staticRulesWinEvenWhenAJsLibraryIsAlsoDeclared() {
        val raw = """
            {"sourceName":"普通源","sourceUrl":"https://example.com",
             "ruleArticles":"ul.list li","ruleTitle":"h2 a@text",
             "jsLib":"{\"x\":\"https://example.com/lib.js\"}"}
        """.trimIndent()
        assertFalse(LegadoRss.requiresJsRuntime(source(raw)))
    }

    @Test
    fun plainFeedIsNotTreatedAsJsSource() {
        assertFalse(LegadoRss.requiresJsRuntime(source("")))
        assertFalse(
            LegadoRss.requiresJsRuntime(
                source("""{"sourceUrl":"https://example.com/feed.xml","type":0}""")
            )
        )
    }

    /**
     * 单 URL / 网页型源（PixivSource 的「Pixiv 书源」卡片、兽展日历…）：`singleUrl`
     * 为 true、没有规则、也没有 jsLib —— 这类源应当直接交给全屏浏览器，而不是当
     * 订阅源解析（否则永远报「返回内容无法解析」）。
     */
    @Test
    fun singleUrlCardWithoutRulesIsBrowseOnly() {
        val raw = """
            {"sourceName":"Pixiv 书源","sourceUrl":"https://pixivsource.pages.dev",
             "type":0,"singleUrl":true,"enableJs":true,"enabled":true}
        """.trimIndent()
        assertTrue(LegadoRss.isBrowseOnly(source(raw)))
    }

    /**
     * PixivSource 的 btsrk 规则订阅：`jsLib` 只定义菜单动作（打开设置/反馈等），
     * 规则本身是「单 URL 网页型」——这类源要按浏览器打开，不能当成需要
     * 运行时生成规则的 JS 源去报错。
     */
    @Test
    fun jsLibWithOnlyMenuActionsStaysBrowseOnly() {
        val raw = """
            {"sourceName":"Pixiv","sourceUrl":"https://www.pixiv.net/novel","type":0,
             "singleUrl":true,
             "jsLib":"function startPixivSettings() { java.startBrowser('https://www.pixiv.net/settings/viewing','账号设置') }"}
        """.trimIndent()
        val src = source(raw)
        assertTrue(LegadoRss.isBrowseOnly(src))
        assertFalse(LegadoRss.requiresJsRuntime(src))
    }

    @Test
    fun plainFeedAndJsSourceAreNotBrowseOnly() {
        // 普通订阅源（手动添加的 feed，rawJson 为空或没有 singleUrl）。
        assertFalse(LegadoRss.isBrowseOnly(source("")))
        assertFalse(
            LegadoRss.isBrowseOnly(
                source("""{"sourceUrl":"https://example.com/feed.xml","type":0}""")
            )
        )
        // JS 源（规则要运行时从远程 jsLib 解出来）：我们跑不了，所以**当网页型源** ——
        // 点开源直接打开原文（用户导入的 XH发布页 就是这个形态；以前点进去只能看到
        //「不支持的 JS 源」）。
        val js = """
            {"sourceName":"Pixiv","sourceUrl":"https://www.pixiv.net/novel","singleUrl":true,
             "header":"<js>\neval(String(getJs()));\n</js>",
             "jsLib":"{\"Pixiv\":\"https://qyyuapi.com/dy/js/Pixiv/jsLib.js\"}"}
        """.trimIndent()
        assertTrue(LegadoRss.isBrowseOnly(source(js)))
        assertTrue(LegadoRss.requiresJsRuntime(source(js)))
    }

    @Test
    fun singleUrlSourceWithStaticRulesIsNotBrowseOnly() {
        val raw = """
            {"sourceName":"规则源","sourceUrl":"https://example.com","singleUrl":true,
             "ruleArticles":"ul.list li","ruleTitle":"h2 a@text"}
        """.trimIndent()
        assertFalse(LegadoRss.isBrowseOnly(source(raw)))
    }
}
