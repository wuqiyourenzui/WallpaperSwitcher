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

    /** 阅读 caches compiled scripts; re-compiling per rule evaluation dominates. */
    private val scriptCache = ConcurrentHashMap<String, Script>()

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

    /** Outcome of a script: the value, or the error a login script threw. */
    data class Result(val value: String?, val error: String?)

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
            val context = Context.enter()
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
                val compiled = scriptCache.getOrPut(source) {
                    context.compileString(source, "legado", 1, null)
                }
                val evaluated = compiled.exec(context, scope)
                val completion = Context.jsToJava(evaluated, Any::class.java)
                if (completion != null && evaluated != Undefined.instance) {
                    // JS numbers are doubles; 阅读 renders an integral result
                    // without the trailing ".0" ({{Math.floor(…)+1}}).
                    val number = completion as? Number
                    if (number != null && number.toDouble() % 1.0 == 0.0) {
                        return Result(number.toLong().toString(), null)
                    }
                    return Result(completion.toString(), null)
                }
                val fromScope = scope.get("result", scope)
                if (fromScope == Scriptable.NOT_FOUND || fromScope == null) {
                    Result(null, null)
                } else {
                    Result(Context.jsToJava(fromScope, Any::class.java)?.toString(), null)
                }
            } finally {
                Context.exit()
            }
        } catch (t: Throwable) {
            AppLog.w("LegadoJs", "js failed: ${t.javaClass.simpleName}")
            Result(null, cleanError(t))
        }
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
}
