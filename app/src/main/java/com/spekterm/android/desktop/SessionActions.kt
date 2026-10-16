package com.spekterm.android.desktop

import com.spekterm.android.net.Operations
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.Session

/** A session's menu: the actions the desktop accepts for it, in order (design D8). */
object SessionActions {
    enum class Action { RENAME, HIBERNATE, WAKE, CLOSE }

    fun of(session: Session, operations: Set<String>): List<Action> = listOfNotNull(
        Action.RENAME.takeIf { Operations.RENAME in operations },
        Action.HIBERNATE.takeIf { Operations.HIBERNATE in operations && session.running },
        Action.WAKE.takeIf { Operations.WAKE in operations && !session.running },
        Action.CLOSE.takeIf { Operations.CLOSE in operations },
    )

    /** Whether [session]'s agent offered to close it: marked by the desktop, running, an agent (`close-offer`, D1). */
    fun offered(session: Session): Boolean = session.closeOffered && session.running && session.isAgent

    /** The answers an offer shows, in the desktop card's order (D2); none when [session] is not offered. */
    fun offerActions(session: Session, operations: Set<String>): List<SessionAction.Kind> = if (!offered(session)) emptyList() else listOfNotNull(
        SessionAction.Kind.CLOSE.takeIf { Operations.CLOSE in operations },
        SessionAction.Kind.KEEP.takeIf { Operations.KEEP in operations },
    )

    /** Every item is usable while connected, not paused, the shown list current and the desktop has a window. */
    fun enabled(connected: Boolean, paused: Boolean, sessions: SessionsState): Boolean =
        connected && !paused && sessions.current && sessions.list?.window == true
}

/** Renaming as the desktop does it (design D8). */
object Rename {
    const val MAX_CODE_POINTS = 200

    private val CONTROL = Regex("\\p{Cntrl}")

    /** The desktop's cleaning: control characters (newlines included) removed, the first 200 code points, trimmed. */
    fun clean(name: String): String {
        val stripped = name.replace(CONTROL, "")
        val end = if (stripped.codePointCount(0, stripped.length) > MAX_CODE_POINTS) stripped.offsetByCodePoints(0, MAX_CODE_POINTS) else stripped.length
        return Replies.jsTrim(stripped.substring(0, end))
    }

    /**
     * Whether renaming to [name] would leave [label] as it is, so nothing is sent. Never for clearing: the phone
     * cannot tell a given name from the agent's own title.
     */
    fun unchanged(name: String, label: String): Boolean {
        val cleaned = clean(name)
        return cleaned.isNotEmpty() && cleaned == label
    }
}

/**
 * The session action dialog's state (design D9): hibernate is sent at once without force; close is confirmed
 * first; a busy refusal asks again, showing why, and only agreeing forces (obligation 12).
 */
sealed interface SessionAction {
    val kind: Kind
    val sessionId: String
    val label: String

    enum class Kind { HIBERNATE, CLOSE, WAKE, KEEP }

    data class Confirm(override val kind: Kind, override val sessionId: String, override val label: String) : SessionAction

    data class Working(override val kind: Kind, override val sessionId: String, override val label: String, val force: Boolean) : SessionAction

    data class Busy(override val kind: Kind, override val sessionId: String, override val label: String, val reason: String?) : SessionAction

    data class Failed(override val kind: Kind, override val sessionId: String, override val label: String, val text: String) : SessionAction

    data class Done(override val kind: Kind, override val sessionId: String, override val label: String) : SessionAction

    companion object {
        /** Close asks first; hibernating and waking stop nothing, so they start at once. */
        fun start(kind: Kind, sessionId: String, label: String): SessionAction =
            if (kind == Kind.CLOSE) Confirm(kind, sessionId, label) else Working(kind, sessionId, label, force = false)

        /**
         * An answer to an agent's close offer (`close-offer`, D3): sent at once, unforced. Choosing Close on the offer
         * is the confirmation (obligation 13); a busy refusal still asks (obligation 12).
         */
        fun fromOffer(kind: Kind, sessionId: String, label: String): Working = Working(kind, sessionId, label, force = false)

        fun confirmed(s: Confirm): SessionAction = Working(s.kind, s.sessionId, s.label, force = false)

        /** The user agreed to force a busy session: the only way to a forced request. */
        fun agreed(s: Busy): SessionAction = Working(s.kind, s.sessionId, s.label, force = true)

        /** The desktop answered [s] with [error] (`null`: done). A busy answer to a forced request is an error. */
        fun answered(s: Working, error: RemoteError?): SessionAction = when {
            error == null -> Done(s.kind, s.sessionId, s.label)
            error.code == RemoteError.SESSION_BUSY && !s.force && (s.kind == Kind.HIBERNATE || s.kind == Kind.CLOSE) -> Busy(s.kind, s.sessionId, s.label, error.detailString("reason"))
            else -> Failed(s.kind, s.sessionId, s.label, errorText(s.kind, error))
        }

        /** Why the desktop thinks the session is busy; a reason not known reads as the desktop not being able to tell. */
        fun busyText(reason: String?): String = when (reason) {
            "working" -> "The agent is working."
            "choice" -> "The agent is waiting for an answer to a choice."
            "unsent" -> "A message to the agent has not been confirmed sent yet."
            "job" -> "A command is running in it."
            else -> "The desktop can't tell whether it is idle."
        }

        /** Waking's own texts, shared with the dormant screen's "Wake". */
        fun wakeText(e: RemoteError): String = when (e.code) {
            RemoteError.NO_WINDOW -> "spekterm has no open window on the desktop. Open it there first."
            RemoteError.CREATE_FAILED -> "The desktop could not start the session." + e.message.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            RemoteError.TIMEOUT -> "The desktop did not answer in time. Try again."
            RemoteError.NOT_FOUND -> "This session no longer exists."
            Desktop.NOT_CONNECTED -> "Not connected to the desktop."
            else -> "Could not wake it (${e.code})."
        }

        /** Keep's texts (D3). A timeout may leave the desktop's card up: its list has dropped the mark already. */
        fun keepText(e: RemoteError): String = when (e.code) {
            RemoteError.NOT_FOUND -> "This session no longer exists."
            RemoteError.NO_WINDOW -> "spekterm has no open window on the desktop."
            RemoteError.TIMEOUT -> "The desktop did not answer in time; its card may still show there."
            RemoteError.CREATE_FAILED -> "The desktop could not do it." + e.message.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            Desktop.NOT_CONNECTED -> "Not connected to the desktop. The session list shows whether it happened."
            else -> "Could not keep it (${e.code})."
        }

        fun errorText(kind: Kind, e: RemoteError): String = if (kind == Kind.WAKE) wakeText(e) else if (kind == Kind.KEEP) keepText(e) else when (e.code) {
            RemoteError.NOT_FOUND -> "This session no longer exists."
            RemoteError.NO_WINDOW -> "spekterm has no open window on the desktop."
            RemoteError.TIMEOUT -> "The desktop did not answer in time; it may still happen."
            RemoteError.CREATE_FAILED -> "The desktop could not do it." + e.message.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            Desktop.NOT_CONNECTED -> "Not connected to the desktop. The session list shows whether it happened."
            else -> if (kind == Kind.HIBERNATE) "Could not hibernate it (${e.code})." else "Could not close it (${e.code})."
        }
    }
}
