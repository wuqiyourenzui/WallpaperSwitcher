package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.data.RssSource
import com.wallpaperswitcher.engine.Json
import com.wallpaperswitcher.engine.RssHttp
import com.wallpaperswitcher.util.AppLog
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 阅读 的「JS 源 / 加密源」运行时。
 *
 * 这类源的 JSON 里没有静态规则，只有一份 `jsLib`：
 *
 * ```json
 * "header": "<js>eval(String(getJs()));</js>",
 * "jsLib":  "{\"源名\":\"https://host/dy/js/源名/jsLib.js\"}"
 * ```
 *
 * 阅读的做法（`SharedJsScope.getScope` + `BaseSource.evalJS`）：
 *  `jsLib` 是 JSON 对象时，把每个值当 URL 下载下来、整份 eval 进源的共享作用域；
 *  之后源的 `header` 规则（上面那段 JS）执行 `eval(String(getJs()))`，
 *  `getJs()` 用 `java.head` / okhttp 拉回一份「规则脚本」，eval 后把
 *  `source.ruleArticles` / `ruleTitle` … 写进源对象，列表解析才用得上。
 *
 * 这里复刻同一条链：下载并缓存 jsLib → 建作用域（`source` 是一份可写的字段表、
 * `java`/`cache`/`cookie` 用本应用的实现）→ 执行源自己的脚本 → 把字段读回来。
 * 作用域里保留 Rhino 的 Java 包，但用 [PackageShutter] 白名单限制可访问的类，
 * 仅放行 JS 源常用的 okhttp3 / okio / java 工具类（文件、进程、反射全部挡住）。
 */
internal object LegadoJsSource {

    /** 最多缓存几份 jsLib 源码（每份可达 4MB，见 [libCache]）。 */
    private const val LIB_CACHE_MAX = 6

    /** 同一份 rawJson 只跑一次（脚本解析 + 下载都很贵）。 */
    private val rulesCache = ConcurrentHashMap<Long, Entry>()
    private class Entry(val fingerprint: Int, val rules: LegadoRss.Rules)

