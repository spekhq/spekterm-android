package com.spekterm.android.desktop

import com.spekterm.android.net.InboxItem
import com.spekterm.android.net.Operations
import com.spekterm.android.net.Prefill
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList

/**
 * The desktop's inbox as last received (`inbox` design D2): replaced whole by every list of the attached connection,
 * stamped so an older one is dropped; kept across a detach, emptied by [Desktop.clear]. In memory only.
 */
data class InboxState(val items: List<InboxItem> = emptyList(), val current: Boolean = false, val seq: Long = 0, val receivedAt: Long? = null) {
    /** [items] stamped [seq] applied at [at], or `null` when an equal or newer list was already applied on this connection. */
    fun applied(seq: Long, items: List<InboxItem>, at: Long): InboxState? =
        if (seq <= this.seq) null else InboxState(items, current = true, seq = seq, receivedAt = at)

    /** A new connection: its stamps start again; the items stay until its first list. */
    fun attached(): InboxState = copy(current = false, seq = 0)

    fun detached(): InboxState = copy(current = false)
}

/** The inbox operations a card sends (`inbox` design D6, D8), as the log names them. */
enum class InboxOp(val wire: String) {
    ACCEPT("accept"),
    IGNORE("ignore"),
    SUBMIT("submit"),
}

/** Why an inbox action failed, in the user's words (`inbox` design D6, D8). */
data class InboxFailure(val reason: Reason, val message: String? = null, val code: String? = null) {
    enum class Reason {
        CHOOSE_FOLDER, COULD_NOT_OPEN, NO_LONGER_IN_INBOX, NO_WINDOW, TIMEOUT, NOT_CONNECTED,
        SESSION_NOT_RUNNING, PROMPT_GONE, PROMPT_EDITED, AGENT_NOT_READY, FAILED,
    }

    companion object {
        /**
         * The failure for [error] answering [op]; `null` [error] means no answer came (the connection ended).
         * [itemStillHeld]: the item is still in the held inbox. The desktop's message is kept where it says which cause
         * a shared code stands for (it is fixed English; shown as sent).
         */
        fun of(op: InboxOp, error: RemoteError?, itemStillHeld: Boolean): InboxFailure {
            if (error == null) return InboxFailure(Reason.NOT_CONNECTED)
            val message = error.message.takeIf { it.isNotBlank() }
            return when (error.code) {
                RemoteError.FOLDER_REQUIRED -> InboxFailure(Reason.CHOOSE_FOLDER)
                RemoteError.NOT_FOUND -> when {
                    op == InboxOp.SUBMIT -> InboxFailure(Reason.SESSION_NOT_RUNNING)
                    // An accept's NOT_FOUND also stands for a chosen folder that went away.
                    op == InboxOp.ACCEPT && itemStillHeld -> InboxFailure(Reason.COULD_NOT_OPEN, message)
                    else -> InboxFailure(Reason.NO_LONGER_IN_INBOX)
                }
                RemoteError.NO_WINDOW -> InboxFailure(Reason.NO_WINDOW)
                RemoteError.TIMEOUT -> InboxFailure(Reason.TIMEOUT)
                RemoteError.CREATE_FAILED -> InboxFailure(Reason.COULD_NOT_OPEN, message)
                RemoteError.PREFILL_GONE -> InboxFailure(Reason.PROMPT_GONE)
                RemoteError.PREFILL_EDITED -> InboxFailure(Reason.PROMPT_EDITED)
                RemoteError.WAIT_STATE -> InboxFailure(Reason.AGENT_NOT_READY, error.detailString("state"))
                else -> InboxFailure(Reason.FAILED, code = error.code)
            }
        }
    }
}

/** An inbox action's fields for the connection log: the operation, its outcome and error code — never item text. */
object InboxLog {
    fun fields(op: InboxOp, outcome: String, code: String?): Array<Pair<String, Any?>> =
        arrayOf("op" to op.wire, "outcome" to outcome, "error" to code)
}

/** An action on one item, held by its key until the next action on it (`inbox` design D5, D6). */
sealed interface ItemAction {
    data class InFlight(val op: InboxOp) : ItemAction

    data class Failed(val op: InboxOp, val failure: InboxFailure) : ItemAction

    /** Accepted as [sessionId]; waiting for a current list to hold it. */
    data class Opening(val sessionId: String, val id: Long, val connection: Any) : ItemAction

    /** Listed; [autoOpen] while the Inbox was at the front when it was listed and its conversation has not opened yet. */
    data class Opened(val sessionId: String, val autoOpen: Boolean) : ItemAction

    /** Accepted, but not listed in time, or the connection ended first. */
    data class Unlisted(val sessionId: String, val connectionEnded: Boolean) : ItemAction

    /** A filled-in prompt was sent: [confirmed] when the agent was seen starting. */
    data class Submitted(val confirmed: Boolean) : ItemAction
}

