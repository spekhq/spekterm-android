package com.spekterm.android

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's code never takes a class's name at run time (`play-release` D3): R8 renames classes in the shipped build, so a
 * log line or a key built from one would read differently there. Comments are skipped. The one exception is
 * `Throwable.logName()`, whose names `proguard-rules.pro` keeps.
 */
class NoClassNamesTest {
    private val allowed = setOf("log/ConnectionLog.kt")

    private val className = Regex("(javaClass|::class(\\.java)?)\\.(simpleName|name|qualifiedName|canonicalName)\\b")

    @Test
    fun noClassNameIsTakenAtRunTime() {
        val found = mutableListOf<String>()
        for ((path, file) in KotlinSource.files()) {
            if (path in allowed) continue
            KotlinSource.withoutComments(file.readText()).lines().forEachIndexed { index, line ->
                className.find(line)?.let { found += "$path:${index + 1}: ${it.value}" }
            }
        }
        assertTrue("${found.size} class names taken at run time:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
