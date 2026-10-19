package com.spekterm.android.desktop

import com.spekterm.android.net.Folder
import com.spekterm.android.net.InboxItem
import com.spekterm.android.net.ItemRef
import com.spekterm.android.net.Operations
import com.spekterm.android.net.Prefill
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `inbox` tasks 1.2–1.5: the held inbox, its sections and decisions, the outcomes and the opening wait. */
class InboxTest {
    private fun item(
        id: String,
        state: InboxItem.State = InboxItem.State.PENDING,
        scheduledAt: Long? = null,
        sessionId: String? = null,
        folderId: String? = null,
        unresolved: InboxItem.Unresolved? = null,
        receivedAt: Long = 1,
        bodyLength: Long = 1,
    ) = InboxItem(id, "a", state, body = "b", bodyLength = bodyLength, receivedAt = receivedAt, sessionId = sessionId, folderId = folderId, unresolved = unresolved, scheduledAt = scheduledAt)

    private val folders = listOf(Folder("f1", "one"), Folder("f2", "two"))
    private fun list(vararg sessions: Session, window: Boolean = true) = SessionList(window, folders, sessions.toList())
    private fun agent(id: String, folderId: String? = "f1", running: Boolean = true, prefill: Prefill? = null) =
        Session(id, folderId, Session.CLAUDE, running = running, prefill = prefill)
    private fun current(list: SessionList?) = SessionsState(list, current = true)

    // ---- 1.2 InboxState ----

    @Test
    fun anOlderOrEqualStampIsDroppedAndAConnectionKeepsTheItems() {
        val first = InboxState().applied(2, listOf(item("x")), at = 5)!!
        assertEquals(InboxState(listOf(item("x")), current = true, seq = 2, receivedAt = 5), first)
        assertNull(first.applied(2, emptyList(), at = 6))
        assertNull(first.applied(1, emptyList(), at = 6))
        val detached = first.detached()
        assertEquals(listOf(item("x")), detached.items)
        assertFalse(detached.current)
        val attached = first.attached()
        assertEquals(listOf(item("x")), attached.items)
        assertFalse(attached.current)
        assertEquals(InboxState(emptyList(), true, 1, 7), attached.applied(1, emptyList(), at = 7))
    }

    // ---- 1.3 InboxView ----

    @Test
    fun scheduledItemsAreEarliestFirstWithTiesInListOrder() {
        val items = listOf(item("late", scheduledAt = 30), item("w"), item("tie1", scheduledAt = 10), item("past", scheduledAt = 1), item("tie2", scheduledAt = 10))
        assertEquals(listOf("past", "tie1", "tie2", "late"), InboxView.scheduled(items).map { it.id })
    }

    @Test
    fun waitingExcludesScheduledAndAcceptedAndKeepsListOrder() {
        val items = listOf(item("b"), item("s", scheduledAt = 1), item("acc", InboxItem.State.ACCEPTED, sessionId = "s1"), item("a"), item("o", InboxItem.State.OTHER))
        assertEquals(listOf("b", "a"), InboxView.waiting(items).map { it.id })
    }

    @Test
    fun openedFollowsTheCurrentListOrKeepsAllAsLastKnown() {
        val items = listOf(
            item("listed", InboxItem.State.ACCEPTED, sessionId = "s1"),
            item("gone", InboxItem.State.ACCEPTED, sessionId = "s2"),
            item("unlisted-folder", InboxItem.State.ACCEPTED, sessionId = "s3"),
            item("no-session", InboxItem.State.ACCEPTED),
            item("pending", sessionId = "s1"),
        )
        val l = list(agent("s1"), agent("s3", folderId = "f9"))
        assertEquals(listOf("listed"), InboxView.opened(items, current(l)).map { it.id })
        val all = listOf("listed", "gone", "unlisted-folder")
        assertEquals(all, InboxView.opened(items, SessionsState(l, current = false)).map { it.id })
        assertEquals(all, InboxView.opened(items, current(list(window = false))).map { it.id })
        assertEquals(all, InboxView.opened(items, SessionsState()).map { it.id })
    }

    @Test
    fun anItemIsExpiredOnlyWithNeitherArrivalNorBody() {
        assertTrue(InboxView.expired(item("x", receivedAt = 0, bodyLength = 0)))
        assertFalse(InboxView.expired(item("x", receivedAt = 0, bodyLength = 3)))
        assertFalse(InboxView.expired(item("x", receivedAt = 4, bodyLength = 0)))
    }

