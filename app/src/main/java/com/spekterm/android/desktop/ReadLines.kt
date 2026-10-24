package com.spekterm.android.desktop

/**
 * A Read's result as the CLI records it (`tool-result-content` D4): the file's lines with their numbers, and the CLI's
 * notes to the model around them. Pure, so the measured shapes are tested on the JVM.
 */
object ReadLines {
    sealed interface Parsed {
        /** Not the numbered shape: shown as it came. */
        data class Raw(val text: String) : Parsed

        /**
         * The lines from [first], numbers taken off; [above] a leading note (a memory file's age), [below] a trailing
         * one (an artifact's source). [twoColumns]: within Compose's text bounds for the gutter view.
         */
        data class Numbered(val first: Long, val lines: List<String>, val above: String?, val below: String?, val twoColumns: Boolean) : Parsed
    }

    /** Wider than this, or longer, the numbered lines are shown wrapped (design D4). */
    const val MAX_COLUMN_CHARS = 400
    const val MAX_COLUMN_LINES = 1500

    private const val OPEN = "<system-reminder>"
    private const val CLOSE = "</system-reminder>"
    private val LINE = Regex("""^\s*(\d+)[\t→](.*)$""")

    fun parse(text: String): Parsed {
        var rest = text
        val notes = mutableListOf<String>()
        while (rest.startsWith(OPEN)) {
            val end = rest.indexOf(CLOSE)
            if (end < 0) break
            notes += rest.substring(OPEN.length, end).trim()
            rest = rest.substring(end + CLOSE.length).trimStart('\n', '\r')
        }
        val lines = rest.split('\n').toMutableList()
        if (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        var below: String? = null
        if (lines.size >= 3 && lines[lines.lastIndex - 1].isEmpty() && lines.last().let { it.startsWith("[") && it.endsWith("]") }) {
            below = lines.last().removeSurrounding("[", "]")
            lines.subList(lines.size - 2, lines.size).clear()
        }
        if (lines.isEmpty() || lines.size == 1 && lines[0].isEmpty()) return Parsed.Raw(text)
        var first = -1L
        val out = ArrayList<String>(lines.size)
        var longest = 0
        for ((i, line) in lines.withIndex()) {
            val m = LINE.matchEntire(line) ?: return Parsed.Raw(text)
            val n = m.groupValues[1].toLongOrNull() ?: return Parsed.Raw(text)
            if (i == 0) first = n else if (n != first + i) return Parsed.Raw(text)
            val content = m.groupValues[2]
            if (content.length > longest) longest = content.length
            out += content
        }
        val above = notes.joinToString("\n\n").ifEmpty { null }
        return Parsed.Numbered(first, out, above, below, longest <= MAX_COLUMN_CHARS && out.size <= MAX_COLUMN_LINES)
    }
}
