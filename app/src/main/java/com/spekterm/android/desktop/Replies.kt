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
    /**
     * Sent while the agent worked: when the desktop first reported it ready or unknown since (a permission prompt
     * is not a stop). Cleared by a report of it working again within the grace (`addresses-and-mid-turn-replies` D6).
     */
    val stoppedAt: Long? = null,
    /** The attachments it carries, taken out of the composer (design D7). */
    val attachments: List<Attachment> = emptyList(),
    /** Exactly what the desktop wrote (`r.text`); `null` before the answer, or from a desktop before 29a5d0a. */
    val written: String? = null,
) {
    enum class State { SENDING, SUBMITTED, MAY_NOT_HAVE_BEEN_SENT, NOT_SUBMITTED }

    /** What the screen says about it, and whether it has settled at its send position. */
    enum class Label(val settled: Boolean) {
        SENDING(false),
        NOT_YET_CONFIRMED(false),
        WAITING_FOR_AGENT(false),
        NOT_IN_CONVERSATION_YET(true),
        NOT_SHOWN_AFTER_WORK(true),
        MAY_NOT_HAVE_BEEN_SENT(true),
        NOT_SUBMITTED(true),
    }

    fun label(now: Long): Label = when (state) {
        State.SENDING -> Label.SENDING
        State.MAY_NOT_HAVE_BEEN_SENT -> Label.MAY_NOT_HAVE_BEEN_SENT
        State.NOT_SUBMITTED -> Label.NOT_SUBMITTED
        State.SUBMITTED -> when {
            sentWhile == WaitState.BUSY -> when {
                stoppedAt == null -> Label.WAITING_FOR_AGENT
                inGrace(now) -> Label.NOT_YET_CONFIRMED
                else -> Label.NOT_SHOWN_AFTER_WORK
            }
            now - (submittedAt ?: now) >= Replies.NOT_SEEN_MS -> Label.NOT_IN_CONVERSATION_YET
            else -> Label.NOT_YET_CONFIRMED
        }
    }

    /**
     * Within 30 s of the agent stopping: during a text-only answer the desktop reports `ready` just before the turn
     * that takes the reply starts, so the reply is not called missing yet. One predicate for [label] and
     * [Replies.waited], so they agree at the boundary.
     */
    fun inGrace(now: Long): Boolean = stoppedAt != null && now - stoppedAt < Replies.NOT_SEEN_MS

    /** A typed-but-withheld reply is never submitted by the desktop, so nothing can confirm it. */
    val matchable: Boolean get() = state != State.NOT_SUBMITTED

    /** The ids of its attachments on the desktop. */
    val attachmentIds: List<String> get() = attachments.mapNotNull { it.ready?.attachmentId }
}

/** How the desktop answered a `conversation.send`, as the screen treats it (design D6). */
sealed interface SendOutcome {
    data object Submitted : SendOutcome

    data object NotSubmitted : SendOutcome

    /** Refused before anything was written: the text goes back to the composer. */
    data class Refused(val code: String) : SendOutcome

    /** It is unknown whether the text was written. */
    data object Unknown : SendOutcome

    /** The connection log's name for it, fixed in code so a shrunk build logs the same (`play-release` D3). */
    val logName: String
        get() = when (this) {
            Submitted -> "Submitted"
            NotSubmitted -> "NotSubmitted"
            is Refused -> "Refused"
            Unknown -> "Unknown"
        }

