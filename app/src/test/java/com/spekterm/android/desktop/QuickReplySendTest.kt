package com.spekterm.android.desktop

import com.spekterm.android.net.Pending
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.WaitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quick-reply gate and its record and settling (`quick-replies` D1). */
class QuickReplySendTest {
    private fun watching(wait: String = WaitState.READY) =
        ConversationReducer.waited(ConversationReducer.answered(ConversationReducer.requested(Conversation("s1")), null), wait, null, 0)
    private fun ready(localId: Long) = Attachment(localId, "f$localId.jpg", 10, state = AttachmentState.Ready("a$localId", 0, false))
    private fun gate(c: Conversation?, connected: Boolean = true, paused: Boolean = false, inbox: Boolean = false, switch: PendingSwitch? = null, sentence: String = "Can this session be closed now?") =
        Replies.quickBlocked(c, connected, paused, inbox, switch, sentence)
    private val field = listOf(ready(1), Attachment(2, "b.png", state = AttachmentState.Uploading()))
    private fun typing() = watching().copy(draft = "half a reply", attachments = field, attachNote = AttachNote.SEND_NOT_FOUND)

    @Test
    fun theGateRefusesAsATypedReplyWould() {
        val c = watching()
        assertEquals(Blocked.NotConnected, gate(c, connected = false))
        assertEquals(Blocked.Paused, gate(c, paused = true))
        assertEquals(Blocked.NotWatching, gate(Conversation("s1", wait = WaitState.READY)))
        assertEquals(Blocked.NotWatching, gate(null))
        val pending = Pending("Bash", "rm -rf build")
        assertEquals(Blocked.AwaitingChoice(pending), gate(watching(WaitState.AWAITING_CHOICE).copy(pending = pending)))
        assertEquals(Blocked.Unknown, gate(watching(WaitState.UNKNOWN)))
        assertEquals(Blocked.InFlight, gate(Replies.sent(c, 1, "hi")))
        val holding = PendingSwitch(1, Switch.Model("sonnet"), "Opus 5.5", 0, emptySet(), state = PendingSwitch.State.Submitted)
        assertEquals(Blocked.ModelSwitch(holding), gate(c, switch = holding))
        assertEquals(Blocked.InboxPrompt, gate(c, inbox = true))
        val withheld = Replies.answered(Replies.sent(c, 1, "npm test", command = true), 1, SendOutcome.NotSubmitted, 0)
        assertEquals(Blocked.CommandWaits("npm test"), gate(withheld))
        assertEquals(Blocked.BareCommand, gate(c, sentence = "/model"))
    }

    @Test
    fun theGateIgnoresTheFieldAndItsAttachments() {
        assertNull(gate(watching()))
        assertNull(gate(watching(WaitState.BUSY)))
        // An attachment uploading or failed blocks a typed reply, never a quick one.
        assertNull(gate(typing()))
        assertNull(gate(watching().copy(attachments = listOf(Attachment(3, "c", state = AttachmentState.Failed("TOO_MANY"))))))
    }

    @Test
    fun aQuickReplyLeavesTheFieldAlone() {
        val start = typing().copy(commandRefused = CommandRefused("ls", RemoteError.WAIT_STATE), prefillRefused = true, quickRefused = RemoteError.WAIT_STATE)
        val c = Replies.sent(start, 1, "Can this session be closed now?", quick = true)
        assertEquals("half a reply", c.draft)
        assertEquals(field, c.attachments)
        assertEquals(AttachNote.SEND_NOT_FOUND, c.attachNote)
        assertEquals(start.draftReturns, c.draftReturns)
        assertEquals(start.commandRefused, c.commandRefused)
        assertFalse(c.prefillRefused)
        assertNull(c.quickRefused)
        val reply = c.replies.single()
        assertTrue(reply.quick)
        assertTrue(reply.attachments.isEmpty())
        assertEquals(Reply.State.SENDING, reply.state)
    }

    @Test
    fun aRefusedQuickReplyIsSaidNotPutInTheField() {
        for (code in listOf(RemoteError.WAIT_STATE, RemoteError.PREFILL_PENDING, RemoteError.NOT_FOUND)) {
            val sent = Replies.sent(typing(), 1, "Can this session be closed now?", quick = true)
            val c = Replies.answered(sent, 1, SendOutcome.Refused(code), 0)
            assertTrue(code, c.replies.isEmpty())
            assertEquals(code, "half a reply", c.draft)
            assertEquals(code, field, c.attachments)
            assertEquals(code, AttachNote.SEND_NOT_FOUND, c.attachNote)
            assertEquals(code, 0, c.draftReturns)
            assertEquals(code, code, c.quickRefused)
            assertFalse(code, c.prefillRefused)
        }
    }

    @Test
    fun otherOutcomesAreATypedReplys() {
        val sent = Replies.sent(typing(), 1, "hi", quick = true)
        assertEquals(Reply.State.SUBMITTED, Replies.answered(sent, 1, SendOutcome.Submitted, 5).replies.single().state)
        assertEquals(Reply.State.NOT_SUBMITTED, Replies.answered(sent, 1, SendOutcome.NotSubmitted, 5).replies.single().state)
        assertEquals(Reply.State.MAY_NOT_HAVE_BEEN_SENT, Replies.answered(sent, 1, SendOutcome.Unknown, 5).replies.single().state)
    }

    @Test
    fun theNextSendClearsTheRefusal() {
        val refused = watching().copy(quickRefused = RemoteError.WAIT_STATE)
        assertNull(Replies.sent(refused, 1, "typed").quickRefused)
        assertNull(Replies.sent(refused, 1, "ls", command = true).quickRefused)
        assertNull(Replies.sent(refused, 1, "again", quick = true).quickRefused)
    }

    @Test
    fun aKeptSlashCommandRecordsWhetherCommandsShow() {
        val c = Replies.sent(watching().copy(usage = com.spekterm.android.net.AgentUsage("s1")), 1, "/simplify", quick = true)
        assertTrue(c.replies.single().commandsShown)
        val submitted = Replies.answered(c, 1, SendOutcome.Submitted, 0)
        assertEquals(Reply.Label.COMMAND_NOT_SHOWN, submitted.replies.single().label(Replies.NOT_SEEN_MS))
    }
}
