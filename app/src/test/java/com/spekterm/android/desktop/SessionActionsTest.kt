package com.spekterm.android.desktop

import com.spekterm.android.desktop.SessionActions.Action
import com.spekterm.android.net.Folder
import com.spekterm.android.net.Lineage
import com.spekterm.android.net.Operations
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session menu (design D8) and the action dialog's state machine (D9). */
class SessionActionsTest {
    private val running = Session("s1", null, "claude", running = true)
    private val dormant = Session("s2", null, "claude", running = false)
    private val shell = Session("s3", null, "shell", running = true)
    private val all = setOf(Operations.RENAME, Operations.HIBERNATE, Operations.WAKE, Operations.CLOSE)

    @Test
    fun itemsPerOperationAndState() {
        assertEquals(emptyList<Action>(), SessionActions.of(running, emptySet()))
        assertEquals(listOf(Action.RENAME), SessionActions.of(running, setOf(Operations.RENAME, Operations.WAKE)))
        assertEquals(listOf(Action.RENAME), SessionActions.of(dormant, setOf(Operations.RENAME)))
        assertEquals(listOf(Action.RENAME), SessionActions.of(shell, setOf(Operations.RENAME)))
        assertEquals(listOf(Action.RENAME, Action.HIBERNATE, Action.CLOSE), SessionActions.of(running, all))
        assertEquals(listOf(Action.RENAME, Action.HIBERNATE, Action.CLOSE), SessionActions.of(shell, all))
        assertEquals(listOf(Action.RENAME, Action.WAKE, Action.CLOSE), SessionActions.of(dormant, all))
        assertEquals(listOf(Action.HIBERNATE), SessionActions.of(running, setOf(Operations.HIBERNATE, Operations.WAKE)))
        assertEquals(listOf(Action.WAKE), SessionActions.of(dormant, setOf(Operations.HIBERNATE, Operations.WAKE)))
        assertEquals(listOf(Action.CLOSE), SessionActions.of(dormant, setOf(Operations.CLOSE)))
        assertEquals(emptyList<Action>(), SessionActions.of(dormant, setOf(Operations.HIBERNATE)))
    }

    @Test
    fun handoffComesFirstForARelatedSessionWhateverTheDesktopAccepts() {
        val folders = listOf(Folder("f1", "alpha"))
        val parent = Session("p", "f1", "claude", running = true)
        val child = Session("c", "f1", "claude", running = true, lineage = Lineage("p"))
        val list = SessionList(true, folders, listOf(parent, child))
        assertEquals(listOf(Action.HANDOFF), SessionActions.of(child, emptySet(), list))
        assertEquals(listOf(Action.HANDOFF, Action.RENAME, Action.HIBERNATE, Action.CLOSE), SessionActions.of(child, all, list))
        assertEquals(listOf(Action.HANDOFF), SessionActions.of(parent, emptySet(), list))
        assertEquals(emptyList<Action>(), SessionActions.of(running, emptySet(), list))
        // Its only child closed: no relation left.
        assertEquals(emptyList<Action>(), SessionActions.of(parent, emptySet(), SessionList(true, folders, listOf(parent))))
        // A parent in a folder no longer in the workspace does not exist for relations.
        val stray = Session("q", "gone", "claude", running = true)
        assertEquals(emptyList<Action>(), SessionActions.of(stray, emptySet(), SessionList(true, folders, listOf(stray, Session("k", "f1", "claude", lineage = Lineage("q"))))))
    }

    @Test
    fun handoffIsUsableWhileTheOthersAreNot() {
        assertTrue(SessionActions.itemEnabled(Action.HANDOFF, enabled = false))
        assertFalse(SessionActions.itemEnabled(Action.RENAME, enabled = false))
        assertTrue(SessionActions.itemEnabled(Action.CLOSE, enabled = true))
    }

