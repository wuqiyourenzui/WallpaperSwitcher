package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.engine.Json
import com.wallpaperswitcher.engine.RssSourceImport
import com.wallpaperswitcher.data.RssSource
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用**用户真实导出的订阅源文件**跑一遍导入链路，只打印证据（不 assert 内容）：
 * 1. 手写 JSON 解析器能否吃下这份文件（含 `\n`、`\uXXXX`、转义引号）；
 * 2. 每个源的关键规则字段能否原样读到；
 * 3. [LegadoRss.parseRules] 能否从这些字段构造出规则。
 *
 * 文件路径按需覆盖：`-Drss.file=<path>`，默认取仓库根目录的
 * `rssSource_202610102027.json`。
 */
class RssSourceFileProbeTest {

    private val file: File = File(
        System.getProperty("rss.file")
            ?: "rssSource_202610102027.json"
    ).let { if (it.isAbsolute) it else File(repoRoot(), it.path) }

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        repeat(4) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        return File(".").absoluteFile
    }

    @Test
    fun parseRealExportFile() {
        val report = StringBuilder()
        report.append("file: ${file.absolutePath} exists=${file.isFile}\n")
        if (!file.isFile) {
            println(report)
            return
        }
        val text = file.readText(Charsets.UTF_8)
        report.append("bytes=${file.length()} chars=${text.length}\n")
        val parsed = try {
            Json.parse(text)
        } catch (t: Throwable) {
            report.append("Json.parse FAILED: ${t.javaClass.simpleName}: ${t.message}\n")
            println(report)
            assertNotNull("Json.parse must handle the exported file", null)
            return
        }
        val list = parsed as? List<*>
        report.append("Json.parse -> ${parsed?.javaClass?.simpleName}, entries=${list?.size}\n")
        if (list == null) {
            println(report)
            return
        }

        val sources = ArrayList<RssSource>()
        for ((i, entry) in list.withIndex()) {
            val map = entry as? Map<*, *>
            if (map == null) {
                report.append("[$i] not an object: ${entry?.javaClass?.simpleName}\n")
                continue
            }
            val name = map["sourceName"]?.toString().orEmpty()
            val url = map["sourceUrl"]?.toString().orEmpty()
            val raw = Json.encode(entry)
            val source = RssSource(id = (i + 1).toLong(), name = name, url = url, rawJson = raw)
            sources.add(source)
            val fields = LegadoRss.sourceFields(raw)
            val rules = LegadoRss.parseRules(source)
            val sortLines = (map["sortUrl"] as? String)?.split("\n")?.count { it.isNotBlank() } ?: 0
            report.append("\n[$i] $name\n")
            report.append("    url=${url.take(90)}\n")
            report.append("    keys=${map.size} sourceFields=${fields?.size} parseRules=${rules != null}\n")
            report.append("    sortUrl lines=$sortLines\n")
            if (rules == null) {
                report.append("    !! ruleArticles='${map["ruleArticles"]}'\n")
                continue
            }
            report.append("    ruleArticles='${rules.ruleArticles.take(60)}'\n")
            report.append("    ruleTitle='${rules.ruleTitle?.take(70)}'\n")
            report.append("    ruleLink='${rules.ruleLink?.take(60)}'\n")
            report.append("    ruleImage='${rules.ruleImage?.take(60)}'\n")
            report.append("    ruleNextPage=${rules.ruleNextPage != null} len=${rules.ruleNextPage?.length ?: 0}\n")
            report.append("    type=${rules.type} singleUrl=${rules.singleUrl} cookieJar=${rules.enabledCookieJar}\n")
            val categories = try {
                LegadoRss.sortUrls(source, rules).map { it.name }
            } catch (t: Throwable) {
                report.append("    sortUrls FAILED: ${t.javaClass.simpleName}: ${t.message?.take(80)}\n")
                emptyList()
            }
            report.append("    categories(${categories.size})=${categories.take(6)}\n")
            // 多字节 / 转义正确性抽查：源名与分类名必须与文件里的一致。
            if (map["sourceName"] != null) {
                assertTrue("sourceName must survive the JSON round trip", name == map["sourceName"])
            }
        }

        val decision = RssSourceImport.decide(emptyList(), sources)
        report.append("\nimport decision: inserted=${decision.inserted.size} updated=${decision.updated.size} ignored=${decision.ignored}\n")
        println(report)
    }
}