    @Test
    fun theTitleIsShownApartOnlyWhenTheBodyDoesNotHoldIt() {
        assertFalse(InboxView.showTitle("Deploy is red", "@me Deploy is red\nmore"))
        assertFalse(InboxView.showTitle("Deploy is r…", "Deploy is red"))
        assertFalse(InboxView.showTitle("  ", "anything"))
        assertTrue(InboxView.showTitle("Summary", "the body"))
    }

    @Test
    fun preselectionFollowsTheDesktopsOrderAndNeverFallsBackFromAGonePick() {
        val l = list(agent("s1", folderId = "f2"), agent("s9", folderId = "f9"))
        val x = item("x", folderId = "f1", sessionId = "s1")
        assertEquals(InboxView.Preselection("f2"), InboxView.preselect("f2", item("x", folderId = "f1"), l))
        assertEquals(InboxView.Preselection(null, overrideGone = true), InboxView.preselect("f9", x, l))
        assertEquals(InboxView.Preselection("f2"), InboxView.preselect(null, x, l))
        assertEquals(InboxView.Preselection("f1"), InboxView.preselect(null, item("x", folderId = "f1", sessionId = "s9"), l))
        assertEquals(InboxView.Preselection(null), InboxView.preselect(null, item("x", folderId = "f9"), l))
        assertEquals(InboxView.Preselection(null), InboxView.preselect(null, item("x"), l))
    }

    @Test
    fun noFolderReasonSaysWhy() {
        val none = InboxView.Preselection(null)
        assertNull(InboxView.noFolderReason(item("x", folderId = "f1"), InboxView.Preselection("f1")))
        assertEquals(InboxView.NoFolder.CHOSEN_GONE, InboxView.noFolderReason(item("x", folderId = "f1"), InboxView.Preselection(null, true)))
        assertEquals(InboxView.NoFolder.RULE_FOLDER_GONE, InboxView.noFolderReason(item("x", unresolved = InboxItem.Unresolved.FOLDER_GONE), none))
        assertEquals(InboxView.NoFolder.RULE_FOLDER_GONE, InboxView.noFolderReason(item("x", folderId = "f9"), none))
        assertEquals(InboxView.NoFolder.CANNOT_TELL, InboxView.noFolderReason(item("x", unresolved = InboxItem.Unresolved.NO_MATCH), none))
        assertEquals(InboxView.NoFolder.CANNOT_TELL, InboxView.noFolderReason(item("x", unresolved = InboxItem.Unresolved.OTHER), none))
        assertNull(InboxView.noFolderReason(item("x", receivedAt = 0, bodyLength = 0), none))
    }

    @Test
    fun aScheduledHandoffRunsOnlyIntoAListedFolder() {
        val l = list()
        assertTrue(InboxView.runnable(item("x", scheduledAt = 1, folderId = "f1"), l))
        assertFalse(InboxView.runnable(item("x", scheduledAt = 1, folderId = "f9"), l))
        assertFalse(InboxView.runnable(item("x", scheduledAt = 1), l))
        assertFalse(InboxView.runnable(item("x", scheduledAt = 1, folderId = "f1"), null))
    }

    @Test
    fun gatesFollowTheOperationsTheConnectionAndTheWindow() {
        val all = setOf(Operations.INBOX_ACCEPT, Operations.INBOX_IGNORE, Operations.SUBMIT_PREFILL)
        val ok = current(list())
        assertEquals(InboxView.Gates(true, true, true), InboxView.gates(all, true, false, ok))
        assertEquals(InboxView.Gates(false, false, false), InboxView.gates(emptySet(), true, false, ok))
        assertEquals(InboxView.Gates(false, false, false), InboxView.gates(all, false, false, ok))
        assertEquals(InboxView.Gates(false, false, false), InboxView.gates(all, true, true, ok))
        assertEquals(InboxView.Gates(false, true, false), InboxView.gates(all, true, false, current(list(window = false))))
        assertEquals(InboxView.Gates(false, true, false), InboxView.gates(all, true, false, SessionsState(list(), current = false)))
        assertEquals(InboxView.Gates(true, false, false), InboxView.gates(setOf(Operations.INBOX_ACCEPT), true, false, ok))
    }

