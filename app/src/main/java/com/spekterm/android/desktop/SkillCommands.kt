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
}

/** Putting a chosen skill at the start of the reply field (D4). */
object SkillInsert {
    /** The field's new text, and where the cursor goes: after the name and its separator. */
    data class Inserted(val text: String, val cursor: Int)

    fun insert(draft: String, name: String): Inserted {
        val command = "/$name"
        val d = draft.dropWhile(Replies::jsSpace)
        val end = d.indexOfFirst(Replies::jsSpace).let { if (it < 0) d.length else it }
        val first = d.substring(0, end)
        val text = when {
            d.isEmpty() -> "$command "
            // A command name or a bare slash — one slash, no other, so a path typed first is kept.
            first.startsWith("/") && first.indexOf('/', 1) < 0 -> if (end == d.length) "$command " else command + d.substring(end)
            else -> "$command $d"
        }
        return Inserted(text, command.length + 1)
    }
}