    @Test
    fun enabledOnlyWhenTheListIsCurrentWithAWindow() {
        val list = SessionList(true, emptyList(), listOf(running))
        assertTrue(SessionActions.enabled(true, false, SessionsState(list, current = true)))
        assertFalse(SessionActions.enabled(false, false, SessionsState(list, current = true)))
        assertFalse(SessionActions.enabled(true, true, SessionsState(list, current = true)))
        assertFalse(SessionActions.enabled(true, false, SessionsState(list, current = false)))
        assertFalse(SessionActions.enabled(true, false, SessionsState(list.copy(window = false), current = true)))
    }

    private fun busy(reason: String?) = RemoteError(RemoteError.SESSION_BUSY, "", reason?.let { JsonObject(mapOf("reason" to JsonPrimitive(it))) })

    @Test
    fun hibernateStartsUnforcedAndForcesOnlyAfterAgreeing() {
        val start = SessionAction.start(SessionAction.Kind.HIBERNATE, "s1", "A")
        assertEquals(SessionAction.Working(SessionAction.Kind.HIBERNATE, "s1", "A", force = false), start)
        val busy = SessionAction.answered(start as SessionAction.Working, busy("working"))
        assertEquals(SessionAction.Busy(SessionAction.Kind.HIBERNATE, "s1", "A", "working"), busy)
        val forced = SessionAction.agreed(busy as SessionAction.Busy)
        assertEquals(SessionAction.Working(SessionAction.Kind.HIBERNATE, "s1", "A", force = true), forced)
        assertEquals(SessionAction.Done(SessionAction.Kind.HIBERNATE, "s1", "A"), SessionAction.answered(forced as SessionAction.Working, null))
        // A busy answer to a forced request is an error, never another busy question.
        assertTrue(SessionAction.answered(forced, busy("working")) is SessionAction.Failed)
    }

    @Test
    fun closeAsksFirst() {
        val start = SessionAction.start(SessionAction.Kind.CLOSE, "s1", "A")
        assertEquals(SessionAction.Confirm(SessionAction.Kind.CLOSE, "s1", "A"), start)
        assertEquals(SessionAction.Working(SessionAction.Kind.CLOSE, "s1", "A", force = false), SessionAction.confirmed(start as SessionAction.Confirm))
        assertEquals(SessionAction.Working(SessionAction.Kind.WAKE, "s1", "A", force = false), SessionAction.start(SessionAction.Kind.WAKE, "s1", "A"))
    }

    @Test
    fun busyTexts() {
        assertEquals("The agent is working.", SessionAction.busyText("working"))
        assertEquals("The agent is waiting for an answer to a choice.", SessionAction.busyText("choice"))
        assertEquals("A message to the agent has not been confirmed sent yet.", SessionAction.busyText("unsent"))
        assertEquals("A command is running in it.", SessionAction.busyText("job"))
        assertEquals("The desktop can't tell whether it is idle.", SessionAction.busyText("unknown"))
        assertEquals("The desktop can't tell whether it is idle.", SessionAction.busyText("later"))
        assertEquals("The desktop can't tell whether it is idle.", SessionAction.busyText(null))
        val working = SessionAction.Working(SessionAction.Kind.CLOSE, "s1", "A", force = false)
        assertEquals(SessionAction.Busy(SessionAction.Kind.CLOSE, "s1", "A", null), SessionAction.answered(working, busy(null)))
    }

    @Test
    fun errorTexts() {
        val h = SessionAction.Kind.HIBERNATE
        assertEquals("This session no longer exists.", SessionAction.errorText(h, RemoteError(RemoteError.NOT_FOUND)))
        assertEquals("spekterm has no open window on the desktop.", SessionAction.errorText(h, RemoteError(RemoteError.NO_WINDOW)))
        assertEquals("The desktop did not answer in time; it may still happen.", SessionAction.errorText(h, RemoteError(RemoteError.TIMEOUT)))
        assertEquals("The desktop could not do it. (boom)", SessionAction.errorText(h, RemoteError(RemoteError.CREATE_FAILED, "boom")))
        assertEquals("Not connected to the desktop. The session list shows whether it happened.", SessionAction.errorText(h, RemoteError(Desktop.NOT_CONNECTED)))
        assertEquals("Could not hibernate it (BAD_PARAMS).", SessionAction.errorText(h, RemoteError(RemoteError.BAD_PARAMS)))
        assertEquals("Could not close it (INTERNAL).", SessionAction.errorText(SessionAction.Kind.CLOSE, RemoteError(RemoteError.INTERNAL)))
        assertEquals("spekterm has no open window on the desktop. Open it there first.", SessionAction.errorText(SessionAction.Kind.WAKE, RemoteError(RemoteError.NO_WINDOW)))
    }

