package com.spekterm.android.desktop

import com.spekterm.android.net.ConversationUpdate
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Pending
import com.spekterm.android.net.SessionList
import com.spekterm.android.net.WaitState

/** Where the watch of the focused session stands (design D3). */
sealed interface Watch {
    data object Idle : Watch

    /** Sent, not answered yet. Counts as watched: a list arriving meanwhile never sends a second watch. */
    data object Requested : Watch

    data object Watching : Watch

    /** Refused with [code]; not retried until the focus changes, a new connection, or the session starts running. */
    data class Refused(val code: String) : Watch

    val active: Boolean get() = this == Requested || this == Watching
}

/** What the conversation screen can say about its content (design D4). */
enum class Phase {
    /** Not watched: never yet, or left. */
    IDLE,

    /** The connection ended; entries kept. */
    DISCONNECTED,

    /** The desktop's window went away (`conversation.ended`), until a list with a window arrives. */
    WINDOW_GONE,

    /** Watched, no update yet. */
    CATCHING_UP,
    OK,
    UNAVAILABLE,
}

/**
 * The conversation of the session of interest, held in memory only (design D4). [notRunning] records a watch
 * answered `NOT_FOUND`, until a list shows the session running.
 */
data class Conversation(
    val sessionId: String,
    val entries: List<Entry> = emptyList(),
    val phase: Phase = Phase.IDLE,
    val truncated: Boolean = false,
    val wait: String = WaitState.UNKNOWN,
    val pending: Pending? = null,
    val watch: Watch = Watch.Idle,
    val notRunning: Boolean = false,
    /** Whether the last list of the current connection showed the session running; `null` before one. */
    val listedRunning: Boolean? = null,
    val draft: String = "",
    /** Bumped whenever a refused reply's text is put back into [draft], so the composer takes it. */
    val draftReturns: Int = 0,
    val replies: List<Reply> = emptyList(),
) {
    /** The uuid to resume from: the last held entry's, or none when nothing is held. */
    val afterUuid: String? get() = entries.lastOrNull()?.uuid?.takeIf { it.isNotEmpty() }
}

/** Pure transitions of [Conversation] (design D4). */
object ConversationReducer {
    const val CAP = 2_000

    /**
     * Applies one `conversation.update`. A replacing update replaces and brings its `truncated`; an appending one
     * drops entries whose uuid is already held — a record's entries arrive in one update, so a held uuid means a
     * re-delivery (a previous follow's update overlapping a resume) — and keeps `truncated`, which appending
     * updates do not carry meaningfully.
     */
    fun apply(c: Conversation, update: ConversationUpdate, now: Long): Conversation {
        val phase = when (update.status) {
            ConversationUpdate.OK -> Phase.OK
            ConversationUpdate.UNAVAILABLE -> Phase.UNAVAILABLE
            else -> Phase.CATCHING_UP
        }
        var truncated: Boolean
        var entries: List<Entry>
        if (update.reset) {
            entries = update.events
            truncated = update.truncated
        } else {
            val held = c.entries.mapNotNullTo(HashSet()) { it.uuid.takeIf(String::isNotEmpty) }
            entries = c.entries + update.events.filter { it.uuid.isEmpty() || it.uuid !in held }
            truncated = c.truncated
        }
        if (entries.size > CAP) {
            entries = entries.takeLast(CAP)
            truncated = true
        }
        val next = c.copy(entries = entries, truncated = truncated, phase = phase)
        return Replies.confirm(next, update.events, now)
    }

    /** List keys: the uuid plus the entry's index among held entries sharing it. */
    fun keys(entries: List<Entry>): List<String> {
        val seen = HashMap<String, Int>()
        return entries.map { entry ->
            val n = seen.merge(entry.uuid, 1, Int::plus)!! - 1
            "${entry.uuid}#$n"
        }
    }

    /** A watch is being sent: catching up, and nothing known about the wait state yet. */
    fun requested(c: Conversation) = c.copy(watch = Watch.Requested, phase = Phase.CATCHING_UP, wait = WaitState.UNKNOWN, pending = null, notRunning = false)

    fun answered(c: Conversation, errorCode: String?): Conversation = when (errorCode) {
        null -> c.copy(watch = Watch.Watching)
        // Not running: dormant until a list shows it running (design D7).
        com.spekterm.android.net.RemoteError.NOT_FOUND -> c.copy(watch = Watch.Idle, notRunning = true, phase = Phase.IDLE)
        else -> c.copy(watch = Watch.Refused(errorCode), phase = Phase.IDLE)
    }

    fun unwatched(c: Conversation) = c.copy(watch = Watch.Idle, phase = if (c.phase == Phase.WINDOW_GONE) c.phase else Phase.IDLE, wait = WaitState.UNKNOWN, pending = null)

    fun disconnected(c: Conversation) = Replies.disconnected(
        c.copy(
            watch = Watch.Idle,
            listedRunning = null,
            notRunning = false,
            phase = Phase.DISCONNECTED,
            wait = WaitState.UNKNOWN,
            pending = null,
        ),
    )

    fun ended(c: Conversation) = c.copy(watch = Watch.Idle, phase = Phase.WINDOW_GONE, wait = WaitState.UNKNOWN, pending = null)

    /**
     * The desktop reports one prompt more than once, and only its first report names the tool (a later
     * `Notification` for the same prompt folds to no request), so a report without a tool keeps the one held
     * while the agent still asks for a choice.
     */
    fun waited(c: Conversation, state: String, pending: Pending?): Conversation {
        val wait = WaitState.of(state)
        val asked = when {
            wait != WaitState.AWAITING_CHOICE -> null
            pending?.tool.isNullOrBlank() && c.wait == WaitState.AWAITING_CHOICE && c.pending != null -> c.pending
            else -> pending
        }
        return Replies.waited(c.copy(wait = wait, pending = asked), wait)
    }

    /**
     * A session list arrived on the current connection. A list with a window clears "window gone". The session
     * turning from not running to running clears a `NOT_FOUND` and a refusal, so the watch is tried again — only
     * on that turn, so a refusal is not retried on every list (any agent's wait change sends one).
     */
    fun listed(c: Conversation, list: SessionList): Conversation {
        if (!list.window) return c
        val running = list.sessions.firstOrNull { it.id == c.sessionId }?.running ?: false
        var next = c.copy(listedRunning = running)
        if (next.phase == Phase.WINDOW_GONE) next = next.copy(phase = Phase.IDLE)
        if (running && c.listedRunning == false) next = cleared(next)
        return next
    }

    /** Clears what keeps the watch from being tried again: after a successful wake, or the session starting. */
    fun cleared(c: Conversation) = c.copy(notRunning = false, watch = if (c.watch is Watch.Refused) Watch.Idle else c.watch)
}
