package com.spekterm.android.desktop

import com.spekterm.android.net.Skill

/** Finding a skill in the sheet (`agent-usage-and-skills` D4). */
object SkillFilter {
    /** Case-insensitive, in the name or the description; a blank query matches every skill. */
    fun matches(skill: Skill, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || skill.name.contains(q, ignoreCase = true) || skill.description?.contains(q, ignoreCase = true) == true
    }

    /** By name, ignoring case: the desktop's order is the agent's listing order, which mixes sources. */
    fun sorted(skills: List<Skill>): List<Skill> = skills.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Skill::name))

    /**
     * The skills matching [query], those whose name starts with it (ignoring case) first, each group by name
     * (`tool-call-display` D10). An empty query lists all by name.
     */
    fun ranked(skills: List<Skill>, query: String): List<Skill> {
        val (first, rest) = sorted(skills.filter { matches(it, query) }).partition { it.name.startsWith(query.trim(), ignoreCase = true) }
        return first + rest
    }
}

/** The command name being typed at the start of the reply field (`tool-call-display` D10). */
object SlashQuery {
    /** The end of the field's first word: the first white space as the agent CLI counts it, or the end. */
    private fun wordEnd(text: String): Int = text.indexOfFirst(Replies::jsSpace).let { if (it < 0) text.length else it }

    /** Whether the first word is a command name: a slash and no other slash. */
    private fun commandWord(text: String, end: Int): Boolean = text.startsWith("/") && text.indexOf('/', 1).let { it < 0 || it >= end }

    /**
     * The text between the slash and the cursor while the cursor is in a command word at the start of the field, or
     * `null`: a selection that is not collapsed, a cursor outside the word, a word that is a path, a field not
     * starting with `/` (leading white space included).
     */
    fun query(text: String, selectionStart: Int, selectionEnd: Int = selectionStart): String? {
        if (selectionStart != selectionEnd) return null
        val c = selectionStart
        val end = wordEnd(text)
        if (!commandWord(text, end) || c < 1 || c > end) return null
        return text.substring(1, c)
    }
}

/** Putting a chosen skill at the start of the reply field (`tool-call-display` D10). */
object SkillInsert {
    /** The field's new text, and where the cursor goes: after the name and its separator. */
    data class Inserted(val text: String, val cursor: Int)

    /**
     * Choosing [name] while [cursor] is in the command word at the start of [text] (`tool-call-display` D10): right
     * after a bare slash typed before other text, that text is kept as the arguments; anywhere else in the word, the
     * whole word is replaced and the rest, its separator first, kept. The cursor goes after the separator.
     */
    fun atCursor(text: String, cursor: Int, name: String): Inserted {
        val command = "/$name"
        val end = text.indexOfFirst(Replies::jsSpace).let { if (it < 0) text.length else it }
        val rest = when {
            cursor == 1 && end > 1 -> " " + text.substring(1)
            end == text.length -> " "
            else -> text.substring(end)
        }
        return Inserted(command + rest, command.length + 1)
    }
}
