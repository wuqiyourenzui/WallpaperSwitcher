package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test

/**
 * 真机/联网探针：用 yckceo 上的真实「XH发布页」JS 源跑一遍运行时
 * （jsLib 下载 → getJs() → 规则脚本 eval → 规则读回）。
 * 需要网络，默认忽略；排查 JS 源问题时可单独运行。
 */
@Ignore("needs network; run manually when debugging JS sources")
class RealJsSourceProbeTest {

    @Test
    fun realSourceProducesRules() {
        val header = "<js>\neval(String(getJs()));\n</js>"
        val raw = """{"sourceName":"XH发布页","sourceUrl":"https://qyyuapi.com",""" +
            """"type":0,"singleUrl":true,"header":${com.wallpaperswitcher.engine.Json.encode(header)},""" +
            """"jsLib":"{\"XH发布页\":\"https://qyyuapi.com/dy/js/XH发布页/jsLib.js\"}"}"""
        val source = RssSource(
            id = 99L,
            name = "XH发布页",
            url = "https://qyyuapi.com",
            rawJson = raw,
        )
        val rules = runBlocking { LegadoRss.rulesFor(source) }
        println("REAL JS SOURCE rules=${rules?.ruleArticles}")
        println("  title=${rules?.ruleTitle}")
        println("  link=${rules?.ruleLink}")
        println("  sortUrl=${rules?.sortUrl}")
        println("  sourceUrl=${rules?.sourceUrl}")
    }
}
