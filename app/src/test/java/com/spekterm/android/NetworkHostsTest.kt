package com.spekterm.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The app names no host but its own pages (`play-distribution`, "Talk to no one but the user's desktop"): every URL in a
 * Kotlin string literal (comments skipped) or in a `<string>` / `<plurals>` resource is the privacy policy, in either
 * language, or the source repository and its issue page. The desktop's addresses come from the pairing, never from code.
 */
class NetworkHostsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private val allowed = setOf(
        "https://spekterm.com/android/privacy/",
        "https://spekterm.com/zh-tw/android/privacy/",
        "https://github.com/spekhq/spekterm-android",
        "https://github.com/spekhq/spekterm-android/issues/new",
    )

    private val url = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s\"'<>)]+")

    @Test
    fun everyUrlIsOneOfTheAppsOwnPages() {
        val found = mutableListOf<String>()
        for ((path, file) in KotlinSource.files()) {
            for ((line, text) in KotlinSource.literals(file.readText())) {
                url.findAll(text).map { it.value }.filter { it !in allowed }.forEach { found += "$path:$line: $it" }
            }
        }
        res.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values") }.forEach { dir ->
            dir.listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                for (tag in listOf("string", "item")) {
                    val nodes = doc.getElementsByTagName(tag)
                    for (i in 0 until nodes.length) {
                        val e = nodes.item(i) as Element
                        if (tag == "item" && (e.parentNode as? Element)?.tagName != "plurals") continue
                        url.findAll(e.textContent).map { it.value }.filter { it !in allowed }.forEach { found += "${dir.name}/${file.name}: $it" }
                    }
                }
            }
        }
        assertTrue("${found.size} URLs not of the app's own pages:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