    private val close = SessionAction.Kind.CLOSE
    private val keep = SessionAction.Kind.KEEP

    @Test
    fun anOfferIsARunningAgentMarkedByTheDesktop() {
        val offered = running.copy(closeOffered = true)
        assertTrue(SessionActions.offered(offered))
        assertFalse(SessionActions.offered(running))
        assertFalse(SessionActions.offered(dormant.copy(closeOffered = true)))
        assertFalse(SessionActions.offered(shell.copy(closeOffered = true)))

        val both = all + Operations.KEEP
        assertEquals(listOf(close, keep), SessionActions.offerActions(offered, both))
        assertEquals(listOf(close), SessionActions.offerActions(offered, all))
        assertEquals(listOf(keep), SessionActions.offerActions(offered, setOf(Operations.KEEP)))
        assertEquals(emptyList<SessionAction.Kind>(), SessionActions.offerActions(running, both))
        assertEquals(emptyList<SessionAction.Kind>(), SessionActions.offerActions(dormant.copy(closeOffered = true), both))
        // The menu is not changed by an offer.
        assertEquals(SessionActions.of(running, both), SessionActions.of(offered, both))
    }

    @Test
    fun anOffersCloseSkipsTheConfirmationButABusyAnswerStillAsks() {
        val start = SessionAction.fromOffer(close, "s1", "A")
        assertEquals(SessionAction.Working(close, "s1", "A", force = false), start)
        val busy = SessionAction.answered(start, busy("working"))
        assertEquals(SessionAction.Busy(close, "s1", "A", "working"), busy)
        assertEquals(SessionAction.Working(close, "s1", "A", force = true), SessionAction.agreed(busy as SessionAction.Busy))
        assertEquals(SessionAction.Done(close, "s1", "A"), SessionAction.answered(start, null))
        // The menu's close still asks first.
        assertTrue(SessionAction.start(close, "s1", "A") is SessionAction.Confirm)
    }

    @Test
    fun keepIsNeverBusyAndHasItsOwnTexts() {
        val start = SessionAction.fromOffer(keep, "s1", "A")
        assertEquals(SessionAction.Working(keep, "s1", "A", force = false), start)
        assertEquals(SessionAction.Done(keep, "s1", "A"), SessionAction.answered(start, null))
        assertEquals(
            SessionAction.Failed(keep, "s1", "A", "Could not keep it (SESSION_BUSY)."),
            SessionAction.answered(start, busy("working")),
        )
        assertEquals("This session no longer exists.", SessionAction.errorText(keep, RemoteError(RemoteError.NOT_FOUND)))
        assertEquals("spekterm has no open window on the desktop.", SessionAction.errorText(keep, RemoteError(RemoteError.NO_WINDOW)))
        assertEquals("The desktop did not answer in time; its card may still show there.", SessionAction.errorText(keep, RemoteError(RemoteError.TIMEOUT)))
        assertEquals("The desktop could not do it. (boom)", SessionAction.errorText(keep, RemoteError(RemoteError.CREATE_FAILED, "boom")))
        assertEquals("Not connected to the desktop. The session list shows whether it happened.", SessionAction.errorText(keep, RemoteError(Desktop.NOT_CONNECTED)))
        assertEquals("Could not keep it (BAD_PARAMS).", SessionAction.errorText(keep, RemoteError(RemoteError.BAD_PARAMS)))
    }
}