    /**
     * 下载过的 jsLib 源码：URL → JS。
     *
     * 有界 LRU（几份就够用）：一份 jsLib 可达 4MB（见 [runSourceScript] 的
     * 下载分支），而这是一个进程级单例、壁纸服务会长期保活进程 —— 无界
     * Map 会随源编辑/换源一路增长。淘汰后只是重新下载/读缓存。
     */
    private val libCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(LIB_CACHE_MAX, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?
            ): Boolean = size > LIB_CACHE_MAX
        }
    )

    /**
     * 源里声明了 jsLib、又没有静态规则 —— 阅读的 JS 源形态。
     * `jsLib` 只是给 `@js:` 用的工具库时不算（那种源有静态规则或只当网页打开）。
     */
    fun isJsSource(fields: Map<*, *>?): Boolean {
        val map = fields ?: return false
        val jsLib = (map["jsLib"] as? String) ?: return false
        if (jsLib.isBlank()) return false
        if (!(map["ruleArticles"] as? String).isNullOrBlank()) return false
        // 规则由 `getJs()` 生成：约定写在 header 里
        //（`<js>eval(String(getJs()));</js>`），或 jsLib 内联定义了 getJs。
        val header = (map["header"] as? String).orEmpty()
        return header.contains("getJs") || jsLib.contains("getJs")
    }

    fun resolveRules(source: RssSource, fields: Map<*, *>): LegadoRss.Rules? {
        val jsLib = (fields["jsLib"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val fingerprint = source.rawJson.hashCode()
        rulesCache[source.id]?.takeIf { it.fingerprint == fingerprint }?.let { return it.rules }
        val script = runSourceScript(source, fields, jsLib) ?: return null
        val rules = LegadoRss.rulesFrom(source, script) ?: return null
        if (rulesCache.size > 16) rulesCache.clear()
        rulesCache[source.id] = Entry(fingerprint, rules)
        return rules
    }

    /** 源 JSON 变化（重新导入）后要重新生成。 */
    fun forget(sourceId: Long) {
        rulesCache.remove(sourceId)
    }

    // --- 运行时 ---------------------------------------------------------------

    /**
     * 执行 JS 源的脚本，返回它写回 `source` 的字段表（含 jsLib 里新的规则）。
     * 失败一律返回 null —— 调用方按「该源需要阅读的 JS 运行时而本应用拿不到规则」处理。
     */
    private fun runSourceScript(
        source: RssSource,
        fields: Map<*, *>,
        jsLib: String,
    ): Map<String, Any?>? {
        val library = loadLibrary(jsLib) ?: return null
        val script = sourceScript(fields)
        // Timed context: a source script that never returns (or that calls back
        // into a rule doing so) used to pin this thread for good - see
        // LegadoJsRuntime.
        val context = LegadoJsRuntime.enter()
        return try {
            context.optimizationLevel = -1
            context.setClassShutter(PackageShutter)
            val scope = context.initStandardObjects(null, false)
            val holder = context.newObject(scope)
            for ((key, value) in fields) {
                if (key is String && (value is String || value is Boolean || value is Number)) {
                    ScriptableObject.putProperty(holder, key, value)
                }
            }
            bindSourceFunctions(context, scope, holder, source)
            defineReadOnly(context, scope, "source", holder)
            defineReadOnly(context, scope, "java", Context.javaToJS(LegadoJsHelpers(source.id), scope))
            defineReadOnly(context, scope, "cookie", Context.javaToJS(LegadoCookieHelper(), scope))
            defineReadOnly(context, scope, "cache", jsCache(context, scope))
            defineReadOnly(
                context, scope, "baseUrl",
                (fields["sourceUrl"] as? String).orEmpty().ifBlank { source.url },
            )
            context.evaluateString(scope, library, "jsLib", 1, null)
            val evaluated = context.evaluateString(scope, script, "sourceJs", 1, null)
            val fields = readBack(holder)
            if (System.getenv("WS_JS_DEBUG") != null) {
                val payload = context.evaluateString(scope, "String(getJs())", "debugPayload", 1, null)
                val text = Context.toString(payload)
                System.err.println("[wsjs] payload = " + text.take(900))
                System.err.println("[wsjs] script result = " + Context.toString(evaluated))
                System.err.println("[wsjs] ruleArticles = " + fields["ruleArticles"])
                System.err.println("[wsjs] fields = " + fields.keys.joinToString(","))
            }
            fields
        } catch (t: Throwable) {
            if (LegadoJsRuntime.isTimeout(t)) {
                AppLog.w("LegadoJsSource", "source script aborted: ${LegadoJsRuntime.timeoutMs}ms rule timeout")
            }
            if (System.getenv("WS_JS_DEBUG") != null) t.printStackTrace()
            // 脚本、网络、类白名单哪一步失败都按「拿不到规则」处理：
            // 上层会报「该源依赖阅读的 JS 库」之类的明确原因，而不是解析异常。
            null
        } finally {
            Context.exit()
        }
    }

    /**
     * 源自己的脚本：`header` 里的 `<js>…</js>` / `@js:` 规则（阅读的 JS 源约定
     * 就是 `eval(String(getJs()))`）。没有 header 规则时退化为直接调用 `getJs()`。
     */
    private fun sourceScript(fields: Map<*, *>): String {
        val header = (fields["header"] as? String).orEmpty().trim()
        if (header.startsWith("<js>", ignoreCase = true)) {
            val end = header.lastIndexOf("</js>")
            if (end > 4) return header.substring(4, end)
        }
        if (header.startsWith("@js:", ignoreCase = true)) return header.substring(4)
        return "eval(String(getJs()));"
    }

    /** `jsLib` 支持两种形态：内联 JS，或 `{"名称":"JS URL"}`（阅读 `SharedJsScope`）。 */
    private fun loadLibrary(jsLib: String): String? {
        val map = try {
            (Json.parse(jsLib) as? Map<*, *>)?.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        } ?: return jsLib
        val codes = ArrayList<String>()
        for ((_, value) in map) {
            val url = value as? String ?: continue
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
            val code = libCache[url] ?: downloadLibrary(url) ?: return null
            codes.add(code)
        }
        return codes.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /** 下载 jsLib 并落到 `cache/legado_js`（阅读用 ACache 缓存同一份文件）。 */
    private fun downloadLibrary(url: String): String? {
        val file = cacheFile(url)
        if (file != null && file.isFile && file.length() > 0) {
            return try {
                file.readText().also { libCache[url] = it }
            } catch (_: Throwable) {
                null
            }
        }
        return try {
            val code = RssHttp.client.newCall(
                okhttp3.Request.Builder().url(url).header("User-Agent", RssHttp.USER_AGENT).build()
            ).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.peekBody(4L * 1024 * 1024)
                ResponseCharset.decode(body.bytes(), body.contentType())
            }
            if (code.isBlank()) return null
            libCache[url] = code
            try {
                file?.parentFile?.mkdirs()
                file?.writeText(code)
            } catch (_: Throwable) {
            }
            code
        } catch (_: Throwable) {
            null
        }
    }

    private fun cacheFile(url: String): File? {
        val root = RssHttp.engineCacheDir ?: return null
        val digest = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(root, "legado_js"), digest)
    }

    /** `source` 上阅读 JS 源会用到的方法（变量、登录信息、getKey）。 */
    private fun bindSourceFunctions(
        context: Context,
        scope: Scriptable,
        holder: Scriptable,
        source: RssSource,
    ) {
        val helpers = LegadoJsHelpers(source.id)
        fun method(name: String, arity: Int, body: (Array<out Any?>) -> Any?) {
            ScriptableObject.defineProperty(
                holder,
                name,
                object : BaseFunction() {
                    override fun getArity() = arity
                    override fun call(
                        cx: Context,
                        s: Scriptable,
                        thisObj: Scriptable,
                        args: Array<out Any?>,
                    ): Any? = body(args)
                },
                ScriptableObject.DONTENUM,
            )
        }
        val key = (holder.get("sourceUrl", holder) as? String)
            .orEmpty().ifBlank { source.url }
        method("getKey", 0) { key }
        method("getVariable", 0) { helpers.getVariable() }
        method("setVariable", 1) { helpers.setVariable(argText(it, 0)) }
        method("get", 1) { helpers.get(argText(it, 0)) }
        method("put", 2) { helpers.put(argText(it, 0), argText(it, 1)) }
        method("getLoginInfo", 0) { helpers.getLoginInfo() }
        method("putLoginInfo", 1) { helpers.putLoginInfo(argText(it, 0)); "" }
        method("getLoginHeader", 0) { helpers.getLoginHeader() }
        method("putLoginHeader", 1) { helpers.putLoginHeader(argText(it, 0)); "" }
        // `with (javaImport)` 之类的写法会用到作用域本身，这里给一个稳定的 this。
        ScriptableObject.putProperty(scope, "_wsSourceKey", key)
    }

    private fun argText(values: Array<out Any?>, index: Int): String =
        values.getOrNull(index)?.let { Context.toString(it) }.orEmpty()

    private fun defineReadOnly(context: Context, scope: Scriptable, name: String, value: Any?) {
        ScriptableObject.defineProperty(
            scope,
            name,
            if (value is Scriptable) value else Context.javaToJS(value, scope),
            ScriptableObject.READONLY or ScriptableObject.PERMANENT or ScriptableObject.DONTENUM,
        )
    }

    /** `cache.get/put/delete`：JS 源用它缓存 etag 与拉到的规则。 */
    private fun jsCache(context: Context, scope: Scriptable): Scriptable {
        val cache = context.newObject(scope)
        fun method(name: String, body: (String, String) -> Any?) {
            ScriptableObject.defineProperty(
                cache,
                name,
                object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        s: Scriptable,
                        thisObj: Scriptable,
                        args: Array<out Any?>,
                    ): Any? = body(argText(args, 0), argText(args, 1))
                },
                ScriptableObject.DONTENUM,
            )
        }
        method("get") { key, _ -> JsCache.get(key) }
        method("put") { key, value -> JsCache.put(key, value); "" }
        method("delete") { key, _ -> JsCache.remove(key); "" }
        method("remove") { key, _ -> JsCache.remove(key); "" }
        return cache
    }

    /** 把 `source` 上的字段读回 Kotlin（含脚本新写入的规则）。 */
    private fun readBack(holder: Scriptable): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (id in holder.ids) {
            val name = Context.toString(id)
            if (name.startsWith("_")) continue
            val value = ScriptableObject.getProperty(holder, name)
            if (value == null || value == Scriptable.NOT_FOUND) continue
            out[name] = when (value) {
                is org.mozilla.javascript.Undefined -> null
                is Scriptable -> Context.toString(value)
                else -> value
            }
        }
        return out
    }

    /**
     * JS 源可以用的 Java 类白名单：okhttp3 / okio 网络栈 + 常用工具类。
     * 文件、进程、反射、类加载器一律不可见（Rhino 的 ClassShutter）。
     */
    private object PackageShutter : ClassShutter {

        private val prefixes = listOf("okhttp3.", "okio.", "org.json.")
        private val classes = setOf(
            // 绑进作用域的助手对象（java / cookie）；不放开整个应用包。
            "com.wallpaperswitcher.engine.legado.LegadoJsHelpers",
            "com.wallpaperswitcher.engine.legado.LegadoCookieHelper",
            "com.wallpaperswitcher.engine.legado.LegadoJsResponse",
            "com.wallpaperswitcher.engine.legado.LegadoJsCryptoHandle",
            "java.lang.String", "java.lang.StringBuilder", "java.lang.StringBuffer",
            "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
            "java.lang.Boolean", "java.lang.Character", "java.lang.Byte", "java.lang.Short",
            "java.lang.Number", "java.lang.Math", "java.lang.Object", "java.lang.CharSequence",
            "java.lang.Exception", "java.lang.RuntimeException", "java.lang.IllegalStateException",
            "java.lang.IllegalArgumentException", "java.lang.StringIndexOutOfBoundsException",
            "java.lang.ThreadLocal", "java.lang.Iterable", "java.lang.Comparable",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.HashMap",
            "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.HashSet",
            "java.util.LinkedHashSet", "java.util.Arrays", "java.util.Collections",
            "java.util.List", "java.util.Map", "java.util.Set", "java.util.Collection",
            "java.util.Iterator", "java.util.Date", "java.util.Locale", "java.util.Random",
            "java.util.UUID", "java.util.Base64", "java.util.Objects", "java.util.Optional",
            "java.net.URLEncoder", "java.net.URLDecoder", "java.net.URI", "java.net.URL",
            "java.security.MessageDigest", "java.nio.charset.Charset",
            "java.nio.charset.StandardCharsets", "java.text.SimpleDateFormat",
            "java.text.DecimalFormat", "java.math.BigInteger", "java.math.BigDecimal",
            "javax.crypto.Mac", "javax.crypto.Cipher", "javax.crypto.spec.SecretKeySpec",
        )

        override fun visibleToScripts(fullClassName: String): Boolean =
            fullClassName in classes || prefixes.any { fullClassName.startsWith(it) }
    }

    /** `cache` 绑定的存储：内存 + 可选磁盘（阅读用 ACache，语义一致）。 */
    private object JsCache {
        private val memory = ConcurrentHashMap<String, String>()

        fun get(key: String): String {
            load()
            return memory[key].orEmpty()
        }

        fun put(key: String, value: String) {
            load()
            memory[key] = value
            persist()
        }

        fun remove(key: String) {
            load()
            memory.remove(key)
            persist()
        }

        private fun file(): File? = RssHttp.engineCacheDir?.let { File(it, "legado_js_cache.json") }

        @Synchronized
        private fun persist() {
            val target = file() ?: return
            try {
                target.parentFile?.mkdirs()
                target.writeText(Json.encode(memory.toMap()))
            } catch (_: Throwable) {
            }
        }

        @Synchronized
        fun load() {
            if (memory.isNotEmpty()) return
            val target = file() ?: return
            if (!target.isFile) return
            try {
                (Json.parse(target.readText()) as? Map<*, *>)?.forEach { (k, v) ->
                    if (k is String && v != null) memory[k] = v.toString()
                }
            } catch (_: Throwable) {
            }
        }
    }
}
