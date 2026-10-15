package com.spekterm.android.desktop

import com.spekterm.android.net.SessionList

/** A session being started from the phone, as the session list shows it (`session-create`, design D6–D7). */
sealed interface Creation {
    /** The folder's name, or "Global". */
    val name: String

    data class Starting(override val name: String) : Creation

    /** Listed running; [autoOpen] when the session list was on screen when it was listed and still is. */
    data class Opened(val sessionId: String, override val name: String, val autoOpen: Boolean) : Creation

    /** Started, but not listed in time (or the connection ended meanwhile): it will appear in the list. */
    data class Unlisted(override val name: String) : Creation

    data class Failed(override val name: String, val reason: Reason, val message: String? = null) : Creation

    enum class Reason { NO_WINDOW, CREATE_FAILED, UNKNOWN }
}

/** The desktop's answer to `sessions.create` (design D6). */
sealed interface CreateOutcome {
    data class Created(val sessionId: String) : CreateOutcome

    data class Refused(val reason: Creation.Reason, val message: String? = null) : CreateOutcome

    companion object {
        /** [answered] false: the connection ended (or the call timed out) before an answer. */
        fun of(answered: Boolean, sessionId: String?, code: String?, message: String?): CreateOutcome = when {
            !answered -> Refused(Creation.Reason.UNKNOWN)
            code == null -> sessionId?.takeIf { it.isNotEmpty() }?.let { Created(it) } ?: Refused(Creation.Reason.CREATE_FAILED)
            code == "NO_WINDOW" -> Refused(Creation.Reason.NO_WINDOW)
            code == "CREATE_FAILED" -> Refused(Creation.Reason.CREATE_FAILED, message?.takeIf { it.isNotBlank() })
            // The desktop gave up waiting for its interface, which may still create the session.
            code == "TIMEOUT" -> Refused(Creation.Reason.UNKNOWN)
            else -> Refused(Creation.Reason.CREATE_FAILED)
        }
    }
}

/**
 * Decides when a created session is opened (design D6): the answer, then a list of the same connection holding
 * it running, or the connection ending, or a timeout — whichever comes first. Pure; [Desktop] feeds it on its
 * dispatcher. [connection] is compared by identity.
 */
object CreateTracker {
    /** A created session waiting to be listed. [id] tells one create's timer from another's. */
    data class Pending(val id: Long, val sessionId: String, val name: String, val connection: Any)

    data class State(val pending: Pending? = null, val creation: Creation? = null)

    /**
     * The desktop answered create [id]. [attached] is the connection attached now; [list] the current list, if
     * any, of that connection.
     */
    fun answered(
        state: State,
        id: Long,
        name: String,
        outcome: CreateOutcome,
        connection: Any,
        attached: Any?,
        list: SessionList?,
        listOnScreen: Boolean,
    ): State = when (outcome) {
        is CreateOutcome.Refused -> State(null, Creation.Failed(name, outcome.reason, outcome.message))
        is CreateOutcome.Created -> when {
            attached !== connection -> State(null, Creation.Unlisted(name))
            list != null && runningIn(list, outcome.sessionId) -> State(null, Creation.Opened(outcome.sessionId, name, listOnScreen))
            else -> State(Pending(id, outcome.sessionId, name, connection), state.creation)
        }
    }

    /** A current list arrived on [connection]. */
    fun listed(state: State, list: SessionList, connection: Any, listOnScreen: Boolean): State {
        val p = state.pending ?: return state
        if (p.connection !== connection || !runningIn(list, p.sessionId)) return state
        return State(null, Creation.Opened(p.sessionId, p.name, listOnScreen))
    }

    /** [connection] detached. */
    fun detached(state: State, connection: Any): State {
        val p = state.pending ?: return state
        if (p.connection !== connection) return state
        return State(null, Creation.Unlisted(p.name))
    }

    /** Create [id]'s timer fired; ignored unless that create is still the one waiting. */
    fun timedOut(state: State, id: Long): State {
        val p = state.pending ?: return state
        if (p.id != id) return state
        return State(null, Creation.Unlisted(p.name))
    }

    /** The session list left the screen: an `Opened` not yet acted on must not navigate on return. */
    fun leftList(state: State): State {
        val c = state.creation as? Creation.Opened ?: return state
        return if (c.autoOpen) state.copy(creation = c.copy(autoOpen = false)) else state
    }

    private fun runningIn(list: SessionList, sessionId: String): Boolean =
        list.window && list.sessions.any { it.id == sessionId && it.running }
}
