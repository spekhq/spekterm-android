package com.spekterm.android.desktop

import com.spekterm.android.ui.UiText
import com.spekterm.android.R
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList

/** How the desktop's sessions relate by handoff (`session-relations`, design D1–D2). */
object Relations {
    /** Indentation stops growing here, as on the desktop's rail. */
    const val MAX_DEPTH = 3

    /** A row of a group's tree: [depth] for drawing (at most [MAX_DEPTH]); [nested] when shown under its source. */
    data class Placed(val id: String, val depth: Int, val nested: Boolean)

    /**
     * The ids that exist for relations (D1): in [list] — the one the phone holds, current or not — and in the global
     * item or a folder the list names. A dormant session exists; one of a folder no longer in the workspace does not.
     */
    fun existing(list: SessionList): Set<String> {
        val folders = list.folders.map { it.id }.toSet()
        return list.sessions.filter { it.folderId == null || it.folderId in folders }.map { it.id }.toSet()
    }

    /**
     * A group's tree (D1, the rail's `buildForest`): a session sits under its source when the source is in [present]
     * and is not itself; a session whose chain of sources loops is a root; siblings keep [ordered]'s order; depth first.
     */
    fun tree(ordered: List<Session>, present: Set<String>): List<Placed> {
        val ids = ordered.map { it.id }.toSet()
        val declared = HashMap<String, String>()
        for (s in ordered) {
            val parent = s.lineage?.parentId ?: continue
            if (parent != s.id && parent in ids && parent in present) declared[s.id] = parent
        }
        val parentOf = HashMap<String, String>()
        for (s in ordered) {
            val seen = hashSetOf(s.id)
            var cursor = declared[s.id]
            var cyclic = false
            while (cursor != null) {
                if (!seen.add(cursor)) {
                    cyclic = true
                    break
                }
                cursor = declared[cursor]
            }
            if (!cyclic) declared[s.id]?.let { parentOf[s.id] = it }
        }
        val childrenOf = LinkedHashMap<String?, MutableList<String>>()
        for (s in ordered) childrenOf.getOrPut(parentOf[s.id]) { mutableListOf() }.add(s.id)
        val out = ArrayList<Placed>(ordered.size)
        fun visit(id: String, depth: Int) {
            out += Placed(id, minOf(depth, MAX_DEPTH), depth > 0)
            childrenOf[id]?.forEach { visit(it, depth + 1) }
        }
        childrenOf[null]?.forEach { visit(it, 0) }
        return out
    }

    /** The existing sessions handed off from [session], in the list's order, in any group (D2). */
    fun children(session: Session, list: SessionList, existing: Set<String> = existing(list)): List<Session> =
        if (session.id !in existing) emptyList() else list.sessions.filter { it.id in existing && it.id != session.id && it.lineage?.parentId == session.id }

    /** The existing source of [session], or `null` when it has none or it is gone. */
    fun source(session: Session, list: SessionList, existing: Set<String> = existing(list)): Session? {
        val parentId = session.lineage?.parentId ?: return null
        if (parentId == session.id || parentId !in existing) return null
        return list.sessions.firstOrNull { it.id == parentId }
    }

    /** Where [session] came from, wherever it sits (D2), in the desktop's words; `null` without a lineage. */
    fun sourceText(session: Session, list: SessionList, existing: Set<String> = existing(list)): UiText? {
        val lineage = session.lineage ?: return null
        source(session, list, existing)?.let { return UiText.Res(R.string.relations_from, groupName(it, list), label(it)) }
        val title = lineage.title?.takeIf { it.isNotBlank() }
        return if (title != null) UiText.Res(R.string.relations_from_closed, title) else UiText.Res(R.string.relations_from_gone)
    }

    /** A handoff state in words (D2); `null` for `idle`, an unknown value or none. */
    fun stateText(lifecycle: String?): UiText? = when (lifecycle) {
        "working" -> UiText.Res(R.string.relations_state_working)
        "waiting" -> UiText.Res(R.string.relations_state_waiting)
        "done" -> UiText.Res(R.string.relations_state_done)
        else -> null
    }

    /** The row's handoff line (D2): the source when a root, "Handoff" when nested, then the state; `null` without a lineage. */
    fun line(session: Session, nested: Boolean, list: SessionList, existing: Set<String> = existing(list)): UiText? {
        if (session.lineage == null) return null
        val head = if (nested) UiText.Res(R.string.relations_handoff) else sourceText(session, list, existing)
        return UiText.Joined(listOfNotNull(head, stateText(session.lifecycle)))
    }

    /** The row's children line (D2), for an existing session with existing children; else `null`. */
    fun childrenText(session: Session, list: SessionList, existing: Set<String> = existing(list)): UiText? {
        val children = children(session, list, existing)
        if (children.isEmpty()) return null
        val n = children.size
        val done = children.count { it.lifecycle == "done" }
        val head = UiText.Plural(R.plurals.relations_handed_off, n)
        return when {
            done == 0 -> head
            done == n -> UiText.Joined(listOf(head, UiText.Res(R.string.relations_all_done)))
            else -> UiText.Joined(listOf(head, UiText.Res(R.string.relations_done_of, done, n)))
        }
    }

    /** Whether [session] has a relation worth a "Handoff" item and screen (D5). */
    fun related(session: Session, list: SessionList?, existing: Set<String>? = null): Boolean {
        if (session.lineage != null) return true
        list ?: return false
        return children(session, list, existing ?: existing(list)).isNotEmpty()
    }

    fun groupName(session: Session, list: SessionList): UiText =
        if (session.folderId == null) {
            UiText.Res(R.string.sessions_global)
        } else {
            UiText.Raw(list.folders.firstOrNull { it.id == session.folderId }?.name?.ifEmpty { null } ?: session.folderId)
        }

    fun label(session: Session): String = session.label.ifEmpty { session.id }
}
