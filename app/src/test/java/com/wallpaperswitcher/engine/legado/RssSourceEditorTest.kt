package com.wallpaperswitcher.engine.legado

import com.wallpaperswitcher.engine.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Editing an imported source must keep every field it does not touch. */
class RssSourceEditorTest {

    private val raw = """
        {"sourceName":"t","sourceUrl":"https://a.com/feed","customOrder":3,
         "header":"{\"Referer\":\"https://a.com/\"}","ruleArticles":"class.item",
         "ruleTitle":"tag.h2@text","enabled":true}
    """.trimIndent()

    @Test
    fun changesReplaceOnlyTheEditedFields() {
        val merged = RssSourceEditor.applyChanges(
            raw,
            mapOf("ruleTitle" to "tag.h1@text", "sourceGroup" to "g1"),
        )
        val map = Json.parse(merged) as Map<*, *>
        assertEquals("tag.h1@text", map["ruleTitle"])
        assertEquals("g1", map["sourceGroup"])
        // Untouched fields survive, including ones the app never reads.
        assertEquals("class.item", map["ruleArticles"])
        assertEquals(3, (map["customOrder"] as Number).toInt())
        assertEquals("""{"Referer":"https://a.com/"}""", map["header"])
    }

    @Test
    fun blankValuesRemoveTheField() {
        val merged = RssSourceEditor.applyChanges(raw, mapOf("ruleArticles" to "  "))
        val map = Json.parse(merged) as Map<*, *>
        assertNull(map["ruleArticles"])
        assertEquals("tag.h2@text", map["ruleTitle"])
    }

    @Test
    fun typedChangesWriteNumbersAndBooleans() {
        val merged = RssSourceEditor.applyTypedChanges(
            raw,
            mapOf("type" to 1L, "enabled" to false, "enabledCookieJar" to false),
        )
        val map = Json.parse(merged) as Map<*, *>
        assertEquals(1, (map["type"] as Number).toInt())
        assertEquals(false, map["enabled"])
        assertEquals(false, map["enabledCookieJar"])
        assertEquals("class.item", map["ruleArticles"])
    }

    @Test
    fun brokenJsonStillProducesAnObject() {
        val merged = RssSourceEditor.applyChanges("{not json", mapOf("sourceUrl" to "https://b.com"))
        val map = Json.parse(merged) as Map<*, *>
        assertEquals("https://b.com", map["sourceUrl"])
    }

    @Test
    fun editedBooleanKeepsItsJsonType() {
        val merged = RssSourceEditor.applyChanges(raw, mapOf("enabled" to "false"))
        val map = Json.parse(merged) as Map<*, *>
        assertEquals(false, map["enabled"])
    }

    @Test
    fun editedNumberKeepsItsJsonType() {
        val merged = RssSourceEditor.applyChanges(raw, mapOf("customOrder" to "-12"))
        val map = Json.parse(merged) as Map<*, *>
        assertEquals(-12, (map["customOrder"] as Number).toInt())
    }

    @Test
    fun nonNumericTextOnANumberFieldFallsBackToString() {
        val merged = RssSourceEditor.applyChanges(raw, mapOf("customOrder" to "auto"))
        val map = Json.parse(merged) as Map<*, *>
        assertEquals("auto", map["customOrder"])
    }

    @Test
    fun newBooleanFieldIsAddedAsABoolean() {
        val merged = RssSourceEditor.applyChanges(raw, mapOf("preload" to "true"))
        val map = Json.parse(merged) as Map<*, *>
        assertEquals(true, map["preload"])
    }

    @Test
    fun remainingKeysListsEverythingTheEditorDoesNotLabel() {
        val json = """
            {"sourceName":"t","sourceUrl":"https://a.com","type":0,"enabled":true,
             "enabledCookieJar":false,"sourceGroup":"g","ruleArticles":"x",
             "enableJs":true,"preload":false,"style":"body{}","sourceIcon":"i.png"}
        """.trimIndent()
        // enableJs / style / sourceIcon 现在有了自己的页签字段（阅读也是这样分组），
        // 所以它们不再出现在「其余字段」兜底区；preload 仍然只有兜底区能改到。
        // 这条断言守的是：**没被编辑器标名的键必须仍然被兜底区列出来**。
        val keys = RssSourceEditor.remainingKeys(json)
        assertEquals(listOf("preload"), keys)
    }

    @Test
    fun everyLabelledTabFieldIsActuallyCoveredByTheFallbackRule() {
        // 四个页签里的字段不该重复出现在兜底区（否则同一个键会有两个输入框）。
        val all = RssSourceEditor.TAB_BASE_FIELDS + RssSourceEditor.TAB_START_FIELDS +
            RssSourceEditor.TAB_LIST_FIELDS + RssSourceEditor.TAB_WEB_FIELDS +
            RssSourceEditor.WEB_BOOL_FIELDS
        val json = "{" + all.joinToString(",") { """"$it":"v"""" } + "}"
        assertEquals(emptyList<String>(), RssSourceEditor.remainingKeys(json))
    }
}
