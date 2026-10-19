package com.spekterm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Play listing's texts fit Play's limits, in both locales (`play-release` D6). */
class PlayListingTest {
    private val listings = listOf(File("../play/listings"), File("play/listings")).first { it.isDirectory }

    private val limits = mapOf("title.txt" to 30, "short-description.txt" to 80, "full-description.txt" to 4000)

    @Test
    fun everyTextFitsItsLimit() {
        val problems = mutableListOf<String>()
        for (locale in listOf("en-US", "zh-TW")) {
            for ((name, limit) in limits) {
                val file = File(listings, "$locale/$name")
                if (!file.isFile) {
                    problems += "$locale/$name: missing"
                    continue
                }
                val text = file.readText().trimEnd('\n')
                val length = text.codePointCount(0, text.length)
                if (text.isBlank()) problems += "$locale/$name: empty"
                if (length > limit) problems += "$locale/$name: $length characters, over $limit"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
