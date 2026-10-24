package com.spekterm.android.desktop

/**
 * A received peer message without the agent CLI's wrapper (`tool-call-display` D7). The desktop sends a session's
 * message unwrapped; a subagent's report (and the rare session message without a body) comes as the CLI recorded it:
 * a lead line, an `<agent-message from=…>` or `<cross-session-message from=…>` tag holding the sender's internal id or
 * socket path, a hand-back preamble with the report indented two spaces, and an instruction paragraph after the
 * closing tag. Only these known shapes are removed; text that does not match is returned as it is.
 */
object PeerText {
    private const val LEAD = "Another Claude session sent a message:"
    private val OPEN = Regex("^<(cross-session-message|agent-message)(\\s[^>]*)?>$")
    private const val HANDBACK = "[Subagent hand-back]"
    private const val FOLLOWS = "The report follows:"
    private val TRAILERS = listOf("That \"other Claude session\"", "This came from another Claude session")

    fun strip(text: String): String {
        val lines = text.split("\n").toMutableList()
        var i = 0
        if (lines.getOrNull(i)?.trim() == LEAD) i++
        val open = lines.getOrNull(i)?.trim()?.let(OPEN::matchEntire) ?: return text
        val close = "</${open.groupValues[1]}>"
        val end = (lines.size - 1 downTo i + 1).firstOrNull { lines[it].trim() == close } ?: return text
        val after = lines.subList(end + 1, lines.size).joinToString("\n").trim()
        if (after.isNotEmpty() && TRAILERS.none { after.startsWith(it) }) return text
        var body = lines.subList(i + 1, end).toList()
        if (body.firstOrNull()?.startsWith(HANDBACK) == true) {
            val follows = body.indexOfFirst { it.trimEnd().endsWith(FOLLOWS) }
            if (follows < 0) return text
            body = body.drop(follows + 1).map { if (it.startsWith("  ")) it.substring(2) else it.trimStart(' ') }
            if (body.firstOrNull()?.startsWith("[harness:") == true) body = body.drop(1)
        }
        return body.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
    }
}
