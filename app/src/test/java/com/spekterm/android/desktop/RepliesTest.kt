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

    // ---- Slash commands (`agent-usage-and-skills` D5, D6) ----

    private fun withUsage(c: Conversation) = c.copy(usage = com.spekterm.android.net.AgentUsage("s1"))

    @Test
    fun aReplyRecordsWhetherTheDesktopShowsCommandsAtTheSend() {
        val shown = send(withUsage(watching()), "/simplify")
        assertTrue(shown.replies.single().commandsShown)
        assertFalse(send(watching(), "/simplify").replies.single().commandsShown)
        // A dropped connection clears the usage, not what the reply recorded.
        assertTrue(ConversationReducer.disconnected(shown).replies.single().commandsShown)
    }

    @Test
    fun aCommandNotShownSaysWhyOnADesktopThatShowsCommands() {
        val ready = send(withUsage(watching(WaitState.READY)), "/no-such-skill", at = 1_000).replies.single()
        assertEquals(Reply.Label.NOT_YET_CONFIRMED, ready.label(1_000 + 29_999))
        assertEquals(Reply.Label.COMMAND_NOT_SHOWN, ready.label(1_000 + 30_000))
        assertTrue(Reply.Label.COMMAND_NOT_SHOWN.settled)
        var busy = send(withUsage(watching(WaitState.BUSY)), "/simplify", at = 0)
        busy = waited(busy, WaitState.READY, 10_000)
        assertEquals(Reply.Label.COMMAND_NOT_SHOWN, busy.replies.single().label(40_000))
    }

    @Test
    fun otherRepliesKeepTheirMarks() {
        // An older desktop: a command that ran never matched there.
        assertEquals(Reply.Label.NOT_IN_CONVERSATION_YET, send(watching(), "/simplify").replies.single().label(30_000))
        assertEquals(Reply.Label.NOT_IN_CONVERSATION_YET, send(withUsage(watching()), "hello").replies.single().label(30_000))
        val long = "/simplify " + "x".repeat(800)
        assertEquals(Reply.Label.NOT_IN_CONVERSATION_YET, send(withUsage(watching()), long).replies.single().label(30_000))
        var busy = send(withUsage(watching(WaitState.BUSY)), "plain")
        busy = waited(busy, WaitState.READY, 0)
        assertEquals(Reply.Label.NOT_SHOWN_AFTER_WORK, busy.replies.single().label(30_000))
        val c = withUsage(watching())
        assertEquals(Reply.Label.SENDING, Replies.sent(c, 1, "/x").replies.single().label(60_000))
        assertEquals(Reply.Label.MAY_NOT_HAVE_BEEN_SENT, send(c, "/x", outcome = SendOutcome.Unknown).replies.single().label(60_000))
        assertEquals(Reply.Label.NOT_SUBMITTED, send(c, "/x", outcome = SendOutcome.NotSubmitted).replies.single().label(60_000))
    }

    @Test
    fun whatIsACommand() {
        for (t in listOf("/simplify", " /x", "/opsx:apply 1.2")) assertTrue(t, Replies.command(t))
        for (t in listOf("/", "/ x", "/\u3000x", "a/b", "", "x /y")) assertFalse(t, Replies.command(t))
    }

    @Test
    fun theLineTheDesktopWrites() {
        assertEquals("/x a b", Replies.normalized("  /x   a b "))
        assertEquals("/x a\nb", Replies.normalized("/x a\nb\n"))
        assertEquals("/x a", Replies.normalized("/x\u00a0\u3000a"))
        assertEquals("/x a", Replies.normalized("/x\n a"))
        assertEquals("/x", Replies.normalized(" /x "))
        // Anything else is only cleaned, as the desktop leaves it.
        assertEquals(" plain  text ", Replies.normalized(" plain\u0007  text "))
    }

    @Test
    fun aCommandLongerThanTheCliRunsIsAPaste() {
        val name = "/simplify "
        assertFalse(Replies.pastedCommand(name + "x".repeat(800 - name.length)))
        assertFalse(Replies.pastedCommand(name + "字".repeat(800 - name.length)))
        // 800 only once trimmed and collapsed.
        assertFalse(Replies.pastedCommand("  /simplify     " + "x".repeat(800 - name.length) + "   "))
        assertTrue(Replies.pastedCommand(name + "x".repeat(801 - name.length)))
        assertFalse(Replies.pastedCommand("x".repeat(801)))
        assertTrue(Replies.pastedCommand(name + "x".repeat(640), listOf("photo.jpg")))
        assertFalse(Replies.pastedCommand(name + "x".repeat(490), listOf("photo.jpg")))
    }

    private fun switchOf(s: Switch, state: PendingSwitch.State = PendingSwitch.State.Submitted, anyway: Boolean = false) =
        PendingSwitch(1, s, before = "Opus 5.5", sentAt = 1_000, held = emptySet(), state = state, anyway = anyway)
    private fun gate(text: String = "hi", switch: PendingSwitch? = null, inFlight: Boolean = false, wait: String = WaitState.READY, attachments: List<Attachment> = emptyList(), inbox: Boolean = false) =
        Replies.blocked(true, false, Watch.Watching, wait, null, inFlight, text, attachments, inbox, switch)

    @Test
    fun aModelSwitchHoldsRepliesFromItsSend() {
        val sending = switchOf(Switch.Model("sonnet"), PendingSwitch.State.Sending)
        assertEquals(Blocked.ModelSwitch(sending), gate(switch = sending))
        val held = switchOf(Switch.Model("sonnet"))
        // Said with an empty field, ahead of the attachments.
        assertEquals(Blocked.ModelSwitch(held), gate(text = "", switch = held))
        assertEquals(Blocked.ModelSwitch(held), gate(switch = held, attachments = listOf(Attachment(1, "a.png"))))
        assertFalse((gate(switch = held) as Blocked.ModelSwitch).late(10_999))
        assertTrue((gate(switch = held) as Blocked.ModelSwitch).late(11_000))
        assertNull(gate(switch = held.copy(anyway = true)))
        assertEquals(Blocked.InFlight, gate(switch = held, inFlight = true))
    }

    @Test
    fun anEffortSwitchAndASettledModelSwitchHoldNothing() {
        assertNull(gate(switch = switchOf(Switch.Effort("low"), PendingSwitch.State.Sending)))
        assertNull(gate(switch = switchOf(Switch.Model("sonnet"), PendingSwitch.State.Refused(RemoteError.WAIT_STATE))))
        assertNull(gate(switch = switchOf(Switch.Model("sonnet"), PendingSwitch.State.NotSubmitted)))
        // Confirmed switches leave the map; the gate still holds on the others.
        assertEquals(Blocked.AwaitingChoice(null), gate(wait = WaitState.AWAITING_CHOICE))
        assertEquals(Blocked.InboxPrompt, gate(inbox = true))
        assertEquals(Blocked.InFlight, gate(inFlight = true))
    }

    @Test
    fun aBareModelOrEffortCannotBeSent() {
        for (bare in listOf("/model", " /effort ", "/model\u3000")) assertEquals(bare, Blocked.BareCommand, gate(bare))
        for (fine in listOf("/model sonnet", "/models", "/Model")) assertNull(fine, gate(fine))
    }

    @Test
    fun aHeldTypedModelCommandWaitsForTheSwitchInsteadOfSettling() {
        val c = send(watching(), "/model sonnet")
        val reply = c.replies.single()
        assertEquals(Reply.Label.SWITCHING, reply.label(Replies.NOT_SEEN_MS + 1, holding = true))
        assertFalse(reply.label(Replies.NOT_SEEN_MS + 1, holding = true).settled)
        val p = PendingSwitch(1, Switch.Model("sonnet"), "Opus 5.5", 0, emptySet(), replyId = reply.id, state = PendingSwitch.State.Submitted)
        assertTrue(ConversationTimes.holding(c.copy(switch = p), reply))
        assertFalse(ConversationTimes.holding(c.copy(switch = p.copy(anyway = true)), reply))
        assertFalse(ConversationTimes.holding(c.copy(switch = p.copy(replyId = 99)), reply))
    }

    // ---- `run-shell-commands` (D1–D3) ----

    private fun command(c: Conversation, text: String, id: Long = 1, outcome: SendOutcome = SendOutcome.Submitted, at: Long = 0) =
        Replies.answered(Replies.sent(c, id, text, command = true), id, outcome, at)
    private fun commandGate(text: String = "ls", wait: String = WaitState.READY, inFlight: Boolean = false, inbox: Boolean = false, switch: PendingSwitch? = null, withheld: Reply? = null, attachments: List<Attachment> = emptyList()) =
        Replies.blocked(true, false, Watch.Watching, wait, null, inFlight, text, attachments, inbox, switch, command = true, withheld = withheld)

    @Test
    fun aCommandPassesTheRepliesGateWithoutAttachmentsOrModelChecks() {
        assertNull(commandGate())
        assertNull(commandGate(wait = WaitState.BUSY))
        assertEquals(Blocked.AwaitingChoice(null), commandGate(wait = WaitState.AWAITING_CHOICE))
        assertEquals(Blocked.Unknown, commandGate(wait = WaitState.UNKNOWN))
        assertEquals(Blocked.NotWatching, Replies.blocked(true, false, Watch.Requested, WaitState.READY, null, false, "ls", command = true))
        assertEquals(Blocked.InFlight, commandGate(inFlight = true))
        assertEquals(Blocked.InboxPrompt, commandGate(inbox = true))
        val held = switchOf(Switch.Model("sonnet"))
        assertEquals(Blocked.ModelSwitch(held), commandGate(switch = held))
        assertEquals(Blocked.Empty, commandGate(""))
        assertEquals(Blocked.Empty, commandGate(" \u0007\n "))
        assertEquals(Blocked.TooLong, commandGate("x".repeat(Replies.MAX_FRAME_BYTES)))
        // Not a bare `/model`, and the composer's attachments are not the command's.
        assertNull(commandGate("/model"))
        assertNull(commandGate(attachments = listOf(Attachment(1, "a", state = AttachmentState.Failed("TOO_MANY")))))
    }

    @Test
    fun aCommandIsConfirmedByItsShellRecordOnly() {
        val c = command(watching(), "git status")
        assertTrue(c.replies.single().command)
        for (record in listOf("<bash-input>git status</bash-input>", "<bash-input>   git status</bash-input>", " <bash-input>git status </bash-input>\n")) {
            assertTrue(record, ConversationReducer.apply(c, update(user("u", record)), 1).replies.isEmpty())
        }
        for (other in listOf("git status", "!git status", "<bash-input>git</bash-input>", "x <bash-input>git status</bash-input>")) {
            assertEquals(other, 1, ConversationReducer.apply(c, update(user("u", other)), 1).replies.size)
        }
        assertEquals(1, ConversationReducer.apply(c, update(user("u", "<bash-input>git status</bash-input>", meta = true)), 1).replies.size)
        // The desktop's written `!git status` does not stand in for the record.
        val written = Replies.answered(Replies.sent(watching(), 2, "pwd", command = true), 2, SendOutcome.Submitted, 0, "!pwd")
        assertEquals(1, ConversationReducer.apply(written, update(user("u", "!pwd")), 1).replies.size)
        assertTrue(ConversationReducer.apply(written, update(user("v", "<bash-input>pwd</bash-input>")), 1).replies.isEmpty())
    }

    @Test
    fun aCommandHoldingTheMarkerIsMatchedWhole() {
        val text = "echo '</bash-input>'"
        val c = command(watching(), text)
        assertEquals(text, Replies.ranCommand("<bash-input>$text</bash-input>"))
        assertTrue(ConversationReducer.apply(c, update(user("u", "<bash-input>$text</bash-input>")), 1).replies.isEmpty())
    }

    @Test
    fun aHeldShellRecordDoesNotConfirm() {
        var c = ConversationReducer.apply(watching(), update(user("old", "<bash-input>ls</bash-input>"), reset = true), 0)
        c = command(c, "ls")
        c = ConversationReducer.apply(c, update(user("old", "<bash-input>ls</bash-input>"), reset = true), 1)
        assertEquals(1, c.replies.size)
    }

    @Test
    fun aCommandRunsForUpToTenMinutesBeforeItIsCalledMissing() {
        val c = command(watching(), "sleep 40", at = 0)
        val reply = c.replies.single()
        assertEquals(Reply.Label.RUNNING, reply.label(31_000))
        assertFalse(Reply.Label.RUNNING.settled)
        assertEquals(Reply.Label.RUNNING, reply.label(Replies.COMMAND_NOT_SEEN_MS - 1))
        assertEquals(Reply.Label.NOT_IN_CONVERSATION_YET, reply.label(Replies.COMMAND_NOT_SEEN_MS))
        // A reply keeps its 30 s.
        assertEquals(Reply.Label.NOT_IN_CONVERSATION_YET, send(watching(), "hi").replies.single().label(Replies.NOT_SEEN_MS))
    }

    @Test
    fun aCommandSentWhileBusyWaitsThenRunsThenSettles() {
        var c = command(watching(WaitState.BUSY), "ls", at = 0)
        assertEquals(Reply.Label.WAITING_FOR_AGENT, c.replies.single().label(20 * 60_000))
        c = waited(c, WaitState.READY, 10_000)
        assertEquals(Reply.Label.RUNNING, c.replies.single().label(10_000 + 5 * 60_000))
        // A turn starting within the ten minutes is the agent still on its way, as for a reply.
        assertEquals(Reply.Label.WAITING_FOR_AGENT, waited(c, WaitState.BUSY, 10_000 + 5 * 60_000).replies.single().label(10_000 + 6 * 60_000))
        assertEquals(Reply.Label.NOT_SHOWN_AFTER_WORK, c.replies.single().label(10_000 + Replies.COMMAND_NOT_SEEN_MS))
    }

    @Test
    fun sendingACommandEndsCommandModeAndLeavesTheReplyAlone() {
        val start = watching().copy(
            draft = "half a reply", attachments = listOf(ready(1, "x")), attachNote = AttachNote.SEND_NOT_FOUND,
            commandMode = true, commandDraft = "ls", commandRefused = CommandRefused("old", RemoteError.WAIT_STATE), prefillRefused = true,
        )
        val c = Replies.sent(start, 1, "ls", command = true)
        assertFalse(c.commandMode)
        assertEquals("", c.commandDraft)
        assertNull(c.commandRefused)
        assertFalse(c.prefillRefused)
        assertEquals("half a reply", c.draft)
        assertEquals(start.attachments, c.attachments)
        assertEquals(AttachNote.SEND_NOT_FOUND, c.attachNote)
        assertTrue(c.replies.single().attachments.isEmpty())
        assertFalse(c.replies.single().commandsShown)
        // A reply sent later clears a refused command's notice too.
        assertNull(Replies.sent(start, 2, "hi").commandRefused)
    }

    @Test
    fun aRefusedCommandIsKeptAsideNotWrittenUnderTheField() {
        val sent = Replies.sent(watching().copy(draft = "typing"), 1, "npm test", command = true).copy(commandDraft = "cmd3")
        val c = Replies.answered(sent, 1, SendOutcome.Refused(RemoteError.WAIT_STATE), 0)
        assertTrue(c.replies.isEmpty())
        assertEquals(CommandRefused("npm test", RemoteError.WAIT_STATE), c.commandRefused)
        assertEquals("cmd3", c.commandDraft)
        assertFalse(c.commandMode)
        assertEquals("typing", c.draft)
        assertEquals(0, c.draftReturns)
    }

    @Test
    fun aWithheldCommandBlocksEverySendUntilCleared() {
        val c = command(watching(), "npm test", outcome = SendOutcome.NotSubmitted)
        val withheld = Replies.withheld(c)!!
        assertEquals(Blocked.CommandWaits("npm test"), commandGate(withheld = withheld))
        assertEquals(Blocked.CommandWaits("npm test"), Replies.blocked(true, false, Watch.Watching, WaitState.READY, null, false, "hi", withheld = withheld))
        // Paused and not connected still come first.
        assertEquals(Blocked.NotConnected, Replies.blocked(false, false, Watch.Watching, WaitState.READY, null, false, "hi", withheld = withheld))
        val cleared = c.copy(replies = c.replies.map { it.copy(cleared = true) })
        assertNull(Replies.withheld(cleared))
        // A withheld reply blocks nothing, as before.
        assertNull(Replies.withheld(send(watching(), "hi", outcome = SendOutcome.NotSubmitted)))
    }

    @Test
    fun aCodeSpanSuggestsACommandOnlyAsBangSpaceCommand() {
        assertEquals("npm login", Replies.suggested("! npm login"))
        assertEquals("ls", Replies.suggested(" !  ls "))
        assertEquals("gcloud auth login --x", Replies.suggested("! gcloud auth login --x"))
        // A code block's text may hold several lines.
        assertEquals("cd x &&\ngit push", Replies.suggested("! cd x &&\ngit push\n"))
        for (not in listOf("!important", "a != b", "!", "! ", "npm login", "!!")) assertNull(not, Replies.suggested(not))
    }
}
