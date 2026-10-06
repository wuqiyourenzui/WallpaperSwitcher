package com.wallpaperswitcher.engine

import javax.xml.parsers.DocumentBuilderFactory

/**
 * 共享的不可信 XML 加固：外部实体的 RSS/Atom/WebDAV 文档一律禁用 DTD 与外部实体。
 *
 * Android 默认 factory 对个别开关会抛 `UnsupportedOperationException`，所以每个
 * 开关都是 best-effort；解析器只用于短生命周期的一次性解析。
 */
internal fun DocumentBuilderFactory.hardenForUntrustedXml() {
    try {
        isExpandEntityReferences = false
    } catch (_: Throwable) {
    }
    try {
        isXIncludeAware = false
    } catch (_: Throwable) {
    }
    setFeatureQuietlyForXml("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeatureQuietlyForXml("http://xml.org/sax/features/external-general-entities", false)
    setFeatureQuietlyForXml("http://xml.org/sax/features/external-parameter-entities", false)
}

private fun DocumentBuilderFactory.setFeatureQuietlyForXml(name: String, value: Boolean) {
    try {
        setFeature(name, value)
    } catch (_: Exception) {
        // Not every platform parser knows every feature.
    }
}
