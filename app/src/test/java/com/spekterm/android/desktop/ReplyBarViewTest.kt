package com.spekterm.android.desktop

import com.spekterm.android.desktop.ReplyBarView.Kind
import com.spekterm.android.net.ConversationUpdate
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Session
import com.spekterm.android.net.WaitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyBarViewTest {
    private val agent = Session("s1", "f1", "claude", running = true, wait = "ready")
    private val shell = Session("s1", "f1", "shell", running = true)
    private fun user(uuid: String, text: String) = Entry.User(uuid, 0, text, false)
    private fun watching(vararg entries: Entry): Conversation {
        val c = ConversationReducer.waited(ConversationReducer.answered(ConversationReducer.requested(Conversation("s1")), null), WaitState.READY, null, 0)
        return if (entries.isEmpty()) c else ConversationReducer.apply(c, ConversationUpdate("s1", "ok", entries.toList(), false, true), 0)
    }
    private fun send(c: Conversation, text: String, id: Long, outcome: SendOutcome = SendOutcome.Submitted, at: Long = 0) =
        Replies.answered(Replies.sent(c, id, text), id, outcome, at)

    @Test
    fun aShellSessionGetsNoBarWhateverTheScreen() {
        assertEquals(Kind.NONE, ReplyBarView.of(shell, Screen.Content, null, 0).kind)
        assertEquals(Kind.NONE, ReplyBarView.of(shell, Screen.Paused, null, 0).kind)
        assertEquals(Kind.NONE, ReplyBarView.of(null, Screen.Shell, null, 0).kind)
    }

    @Test
    fun closedAndDormantSessionsGetTheirLine() {
        assertEquals(Kind.CLOSED, ReplyBarView.of(agent, Screen.Closed, null, 0).kind)
        assertEquals(Kind.ASLEEP, ReplyBarView.of(agent, Screen.Dormant, null, 0).kind)
    }

    @Test
    fun otherScreensGetTheReplyArea() {
        for (screen in listOf(Screen.Content, Screen.Paused, Screen.NotConnected, Screen.NoWindow)) {
            assertEquals(Kind.REPLY, ReplyBarView.of(agent, screen, watching(), 0).kind)
        }
        assertEquals(Kind.REPLY, ReplyBarView.of(null, Screen.NotConnected, null, 0).kind)
    }

    @Test
    fun theNewestUnsettledReplyIsTaken() {
        val c = send(send(watching(), "one", 1), "two", 2)
        assertEquals(2L, ReplyBarView.waited(c, 0)!!.reply.id)
    }

    @Test
    fun theLabelFollowsTheClock() {
        val c = send(watching(), "one", 1, at = 0)
        assertEquals(Reply.Label.NOT_YET_CONFIRMED, ReplyBarView.waited(c, 29_000)!!.label)
        assertEquals(Reply.Label.NOT_IN_CONVERSATION_YET, ReplyBarView.waited(c, 31_000)!!.label)
    }

    @Test
    fun anUnsettledReplyIsTakenOverANewerSettledOne() {
        val c = send(send(watching(), "one", 1), "two", 2, SendOutcome.NotSubmitted)
        assertEquals(1L, ReplyBarView.waited(c, 0)!!.reply.id)
    }

    @Test
    fun aSettledReplyShowsUntilTheConversationMovesOn() {
        val c = send(watching(user("u1", "hi")), "one", 1, SendOutcome.NotSubmitted)
        assertEquals(Reply.Label.NOT_SUBMITTED, ReplyBarView.waited(c, 0)!!.label)
        val moved = ConversationReducer.apply(c, ConversationUpdate("s1", "ok", listOf(user("u2", "other")), false, false), 1)
        assertEquals(1, moved.replies.size)
        assertNull(ReplyBarView.waited(moved, 1))
    }

    @Test
    fun aSettledReplyWithNoEntriesAndNoAnchorShows() {
        val c = send(watching(), "one", 1, SendOutcome.NotSubmitted)
        assertEquals(1L, ReplyBarView.waited(c, 0)!!.reply.id)
    }

    @Test
    fun aSettledReplyWhoseAnchorWentIsDropped() {
        val c = send(watching(user("u1", "hi")), "one", 1, SendOutcome.NotSubmitted)
        // The desktop replaced the conversation: the reply's anchor is no longer among the keys.
        val replaced = c.copy(entries = listOf(user("u9", "new")))
        assertNull(ReplyBarView.waited(replaced, 1))
    }

    @Test
    fun noReplyNone() {
        assertNull(ReplyBarView.waited(watching(), 0))
        assertNull(ReplyBarView.of(agent, Screen.Content, watching(), 0).waited)
    }
}
