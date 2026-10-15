package com.spekterm.android.desktop

import com.spekterm.android.net.ConversationUpdate
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Pending
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.SendResult
import com.spekterm.android.net.WaitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepliesTest {
    private fun user(uuid: String, text: String, meta: Boolean = false) = Entry.User(uuid, 0, text, meta)
    private fun update(vararg events: Entry, reset: Boolean = false) = ConversationUpdate("s1", "ok", events.toList(), false, reset)
    private fun watching(wait: String = WaitState.READY) =
        ConversationReducer.waited(ConversationReducer.answered(ConversationReducer.requested(Conversation("s1")), null), wait, null)
    private fun send(c: Conversation, text: String, id: Long = 1, outcome: SendOutcome = SendOutcome.Submitted, at: Long = 0) =
        Replies.answered(Replies.sent(c, id, text), id, outcome, at)

    @Test
    fun cleansLikeTheDesktopAndTrimsLikeJavaScript() {
        assertEquals("ab\ncd", Replies.clean("a\u0007b\ncd\u007f\r"))
        assertEquals("x", Replies.jsTrim("﻿  x \n"))
    }

    @Test
    fun aMatchingUserEntryConfirmsAReply() {
        val c = send(watching(), "  fix\u0007 it \n")
        assertEquals(1, c.replies.size)
        val confirmed = ConversationReducer.apply(c, update(user("u1", "fix it")), now = 1)
        assertTrue(confirmed.replies.isEmpty())
    }

    @Test
    fun metaAndOtherTextDoNotConfirm() {
        val c = send(watching(), "yes")
        assertEquals(1, ConversationReducer.apply(c, update(user("u1", "yes", meta = true), user("u2", "no")), 1).replies.size)
    }

    @Test
    fun anIdenticalEarlierMessageDoesNotConfirm() {
        var c = ConversationReducer.apply(watching(), update(user("old", "yes"), reset = true), 0)
        c = send(c, "yes")
        // The desktop re-delivers the whole conversation without the new message.
        c = ConversationReducer.apply(c, update(user("old", "yes"), reset = true), 1)
        assertEquals(1, c.replies.size)
        c = ConversationReducer.apply(c, update(user("new", "yes")), 2)
        assertTrue(c.replies.isEmpty())
    }

    @Test
    fun oneEntryConfirmsOneReplyOldestFirst() {
        var c = send(watching(), "same", id = 1)
        c = send(c, "same", id = 2)
        c = ConversationReducer.apply(c, update(user("u1", "same")), 1)
        assertEquals(listOf(2L), c.replies.map { it.id })
    }

    @Test
    fun aReplySentWhileReadyIsNotSeenAfterThirtySecondsAndCanStillConfirm() {
        var c = send(watching(WaitState.READY), "hi", at = 1_000)
        val reply = c.replies.single()
        assertEquals(Reply.Label.NOT_YET_CONFIRMED, reply.label(1_000 + 29_999))
        assertEquals(Reply.Label.NOT_IN_CONVERSATION_YET, reply.label(1_000 + 30_000))
        assertTrue(Reply.Label.NOT_IN_CONVERSATION_YET.settled)
        c = ConversationReducer.apply(c, update(user("late", "hi")), 60_000)
        assertTrue(c.replies.isEmpty())
    }

    @Test
    fun aReplySentWhileBusyWaitsForTheAgentThenMayNotShow() {
        var c = send(watching(WaitState.BUSY), "also this", at = 0)
        assertEquals(Reply.Label.WAITING_FOR_AGENT, c.replies.single().label(10 * 60_000))
        c = ConversationReducer.waited(c, WaitState.BUSY, null)
        assertEquals(Reply.Label.WAITING_FOR_AGENT, c.replies.single().label(0))
        c = ConversationReducer.waited(c, WaitState.READY, null)
        assertEquals(Reply.Label.MAY_NOT_SHOW, c.replies.single().label(0))
        assertTrue(ConversationReducer.apply(c, update(user("u", "also this")), 0).replies.isEmpty())
    }

    @Test
    fun aSettledReplyRemembersWhereItWasSent() {
        var c = ConversationReducer.apply(watching(), update(Entry.Text("a", 0, "x"), Entry.Text("a", 0, "y"), reset = true), 0)
        c = send(c, "hi")
        assertEquals("a#1", c.replies.single().anchor)
        assertNull(send(watching(), "first").replies.single().anchor)
    }

    @Test
    fun outcomesMapAsTheDesignSays() {
        assertEquals(SendOutcome.Submitted, SendOutcome.of(SendResult.SUBMITTED, null))
        assertEquals(SendOutcome.NotSubmitted, SendOutcome.of(SendResult.FILLED_NOT_SUBMITTED, null))
        for (code in listOf(RemoteError.WAIT_STATE, RemoteError.NOT_WATCHING, RemoteError.BAD_PARAMS, RemoteError.NOT_FOUND, RemoteError.UNKNOWN_OPERATION)) {
            assertEquals(SendOutcome.Refused(code), SendOutcome.of(null, code))
        }
        assertEquals(SendOutcome.Unknown, SendOutcome.of(null, RemoteError.INTERNAL))
        assertEquals(SendOutcome.Unknown, SendOutcome.of(null, null))
    }

    @Test
    fun aRefusalReturnsTheTextToTheDraft() {
        val c = send(watching(), "hello", outcome = SendOutcome.Refused(RemoteError.WAIT_STATE))
        assertTrue(c.replies.isEmpty())
        assertEquals("hello", c.draft)
    }

    @Test
    fun aWithheldReplyIsNeverConfirmed() {
        var c = send(watching(), "go", outcome = SendOutcome.NotSubmitted)
        assertEquals(Reply.Label.NOT_SUBMITTED, c.replies.single().label(0))
        c = ConversationReducer.apply(c, update(user("u", "go")), 0)
        assertEquals(1, c.replies.size)
    }

    @Test
    fun aReplyInFlightWhenTheConnectionEndsMayNotHaveBeenSentAndIsStillMatched() {
        var c = ConversationReducer.disconnected(Replies.sent(watching(), 1, "maybe"))
        assertEquals(Reply.Label.MAY_NOT_HAVE_BEEN_SENT, c.replies.single().label(0))
        c = ConversationReducer.apply(c, update(user("u", "maybe")), 0)
        assertTrue(c.replies.isEmpty())
    }

    @Test
    fun theGateGivesOneReasonInPrecedence() {
        val ok = { wait: String -> Replies.blocked(true, false, Watch.Watching, wait, null, false, "hi") }
        assertNull(ok(WaitState.READY))
        assertNull(ok(WaitState.BUSY))
        assertEquals(Blocked.Unknown, ok(WaitState.UNKNOWN))
        assertEquals(Blocked.AwaitingChoice(Pending("Bash", "rm")), Replies.blocked(true, false, Watch.Watching, WaitState.AWAITING_CHOICE, Pending("Bash", "rm"), false, "hi"))
        assertEquals(Blocked.Paused, Replies.blocked(false, true, Watch.Idle, WaitState.UNKNOWN, null, true, ""))
        assertEquals(Blocked.NotConnected, Replies.blocked(false, false, Watch.Watching, WaitState.READY, null, false, "hi"))
        assertEquals(Blocked.NotWatching, Replies.blocked(true, false, Watch.Requested, WaitState.AWAITING_CHOICE, null, false, "hi"))
        assertEquals(Blocked.AwaitingChoice(null), Replies.blocked(true, false, Watch.Watching, WaitState.AWAITING_CHOICE, null, true, ""))
        assertEquals(Blocked.InFlight, Replies.blocked(true, false, Watch.Watching, WaitState.READY, null, true, "hi"))
        assertEquals(Blocked.Empty, Replies.blocked(true, false, Watch.Watching, WaitState.READY, null, false, " \u0007﻿\n"))
    }

    @Test
    fun theFrameLimitIsTheDesktops() {
        assertFalse(Replies.tooLong("x".repeat(100_000)))
        assertTrue(Replies.tooLong("x".repeat(Replies.MAX_FRAME_BYTES)))
        assertEquals(Blocked.TooLong, Replies.blocked(true, false, Watch.Watching, WaitState.READY, null, false, "x".repeat(Replies.MAX_FRAME_BYTES)))
        // Just under the limit once encoded: accepted.
        assertFalse(Replies.tooLong("x".repeat(Replies.MAX_FRAME_BYTES - 400)))
    }
}
