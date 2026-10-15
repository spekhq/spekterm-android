package com.spekterm.android.desktop

import com.spekterm.android.net.Entry
import com.spekterm.android.net.Frames
import com.spekterm.android.net.Pending
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.SendResult
import com.spekterm.android.net.WaitState

/**
 * A reply the user sent that is not (yet) in the conversation (design D6).
 *
 * [held] are the entry uuids the phone held when it was sent: only a user entry not among them can confirm it,
 * so an older identical message in a replacing update does not. [anchor] is the list key of the entry that was
 * last when it was sent, where a settled reply is shown.
 */
data class Reply(
    val id: Long,
    val text: String,
    val sentWhile: String,
    val held: Set<String>,
    val anchor: String?,
    val state: State = State.SENDING,
    /** When the desktop answered `submitted`. */
    val submittedAt: Long? = null,
    /** Sent while the agent worked, and the agent has since stopped working. */
    val agentStopped: Boolean = false,
) {
    enum class State { SENDING, SUBMITTED, MAY_NOT_HAVE_BEEN_SENT, NOT_SUBMITTED }

    /** What the screen says about it, and whether it has settled at its send position. */
    enum class Label(val settled: Boolean) {
        SENDING(false),
        NOT_YET_CONFIRMED(false),
        WAITING_FOR_AGENT(false),
        NOT_IN_CONVERSATION_YET(true),
        MAY_NOT_SHOW(true),
        MAY_NOT_HAVE_BEEN_SENT(true),
        NOT_SUBMITTED(true),
    }

    fun label(now: Long): Label = when (state) {
        State.SENDING -> Label.SENDING
        State.MAY_NOT_HAVE_BEEN_SENT -> Label.MAY_NOT_HAVE_BEEN_SENT
        State.NOT_SUBMITTED -> Label.NOT_SUBMITTED
        State.SUBMITTED -> when {
            sentWhile == WaitState.BUSY -> if (agentStopped) Label.MAY_NOT_SHOW else Label.WAITING_FOR_AGENT
            now - (submittedAt ?: now) >= Replies.NOT_SEEN_MS -> Label.NOT_IN_CONVERSATION_YET
            else -> Label.NOT_YET_CONFIRMED
        }
    }

    /** A typed-but-withheld reply is never submitted by the desktop, so nothing can confirm it. */
    val matchable: Boolean get() = state != State.NOT_SUBMITTED
}

/** How the desktop answered a `conversation.send`, as the screen treats it (design D6). */
sealed interface SendOutcome {
    data object Submitted : SendOutcome

    data object NotSubmitted : SendOutcome

    /** Refused before anything was written: the text goes back to the composer. */
    data class Refused(val code: String) : SendOutcome

    /** It is unknown whether the text was written. */
    data object Unknown : SendOutcome

    companion object {
        private val REFUSED_BEFORE_WRITING = setOf(
            RemoteError.WAIT_STATE,
            RemoteError.NOT_WATCHING,
            RemoteError.BAD_PARAMS,
            RemoteError.NOT_FOUND,
            RemoteError.UNKNOWN_OPERATION,
        )

        /** [outcome] from an ok answer, or [errorCode] from an error answer; both null when the connection ended. */
        fun of(outcome: String?, errorCode: String?): SendOutcome = when {
            errorCode != null -> if (errorCode in REFUSED_BEFORE_WRITING) Refused(errorCode) else Unknown
            outcome == SendResult.SUBMITTED -> Submitted
            outcome == SendResult.FILLED_NOT_SUBMITTED -> NotSubmitted
            else -> Unknown
        }
    }
}

/** Why the composer cannot send now (design D6), in precedence order. */
sealed interface Blocked {
    data object Paused : Blocked

    data object NotConnected : Blocked

    data object NotWatching : Blocked

    data class AwaitingChoice(val pending: Pending?) : Blocked

    data object Unknown : Blocked

    data object InFlight : Blocked

    /** The text is empty once the desktop's cleaning and trimming are applied. */
    data object Empty : Blocked

    data object TooLong : Blocked
}

object Replies {
    const val NOT_SEEN_MS = 30_000L

    /** The desktop's WebSocket message limit; a longer frame closes the connection. */
    const val MAX_FRAME_BYTES = 1024 * 1024

    private val CONTROL = Regex("[\\x00-\\x09\\x0b-\\x1f\\x7f]")

    /** The desktop's cleaning (`cleanReply`): C0 controls except newline, and DEL, removed. */
    fun clean(text: String): String = text.replace(CONTROL, "")