/**
 * Decides when an accepted item's conversation opens (`inbox` design D6): only once a current list with a window holds
 * the session, running or dormant — the conversation screen reads a session the list lacks as closed. Pure; [Desktop]
 * feeds it on its dispatcher. Connections are compared by identity.
 */
object OpeningTracker {
    /** The desktop answered accept [id] of [key] with [sessionId]. [list] is the current list of [attached], if any. */
    fun answered(
        actions: Map<String, ItemAction>,
        key: String,
        id: Long,
        sessionId: String,
        connection: Any,
        attached: Any?,
        list: SessionList?,
        inboxOnScreen: Boolean,
    ): Map<String, ItemAction> = actions + (key to when {
        attached !== connection -> ItemAction.Unlisted(sessionId, connectionEnded = true)
        list != null && holds(list, sessionId) -> ItemAction.Opened(sessionId, inboxOnScreen)
        else -> ItemAction.Opening(sessionId, id, connection)
    })

    /** A current list arrived on [connection]. */
    fun listed(actions: Map<String, ItemAction>, list: SessionList, connection: Any, inboxOnScreen: Boolean): Map<String, ItemAction> =
        actions.mapValues { (_, action) ->
            if (action is ItemAction.Opening && action.connection === connection && holds(list, action.sessionId)) {
                ItemAction.Opened(action.sessionId, inboxOnScreen)
            } else {
                action
            }
        }

    /** [connection] detached. */
    fun detached(actions: Map<String, ItemAction>, connection: Any): Map<String, ItemAction> =
        actions.mapValues { (_, action) ->
            if (action is ItemAction.Opening && action.connection === connection) ItemAction.Unlisted(action.sessionId, connectionEnded = true) else action
        }

    /** Accept [id]'s timer fired; ignored unless that accept of [key] is still waiting. */
    fun timedOut(actions: Map<String, ItemAction>, key: String, id: Long): Map<String, ItemAction> {
        val waiting = actions[key] as? ItemAction.Opening ?: return actions
        if (waiting.id != id) return actions
        return actions + (key to ItemAction.Unlisted(waiting.sessionId, connectionEnded = false))
    }

    /** The Inbox left the front: an `Opened` not acted on must not navigate on return; it becomes a link. */
    fun leftInbox(actions: Map<String, ItemAction>): Map<String, ItemAction> =
        actions.mapValues { (_, action) -> if (action is ItemAction.Opened && action.autoOpen) action.copy(autoOpen = false) else action }

    private fun holds(list: SessionList, sessionId: String): Boolean = list.window && list.sessions.any { it.id == sessionId }
}

/** How the Inbox shows the held items (`inbox` design D4). Pure. */
object InboxView {
    /** Pending handoffs waiting for their time, earliest first. */
    fun scheduled(items: List<InboxItem>): List<InboxItem> =
        items.filter { it.state == InboxItem.State.PENDING && it.scheduledAt != null }.sortedBy { it.scheduledAt }

    /** Pending items waiting for the user, in the desktop's order: what the entry counts. */
    fun waiting(items: List<InboxItem>): List<InboxItem> =
        items.filter { it.state == InboxItem.State.PENDING && it.scheduledAt == null }

    /**
     * Accepted items whose session the current list holds in one of its folders (the desktop's rule); with no current
     * list or no window, every accepted item with a session, as last known.
     */
    fun opened(items: List<InboxItem>, sessions: SessionsState): List<InboxItem> {
        val accepted = items.filter { it.state == InboxItem.State.ACCEPTED && it.sessionId != null }
        val list = sessions.list?.takeIf { sessions.current && it.window } ?: return accepted
        val folders = list.folders.map { it.id }.toSet()
        return accepted.filter { item -> list.sessions.any { it.id == item.sessionId && it.folderId in folders } }
    }

    fun expired(item: InboxItem): Boolean = item.receivedAt == 0L && item.bodyLength == 0L

    /** Whether the title gets its own line: not when the body already holds it (a trailing `…` stripped first). */
    fun showTitle(title: String, body: String): Boolean {
        val core = title.removeSuffix("…")
        if (core.isBlank()) return false
        return !body.contains(core)
    }

    /** The folder selected for a waiting item; [overrideGone] when the user's pick is no longer a folder. */
    data class Preselection(val folderId: String?, val overrideGone: Boolean = false)

    /**
     * The desktop's preselection: the user's pick on this screen (none if it is gone — never a fall-back), else the folder
     * of the session an earlier accept left, else the desktop's suggestion; each only when it is one of [list]'s folders.
     */
    fun preselect(override: String?, item: InboxItem, list: SessionList?): Preselection {
        val folders = list?.folders?.map { it.id }?.toSet().orEmpty()
        if (override != null) return if (override in folders) Preselection(override) else Preselection(null, overrideGone = true)
        val existing = item.sessionId?.let { id -> list?.sessions?.firstOrNull { it.id == id }?.folderId }
        if (existing != null && existing in folders) return Preselection(existing)
        if (item.folderId != null && item.folderId in folders) return Preselection(item.folderId)
        return Preselection(null)
    }

