package com.spekterm.android.desktop

import com.spekterm.android.net.HandoffEntry
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import com.spekterm.android.net.WaitState

/** The last session list, and whether it came from the connection attached now (design D1). */
data class SessionsState(
    val list: SessionList? = null,
    /** From the currently attached connection. False once it ends: the list is then the last one received. */
    val current: Boolean = false,
    val receivedAt: Long? = null,
)

/**
 * The desktop's handoff list by session id (`session-relations` D3), whether it came from the attached connection,
 * and the stamp of the last one applied there. Only `summary` / `reportedAt` are read from it.
 */
data class HandoffsState(val entries: Map<String, HandoffEntry> = emptyMap(), val current: Boolean = false, val seq: Long = 0) {
    /** [list] stamped [seq] applied, or `null` when an equal or newer one was already applied on this connection. */
    fun applied(seq: Long, list: List<HandoffEntry>): HandoffsState? =
        if (seq <= this.seq) null else HandoffsState(list.associateBy { it.sessionId }, current = true, seq = seq)

    /** A new connection: its stamps start again; the entries stay until its first list. */
    fun attached(): HandoffsState = copy(current = false, seq = 0)

    fun detached(): HandoffsState = copy(current = false)
}

/** Applies session lists in arrival order within one connection (design D2). */
class ListClock {
    private var last = 0L

    /** Whether a list stamped [seq] is newer than the last applied one; if so, it becomes the last. */
    fun accept(seq: Long): Boolean {
        if (seq <= last) return false
        last = seq
        return true
    }

    /** A new connection: its stamps start again. */
    fun reset() {
        last = 0L
    }
}

/** How the session list shows a desktop session (design D8). */
object SessionsView {
    enum class RowState { DORMANT, READY, BUSY, CHOICE, NONE }

    /**
     * [offered]: its agent asked to close it (`close-offer`). [depth] and [nested]: its place in the group's handoff
     * tree (`session-relations` D1).
     */
    data class Row(val session: Session, val state: RowState, val offered: Boolean = false, val depth: Int = 0, val nested: Boolean = false)

    /** How many sessions of a group need the user (design D1), each counted once (`close-offer` D5). */
    data class Summary(val choice: Int, val ready: Int, val offers: Int = 0)

    /**
     * [folderId] null with [global] true is the global item; null with [global] false holds unknown folders.
     * [key] identifies the group for the list and for collapsing (design D1).
     */
    data class Group(
        val folderId: String?,
        val name: String?,
        val global: Boolean,
        val rows: List<Row>,
        val branch: String? = null,
        /** The desktop marks the folder as having OpenSpec (`openspec-browse`). */
        val hasOpenSpec: Boolean = false,
    ) {
        val key: String = when {
            global -> GLOBAL
            folderId != null -> "folder:$folderId"
            else -> OTHER
        }
        val summary: Summary = Summary(
            choice = rows.count { it.state == RowState.CHOICE },
            ready = rows.count { it.state == RowState.READY && !it.offered },
            offers = rows.count { it.offered && it.state != RowState.CHOICE },
        )
    }

    /** Where a new session can be started: [folderId] null is the global item (design D5). */
    data class Choice(val folderId: String?, val name: String, val branch: String?)

    const val GLOBAL = "global"
    const val OTHER = "other"

    /** The global item, then every folder of the list in its order, with or without sessions (design D5). */
    fun choices(list: SessionList): List<Choice> =
        listOf(Choice(null, "Global", null)) + list.folders.map { Choice(it.id, it.name.ifEmpty { it.id }, it.branch) }

    /**
     * Whether a new session can be started now (design D5): connected, not paused, the shown list current with
     * a window, and no start in progress.
     */
    fun canCreate(connected: Boolean, paused: Boolean, sessions: SessionsState, creation: Creation?): Boolean =
        connected && !paused && sessions.current && sessions.list?.window == true && creation !is Creation.Starting

    fun state(session: Session): RowState = when {
        !session.running -> RowState.DORMANT
        !session.isAgent -> RowState.NONE
        else -> when (WaitState.of(session.wait)) {
            WaitState.READY -> RowState.READY
            WaitState.BUSY -> RowState.BUSY
            WaitState.AWAITING_CHOICE -> RowState.CHOICE
            else -> RowState.NONE
        }
    }

    /**
     * The global item first (pinned on the desktop's rail), then folders in the desktop's order, each with its
     * sessions in the desktop's order arranged as a handoff tree (`session-relations` D1); folders without sessions
     * are left out, and sessions of a folder the list does not name come last, with no tree.
     */
    fun groups(list: SessionList): List<Group> {
        val known = list.folders.map { it.id }.toSet()
        fun rows(sessions: List<Session>, tree: Boolean): List<Row> {
            val byId = sessions.associateBy { it.id }
            val placed = Relations.tree(sessions, if (tree) byId.keys else emptySet())
            return placed.map { p -> byId.getValue(p.id).let { Row(it, state(it), SessionActions.offered(it), p.depth, p.nested) } }
        }
        return buildList {
            list.sessions.filter { it.folderId == null }.takeIf { it.isNotEmpty() }?.let { add(Group(null, null, true, rows(it, true))) }
            for (folder in list.folders) {
                list.sessions.filter { it.folderId == folder.id }.takeIf { it.isNotEmpty() }
                    ?.let { add(Group(folder.id, folder.name, false, rows(it, true), folder.branch, folder.hasOpenSpec)) }
            }
            list.sessions.filter { it.folderId != null && it.folderId !in known }.takeIf { it.isNotEmpty() }
                ?.let { add(Group(null, null, false, rows(it, false))) }
        }
    }
}

/** What the conversation screen shows first (design D5), in precedence order. */
sealed interface Screen {
    data object Paused : Screen

    data object NotConnected : Screen

    data object NoWindow : Screen

    data object Closed : Screen

    data object Shell : Screen

    data object Dormant : Screen

    data object Content : Screen

    companion object {
        fun of(connected: Boolean, paused: Boolean, sessions: SessionsState, conversation: Conversation?, sessionId: String): Screen {
            if (paused) return Paused
            if (!connected) return NotConnected
            val list = sessions.list?.takeIf { sessions.current }
            if (list?.window == false || conversation?.phase == Phase.WINDOW_GONE) return NoWindow
            val session = list?.sessions?.firstOrNull { it.id == sessionId }
            // Closed by the desktop's word (design D10), or missing from a current list with a window.
            if (conversation?.closed == true || (list != null && session == null)) return Closed
            if (session != null && !session.isAgent) return Shell
            if (session?.running == false || conversation?.notRunning == true) return Dormant
            return Content
        }
    }
}
