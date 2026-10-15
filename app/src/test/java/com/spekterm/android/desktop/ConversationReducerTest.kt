package com.spekterm.android.desktop

import com.spekterm.android.net.ConversationUpdate
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Folder
import com.spekterm.android.net.Pending
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import com.spekterm.android.net.WaitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationReducerTest {
    private fun text(uuid: String, body: String = uuid) = Entry.Text(uuid, 0, body)
    private fun update(vararg events: Entry, reset: Boolean, truncated: Boolean = false, status: String = "ok") =
        ConversationUpdate("s1", status, events.toList(), truncated, reset)
    private fun apply(c: Conversation, u: ConversationUpdate) = ConversationReducer.apply(c, u, now = 0)
    private val empty = Conversation("s1")

    @Test
    fun aReplacingUpdateReplacesAndBringsItsTruncated() {
        val held = apply(empty, update(text("a"), reset = true, truncated = false))
        val replaced = apply(held, update(text("b"), text("c"), reset = true, truncated = true))
        assertEquals(listOf("b", "c"), replaced.entries.map { it.uuid })
        assertTrue(replaced.truncated)
        assertEquals(Phase.OK, replaced.phase)
    }

    @Test
    fun anAppendingUpdateAppendsAndKeepsTruncated() {
        val first = apply(empty, update(text("a"), reset = true, truncated = true))
        val appended = apply(first, update(text("b"), reset = false, truncated = false))
        assertEquals(listOf("a", "b"), appended.entries.map { it.uuid })
        assertTrue("a resumed or appended update does not clear the notice", appended.truncated)
        val notTruncated = apply(apply(empty, update(text("a"), reset = true)), update(text("b"), reset = false, truncated = true))
        assertFalse("an appended update's flag is not taken either", notTruncated.truncated)
    }

    @Test
    fun anAppendRedeliveringHeldRecordsAddsNothingTwice() {
        // One record: text and a tool call sharing its uuid; a previous follow's update overlaps the resume.
        val record = listOf(text("r1", "hello"), Entry.Tool("r1", 0, "t", "Bash", "ls"))
        val held = apply(empty, update(*record.toTypedArray(), reset = true))
        val again = apply(held, update(*(record + text("r2")).toTypedArray(), reset = false))
        assertEquals(listOf("r1", "r1", "r2"), again.entries.map { it.uuid })
        val keys = ConversationReducer.keys(again.entries)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun keysAreUniqueForEntriesSharingAUuid() {
        val entries = listOf(text("u"), Entry.Thinking("u", 0, "t"), Entry.Tool("u", 0, "i", "Read", null), text("v"))
        assertEquals(listOf("u#0", "u#1", "u#2", "v#0"), ConversationReducer.keys(entries))
    }

    @Test
    fun theCapDropsTheOldestAndMarksTruncated() {
        val many = (1..ConversationReducer.CAP).map { text("u$it") }
        val full = apply(empty, update(*many.toTypedArray(), reset = true))
        assertFalse(full.truncated)
        val over = apply(full, update(text("new"), reset = false))
        assertEquals(ConversationReducer.CAP, over.entries.size)
        assertEquals("u2", over.entries.first().uuid)
        assertTrue(over.truncated)
    }

    @Test
    fun statusMapsToThePhase() {
        assertEquals(Phase.UNAVAILABLE, apply(empty, update(reset = true, status = "unavailable")).phase)
        assertEquals(Phase.CATCHING_UP, apply(empty, update(reset = true, status = "attaching")).phase)
    }

    @Test
    fun afterUuidIsTheLastHeldEntrys() {
        assertNull(empty.afterUuid)
        assertEquals("b", apply(empty, update(text("a"), text("b"), reset = true)).afterUuid)
    }

    @Test
    fun aReplacingUpdateAfterAppendsReplacesEverything() {
        var c = apply(empty, update(text("a"), reset = true))
        c = apply(c, update(text("b"), reset = false))
        c = apply(c, update(text("x"), reset = true))
        assertEquals(listOf("x"), c.entries.map { it.uuid })
    }

    @Test
    fun disconnectKeepsEntriesAndForgetsTheWaitState() {
        var c = ConversationReducer.answered(ConversationReducer.requested(empty), null)
        c = apply(c, update(text("a"), reset = true))
        c = ConversationReducer.waited(c, WaitState.READY, null)
        val d = ConversationReducer.disconnected(c)
        assertEquals(listOf("a"), d.entries.map { it.uuid })
        assertEquals(Phase.DISCONNECTED, d.phase)
        assertEquals(WaitState.UNKNOWN, d.wait)
        assertEquals(Watch.Idle, d.watch)
    }

    @Test
    fun aRequestStartsCatchingUpWithAnUnknownWaitState() {
        val c = ConversationReducer.requested(ConversationReducer.waited(empty, WaitState.READY, null))
        assertEquals(Watch.Requested, c.watch)
        assertTrue(c.watch.active)
        assertEquals(Phase.CATCHING_UP, c.phase)
        assertEquals(WaitState.UNKNOWN, c.wait)
    }

    @Test
    fun theWaitStateKeepsPendingOnlyWhileAwaitingAChoice() {
        val choice = ConversationReducer.waited(empty, "awaiting-choice", Pending("Bash", "touch /tmp/x"))
        assertEquals(Pending("Bash", "touch /tmp/x"), choice.pending)
        assertNull(ConversationReducer.waited(choice, "busy", Pending("x")).pending)
        assertEquals(WaitState.UNKNOWN, ConversationReducer.waited(empty, "new-state", null).wait)
    }

    @Test
    fun aLaterReportOfTheSameChoiceWithoutAToolKeepsTheToolShown() {
        val choice = ConversationReducer.waited(empty, "awaiting-choice", Pending("Bash", "mkdir -p /tmp/x"))
        assertEquals(Pending("Bash", "mkdir -p /tmp/x"), ConversationReducer.waited(choice, "awaiting-choice", null).pending)
        assertEquals(Pending("Bash", "mkdir -p /tmp/x"), ConversationReducer.waited(choice, "awaiting-choice", Pending("")).pending)
        assertEquals(Pending("Edit", "a.kt"), ConversationReducer.waited(choice, "awaiting-choice", Pending("Edit", "a.kt")).pending)
        val answered = ConversationReducer.waited(choice, "busy", null)
        assertNull(ConversationReducer.waited(answered, "awaiting-choice", null).pending)
    }

    @Test
    fun notFoundIsDormantUntilTheSessionTurnsRunning() {
        val listed = { running: Boolean -> SessionList(true, emptyList(), listOf(Session("s1", null, "claude", running = running))) }
        var c = ConversationReducer.answered(ConversationReducer.requested(empty), RemoteError.NOT_FOUND)
        assertTrue(c.notRunning)
        assertEquals(Watch.Idle, c.watch)
        c = ConversationReducer.listed(c, listed(true))
        assertTrue("a list that was already running does not clear it", c.notRunning)
        c = ConversationReducer.listed(ConversationReducer.listed(c, listed(false)), listed(true))
        assertFalse(c.notRunning)
    }

    @Test
    fun aRefusalIsRetriedOnlyWhenTheSessionTurnsRunning() {
        val listed = { running: Boolean -> SessionList(true, emptyList(), listOf(Session("s1", null, "claude", running = running))) }
        var c = ConversationReducer.listed(empty, listed(true))
        c = ConversationReducer.answered(ConversationReducer.requested(c), "INTERNAL")
        assertEquals(Watch.Refused("INTERNAL"), c.watch)
        c = ConversationReducer.listed(c, listed(true))
        assertEquals(Watch.Refused("INTERNAL"), c.watch)
        c = ConversationReducer.listed(ConversationReducer.listed(c, listed(false)), listed(true))
        assertEquals(Watch.Idle, c.watch)
        assertEquals(Watch.Idle, ConversationReducer.cleared(ConversationReducer.answered(empty, "TIMEOUT")).watch)
    }

    @Test
    fun endedIsClearedByAListWithAWindow() {
        val ended = ConversationReducer.ended(ConversationReducer.answered(ConversationReducer.requested(empty), null))
        assertEquals(Phase.WINDOW_GONE, ended.phase)
        assertEquals(Phase.WINDOW_GONE, ConversationReducer.listed(ended, SessionList(false, emptyList(), emptyList())).phase)
        val back = ConversationReducer.listed(ended, SessionList(true, listOf(Folder("f")), listOf(Session("s1", "f", "claude", running = false))))
        assertEquals(Phase.IDLE, back.phase)
    }
}
