package com.spekterm.android

import java.io.File

/** Reading the app's Kotlin sources in JVM tests: its main source root, its string literals, its code without comments. */
object KotlinSource {
    val root: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /** Every `.kt` file under [root], with its path below `com/spekterm/android/`. */
    fun files(): Sequence<Pair<String, File>> =
        root.walkTopDown().filter { it.extension == "kt" }.map { it.relativeTo(root).path.substringAfter("com/spekterm/android/") to it }

    /** [source] with its comments blanked out (line breaks kept, so line numbers stay); strings are kept. */
    fun withoutComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var inString: String? = null
        while (i < source.length) {
            val quote = inString
            when {
                quote != null -> {
                    if (quote == "\"" && source[i] == '\\' && i + 1 < source.length) {
                        out.append(source, i, i + 2)
                        i += 2
                        continue
                    }
                    if (source.startsWith(quote, i)) {
                        out.append(quote)
                        i += quote.length
                        inString = null
                        continue
                    }
                    out.append(source[i])
                    i++
                }
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') {
                    out.append(' ')
                    i++
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    for (k in i until end) out.append(if (source[k] == '\n') '\n' else ' ')
                    i = end
                }
                source.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    inString = "\"\"\""
                }
                source[i] == '"' -> {
                    out.append('"')
                    i++
                    inString = "\""
                }
                source[i] == '\'' -> {
                    // A character literal: '"' must not open a string.
                    val end = minOf(source.length, i + if (i + 1 < source.length && source[i + 1] == '\\') 4 else 3)
                    out.append(source, i, end)
                    i = end
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * The string literals of [source] with their line numbers; a template's `${…}` and `$name` are dropped, so
     * the text around them is what is checked. Skips comments and character literals.
     */
    fun literals(source: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = 0
        var line = 1
        fun advance(n: Int = 1) {
            repeat(n) {
                if (i < source.length && source[i] == '\n') line++
                i++
            }
        }
        fun string(raw: Boolean) {
            val start = line
            val text = StringBuilder()
            while (i < source.length) {
                if (raw && source.startsWith("\"\"\"", i)) {
                    advance(3)
                    break
                }
                val c = source[i]
                if (!raw && c == '"') {
                    advance()
                    break
                }
                if (!raw && c == '\\') {
                    text.append(' ')
                    advance(2)
                    continue
                }
                if (c == '$' && i + 1 < source.length && source[i + 1] == '{') {
                    advance(2)
                    var depth = 1
                    while (i < source.length && depth > 0) {
                        when (source[i]) {
                            '{' -> depth++
                            '}' -> depth--
                            '"' -> {
                                advance()
                                string(raw = false)
                                continue
                            }
                        }
                        advance()
                    }
                    continue
                }
                if (c == '$' && i + 1 < source.length && (source[i + 1].isLetter() || source[i + 1] == '_')) {
                    advance()
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) advance()
                    continue
                }
                text.append(c)
                advance()
            }
            out += start to text.toString()
        }
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> while (i < source.length && source[i] != '\n') advance()
                source.startsWith("/*", i) -> {
                    advance(2)
                    while (i < source.length && !source.startsWith("*/", i)) advance()
                    advance(2)
                }
                source.startsWith("\"\"\"", i) -> {
                    advance(3)
                    string(raw = true)
                }
                source[i] == '"' -> {
                    advance()
                    string(raw = false)
                }
                source[i] == '\'' -> {
                    // A character literal: '"' must not open a string.
                    advance()
                    if (i < source.length && source[i] == '\\') advance()
                    advance(2)
                }
                else -> advance()
            }
        }
        return out
    }
}
