package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.util.AppLog
import org.mozilla.javascript.Context
import org.mozilla.javascript.Script
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.ConcurrentHashMap

/**
 * Rhino evaluation shared by the rule engine and the URL templates
 * (`{{Math.floor(Math.random()*10)}}`, `{{java.md5Encode(key)}}`, …).
 */
internal object LegadoJs {

    /**
     * 阅读 caches compiled scripts; re-compiling per rule evaluation dominates.
     *
     * Bounded LRU: one entry per distinct rule text and the key embeds the whole
     * jsLib (up to 4MB for a JS source), so an unbounded map grew for the whole
     * life of the process - which for this app includes the live-wallpaper
     * service.
     */
    private val scriptCache = object : LinkedHashMap<String, Script>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Script>?): Boolean =
            size > SCRIPT_CACHE_MAX
    }

    /** One base scope per `jsLib` (standard objects + the library evaluated once). */
    private val scopeCache = ConcurrentHashMap<String, Scriptable>()
    private val scopeLock = Any()

    fun eval(
        script: String,
        bindings: Map<String, String>,
        baseUrl: String,
        sourceId: Long = 0L,
        textProvider: ((String) -> String?)? = null,
        elementsProvider: ((String) -> List<Any>)? = null,
        jsLib: String? = null,
    ): String? = run(script, bindings, baseUrl, sourceId, textProvider, elementsProvider, jsLib).value

    /**
     * Outcome of a script: the value, or the error a login script threw.
     *
     * [value] 是 `toString()` 之后的文本（老的调用方都按字符串用）；
     * [raw] 保留**未字符串化**的原始值。区别很重要：`ruleArticles` 写成
     * `@js:(function(){… return out;})()` 时 `out` 是一个**数组**，
     * `toString()` 会把它压成一条逗号拼接的字符串 —— 列表于是只剩 1 条，
     * 后续逐项的 `@json:$.t` 全部取不到东西，整个源看起来"打开无内容"
     * （91porn 源就是这个形态）。数组必须交给 `getElements` 去摊平。
     */
    data class Result(val value: String?, val error: String?, val raw: Any? = null)

    fun run(
        script: String,
        bindings: Map<String, String>,
        baseUrl: String,
        sourceId: Long = 0L,
        textProvider: ((String) -> String?)? = null,
        elementsProvider: ((String) -> List<Any>)? = null,
        jsLib: String? = null,
        /** Per-engine scope cache: one JS scope per item instead of per rule. */
        scopeCache: MutableMap<String, Scriptable>? = null,
    ): Result {
        if (script.isBlank()) return Result(null, null)
        return try {
            val context = LegadoJsRuntime.enter()
            try {
                context.optimizationLevel = -1
                val base = sharedScope(context, jsLib)
                val cacheKey = jsLib.orEmpty()
                val scope = scopeCache?.get(cacheKey) ?: context.newObject(base).also {
                    it.prototype = base
                    scopeCache?.put(cacheKey, it)
                }
                for ((key, value) in bindings) {
                    try {
                        ScriptableObject.putProperty(scope, key, value)
                    } catch (_: Exception) {
                    }
                }
                ScriptableObject.putProperty(scope, "baseUrl", baseUrl)
                ScriptableObject.putProperty(scope, "cookie", Context.javaToJS(LegadoCookieHelper(), scope))
                val helpers = LegadoJsHelpers(sourceId, textProvider, elementsProvider)
                ScriptableObject.putProperty(scope, "java", Context.javaToJS(helpers, scope))
                ScriptableObject.putProperty(scope, "source", Context.javaToJS(helpers, scope))
                val source = if (jsLib.isNullOrBlank()) script else "$jsLib\n$script"
                val compiled = synchronized(scriptCache) {
                    scriptCache.getOrPut(source) {
                        context.compileString(source, "legado", 1, null)
                    }
                }
                val evaluated = compiled.exec(context, scope)
                val completion = Context.jsToJava(evaluated, Any::class.java)
                if (completion != null && evaluated != Undefined.instance) {
                    // JS numbers are doubles; 阅读 renders an integral result
                    // without the trailing ".0" ({{Math.floor(…)+1}}).
                    val number = completion as? Number
                    if (number != null && number.toDouble() % 1.0 == 0.0) {
                        return Result(number.toLong().toString(), null, completion)
                    }
                    // 原始值一起带上：数组必须留给 getElements 摊平，不能被
                    // toString() 压成一条字符串（见 Result.raw 的注释）。
                    return Result(completion.toString(), null, completion)
                }
                val fromScope = scope.get("result", scope)
                if (fromScope == Scriptable.NOT_FOUND || fromScope == null) {
                    Result(null, null)
                } else {
                    val raw = contextRawValue(context, fromScope)
                    Result(raw?.toString(), null, raw)
                }
            } finally {
                Context.exit()
            }
        } catch (t: Throwable) {
            if (LegadoJsRuntime.isTimeout(t)) {
                // Say it loudly: a rule that never returns is a source defect, and
                // silently reporting "no value" hid it completely.
                AppLog.w("LegadoJs", "js aborted: ${LegadoJsRuntime.timeoutMs}ms rule timeout")
            } else {
                AppLog.w("LegadoJs", "js failed: ${t.javaClass.simpleName}")
            }
            Result(null, cleanError(t))
        }
    }

    /**
     * Rhino 值 → Java 值，**保住数组**。
     *
     * `Context.jsToJava(value, Any::class.java)` 对 `NativeArray` 会退化成
     * `NativeJavaObject`（它自己也实现 `List`），于是调用方只看到"一个对象"。
     * 这里显式识别 `NativeArray` 并逐项转换，`ruleArticles` 这类返回数组的规则
     * 才能被摊平成 N 个条目。
     */
    private fun contextRawValue(context: Context, value: Any?): Any? = when (value) {
        null -> null
        is org.mozilla.javascript.NativeArray ->
            (0 until value.length).map { i ->
                // `NativeArray.get` 要的是属性名（字符串下标），不是 Int。
                Context.jsToJava(value.get(i.toString(), value), Any::class.java)
            }
        else -> Context.jsToJava(value, Any::class.java)
    }

    /**
     * The cached base scope of a source: standard objects plus its `jsLib`
     * evaluated once (阅读's `SharedJsScope`). Evaluations run in a child scope
     * whose prototype is this one, so bindings never leak between calls.
     */
    private fun sharedScope(context: Context, jsLib: String?): Scriptable {
        val key = jsLib.orEmpty()
        scopeCache[key]?.let { return it }
        synchronized(scopeLock) {
            scopeCache[key]?.let { return it }
            val standard = context.initStandardObjects(null, false)
            // Bound the cache: each entry keeps a compiled jsLib scope alive.
            if (scopeCache.size > 16) scopeCache.clear()
            val base: Scriptable = if (key.isBlank()) {
                standard
            } else {
                val scope = context.newObject(standard)
                scope.prototype = standard
                try {
                    context.evaluateString(scope, key, "jsLib", 1, null)
                } catch (t: Throwable) {
                    AppLog.w("LegadoJs", "jsLib failed: ${t.javaClass.simpleName}")
                }
                scope
            }
            scopeCache[key] = base
            return base
        }
    }

    private fun cleanError(t: Throwable): String {
        val raw = t.message ?: t.javaClass.simpleName
        return raw.replace(Regex("""\s*\(legado#\d+\)$"""), "").take(200)
    }

    /**
     * Compiled-script cache bound (see [scriptCache]): one entry per distinct rule
     * text, with the whole jsLib embedded in the key.
     */
    private const val SCRIPT_CACHE_MAX = 256
}
