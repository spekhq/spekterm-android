package com.spekterm.android.desktop

import com.spekterm.android.desktop.SessionActions.Action
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
}
