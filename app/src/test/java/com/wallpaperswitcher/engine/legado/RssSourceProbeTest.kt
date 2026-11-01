package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.engine.Json
import com.wallpaperswitcher.engine.RssHttp
import java.io.File
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 联网诊断探针（**不 assert 结果**，只打印证据）：把用户导出的 14 个订阅源逐个
 * 用本项目自己的规则引擎抓第一页，报告每一步的产出，用来定位「阅读能显示、
 * 壁纸软件显示不出来 / 显示不一样」到底断在哪。
 *
 * 需要网络，跑法：
 *   ./gradlew :app:testDebugUnitTest --tests '*RssSourceProbeTest*' -i
 * 可用 `-Drss.file=<path>` 指定源文件（默认仓库根目录的 rssSource_202610102027.json）。
 */
class RssSourceProbeTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return File(".").absoluteFile
        }
    }

    private fun sourceFile(): File {
        val name = System.getProperty("rss.file") ?: "rssSource_202610102027.json"
        val f = File(name)
        return if (f.isAbsolute) f else File(repoRoot(), name)
    }

    /** 只取前 [limit] 个源，方便逐个排查。 */
    private fun loadSources(limit: Int = Int.MAX_VALUE): List<RssSource> {
        val file = sourceFile()
        if (!file.isFile) return emptyList()
        val list = Json.parse(file.readText(Charsets.UTF_8)) as? List<*> ?: return emptyList()
        var id = 0L
        return list.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            id++
            RssSource(
                id = id,
                name = map["sourceName"]?.toString().orEmpty(),
                url = map["sourceUrl"]?.toString().orEmpty(),
                rawJson = Json.encode(entry),
            )
        }.take(limit)
    }

    @Test
    fun probeEverySource() {
        assumeTrue("needs network", networkReachable())
        val report = StringBuilder()
        val sources = loadSources()
        report.append("sources=${sources.size}\n")
        for (source in sources) {
            report.append("\n=============== [${source.id}] ${source.name} ===============\n")
            val rules = try {
                LegadoRss.parseRules(source)
            } catch (t: Throwable) {
                null
            }
            if (rules == null) {
                report.append("  parseRules -> null\n")
                continue
            }
            val categories = try {
                LegadoRss.sortUrls(source, rules)
            } catch (t: Throwable) {
                report.append("  sortUrls FAILED: ${t.javaClass.simpleName}\n")
                emptyList()
            }
            val category = categories.firstOrNull()
            report.append("  categories=${categories.size} first='${category?.name}'\n")
            val url = try {
                LegadoRss.pageUrl(rules, category, 1, source.id)
            } catch (t: Throwable) {
                report.append("  pageUrl FAILED: ${t.javaClass.simpleName}: ${t.message?.take(100)}\n")
                continue
            }
            report.append("  pageUrl=${url.take(130)}\n")
            val body = try {
                httpGet(url, rules.header, category?.requestJson, source.id, rules.enabledCookieJar)
            } catch (t: Throwable) {
                report.append("  FETCH FAILED: ${t.javaClass.simpleName}: ${t.message?.take(140)}\n")
                continue
            }
            report.append("  body=${body.length} bytes head='${body.take(80).replace("\n", " ")}'\n")
            if (body.isBlank()) {
                report.append("  !! empty body\n")
                continue
            }
            val engine = LegadoRuleEngine(
                baseUrl = url,
                variables = rules.variables + mapOf("page" to "1"),
                sourceId = source.id,
                jsLib = rules.jsLib,
            )
            var rule = rules.ruleArticles
            if (rule.startsWith("-")) rule = rule.substring(1)
            val items = try {
                engine.getElements(rule, body)
            } catch (t: Throwable) {
                report.append("  getElements FAILED: ${t.javaClass.simpleName}: ${t.message?.take(120)}\n")
                continue
            }
            report.append("  items=${items.size}\n")
            for (error in engine.takeErrors()) {
                report.append("    LIST RULE ERROR: ${error.reason} | ${error.rule.take(80)}\n")
            }
            if (items.isEmpty()) continue
            var titles = 0
            var links = 0
            var images = 0
            var dates = 0
            var firstLine = ""
            for ((index, item) in items.withIndex()) {
                val itemEngine = LegadoRuleEngine(
                    baseUrl = url,
                    variables = rules.variables + mapOf("page" to "1"),
                    sourceId = source.id,
                    jsLib = rules.jsLib,
                )
                val title = firstValue(itemEngine, rules.ruleTitle, item)
                val link = firstValue(itemEngine, rules.ruleLink, item, isUrl = true)
                val image = firstValue(itemEngine, rules.ruleImage, item, isUrl = true)
                val date = firstValue(itemEngine, rules.rulePubDate, item)
                if (title.isNotBlank()) titles++
                if (link.isNotBlank()) links++
                if (image.isNotBlank()) images++
                if (date.isNotBlank()) dates++
                if (index == 0) {
                    firstLine = "title='${title.take(48)}' link='${link.take(60)}' image='${image.take(60)}' date='$date'"
                    for (error in itemEngine.takeErrors()) {
                        report.append("    ITEM RULE ERROR: ${error.reason} | ${error.rule.take(80)}\n")
                    }
                }
            }
            report.append("  fields: title=$titles link=$links image=$images date=$dates of ${items.size}\n")
            report.append("  first: $firstLine\n")
            val nextEngine = LegadoRuleEngine(
                baseUrl = url,
                variables = rules.variables + mapOf("page" to "1"),
                sourceId = source.id,
                jsLib = rules.jsLib,
            )
            val next = rules.ruleNextPage?.let { firstValue(nextEngine, it, body, isUrl = true) }
            report.append("  nextPage='${next?.take(110)}'\n")
            for (error in nextEngine.takeErrors()) {
                report.append("    NEXT RULE ERROR: ${error.reason} | ${error.rule.take(80)}\n")
            }
        }
        println(report)
    }

    private fun firstValue(
        engine: LegadoRuleEngine,
        rule: String?,
        content: Any,
        isUrl: Boolean = false,
    ): String {
        if (rule.isNullOrBlank()) return ""
        for (alternative in RuleAlternatives.split(rule)) {
            val value = try {
                engine.getString(alternative, content, isUrl)
            } catch (_: Throwable) {
                ""
            }
            if (value.isNotBlank()) return value
        }
        return ""
    }

    /** 与 [LegadoRss.buildRequest] 同样的请求装配，但直接同步执行便于探针观察。 */
    private fun httpGet(
        url: String,
        header: String?,
        requestJson: String?,
        sourceId: Long,
        cookies: Boolean,
    ): String {
        val request: Request = LegadoRss.buildRequest(url, header, requestJson, sourceId)
        val client = if (cookies) RssHttp.client else RssHttp.clientNoCookies
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val peek = response.peekBody(4L * 1024 * 1024)
            return ResponseCharset.decode(peek.bytes(), peek.contentType())
        }
    }

    private fun networkReachable(): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("www.baidu.com", 443), 5_000) }
        true
    } catch (_: Throwable) {
        false
    }
}
