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
        ConversationReducer.waited(ConversationReducer.answered(ConversationReducer.requested(Conversation("s1")), null), wait, null, 0)
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

    private fun waited(c: Conversation, wait: String, at: Long) = ConversationReducer.waited(c, wait, null, at)

    @Test
    fun aReplySentWhileBusyWaitsForTheAgentThenGetsAGraceThenSettles() {
        var c = send(watching(WaitState.BUSY), "also this", at = 0)
        assertEquals(Reply.Label.WAITING_FOR_AGENT, c.replies.single().label(10 * 60_000))
        c = waited(c, WaitState.BUSY, 1_000)
        assertEquals(Reply.Label.WAITING_FOR_AGENT, c.replies.single().label(1_000))
        c = waited(c, WaitState.READY, 10_000)
        assertEquals(Reply.Label.NOT_YET_CONFIRMED, c.replies.single().label(10_000 + 29_999))
        assertFalse(Reply.Label.NOT_YET_CONFIRMED.settled)
        assertEquals(Reply.Label.NOT_SHOWN_AFTER_WORK, c.replies.single().label(10_000 + 30_000))
        assertTrue(Reply.Label.NOT_SHOWN_AFTER_WORK.settled)
        assertTrue(ConversationReducer.apply(c, update(user("u", "also this")), 60_000).replies.isEmpty())
    }

    @Test
    fun aPermissionPromptDoesNotEndTheWait() {
        var c = send(watching(WaitState.BUSY), "x")
        c = waited(c, WaitState.AWAITING_CHOICE, 1_000)
        assertEquals(Reply.Label.WAITING_FOR_AGENT, c.replies.single().label(10 * 60_000))
    }

    @Test
    fun workingAgainWithinTheGraceWaitsAgain() {
        for (again in listOf(WaitState.BUSY, WaitState.AWAITING_CHOICE)) {
            var c = send(watching(WaitState.BUSY), "x")
            c = waited(c, WaitState.READY, 1_000)
            c = waited(c, again, 1_000 + 29_999)
            assertEquals(again, Reply.Label.WAITING_FOR_AGENT, c.replies.single().label(5 * 60_000))
        }
    }

    @Test
    fun anUnknownReportStartsTheGraceAndARepeatedReportDoesNotRestartIt() {
        var c = send(watching(WaitState.BUSY), "x")
        c = waited(c, WaitState.UNKNOWN, 1_000)
        assertEquals(Reply.Label.NOT_YET_CONFIRMED, c.replies.single().label(1_000))
        c = waited(c, WaitState.READY, 21_000)
        assertEquals(Reply.Label.NOT_SHOWN_AFTER_WORK, c.replies.single().label(31_000))
    }

    @Test
    fun aSettledReplyStaysSettledWhenTheAgentWorksAgain() {
        var c = send(watching(WaitState.BUSY), "x")
        c = waited(c, WaitState.READY, 1_000)
        c = waited(c, WaitState.BUSY, 31_000)
        assertEquals(Reply.Label.NOT_SHOWN_AFTER_WORK, c.replies.single().label(31_000))
        c = ConversationReducer.apply(c, update(user("late", "x")), 40_000)
        assertTrue(c.replies.isEmpty())
    }

    @Test
    fun aReplyTakenInTheTurnAfterATextOnlyAnswerConfirmsWithinTheGrace() {
        var c = send(watching(WaitState.BUSY), "and then")
        c = waited(c, WaitState.READY, 1_000)
        assertEquals(Reply.Label.NOT_YET_CONFIRMED, c.replies.single().label(3_000))
        c = ConversationReducer.apply(c, update(user("next", "and then")), 6_000)
        assertTrue(c.replies.isEmpty())
    }

    @Test
    fun theGraceLeavesOtherRepliesAlone() {
        val ready = send(watching(WaitState.READY), "r", at = 0)
        assertEquals(ready.replies, waited(ready, WaitState.BUSY, 1_000).replies)
        for (outcome in listOf(SendOutcome.Unknown, SendOutcome.NotSubmitted)) {
            val c = waited(send(watching(WaitState.BUSY), "x", outcome = outcome), WaitState.READY, 1_000)
            val expected = if (outcome == SendOutcome.Unknown) Reply.Label.MAY_NOT_HAVE_BEEN_SENT else Reply.Label.NOT_SUBMITTED
            assertEquals(expected, c.replies.single().label(1_000))
        }
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

    // ---- `prompts-attachments-rename` (design D7) ----

    private fun ready(localId: Long, id: String, completedAt: Long = 0, presumed: Boolean = false) =
        Attachment(localId, "f$localId.jpg", 10, state = AttachmentState.Ready(id, completedAt, presumed))
    private fun blocked(text: String, attachments: List<Attachment>) =
        Replies.blocked(true, false, Watch.Watching, WaitState.READY, null, false, text, attachments)

    @Test
    fun attachmentsInTheGate() {
        assertEquals(Blocked.Attaching, blocked("hi", listOf(Attachment(1, "a"))))
        assertEquals(Blocked.Attaching, blocked("hi", listOf(Attachment(1, "a", state = AttachmentState.Uploading()))))
        assertEquals(Blocked.AttachmentFailed, blocked("hi", listOf(ready(1, "x"), Attachment(2, "b", state = AttachmentState.Failed("TOO_MANY")))))
        assertNull(blocked("", listOf(ready(1, "x"))))
        assertEquals(Blocked.Empty, blocked("", emptyList()))
        assertEquals(Blocked.Attaching, blocked("", listOf(Attachment(1, "a", state = AttachmentState.Queued))))
    }

    @Test
    fun sendingTakesTheAttachmentsOutOfTheComposer() {
        val c = watching().copy(attachments = listOf(ready(1, "x1"), ready(2, "x2")))
        val sent = Replies.sent(c, 1, "look", c.attachments)
        assertEquals(emptyList<Attachment>(), sent.attachments)
        assertEquals(listOf("x1", "x2"), sent.replies.single().attachmentIds)
    }

    @Test
    fun confirmedByWhatTheDesktopWroteNotByTheTypedTextAlone() {
        val written = "look\n\n(The user attached 1 file from their phone; read it with the Read tool: `/s/x1-a.jpg`)"
        val c = Replies.answered(Replies.sent(watching(), 1, "look", listOf(ready(1, "x1"))), 1, SendOutcome.Submitted, 0, written)
        assertEquals(1, ConversationReducer.apply(c, update(user("u1", "look")), 0).replies.size)
        assertEquals(0, ConversationReducer.apply(c, update(user("u1", written)), 0).replies.size)
    }

    @Test
    fun anOlderAnswerWithoutTextStillConfirmsByTheTypedText() {
        val c = send(watching(), "hello")
        assertEquals(0, ConversationReducer.apply(c, update(user("u1", "hello")), 0).replies.size)
    }

    @Test
    fun aLostAnswerWithAttachmentsIsConfirmedByTextAndEveryId() {
        val c = Replies.answered(Replies.sent(watching(), 1, "two", listOf(ready(1, "x1"), ready(2, "x2"))), 1, SendOutcome.Unknown, 0)
        val missing = user("u1", "two\n\n(The user attached 2 files: `/s/x1-a.jpg`)")
        assertEquals(1, ConversationReducer.apply(c, update(missing), 0).replies.size)
        val both = user("u2", "two\n\n(The user attached 2 files: `/s/x1-a.jpg`, `/s/x2-b.jpg`)")
        assertEquals(0, ConversationReducer.apply(c, update(both), 0).replies.size)
    }

    @Test
    fun anAttachmentsOnlyReplyIsNotConfirmedByAnEmptyEntry() {
        val c = Replies.sent(watching(), 1, "", listOf(ready(1, "x1")))
        assertEquals(1, ConversationReducer.apply(c, update(user("u1", "")), 0).replies.size)
        assertEquals(0, ConversationReducer.apply(c, update(user("u2", "(The user attached 1 file: `/s/x1-a.jpg`)")), 0).replies.size)
    }

    @Test
    fun theEntryArrivingBeforeTheAnswerConfirmsAReplyWithAttachments() {
        val written = "see\n\n(… `/s/x1-a.jpg`)"
        var c = Replies.sent(watching(), 1, "see", listOf(ready(1, "x1")))
        // The entry arrives while the reply is still sending; the answer then finds it among the entries held.
        c = c.copy(entries = c.entries + user("u9", written))
        c = Replies.answered(c, 1, SendOutcome.Submitted, 0, written)
        assertEquals(0, c.replies.size)
    }

    @Test
    fun twoIdenticalPlainRepliesAreNotBothConfirmedByOneEntry() {
        var c = send(watching(), "same", id = 1)
        c = send(c, "same", id = 2)
        assertEquals(1, ConversationReducer.apply(c, update(user("u1", "same")), 0).replies.size)
    }

    @Test
    fun aRefusalReturnsTextAndReadyAttachments() {
        val c = Replies.answered(Replies.sent(watching(), 1, "hi", listOf(ready(1, "x1"))), 1, SendOutcome.Refused(RemoteError.WAIT_STATE), 0)
        assertEquals("hi", c.draft)
        assertEquals(listOf("x1"), c.attachments.map { it.ready!!.attachmentId })
        assertNull(c.attachNote)
    }

    @Test
    fun notFoundReturnsThemReadyThenFailsTheDoubtfulOnesAtTheSecond() {
        val now = 1_000L
        val three = listOf(ready(1, "x1", completedAt = now), ready(2, "x2", presumed = true, completedAt = now), ready(3, "x3", completedAt = now))
        var c = Replies.answered(Replies.sent(watching(), 1, "hi", three), 1, SendOutcome.Refused(RemoteError.NOT_FOUND), now)
        assertEquals(AttachNote.SEND_NOT_FOUND, c.attachNote)
        assertTrue(c.attachments.all { it.state is AttachmentState.Ready })
        c = Replies.answered(Replies.sent(c, 2, "hi", c.attachments), 2, SendOutcome.Refused(RemoteError.NOT_FOUND), now)
        assertEquals(listOf(false, true, false), c.attachments.map { it.state is AttachmentState.Failed })

        val sure = listOf(ready(1, "x1", completedAt = now), ready(3, "x3", completedAt = now))
        var d = Replies.answered(Replies.sent(watching(), 1, "hi", sure), 1, SendOutcome.Refused(RemoteError.NOT_FOUND), now)
        d = Replies.answered(Replies.sent(d, 2, "hi", d.attachments), 2, SendOutcome.Refused(RemoteError.NOT_FOUND), now)
        assertTrue(d.attachments.all { it.state == AttachmentState.Failed(Uploads.GONE) })
    }

    @Test
    fun anUnknownOutcomeKeepsTheAttachmentsOnTheReply() {
        val c = Replies.answered(Replies.sent(watching(), 1, "hi", listOf(ready(1, "x1"))), 1, SendOutcome.Unknown, 0)
        assertEquals(emptyList<Attachment>(), c.attachments)
        assertEquals(1, c.replies.single().attachments.size)
    }

    @Test
    fun aRefusalAfterTheSessionIsGoneReturnsNoAttachments() {
        val sent = Replies.sent(watching(), 1, "hi", listOf(ready(1, "x1")))
        val c = Replies.answered(ConversationReducer.gone(sent), 1, SendOutcome.Refused(RemoteError.NOT_FOUND), 0)
        assertEquals(emptyList<Attachment>(), c.attachments)
        assertEquals("hi", c.draft)
    }

    @Test
    fun aFilledInPromptRefusesBeforeWritingAndSaysSoUntilTheNextSend() {
        val refused = send(watching(), "hi", outcome = SendOutcome.of(null, RemoteError.PREFILL_PENDING))
        assertEquals(SendOutcome.Refused(RemoteError.PREFILL_PENDING), SendOutcome.of(null, RemoteError.PREFILL_PENDING))
        assertEquals("hi", refused.draft)
        assertTrue(refused.prefillRefused)
        assertFalse(Replies.sent(refused, 2, "again").prefillRefused)
        assertFalse(send(watching(), "hi", outcome = SendOutcome.Refused(RemoteError.WAIT_STATE)).prefillRefused)
    }

    @Test
    fun anInboxPromptHoldsRepliesAfterAChoice() {
        assertEquals(Blocked.InboxPrompt, Replies.blocked(true, false, Watch.Watching, WaitState.READY, null, false, "hi", inboxPrompt = true))
        assertEquals(Blocked.InboxPrompt, Replies.blocked(true, false, Watch.Watching, WaitState.BUSY, null, false, "", inboxPrompt = true))
        assertEquals(Blocked.AwaitingChoice(null), Replies.blocked(true, false, Watch.Watching, WaitState.AWAITING_CHOICE, null, false, "hi", inboxPrompt = true))
        assertEquals(Blocked.NotConnected, Replies.blocked(false, false, Watch.Watching, WaitState.READY, null, false, "hi", inboxPrompt = true))
        assertNull(Replies.blocked(true, false, Watch.Watching, WaitState.READY, null, false, "hi", inboxPrompt = false))
    }

    /** The log names each outcome by a fixed name, the same in a shrunk build (`play-release` D3). */
    @Test
    fun sendOutcomesHaveTheirLogNames() {
        assertEquals("Submitted", SendOutcome.Submitted.logName)
        assertEquals("NotSubmitted", SendOutcome.NotSubmitted.logName)
        assertEquals("Refused", SendOutcome.Refused(RemoteError.WAIT_STATE).logName)
        assertEquals("Unknown", SendOutcome.Unknown.logName)
    }
}
