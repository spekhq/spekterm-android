package com.spekterm.android.desktop

import com.spekterm.android.net.Session

/** What the reply bar below a screen opened from a conversation shows (`reply-from-other-screens` D4, D5). */
object ReplyBarView {
    enum class Kind { NONE, CLOSED, ASLEEP, REPLY }

    /** The reply the user waits on and how the conversation would label it now. */
    data class Waited(val reply: Reply, val label: Reply.Label)

    data class Bar(val kind: Kind, val waited: Waited? = null)

    /** [screen] is the conversation's own, for [session]; a shell session gets no bar whatever the screen (D5). */
    fun of(session: Session?, screen: Screen, c: Conversation?, now: Long): Bar = when {
        session?.isAgent == false || screen == Screen.Shell -> Bar(Kind.NONE)
        screen == Screen.Closed -> Bar(Kind.CLOSED)
        screen == Screen.Dormant -> Bar(Kind.ASLEEP)
        else -> Bar(Kind.REPLY, c?.let { waited(it, now) })
    }

    /**
     * The newest reply not settled; failing that, the newest settled one while nothing came after the point it was
     * sent at, so an old failure does not stay for the rest of the session (D4).
     */
    fun waited(c: Conversation, now: Long): Waited? {
        val labelled = c.replies.map { Waited(it, it.label(now, ConversationTimes.holding(c, it))) }
        labelled.lastOrNull { !it.label.settled }?.let { return it }
        val last = ConversationReducer.keys(c.entries).lastOrNull()
        return labelled.lastOrNull { it.label.settled && it.reply.anchor == last }
    }
}
