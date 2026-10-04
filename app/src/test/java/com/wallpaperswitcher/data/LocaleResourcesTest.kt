package com.wallpaperswitcher.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the language picker against the failure mode it cannot show at runtime.
 *
 * The picker offers exactly [SettingsKeys.TRANSLATED_LOCALES], and Android falls
 * back to `values/strings.xml` (Chinese) for any key a locale file is missing.
 * A half-translated file therefore looks like "the switch is broken" instead of
 * like a missing translation, so the key sets are compared here instead: every
 * offered locale must have a `values-<tag>` folder with exactly the same keys as
 * the default, and no duplicates anywhere.
 */
class LocaleResourcesTest {

    private val resDir = File("src/main/res")

    /** `values/strings.xml` and `values-en/strings.xml` -> "en" / "" for default. */
    private fun localeFolders(): Map<String, File> =
        resDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
            .mapNotNull { dir ->
                val file = File(dir, "strings.xml")
                if (!file.isFile) return@mapNotNull null
                val qualifier = dir.name.removePrefix("values").removePrefix("-")
                qualifier to file
            }
            .toMap()

    /**
     * Every translatable key in the file: `<string>` and `<plurals>` together.
     * Plurals count as keys too - a locale that ships the string but not the
     * plural would silently render "1 groups".
     */
    private fun keysOf(file: File): List<String> =
        Regex("""<(?:string|plurals) name="([^"]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toList()

    /**
     * BCP-47 tag -> Android resource qualifier: the region gets an `r` prefix
     * ("zh-TW" is shipped as `values-zh-rTW`), which is what aapt looks for when
     * `AppLocale` resolves the tag to a `Locale`.
     */
    private fun folderQualifier(tag: String): String =
        if (tag.contains('-')) tag.replaceFirst("-", "-r") else tag

    /**
     * `name -> the format specifiers used in its body`, e.g.
     * `group_media_count -> ["%1$d", "%2$s"]`.
     *
     * A translation that changes a specifier (or drops one) does not fail at
     * build time and does not fail for the default language either - it throws
     * `IllegalFormatConversionException` / `MissingFormatArgumentException` the
     * moment that screen renders in that language, i.e. a crash reachable only
     * by switching the app language and opening one specific screen. The
     * specifiers are therefore compared across the shipped locales here.
     *
     * `formatted="false"` entries are skipped: those legitimately contain a bare
     * `%` (e.g. "默认 10%") and are never passed to `getString(id, args)`.
     */
    private fun formatSpecifiers(file: File): Map<String, Set<String>> =
        Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .filterNot { it.groupValues[2].contains("formatted=\"false\"") }
            .associate { match ->
                val specifiers = Regex("""%(\d+\$)?[a-zA-Z]""")
                    .findAll(match.groupValues[3])
                    .map { it.value }
                    .toSet()
                match.groupValues[1] to specifiers
            }

    /**
     * `plurals name -> the quantities it defines`. Android needs at least
     * `other`; a locale that omits it throws on the very first render of that
     * count (Russian additionally wants one/few/many to read naturally).
     */
    private fun pluralQuantities(file: File): Map<String, Set<String>> =
        Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { match ->
                match.groupValues[1] to
                    Regex("""quantity="([^"]+)"""")
                        .findAll(match.groupValues[2])
                        .map { it.groupValues[1] }
                        .toSet()
            }

    /** Shipped locale files, default first. */
    private fun shippedLocaleFiles(): List<Pair<String, File>> =
        SettingsKeys.TRANSLATED_LOCALES.map { tag ->
            val qualifier = if (tag == "zh") "" else folderQualifier(tag)
            tag to if (tag == "zh") {
                File(resDir, "values/strings.xml")
            } else {
                File(resDir, "values-$qualifier/strings.xml")
            }
        }

    @Test
    fun defaultResourcesHaveNoDuplicateKeys() {
        val keys = keysOf(File(resDir, "values/strings.xml"))
        assertTrue("no strings parsed - is the working directory the module?", keys.size > 100)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun everyOfferedLocaleShipsAFullTranslation() {
        val folders = localeFolders()
        val defaultKeys = keysOf(File(resDir, "values/strings.xml")).toSet()

        SettingsKeys.TRANSLATED_LOCALES.forEach { tag ->
            // "zh" is the default (values/), every other tag needs its own folder.
            val folderName = if (tag == "zh") "" else folderQualifier(tag)
            val file = if (tag == "zh") {
                File(resDir, "values/strings.xml")
            } else {
                folders[folderName]?.takeIf { it.isFile }
            }
            assertTrue("missing values-$folderName/strings.xml for locale $tag", file != null)

            val keys = keysOf(file!!)
            assertEquals("duplicate keys in values-$folderName", keys.size, keys.toSet().size)
            assertEquals(
                "locale $tag is missing keys: " + (defaultKeys - keys.toSet()).sorted(),
                emptyList<String>(),
                (defaultKeys - keys.toSet()).sorted()
            )
            assertEquals(
                "locale $tag has keys that the default does not: " +
                    (keys.toSet() - defaultKeys).sorted(),
                emptyList<String>(),
                (keys.toSet() - defaultKeys).sorted()
            )
        }
    }

    @Test
    fun noTranslationFolderIsForgottenByThePicker() {
        // The other direction: a shipped translation nobody can select.
        val shipped = localeFolders().keys - ""
        val offered = SettingsKeys.TRANSLATED_LOCALES
            .filter { it != "zh" }
            .map { folderQualifier(it) }
            .toSet()
        assertEquals(emptySet<String>(), shipped - offered)
    }

    @Test
    fun formatSpecifiersMatchTheDefaultInEveryLocale() {
        val default = formatSpecifiers(File(resDir, "values/strings.xml"))
        assertTrue("no parameterised strings parsed", default.values.any { it.isNotEmpty() })

        shippedLocaleFiles().forEach { (tag, file) ->
            if (!file.isFile) return@forEach
            val actual = formatSpecifiers(file)
            default.forEach { (key, expected) ->
                if (expected.isEmpty()) return@forEach
                assertEquals(
                    "locale $tag changes the format specifiers of $key " +
                        "(a mismatch crashes at runtime, not at build time)",
                    expected,
                    actual[key] ?: emptySet<String>()
                )
            }
        }
    }

    @Test
    fun everyLocaleCanRenderEveryPluralQuantity() {
        val default = pluralQuantities(File(resDir, "values/strings.xml"))
        assertTrue("no plurals parsed", default.isNotEmpty())

        shippedLocaleFiles().forEach { (tag, file) ->
            if (!file.isFile) return@forEach
            val actual = pluralQuantities(file)
            default.keys.forEach { key ->
                assertTrue(
                    "locale $tag is missing plurals/$key",
                    actual.containsKey(key)
                )
                val quantities = actual.getValue(key)
                assertTrue(
                    "locale $tag: plurals/$key has no `other` fallback (throws at render time)",
                    quantities.contains("other")
                )
            }
        }
    }
}
