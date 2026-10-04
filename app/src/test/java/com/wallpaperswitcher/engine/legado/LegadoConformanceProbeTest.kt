package com.wallpaperswitcher.engine.legado

import org.junit.Test

/**
 * 阅读（Legado）规则语法一致性探针：逐项跑一遍常见写法并打印结果，
 * 用来给「我们和阅读还差什么」出一份有证据的清单（不 assert，纯输出）。
 */
class LegadoConformanceProbeTest {

    private val html = """
        <html><body>
          <div id="main" class="list">
            <div class="item" data-id="1">
              <a href="/a/1.html" class="link"><h3>标题一</h3><img data-src="/i/1.jpg"></a>
              <p class="desc">描述一 <b>加粗</b></p>
              <span class="tag">标签A</span><span class="tag">标签B</span>
            </div>
            <div class="item" data-id="2">
              <a href="/a/2.html" class="link"><h3>标题二</h3><img data-src="/i/2.jpg"></a>
              <p class="desc">描述二</p>
            </div>
            <div class="item special" data-id="3">
              <a href="/a/3.html" class="link"><h3>标题三</h3><img data-src="/i/3.jpg"></a>
            </div>
          </div>
        </body></html>
    """.trimIndent()

    private val json = """
        {"a":{"b":"AB","list":[{"id":1,"name":"n1"},{"id":2,"name":"n2"}]},
         "deep":{"x":{"y":"DEEP"}},"page":2}
    """.trimIndent()

    private fun engine() = LegadoRuleEngine(
        baseUrl = "https://example.com/root/",
        variables = mapOf("page" to "2"),
        sourceId = 7L,
    )

    @Test
    fun probe() {
        val e = engine()
        val item = e.getElements("class.item", html).first()
        fun check(name: String, block: () -> String) {
            val out = try {
                block()
            } catch (t: Throwable) {
                "THREW ${t.javaClass.simpleName}: ${t.message?.take(80)}"
            }
            println("CONF $name = '$out'")
        }

        check("class.X") { e.getString("class.item@data-id", html) }
        check("id.X") { e.getString("id.main@class", html) }
        check("tag.X") { e.getString("tag.h3@text", html) }
        check("raw-css") { e.getString("div.item h3@text", html) }
        check("css-prefix") { e.getString("@css:div.item h3@text", html) }
        check("index.N") { e.getString("class.item.1@data-id", html) }
        check("index.neg") { e.getString("class.item.-1@data-id", html) }
        check("index.exclude") { e.getString("class.item!0@data-id", html) }
        check("index.range") { e.getString("class.item[0:1]@data-id", html) }
        check("index.step") { e.getString("class.item[::2]@data-id", html) }
        check("combine.&&") { e.getElements("class.item&&class.special", html).size.toString() }
        check("combine.||") { e.getString("class.missing||class.item@data-id", html) }
        check("combine.%%") { e.getElements("class.item%%class.special", html).size.toString() }
        check("textNodes") { e.getString("class.desc@textNodes", item) }
        check("ownText") { e.getString("class.desc@ownText", item) }
        check("html") { e.getString("class.desc@html", item) }
        check("all") { e.getString("class.tag@all", item) }
        check("attr") { e.getString("tag.a@href", item, isUrl = true) }
        check("jsoup:contains") { e.getString("div.item:contains(标题二) h3@text", html) }
        check("jsoup:matches") { e.getString("div.item:matches(标题三) h3@text", html) }
        check("json.basic") { e.getString("$.a.b", json) }
        check("json.index") { e.getString("$.a.list[0].name", json) }
        check("json.filter") { e.getString("$.a.list[?(@.id==2)].name", json) }
        check("json.recursive") { e.getString("$..y", json) }
        check("json.prefix") { e.getString("@json:$.a.b", json) }
        check("xpath.prefix") { e.getString("@xpath://h3/text()", html) }
        check("xpath.slash") { e.getString("/html/body/div/div/a/h3/text()", html) }
        check("xpath.slash.explicit") { e.getString("@xpath:/html/body/div/div/a/h3/text()", html) }
        check("extract.item.text") { e.getString("class.item.0@text", html) }
        check("replace.all") { e.getString("class.desc@text##描述##说明", item) }
        check("replace.delete") { e.getString("class.desc@text##描述", item) }
        check("replace.first") { e.getString("class.item@data-id##1##X##first", html) }
        check("braces.variable") { e.getString("page={{page}}", item) }
        check("braces.js") { e.getString("n={{ 1+1 }}", item) }
        check("braces.rule") { e.getString("id={{$.a.list[0].id}}", json) }
        check("braces.rule.escape") { e.getString("{{@@tag.img@html}}", item) }
        check("js.at-prefix") { e.getString("@js:'X'+'Y'", item) }
        check("js.tag") { e.getString("<js>1+2</js>", item) }
        check("js.chain") { e.getString("class.item.0@js:result", html) }
        check("js.chain2") { e.getString("class.item.0@js:result.length", html) }
        check("js.java.getString") { e.getString("@js:java.getString('class.item.0@data-id')", html) }
        check("js.base64") { e.getString("@js:java.base64Decode('5L2g5aW9')", item) }
        check("js.md5") { e.getString("@js:java.md5Encode('abc')", item) }
        check("js.timeFormat") { e.getString("@js:java.timeFormat(0)", item) }
        check("js.source.var") { e.getString("@js:source.setVariable('k')+'/'+source.getVariable()", item) }
        check("js.source.set") { e.getString("@js:source.setVariable('k1')", item) }
        check("js.source.get") { e.getString("@js:source.getVariable()", item) }
        check("xpath.abs.simple") { e.getString("@xpath:/html", html) }
        check("xpath.abs.body") { e.getString("@xpath:/html/body//h3", html) }
        check("xpath.attr") { e.getString("@xpath://a/@href", html, isUrl = true) }
        check("url.options") { e.getString("class.link@href,{headers:{'X':'1'}}", html, isUrl = true) }
        check("url.href.pure") { e.getString("tag.a@href", html, isUrl = true) }
        check("url.js.options") {
            e.getString(
                "tag.a@href@js:result = result + ',{\"headers\":{\"Referer\":\"https://x/\"}}'",
                html,
                isUrl = true
            )
        }
        check("webjs") {
            e.getString("@webjs:document.title", html)
        }
    }
}