    @Test
    fun theNoteComesFromTheListedPromptOrAnAcceptedItem() {
        val prefill = Prefill("p1", "Read …", ItemRef("a", "m"), edited = false)
        val withPrefill = agent("s1", prefill = prefill)
        val accepted = item("m", InboxItem.State.ACCEPTED, sessionId = "s1")
        val items = listOf(accepted)
        val sessions = current(list(withPrefill))
        assertEquals(InboxView.Linked.Listed(prefill, accepted, shown = true), InboxView.linked(withPrefill, items, sessions, emptySet()))
        assertEquals(InboxView.Linked.Listed(prefill, null, shown = false), InboxView.linked(withPrefill, emptyList(), sessions, emptySet()))
        val plain = agent("s1")
        assertEquals(InboxView.Linked.Waiting(accepted, canSend = true), InboxView.linked(plain, items, sessions, setOf(Operations.SUBMIT_PREFILL)))
        assertEquals(InboxView.Linked.Waiting(accepted, canSend = false), InboxView.linked(plain, items, sessions, emptySet()))
        assertNull(InboxView.linked(agent("s1", running = false, prefill = prefill), items, sessions, emptySet()))
        assertNull(InboxView.linked(Session("s1", "f1", Session.SHELL, running = true), items, sessions, emptySet()))
        assertNull(InboxView.linked(agent("s2"), items, sessions, emptySet()))
        assertNull(InboxView.linked(null, items, sessions, emptySet()))
    }

    @Test
    fun repliesAreHeldForAListedPromptAndOnAnOlderDesktopUntilReplyAnyway() {
        val prefill = Prefill("p1", "t", ItemRef("a", "m"), edited = true)
        assertTrue(InboxView.holdsReplies(InboxView.Linked.Listed(prefill, null, false), replyAnyway = true))
        assertFalse(InboxView.holdsReplies(InboxView.Linked.Waiting(item("m"), canSend = true), replyAnyway = false))
        assertTrue(InboxView.holdsReplies(InboxView.Linked.Waiting(item("m"), canSend = false), replyAnyway = false))
        assertFalse(InboxView.holdsReplies(InboxView.Linked.Waiting(item("m"), canSend = false), replyAnyway = true))
        assertFalse(InboxView.holdsReplies(null, replyAnyway = false))
    }

    // ---- 1.4 InboxFailure, InboxLog ----

    private fun error(code: String, message: String = "m", state: String? = null) =
        RemoteError(code, message, state?.let { buildJsonObject { put("state", JsonPrimitive(it)) } })

    @Test
    fun eachCodeMapsToItsReasonByOperation() {
        assertEquals(InboxFailure(InboxFailure.Reason.CHOOSE_FOLDER), InboxFailure.of(InboxOp.ACCEPT, error(RemoteError.FOLDER_REQUIRED), true))
        assertEquals(InboxFailure(InboxFailure.Reason.COULD_NOT_OPEN, "the folder is gone"), InboxFailure.of(InboxOp.ACCEPT, error(RemoteError.NOT_FOUND, "the folder is gone"), true))
        assertEquals(InboxFailure(InboxFailure.Reason.NO_LONGER_IN_INBOX), InboxFailure.of(InboxOp.ACCEPT, error(RemoteError.NOT_FOUND), false))
        assertEquals(InboxFailure(InboxFailure.Reason.NO_LONGER_IN_INBOX), InboxFailure.of(InboxOp.IGNORE, error(RemoteError.NOT_FOUND), true))
        assertEquals(InboxFailure(InboxFailure.Reason.SESSION_NOT_RUNNING), InboxFailure.of(InboxOp.SUBMIT, error(RemoteError.NOT_FOUND), true))
        assertEquals(InboxFailure(InboxFailure.Reason.NO_WINDOW), InboxFailure.of(InboxOp.ACCEPT, error(RemoteError.NO_WINDOW), true))
        assertEquals(InboxFailure(InboxFailure.Reason.TIMEOUT), InboxFailure.of(InboxOp.ACCEPT, error(RemoteError.TIMEOUT), true))
        assertEquals(InboxFailure(InboxFailure.Reason.COULD_NOT_OPEN, "being opened"), InboxFailure.of(InboxOp.ACCEPT, error(RemoteError.CREATE_FAILED, "being opened"), true))
        assertEquals(InboxFailure(InboxFailure.Reason.COULD_NOT_OPEN, null), InboxFailure.of(InboxOp.ACCEPT, error(RemoteError.CREATE_FAILED, " "), true))
        assertEquals(InboxFailure(InboxFailure.Reason.PROMPT_GONE), InboxFailure.of(InboxOp.SUBMIT, error(RemoteError.PREFILL_GONE), true))
        assertEquals(InboxFailure(InboxFailure.Reason.PROMPT_EDITED), InboxFailure.of(InboxOp.SUBMIT, error(RemoteError.PREFILL_EDITED), true))
        assertEquals(InboxFailure(InboxFailure.Reason.AGENT_NOT_READY, "busy"), InboxFailure.of(InboxOp.SUBMIT, error(RemoteError.WAIT_STATE, state = "busy"), true))
        assertEquals(InboxFailure(InboxFailure.Reason.FAILED, code = "NEW_CODE"), InboxFailure.of(InboxOp.IGNORE, error("NEW_CODE"), true))
        assertEquals(InboxFailure(InboxFailure.Reason.NOT_CONNECTED), InboxFailure.of(InboxOp.ACCEPT, null, true))
    }

