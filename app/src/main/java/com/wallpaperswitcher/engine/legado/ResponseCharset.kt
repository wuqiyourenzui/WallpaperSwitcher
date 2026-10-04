package com.wallpaperswitcher.engine.legado

import okhttp3.MediaType
import java.nio.charset.Charset

/**
 * 阅读 抓网页时的编码判定顺序：响应头 `Content-Type` 的 charset → 网页头部
 * `<meta charset>` / `<meta http-equiv>` → UTF-8。
 *
 * OkHttp 的 `body.string()` 只看响应头，对没在响应头里声明编码的 GBK / GB2312
 * 站点（国内源很常见）会把中文解成乱码；这里补上阅读同款的<meta> 嗅探，
 * 两边解析出的标题、正文才一致。
 */
internal object ResponseCharset {

    /** 只在开头这些字节里找 `<meta charset>`（阅读同样只看头部）。 */
    private const val SNIFF_BYTES = 4096

    private val META_CHARSET = Regex(
        """<meta[^>]{0,300}?charset\s*=\s*["']?\s*([a-zA-Z0-9_\-]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_CHARSET = Regex(
        """charset\s*=\s*["']?\s*([a-zA-Z0-9_\-]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** 响应头 → `<meta charset>` → UTF-8。 */
    fun decode(bytes: ByteArray, contentType: MediaType?): String =
        String(bytes, charsetOf(bytes, contentType) ?: Charsets.UTF_8)

    fun charsetOf(bytes: ByteArray, contentType: MediaType?): Charset? {
        contentType?.charset()?.let { return it }
        if (bytes.isEmpty()) return null
        // 头部按单字节编码读，任何一种 charset 声明都能被 ASCII 匹配到。
        val head = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.ISO_8859_1)
        val name = (META_CHARSET.find(head) ?: BARE_CHARSET.find(head))
            ?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (name.isBlank()) return null
        return try {
            Charset.forName(normalize(name))
        } catch (_: Exception) {
            null
        }
    }

    /** charset 名称的常见别名；认不出来的交给 JDK，失败则继续降级。 */
    private fun normalize(name: String): String = when (name.lowercase()) {
        "gb2312", "gbk", "gb18030" -> "GBK"
        else -> name
    }
}