    companion object {
        private val REFUSED_BEFORE_WRITING = setOf(
            RemoteError.WAIT_STATE,
            RemoteError.NOT_WATCHING,
            RemoteError.BAD_PARAMS,
            RemoteError.NOT_FOUND,
            RemoteError.UNKNOWN_OPERATION,
            // A filled-in prompt from the inbox waits unsent: nothing was written (`inbox` design D7).
            RemoteError.PREFILL_PENDING,
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

    /** A first prompt from the inbox waits unsent in the session (`inbox` design D7); the note above says so. */
    data object InboxPrompt : Blocked

    data object Unknown : Blocked

    data object InFlight : Blocked

    /** An attachment is still being prepared or uploaded. */
    data object Attaching : Blocked

    /** An attachment failed: retry or remove it first. */
    data object AttachmentFailed : Blocked

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
    fun jsTrim(text: String): String = text.trim { it.isWhitespace() || it == '\uFEFF' }

    /** The reply gate (design D6). `null` when the text can be sent now. */
    fun blocked(
        connected: Boolean,
        paused: Boolean,
        watch: Watch,
        wait: String,
        pending: Pending?,
        inFlight: Boolean,
        text: String,
        attachments: List<Attachment> = emptyList(),
        /** `InboxView.linked` holds replies back now (`inbox` design D7). */
        inboxPrompt: Boolean = false,
    ): Blocked? = when {
        paused -> Blocked.Paused
        !connected -> Blocked.NotConnected
        watch != Watch.Watching -> Blocked.NotWatching
        wait == WaitState.AWAITING_CHOICE -> Blocked.AwaitingChoice(pending)
        inboxPrompt -> Blocked.InboxPrompt
        wait != WaitState.READY && wait != WaitState.BUSY -> Blocked.Unknown
        inFlight -> Blocked.InFlight
        attachments.any { it.state is AttachmentState.Failed } -> Blocked.AttachmentFailed
        attachments.any { it.state !is AttachmentState.Ready } -> Blocked.Attaching
        // Empty text is sendable when it carries an attachment: the desktop writes its sentence alone.
        jsTrim(clean(text)).isEmpty() && attachments.isEmpty() -> Blocked.Empty
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

    /** A new reply, sent now, in state SENDING, carrying [attachments] out of the composer. */
    fun sent(c: Conversation, id: Long, text: String, attachments: List<Attachment> = emptyList()): Conversation {
        val reply = Reply(
            id = id,
            text = text,
            sentWhile = c.wait,
            held = c.entries.mapNotNullTo(HashSet()) { it.uuid.takeIf(String::isNotEmpty) },
            anchor = ConversationReducer.keys(c.entries).lastOrNull(),
            attachments = attachments,
        )
        val sentIds = attachments.map { it.localId }.toSet()
        return c.copy(replies = c.replies + reply, draft = "", attachments = c.attachments.filter { it.localId !in sentIds }, attachNote = null, prefillRefused = false)
    }

    /**
     * The desktop answered reply [id], having written [written]. A refusal removes it and returns its text to the
     * draft and its attachments to the composer, still ready (design D7) — unless the session is gone.
     */
    fun answered(c: Conversation, id: Long, outcome: SendOutcome, now: Long, written: String? = null): Conversation {
        val reply = c.replies.firstOrNull { it.id == id } ?: return c
        val others = c.replies - reply
        return when (outcome) {
            SendOutcome.Submitted -> rematch(c.copy(replies = others + reply.copy(state = Reply.State.SUBMITTED, submittedAt = now, written = written)), id)
            SendOutcome.NotSubmitted -> c.copy(replies = others + reply.copy(state = Reply.State.NOT_SUBMITTED, written = written))
            SendOutcome.Unknown -> rematch(c.copy(replies = others + reply.copy(state = Reply.State.MAY_NOT_HAVE_BEEN_SENT)), id)
            is SendOutcome.Refused -> {
                val notFound = outcome.code == RemoteError.NOT_FOUND
                val back = if (c.closed) emptyList() else returned(reply.attachments, notFound, now)
                c.copy(
                    replies = others,
                    draft = if (c.draft.isEmpty()) reply.text else reply.text + "\n" + c.draft,
                    draftReturns = c.draftReturns + 1,
                    attachments = back + c.attachments,
                    attachNote = if (notFound && back.isNotEmpty()) AttachNote.SEND_NOT_FOUND else c.attachNote,
                    prefillRefused = outcome.code == RemoteError.PREFILL_PENDING,
                )
            }
        }.let { next -> next.copy(replies = next.replies.sortedBy(Reply::id)) }
    }

    /**
     * Attachments of a refused send, back in the composer. On `NOT_FOUND` each counts it; at an attachment's
     * second, the doubtful ones of that send fail, or all of them when none is doubtful (design D7).
     */
    private fun returned(attachments: List<Attachment>, notFound: Boolean, now: Long): List<Attachment> {
        if (!notFound) return attachments
        val counted = attachments.map { it.copy(notFoundSends = it.notFoundSends + 1) }
        val second = counted.filter { it.notFoundSends >= 2 }
        if (second.isEmpty()) return counted
        val doubtful = second.filter { Uploads.doubtful(it, now) }
        val failing = (doubtful.ifEmpty { second }).map { it.localId }.toSet()
        return counted.map { if (it.localId in failing) it.copy(state = AttachmentState.Failed(Uploads.GONE), notFoundSends = 0) else it }
    }

    /**
     * Whether [entry] confirms [reply] (design D7): equal to what the desktop wrote; without that, equal to the
     * cleaned text — or, for a reply with attachments, starting with it and naming every attachment's id.
     */
    fun matches(reply: Reply, entry: Entry): Boolean {
        if (entry !is Entry.User || entry.meta || !reply.matchable || entry.uuid in reply.held) return false
        val text = jsTrim(entry.text)
        reply.written?.let { return text == jsTrim(it) }
        if (reply.attachments.isEmpty()) return text == jsTrim(clean(reply.text))
        val ids = reply.attachmentIds
        return ids.isNotEmpty() && text.startsWith(jsTrim(clean(reply.text))) && ids.all { it in text }
    }

    /**
     * A reply with attachments may be in the conversation before its answer: match it against the entries held now
     * that were not held when it was sent. Only such a reply: its ids make the match unique.
     */
    private fun rematch(c: Conversation, id: Long): Conversation {
        val reply = c.replies.firstOrNull { it.id == id }?.takeIf { it.attachments.isNotEmpty() } ?: return c
        return if (c.entries.any { matches(reply, it) }) c.copy(replies = c.replies - reply) else c
    }

    /**
     * Confirms replies by the user entries of an update (design D6, D7). Each entry confirms at most one reply,
     * oldest first; a confirmed reply disappears.
     */
    fun confirm(c: Conversation, arrived: List<Entry>, @Suppress("UNUSED_PARAMETER") now: Long): Conversation {
        if (c.replies.isEmpty()) return c
        var remaining = c.replies
        for (entry in arrived) {
            val match = remaining.firstOrNull { matches(it, entry) } ?: continue
            remaining = remaining - match
        }
        return if (remaining.size == c.replies.size) c else c.copy(replies = remaining)
    }

    /**
     * The desktop reported [wait] (D6). For a reply sent while the agent worked: `ready`
     * or `unknown` starts the grace once; `busy` or `awaiting-choice` (a prompt pauses the turn, it does not end it)
     * within the grace ends it again. After the grace the reply has settled and stays so.
     */
    fun waited(c: Conversation, wait: String, now: Long): Conversation {
        val working = wait == WaitState.BUSY || wait == WaitState.AWAITING_CHOICE
        var changed = false
        val replies = c.replies.map { reply ->
            // Kept for every state; only a submitted reply's label reads it (one still sending may become one).
            if (reply.sentWhile != WaitState.BUSY) return@map reply
            val next = when {
                working && reply.inGrace(now) -> reply.copy(stoppedAt = null)
                !working && reply.stoppedAt == null -> reply.copy(stoppedAt = now)
                else -> reply
            }
            if (next !== reply) changed = true
            next
        }
        return if (changed) c.copy(replies = replies) else c
    }

    /** The connection ended: a reply still waiting for its answer may or may not have been written. */
    fun disconnected(c: Conversation): Conversation =
        c.copy(replies = c.replies.map { if (it.state == Reply.State.SENDING) it.copy(state = Reply.State.MAY_NOT_HAVE_BEEN_SENT) else it })
}
