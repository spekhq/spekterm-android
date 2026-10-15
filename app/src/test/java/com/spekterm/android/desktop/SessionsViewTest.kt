package com.spekterm.android.desktop

import com.spekterm.android.net.Folder
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionsViewTest {
    private val list = SessionList(
        window = true,
        folders = listOf(Folder("f1", "alpha"), Folder("f2", "beta"), Folder("empty", "unused")),
        sessions = listOf(
            Session("b1", "f2", "claude", running = true, wait = "busy"),
            Session("g1", null, "claude", running = true, wait = "ready"),
            Session("a1", "f1", "claude", running = false, wait = "ready"),
            Session("a2", "f1", "shell", running = true),
            Session("a3", "f1", "claude", running = true, wait = "awaiting-choice", lifecycle = "working"),
            Session("x1", "gone", "claude", running = true, wait = "unknown"),
        ),
    )

    @Test
    fun groupsGlobalFirstThenFoldersInOrderThenUnknownFolders() {
        val groups = SessionsView.groups(list)
        assertEquals(listOf(true, false, false, false), groups.map { it.global })
        assertEquals(listOf(null, "alpha", "beta", null), groups.map { it.name })
        assertEquals(listOf("a1", "a2", "a3"), groups[1].rows.map { it.session.id })
        assertEquals(listOf("x1"), groups[3].rows.map { it.session.id })
    }

    @Test
    fun rowStates() {
        val states = list.sessions.associate { it.id to SessionsView.state(it) }
        assertEquals(SessionsView.RowState.BUSY, states["b1"])
        assertEquals(SessionsView.RowState.READY, states["g1"])
        assertEquals(SessionsView.RowState.DORMANT, states["a1"])
        assertEquals(SessionsView.RowState.NONE, states["a2"])
        assertEquals(SessionsView.RowState.CHOICE, states["a3"])
        assertEquals(SessionsView.RowState.NONE, states["x1"])
    }

    @Test
    fun aListIsAppliedOnlyIfNewerAndTheClockResetsPerConnection() {
        val clock = ListClock()
        assertTrue(clock.accept(3)) // a newer event
        assertFalse(clock.accept(2)) // the older answer, completed later
        assertTrue(clock.accept(4))
        clock.reset() // a new connection: its stamps start again
        assertTrue(clock.accept(1))
    }

    private fun screen(
        connected: Boolean = true,
        paused: Boolean = false,
        sessions: SessionsState = SessionsState(list, current = true),
        conversation: Conversation? = Conversation("g1"),
        id: String = "g1",
    ) = Screen.of(connected, paused, sessions, conversation, id)

    @Test
    fun screenPrecedence() {
        val noWindow = SessionsState(SessionList(false, emptyList(), emptyList()), current = true)
        val dormant = Conversation("a1")
        assertEquals(Screen.Paused, screen(connected = false, paused = true, sessions = noWindow))
        assertEquals(Screen.NotConnected, screen(connected = false, sessions = noWindow))
        assertEquals(Screen.NoWindow, screen(sessions = noWindow, id = "missing"))
        assertEquals(Screen.NoWindow, screen(conversation = Conversation("g1", phase = Phase.WINDOW_GONE)))
        assertEquals(Screen.Closed, screen(id = "missing", conversation = Conversation("missing", notRunning = true)))
        assertEquals(Screen.Dormant, screen(id = "a1", conversation = dormant))
        assertEquals(Screen.Dormant, screen(conversation = Conversation("g1", notRunning = true)))
        assertEquals(Screen.Shell, screen(id = "a2", conversation = null))
        assertEquals(Screen.Content, screen())
    }

    @Test
    fun aNoWindowListNeverClosesTheSession() {
        val noWindow = SessionsState(SessionList(false, emptyList(), emptyList()), current = true)
        assertEquals(Screen.NoWindow, screen(sessions = noWindow))
    }

    @Test
    fun aListOfAnEarlierConnectionIsNotUsedToCallASessionClosed() {
        val stale = SessionsState(SessionList(true, emptyList(), emptyList()), current = false)
        assertEquals(Screen.Content, screen(sessions = stale))
    }

    @Test
    fun afterAWindowReloadTheSessionShowsDormantNotNoWindow() {
        val ended = ConversationReducer.ended(Conversation("a1", watch = Watch.Watching))
        val back = ConversationReducer.listed(ended, list)
        assertEquals(Screen.Dormant, screen(id = "a1", conversation = back))
    }

    @Test
    fun groupsHaveKeysBranchesAndSummaries() {
        val withBranch = list.copy(folders = listOf(Folder("f1", "alpha", branch = "feat/x"), Folder("f2", "beta")))
        val groups = SessionsView.groups(withBranch)
        assertEquals(listOf("global", "folder:f1", "folder:f2", "other"), groups.map { it.key })
        assertEquals(listOf(null, "feat/x", null, null), groups.map { it.branch })
    }

    @Test
    fun aSummaryCountsChoicesAndWaitingAgentsOnly() {
        val many = SessionList(
            true,
            listOf(Folder("f1", "alpha")),
            listOf(
                Session("c", "f1", "claude", running = true, wait = "awaiting-choice"),
                Session("r1", "f1", "claude", running = true, wait = "ready"),
                Session("r2", "f1", "claude", running = true, wait = "ready"),
                Session("d", "f1", "claude", running = false, wait = "ready"),
                Session("sh", "f1", "shell", running = true),
            ),
        )
        assertEquals(SessionsView.Summary(choice = 1, ready = 2), SessionsView.groups(many).single().summary)
    }

    @Test
    fun choicesHoldGlobalThenEveryFolderEvenWithoutSessions() {
        val choices = SessionsView.choices(list)
        assertEquals(listOf(null, "f1", "f2", "empty"), choices.map { it.folderId })
        assertEquals(listOf("Global", "alpha", "beta", "unused"), choices.map { it.name })
    }

    @Test
    fun startingASessionNeedsAConnectedCurrentListWithAWindowAndNoStartInProgress() {
        val current = SessionsState(list, current = true)
        assertTrue(SessionsView.canCreate(true, false, current, null))
        assertTrue(SessionsView.canCreate(true, false, current, Creation.Unlisted("a")))
        assertFalse(SessionsView.canCreate(false, false, current, null))
        assertFalse(SessionsView.canCreate(true, true, current, null))
        assertFalse(SessionsView.canCreate(true, false, current.copy(current = false), null))
        assertFalse(SessionsView.canCreate(true, false, SessionsState(SessionList(false, emptyList(), emptyList()), current = true), null))
        assertFalse(SessionsView.canCreate(true, false, SessionsState(), null))
        assertFalse(SessionsView.canCreate(true, false, current, Creation.Starting("a")))
    }
}
