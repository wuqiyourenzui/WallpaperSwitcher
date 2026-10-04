package com.wallpaperswitcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupTest {

    private val sample = ConfigBackup.Config(
        groups = listOf(
            ConfigBackup.GroupConfig(
                name = "海边的照片",
                target = "LOCK",
                isEnabled = false,
                intervalMs = 300_000L,
                switchMode = "SHUFFLE",
                activeFromMinute = 22 * 60,
                activeToMinute = 6 * 60
            ),
            ConfigBackup.GroupConfig(name = "plain \"quotes\" and \\slashes\\")
        ),
        settings = mapOf("global_interval_ms" to "60000", "global_switch_mode" to "RANDOM")
    )

    @Test
    fun roundTripKeepsEveryField() {
        val decoded = ConfigBackup.decode(ConfigBackup.encode(sample))
        assertEquals(sample, decoded)
    }

    @Test
    fun escapingSurvivesQuotesBackslashesAndNewlines() {
        val tricky = ConfigBackup.Config(
            groups = listOf(
                ConfigBackup.GroupConfig(name = "a\"b\\c\nd\te\u0001f", target = "HOME")
            )
        )
        val decoded = ConfigBackup.decode(ConfigBackup.encode(tricky))
        assertEquals(tricky, decoded)
    }

    @Test
    fun emptyConfigRoundTrips() {
        val empty = ConfigBackup.Config(groups = emptyList(), settings = emptyMap())
        assertEquals(empty, ConfigBackup.decode(ConfigBackup.encode(empty)))
    }

    @Test
    fun garbageIsRejectedInsteadOfGuessed() {
        assertNull(ConfigBackup.decode(""))
        assertNull(ConfigBackup.decode("not json at all"))
        assertNull(ConfigBackup.decode("{\"hello\": 1}"))
        assertNull(ConfigBackup.decode("[1, 2, 3]"))
    }

    @Test
    fun foreignJsonWithOurFormatFieldButNoVersionIsRejected() {
        assertNull(ConfigBackup.decode("{\"format\": \"${ConfigBackup.FORMAT}\"}"))
    }

    @Test
    fun aNewerFormatVersionIsRejected() {
        val text = """
            {"format": "${ConfigBackup.FORMAT}", "version": ${ConfigBackup.VERSION + 1}, "groups": []}
        """.trimIndent()
        assertNull(ConfigBackup.decode(text))
    }

    @Test
    fun malformedGroupsAreSkippedNotFatal() {
        val text = """
            {
              "format": "${ConfigBackup.FORMAT}",
              "version": ${ConfigBackup.VERSION},
              "groups": [
                {"target": "HOME"},
                {"name": "  "},
                {"name": "ok", "target": "LOCK", "intervalMs": 60000},
                "not an object"
              ]
            }
        """.trimIndent()
        val decoded = ConfigBackup.decode(text)
        assertEquals(1, decoded?.groups?.size)
        assertEquals("ok", decoded?.groups?.first()?.name)
        assertEquals("LOCK", decoded?.groups?.first()?.target)
        assertEquals(60_000L, decoded?.groups?.first()?.intervalMs)
    }

    @Test
    fun outOfRangeWindowsAreClamped() {
        val text = """
            {
              "format": "${ConfigBackup.FORMAT}",
              "version": ${ConfigBackup.VERSION},
              "groups": [
                {"name": "g", "activeFromMinute": 99999, "activeToMinute": -77}
              ]
            }
        """.trimIndent()
        val group = ConfigBackup.decode(text)?.groups?.first()
        assertEquals(1439, group?.activeFromMinute)
        assertEquals(-1, group?.activeToMinute)
    }

    @Test
    fun settingsSurfaceIsLimitedToTheExportedKeys() {
        // The file must not smuggle arbitrary settings into the database.
        assertTrue(ConfigBackup.EXPORTED_SETTINGS.isNotEmpty())
        assertTrue(
            ConfigBackup.EXPORTED_SETTINGS.none {
                it.contains("image_uri") || it.contains("log")
            }
        )
    }

    @Test
    fun subscriptionsArePartOfTheConfig() {
        val config = ConfigBackup.Config(
            groups = emptyList(),
            settings = emptyMap(),
            sources = listOf(
                ConfigBackup.SourceConfig(
                    name = "美人图",
                    url = "https://meirentu.club",
                    type = 0,
                    enabled = false,
                    rawJson = """{"sourceName":"美人图","ruleArticles":"class.item"}""",
                ),
            ),
        )
        val decoded = ConfigBackup.decode(ConfigBackup.encode(config))
        assertEquals(config, decoded)
    }

    @Test
    fun sourcesWithoutUrlAreSkipped() {
        val text = """
            {
              "format": "${ConfigBackup.FORMAT}",
              "version": ${ConfigBackup.VERSION},
              "groups": [],
              "sources": [
                {"name": "no url"},
                {"name": "ok", "url": "https://a.example/feed", "type": 2, "enabled": false}
              ]
            }
        """.trimIndent()
        val sources = ConfigBackup.decode(text)?.sources
        assertEquals(1, sources?.size)
        assertEquals("https://a.example/feed", sources?.first()?.url)
        assertEquals(2, sources?.first()?.type)
        assertEquals(false, sources?.first()?.enabled)
    }

    @Test
    fun anOldVersionOneFileStillLoads() {
        // Files exported before subscriptions existed must keep working.
        val text = """
            {"format": "${ConfigBackup.FORMAT}", "version": 1,
             "groups": [{"name": "g"}], "settings": {}}
        """.trimIndent()
        val decoded = ConfigBackup.decode(text)
        assertEquals(1, decoded?.groups?.size)
        assertEquals(0, decoded?.sources?.size)
    }
}