    /** JavaScript's `trim()`: white space and line terminators, U+FEFF included. */
    fun jsTrim(text: String): String = text.trim { it.isWhitespace() || it == '﻿' }

    /** The reply gate (design D6). `null` when the text can be sent now. */
    fun blocked(
        connected: Boolean,
        paused: Boolean,
        watch: Watch,
        wait: String,
        pending: Pending?,
        inFlight: Boolean,
        text: String,
    ): Blocked? = when {
        paused -> Blocked.Paused
        !connected -> Blocked.NotConnected
        watch != Watch.Watching -> Blocked.NotWatching
        wait == WaitState.AWAITING_CHOICE -> Blocked.AwaitingChoice(pending)
        wait != WaitState.READY && wait != WaitState.BUSY -> Blocked.Unknown
        inFlight -> Blocked.InFlight
        jsTrim(clean(text)).isEmpty() -> Blocked.Empty
        tooLong(text) -> Blocked.TooLong
        else -> null
    }

    /**
     * Whether the encoded `conversation.send` frame reaches the desktop's limit. Escaping takes at most six bytes
     * per character, so short text is answered without encoding.
     */
    fun tooLong(text: String, sessionId: String = "0".repeat(64)): Boolean {
        if (text.length * 6L + 512 < MAX_FRAME_BYTES) return false
        val frame = Frames.request(Long.MAX_VALUE, "conversation.send", Frames.conversationSend(sessionId, text))
        return frame.toByteArray(Charsets.UTF_8).size >= MAX_FRAME_BYTES
    }

    /** A new reply, sent now, in state SENDING. */
    fun sent(c: Conversation, id: Long, text: String): Conversation {
        val reply = Reply(
            id = id,
            text = text,
            sentWhile = c.wait,
            held = c.entries.mapNotNullTo(HashSet()) { it.uuid.takeIf(String::isNotEmpty) },
            anchor = ConversationReducer.keys(c.entries).lastOrNull(),
        )
        return c.copy(replies = c.replies + reply, draft = "")
    }

    /** The desktop answered reply [id]. A refusal removes it and returns its text to the draft. */
    fun answered(c: Conversation, id: Long, outcome: SendOutcome, now: Long): Conversation {
        val reply = c.replies.firstOrNull { it.id == id } ?: return c
        val others = c.replies - reply
        return when (outcome) {
            SendOutcome.Submitted -> c.copy(replies = others + reply.copy(state = Reply.State.SUBMITTED, submittedAt = now))
            SendOutcome.NotSubmitted -> c.copy(replies = others + reply.copy(state = Reply.State.NOT_SUBMITTED))
            SendOutcome.Unknown -> c.copy(replies = others + reply.copy(state = Reply.State.MAY_NOT_HAVE_BEEN_SENT))
            is SendOutcome.Refused -> c.copy(
                replies = others,
                draft = if (c.draft.isEmpty()) reply.text else reply.text + "\n" + c.draft,
                draftReturns = c.draftReturns + 1,
            )
        }.let { next -> next.copy(replies = next.replies.sortedBy(Reply::id)) }
    }

    /**
     * Confirms replies by the user entries of an update (design D6): a non-meta user entry, not held when the
     * reply was sent, whose text equals the reply's after the desktop's cleaning, both trimmed. Each entry
     * confirms at most one reply, oldest first; a confirmed reply disappears.
     */
    fun confirm(c: Conversation, arrived: List<Entry>, @Suppress("UNUSED_PARAMETER") now: Long): Conversation {
        if (c.replies.isEmpty()) return c
        var remaining = c.replies
        for (entry in arrived) {
            if (entry !is Entry.User || entry.meta) continue
            val text = jsTrim(entry.text)
            val match = remaining.firstOrNull { it.matchable && entry.uuid !in it.held && jsTrim(clean(it.text)) == text } ?: continue
            remaining = remaining - match
        }
        return if (remaining.size == c.replies.size) c else c.copy(replies = remaining)
    }

    /** The wait state changed: a reply sent while the agent worked notes that the agent stopped. */
    fun waited(c: Conversation, wait: String): Conversation {
        if (wait == WaitState.BUSY || c.replies.none { it.sentWhile == WaitState.BUSY && !it.agentStopped }) return c
        return c.copy(replies = c.replies.map { if (it.sentWhile == WaitState.BUSY) it.copy(agentStopped = true) else it })
    }

    /** The connection ended: a reply still waiting for its answer may or may not have been written. */
    fun disconnected(c: Conversation): Conversation =
        c.copy(replies = c.replies.map { if (it.state == Reply.State.SENDING) it.copy(state = Reply.State.MAY_NOT_HAVE_BEEN_SENT) else it })
}
