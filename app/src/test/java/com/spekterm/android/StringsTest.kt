package com.spekterm.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The two string files match (`app-language`, `scan-connect-zh` D9): every English string has its zh-TW
 * counterpart with the same placeholders, every plural has `other`, and nothing is left untranslated.
 */
class StringsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    /** Names whose zh-TW text may equal the English one: names of things, not sentences. */
    private val sameInBoth = setOf("app_name", "conversation_title_fallback", "sessions_agent", "sessions_shell", "openspec_tab_spec", "openspec_tab_specs",
        "background_kind_agent", "background_kind_shell", "background_kind_monitor",
        // The agent's own terms, kept as they are in zh-TW (`agent-usage-and-skills`).
        "usage_context_percent", "usage_row_model", "usage_row_effort", "usage_row_context")

    private data class Entry(val text: String, val translatable: Boolean, val plural: Map<String, String> = emptyMap())

    private fun read(dir: String): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val out = linkedMapOf<String, Entry>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            out[e.getAttribute("name")] = Entry(e.textContent, e.getAttribute("translatable") != "false")
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val e = plurals.item(i) as Element
            val items = e.getElementsByTagName("item")
            val map = (0 until items.length).associate { (items.item(it) as Element).let { item -> item.getAttribute("quantity") to item.textContent } }
            out[e.getAttribute("name")] = Entry(map["other"].orEmpty(), true, map)
        }
        return out
    }

    private val placeholder = Regex("%(\\d+\\$)?[sdf]")

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.toSortedSet()

    @Test
    fun everyEnglishStringHasItsTranslationWithTheSamePlaceholders() {
        val en = read("values")
        val zh = read("values-zh-rTW")
        val problems = mutableListOf<String>()
        for ((name, entry) in en) {
            if (!entry.translatable) {
                if (name in zh) problems += "$name: marked untranslatable but translated"
                continue
            }
            val other = zh[name]
            if (other == null) {
                problems += "$name: no zh-TW"
                continue
            }
            val texts = if (entry.plural.isEmpty()) listOf(entry.text) else entry.plural.values.toList()
            val otherTexts = if (other.plural.isEmpty()) listOf(other.text) else other.plural.values.toList()
            for (t in otherTexts) if (placeholders(t) != placeholders(texts.last())) problems += "$name: placeholders ${placeholders(texts.last())} vs ${placeholders(t)}"
            if (entry.plural.isNotEmpty() && "other" !in entry.plural) problems += "$name: English plural lacks other"
            if (other.plural.isNotEmpty() && "other" !in other.plural) problems += "$name: zh-TW plural lacks other"
            if (entry.plural.isEmpty() != other.plural.isEmpty()) problems += "$name: plural in one file only"
            if (name !in sameInBoth && other.text == entry.text) problems += "$name: zh-TW is the English text"
        }
        for (name in zh.keys - en.keys) problems += "$name: zh-TW only"
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
