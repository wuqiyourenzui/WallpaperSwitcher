package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import org.junit.Test

/**
 * 临时探针：把 h视频源的真实规则逐步跑一遍，找出设备上
 * `PatternSyntaxException: Syntax error in regexp pattern near index 13` 的来源。
 */
class HVideoPipelineProbeTest {

    private val titleRule = "$.title##.*屎.*|.*Ts.*|.*TS.*|.*ts.*|.*伪娘.*|.*人妖.*|.*男同.*|" +
        ".*mbrba.*|.*水果派.*|.*解说.*|.*mmraa.*|.*ss-.*|.*fway.*|.*rebd.*|.*mbdd.*|.*双性.*|" +
        ".*妈妈.*|.*儿子.*|.*爸爸.*|.*女儿.*|.*母子.*|.*父女.*|.*熟女.*|.*眼射.*|.*直男.*|" +
        ".*CD.*|.*肥女.*|.*黄金.*|.*骚0.*|.*gv.*|.*父子.*|.*飞机.*|.*撸.*|.*厕.*|.*老阿姨.*|.*乱伦.*"

    @Test
    fun probeEachRule() {
        val body = """
            {"code":200,"rescont":{"current_page":1,"data":[
              {"id":1,"title":"示例一","coverbase64":{"url":"/c/1.jpg"},
               "updated_at":"2026-10-01 10:00:00","playtimes":3}
            ],"next_page_url":"https://api.example.com/api/videosort/0?page=2"},"msg":""}
        """.trimIndent()
        val engine = LegadoRuleEngine(baseUrl = "https://api.sgapiaba.xyz/api/videosort/0?page=1")
        val items = engine.getElements("$.rescont.data[*]", body)
        val item = items.firstOrNull()
        fun step(name: String, block: () -> String) {
            val out = try {
                block()
            } catch (t: Throwable) {
                "THREW ${t.javaClass.simpleName}: ${t.message?.take(120)}"
            }
            println("PROBE $name = '$out'")
        }
        step("articles.size") { items.size.toString() }
        step("title") { engine.getString(titleRule, item) }
        step("link") { engine.getString("/api/videoplay/{{$.id}}?uuid=1", item, isUrl = true) }
        step("image") { engine.getString("{{$.coverbase64.url}}", item, isUrl = true) }
        step("pubdate") { engine.getString("📆{{$.updated_at## .*}}  ⏱️{{$.playtimes}}", item) }
        step("next") { engine.getString("$.rescont.next_page_url", body, isUrl = true) }
        step("parseRules+sortUrls") {
            val raw = """{"sourceUrl":"https://api.sgapiaba.xyz","ruleArticles":"$.rescont.data[*]",""" +
                """"ruleTitle":${com.wallpaperswitcher.engine.Json.encode(titleRule)}}"""
            val rules = LegadoRss.parseRules(
                RssSource(id = 38L, name = "h视频", url = "https://api.sgapiaba.xyz", rawJson = raw)
            )
            (rules != null).toString()
        }
    }
}