    enum class NoFolder { CHOSEN_GONE, RULE_FOLDER_GONE, CANNOT_TELL }

    /** Why no folder is selected for [item]; `null` when one is, or the item expired (it says so itself). */
    fun noFolderReason(item: InboxItem, selection: Preselection): NoFolder? = when {
        expired(item) || selection.folderId != null -> null
        selection.overrideGone -> NoFolder.CHOSEN_GONE
        item.unresolved == InboxItem.Unresolved.FOLDER_GONE || item.folderId != null -> NoFolder.RULE_FOLDER_GONE
        else -> NoFolder.CANNOT_TELL
    }

    /** A scheduled handoff runs now only into the folder the desktop names, when that is one of [list]'s folders. */
    fun runnable(item: InboxItem, list: SessionList?): Boolean =
        item.folderId != null && list?.folders?.any { it.id == item.folderId } == true

    /** Which inbox controls may be used now (obligation 8 and what the desktop needs; design D6). */
    data class Gates(val accept: Boolean, val ignore: Boolean, val send: Boolean)

    fun gates(operations: Set<String>, connected: Boolean, paused: Boolean, sessions: SessionsState): Gates {
        val live = connected && !paused
        val window = live && sessions.current && sessions.list?.window == true
        return Gates(
            accept = window && Operations.INBOX_ACCEPT in operations,
            ignore = live && Operations.INBOX_IGNORE in operations,
            send = window && Operations.SUBMIT_PREFILL in operations,
        )
    }

    /** What the conversation screen says about a first prompt from the inbox (design D7). */
    sealed interface Linked {
        /** The desktop lists the prompt; [item] when held, [shown] when the Inbox shows it under Already opened. */
        data class Listed(val prefill: Prefill, val item: InboxItem?, val shown: Boolean) : Linked

        /** An accepted item names the session and no prompt is listed; [canSend] on a desktop that has the send. */
        data class Waiting(val item: InboxItem, val canSend: Boolean) : Linked
    }

    /**
     * Whether [linked] holds free-text replies back (design D7): a listed prompt always (the desktop would refuse them);
     * a waiting one only on a desktop without the send — where a reply would be submitted with the prompt — until the user
     * chose to [replyAnyway].
     */
    fun holdsReplies(linked: Linked?, replyAnyway: Boolean): Boolean = when (linked) {
        null -> false
        is Linked.Listed -> true
        is Linked.Waiting -> !linked.canSend && !replyAnyway
    }

    /** The note for [session] (design D4, D7): for a running agent session only. */
    fun linked(session: Session?, items: List<InboxItem>, sessions: SessionsState, operations: Set<String>): Linked? {
        if (session == null || !session.running || !session.isAgent) return null
        session.prefill?.let { prefill ->
            val key = InboxItem.keyOf(prefill.intake.adapter, prefill.intake.id)
            val item = items.firstOrNull { it.key == key }
            val shown = opened(items, sessions).any { it.key == key && it.sessionId == session.id }
            return Linked.Listed(prefill, item, shown)
        }
        val item = items.firstOrNull { it.state == InboxItem.State.ACCEPTED && it.sessionId == session.id } ?: return null
        return Linked.Waiting(item, canSend = Operations.SUBMIT_PREFILL in operations)
    }
}

/** Where an inbox item's notification opens (`inbox` design D9). Pure. */
object IntakeTap {
    sealed interface Route {
        /** The Inbox, scrolled to [focus] when it is held there. */
        data class Inbox(val focus: String?) : Route

        data class Conversation(val sessionId: String) : Route

        data object Sessions : Route
    }

    /**
     * [key]'s item looked up in [inbox] (after waiting for a current one, which may not have come): pending → the Inbox at
     * it; accepted with a session the current list holds → its conversation; accepted otherwise → the session list (a
     * conversation never opens before its session is listed); not held → the session list when [inbox] is current (a
     * handoff opened and already started), else the Inbox, which says it is not current.
     */
    fun route(key: String, inbox: InboxState, sessions: SessionsState): Route {
        val item = inbox.items.firstOrNull { it.key == key } ?: return if (inbox.current) Route.Sessions else Route.Inbox(null)
        return when (item.state) {
            InboxItem.State.PENDING -> Route.Inbox(key)
            InboxItem.State.ACCEPTED -> {
                val list = sessions.list?.takeIf { sessions.current && it.window }
                val sessionId = item.sessionId
                if (sessionId != null && list?.sessions?.any { it.id == sessionId } == true) Route.Conversation(sessionId) else Route.Sessions
            }
            InboxItem.State.OTHER -> Route.Sessions
        }
    }
}