    @Test
    fun theLogHoldsOnlyTheOperationTheOutcomeAndTheCode() {
        for (op in InboxOp.entries) {
            val fields = InboxLog.fields(op, "failed", "NOT_FOUND")
            assertEquals(listOf("op", "outcome", "error"), fields.map { it.first })
            assertEquals(listOf(op.wire, "failed", "NOT_FOUND"), fields.map { it.second })
        }
    }

    // ---- 1.5 OpeningTracker ----

    private val conn = Any()
    private fun answered(list: SessionList? = null, attached: Any? = conn, onScreen: Boolean = true, id: Long = 1) =
        OpeningTracker.answered(emptyMap(), "a/x", id, "s1", conn, attached, list, onScreen)

    @Test
    fun anAnswerThenAListHoldingItOpensIt() {
        val waiting = answered()
        assertEquals(ItemAction.Opening("s1", 1, conn), waiting["a/x"])
        assertEquals(waiting, OpeningTracker.listed(waiting, list(), conn, true))
        assertEquals(waiting, OpeningTracker.listed(waiting, list(agent("s1"), window = false), conn, true))
        assertEquals(waiting, OpeningTracker.listed(waiting, list(agent("s1")), Any(), true))
        assertEquals(ItemAction.Opened("s1", true), OpeningTracker.listed(waiting, list(agent("s1")), conn, true)["a/x"])
        assertEquals(ItemAction.Opened("s1", true), OpeningTracker.listed(waiting, list(agent("s1", running = false)), conn, true)["a/x"])
        assertEquals(ItemAction.Opened("s1", false), OpeningTracker.listed(waiting, list(agent("s1")), conn, false)["a/x"])
    }

    @Test
    fun aListAlreadyHoldingItOpensAtOnceAndAGoneConnectionIsUnlisted() {
        assertEquals(ItemAction.Opened("s1", true), answered(list(agent("s1")))["a/x"])
        assertEquals(ItemAction.Unlisted("s1", connectionEnded = true), answered(attached = null)["a/x"])
    }

    @Test
    fun aTimeoutOrADetachEndsTheWait() {
        val waiting = answered(id = 2)
        assertEquals(waiting, OpeningTracker.timedOut(waiting, "a/x", 1))
        assertEquals(ItemAction.Unlisted("s1", connectionEnded = false), OpeningTracker.timedOut(waiting, "a/x", 2)["a/x"])
        assertEquals(waiting, OpeningTracker.detached(waiting, Any()))
        assertEquals(ItemAction.Unlisted("s1", connectionEnded = true), OpeningTracker.detached(waiting, conn)["a/x"])
    }

    @Test
    fun leavingTheInboxKeepsAnOpenedFromNavigating() {
        val opened = mapOf("a/x" to ItemAction.Opened("s1", true), "a/y" to ItemAction.InFlight(InboxOp.IGNORE))
        assertEquals(mapOf("a/x" to ItemAction.Opened("s1", false), "a/y" to ItemAction.InFlight(InboxOp.IGNORE)), OpeningTracker.leftInbox(opened))
    }
}
