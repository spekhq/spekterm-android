package com.spekterm.android.desktop

import com.spekterm.android.R
import com.spekterm.android.net.BackgroundTask
import com.spekterm.android.net.ConversationEnded
import com.spekterm.android.net.Folder
import com.spekterm.android.net.HandoffEntry
import com.spekterm.android.net.Lineage
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import com.spekterm.android.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun aHandoffListIsAppliedOnlyIfNewerAndTheStampsRestartPerConnection() {
        val first = HandoffsState().applied(3, listOf(HandoffEntry("s1", "working")))!!
        assertTrue(first.current)
        assertEquals(null, first.applied(2, listOf(HandoffEntry("s1", "done")))) // the older answer, completed later
        assertEquals(null, first.applied(3, emptyList()))
        val newer = first.applied(4, listOf(HandoffEntry("s1", "done", "ok", 9)))!!
        assertEquals("ok", newer.entries["s1"]!!.summary)
        val detached = newer.detached()
        assertFalse(detached.current)
        assertEquals(newer.entries, detached.entries)
        val again = detached.attached() // a new connection: any stamp goes, the entries stay until then
        assertEquals(newer.entries, again.entries)
        assertTrue(again.applied(1, emptyList())!!.entries.isEmpty())
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
        val ended = ConversationReducer.ended(Conversation("a1", watch = Watch.Watching), ConversationEnded.WINDOW)
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
    fun aGroupCarriesItsFoldersOpenSpecMark() {
        val marked = list.copy(folders = listOf(Folder("f1", "alpha", hasOpenSpec = true), Folder("f2", "beta")))
        assertEquals(listOf(false, true, false, false), SessionsView.groups(marked).map { it.hasOpenSpec })
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
    fun aReadyAgentSomeoneLookedAtIsIdle() {
        fun state(wait: String, seen: Boolean?, running: Boolean = true) = SessionsView.state(Session("s", null, "claude", running = running, wait = wait, seen = seen))
        assertEquals(SessionsView.RowState.IDLE, state("ready", true))
        assertEquals(SessionsView.RowState.READY, state("ready", false))
        // An older desktop sends no `seen`: ready stays waiting.
        assertEquals(SessionsView.RowState.READY, state("ready", null))
        assertEquals(SessionsView.RowState.BUSY, state("busy", true))
        assertEquals(SessionsView.RowState.DORMANT, state("ready", true, running = false))
    }

    @Test
    fun anIdleRowCountsNowhereButAnOfferedOneStillCountsAsAnOffer() {
        val seen = SessionList(
            true,
            listOf(Folder("f1", "alpha")),
            listOf(
                Session("u", "f1", "claude", running = true, wait = "ready", seen = false),
                Session("s", "f1", "claude", running = true, wait = "ready", seen = true),
                Session("o", "f1", "claude", running = true, wait = "ready", seen = true, closeOffered = true),
            ),
        )
        val group = SessionsView.groups(seen).single()
        assertEquals(SessionsView.Summary(choice = 0, ready = 1, offers = 1), group.summary)
        assertEquals(listOf(SessionsView.RowState.READY, SessionsView.RowState.IDLE, SessionsView.RowState.IDLE), group.rows.map { it.state })
    }

    @Test
    fun aSummaryCountsEachSessionOnceChoiceThenOfferThenReady() {
        val offers = SessionList(
            true,
            listOf(Folder("f1", "alpha")),
            listOf(
                Session("o", "f1", "claude", running = true, wait = "ready", closeOffered = true),
                Session("r", "f1", "claude", running = true, wait = "ready"),
                Session("c", "f1", "claude", running = true, wait = "awaiting-choice"),
                Session("oc", "f1", "claude", running = true, wait = "awaiting-choice", closeOffered = true),
                Session("ob", "f1", "claude", running = true, wait = "busy", closeOffered = true),
                Session("od", "f1", "claude", running = false, wait = "ready", closeOffered = true),
            ),
        )
        val group = SessionsView.groups(offers).single()
        assertEquals(SessionsView.Summary(choice = 2, ready = 1, offers = 2), group.summary)
        assertEquals(listOf(true, false, false, true, true, false), group.rows.map { it.offered })
        assertEquals(SessionsView.RowState.DORMANT, group.rows.last().state)
    }

    @Test
    fun choicesHoldGlobalThenEveryFolderEvenWithoutSessions() {
        val choices = SessionsView.choices(list, globalName = "Global")
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

    // ---- `prompts-attachments-rename` (design D10) ----

    private fun watched(id: String) = ConversationReducer.answered(ConversationReducer.requested(Conversation(id)), null)

    @Test
    fun closedOrExitedShowsClosedAtOnceWhileTheListStillHoldsTheSession() {
        for (reason in listOf(ConversationEnded.CLOSED, ConversationEnded.EXITED)) {
            val ended = ConversationReducer.ended(watched("g1"), reason)
            assertEquals(reason, Screen.Closed, screen(conversation = ended))
        }
    }

    @Test
    fun hibernatedShowsDormantNeverNoWindow() {
        val ended = ConversationReducer.ended(watched("g1"), ConversationEnded.HIBERNATED)
        assertEquals(Screen.Dormant, screen(conversation = ended))
        val unknown = ConversationReducer.ended(watched("g1"), "something-new")
        assertEquals(Screen.Dormant, screen(conversation = unknown))
    }

    @Test
    fun aSessionsScreenShowsTheStateItsRowShows() {
        fun on(wait: String) = watched("g1").copy(wait = wait)
        val ready = Session("g1", null, "claude", running = true, wait = "ready")
        assertEquals(SessionsView.RowState.IDLE, SessionsView.screenState(on("ready"), ready.copy(seen = true)))
        assertEquals(SessionsView.RowState.READY, SessionsView.screenState(on("ready"), ready.copy(seen = false)))
        assertEquals(SessionsView.RowState.READY, SessionsView.screenState(on("ready"), ready))
        assertEquals(SessionsView.RowState.READY, SessionsView.screenState(on("ready"), null))
        assertEquals(SessionsView.RowState.BUSY, SessionsView.screenState(on("busy"), ready.copy(seen = true)))
        assertEquals(SessionsView.RowState.CHOICE, SessionsView.screenState(on("awaiting-choice"), ready.copy(seen = true)))
        assertEquals(null, SessionsView.screenState(on("unknown"), ready))
        assertEquals(null, SessionsView.screenState(Conversation("g1", wait = "ready"), ready.copy(seen = true)))
    }

    @Test
    fun backgroundWorkIsShownOnlyForARunningAgent() {
        val tasks = listOf(BackgroundTask("t1", BackgroundTask.Kind.SHELL, "npm run dev"))
        assertEquals(tasks, SessionsView.background(Session("a", null, "claude", running = true, wait = "ready", background = tasks)))
        assertEquals(emptyList<BackgroundTask>(), SessionsView.background(Session("d", null, "claude", running = false, background = tasks)))
        assertEquals(emptyList<BackgroundTask>(), SessionsView.background(Session("s", null, "shell", running = true, background = tasks)))
    }

    @Test
    fun aMonitorIsNotShownAsBackgroundWork() {
        val shell = BackgroundTask("t1", BackgroundTask.Kind.SHELL, "npm run dev")
        val monitor = BackgroundTask("m1", BackgroundTask.Kind.MONITOR, "live updates for artifact https://claude.ai/artifact/abc")
        assertEquals(listOf(shell), SessionsView.background(Session("a", null, "claude", running = true, wait = "ready", background = listOf(monitor, shell))))
        assertEquals(emptyList<BackgroundTask>(), SessionsView.background(Session("b", null, "claude", running = true, wait = "ready", background = listOf(monitor))))
    }

    @Test
    fun backgroundCountsInAFixedOrderAndTheirLine() {

        val tasks = listOf(BackgroundTask.Kind.SHELL, BackgroundTask.Kind.AGENT, BackgroundTask.Kind.SHELL, BackgroundTask.Kind.OTHER).mapIndexed { i, kind -> BackgroundTask("t$i", kind) }
        val counts = SessionsView.backgroundCounts(tasks)
        assertEquals(listOf(BackgroundTask.Kind.AGENT to 1, BackgroundTask.Kind.SHELL to 2, BackgroundTask.Kind.OTHER to 1), counts)
        assertEquals(emptyList<Pair<BackgroundTask.Kind, Int>>(), SessionsView.backgroundCounts(emptyList()))
        assertEquals(
            UiText.Res(
                R.string.sessions_background,
                UiText.JoinedRes(
                    listOf(
                        UiText.Plural(R.plurals.sessions_background_agent, 1),
                        UiText.Plural(R.plurals.sessions_background_shell, 2),
                        UiText.Plural(R.plurals.sessions_background_other, 1),
                    ),
                    R.string.sessions_background_separator,
                ),
            ),
            SessionsView.backgroundLine(counts),
        )
        assertNull(SessionsView.backgroundLine(emptyList()))
    }

    // `session-state-summary`

    private fun agent(id: String, wait: String?, folder: String? = "f1", seen: Boolean? = null, running: Boolean = true, offered: Boolean = false, parent: String? = null) =
        Session(id, folder, "claude", running = running, wait = wait, seen = seen, closeOffered = offered, lineage = parent?.let { Lineage(it) })

    private fun of(vararg sessions: Session) =
        SessionsView.groups(SessionList(window = true, folders = listOf(Folder("f1", "alpha"), Folder("f2", "beta")), sessions = sessions.toList()))

    @Test
    fun summaryCountsEachStateInOrder() {
        val groups = of(
            agent("dormant", "ready", running = false),
            agent("idle", "ready", seen = true),
            Session("shell", "f1", "shell", running = true),
            agent("busy", "busy", folder = "f2"),
            agent("ready", "ready", seen = false),
            agent("choice", "awaiting-choice", folder = null),
        )
        assertEquals(
            listOf(SessionsView.Bucket.NEEDS_YOU to 2, SessionsView.Bucket.WORKING to 1, SessionsView.Bucket.RUNNING to 1, SessionsView.Bucket.IDLE to 1, SessionsView.Bucket.DORMANT to 1),
            SessionsView.summary(groups),
        )
    }

    @Test
    fun anOfferNeedsTheUserWhateverTheWaitAndCountsOnce() {
        val groups = of(
            agent("ob", "busy", offered = true),
            agent("os", "ready", seen = true, offered = true),
            agent("oc", "awaiting-choice", offered = true),
            agent("unknown", "unknown"),
        )
        assertEquals(listOf(SessionsView.Bucket.NEEDS_YOU to 3, SessionsView.Bucket.RUNNING to 1), SessionsView.summary(groups))
        assertTrue(SessionsView.filtered(groups, SessionsView.Bucket.WORKING).isEmpty())
    }

    @Test
    fun onlyOneState() {
        assertEquals(listOf(SessionsView.Bucket.WORKING to 2), SessionsView.summary(of(agent("a", "busy"), agent("b", "busy", folder = "f2"))))
    }

    @Test
    fun needsYouEqualsWhatTheCollapsedHeadingsSay() {
        val groups = of(
            agent("c", "awaiting-choice"), agent("oc", "awaiting-choice", offered = true), agent("ob", "busy", offered = true),
            agent("os", "ready", seen = true, offered = true), agent("r", "ready", folder = "f2"), agent("s", "ready", seen = true, folder = "f2"),
            agent("b", "busy", folder = null), agent("d", "ready", running = false, folder = null),
        )
        val headings = groups.sumOf { it.summary.choice + it.summary.offers + it.summary.ready }
        assertEquals(headings, SessionsView.summary(groups).first { it.first == SessionsView.Bucket.NEEDS_YOU }.second)
        assertEquals(5, headings)
    }

    @Test
    fun theSelectedStateKeepsItsPlaceAtZero() {
        val summary = listOf(SessionsView.Bucket.NEEDS_YOU to 1, SessionsView.Bucket.RUNNING to 2, SessionsView.Bucket.DORMANT to 1)
        assertEquals(
            listOf(SessionsView.Bucket.NEEDS_YOU to 1, SessionsView.Bucket.RUNNING to 2, SessionsView.Bucket.IDLE to 0, SessionsView.Bucket.DORMANT to 1),
            SessionsView.chips(summary, SessionsView.Bucket.IDLE),
        )
        assertEquals(summary, SessionsView.chips(summary, null))
        assertEquals(summary, SessionsView.chips(summary, SessionsView.Bucket.RUNNING))
        assertEquals(listOf(SessionsView.Bucket.NEEDS_YOU to 0), SessionsView.chips(emptyList(), SessionsView.Bucket.NEEDS_YOU))
    }

    @Test
    fun filteredKeepsTheListsOrderFlat() {
        // The desktop's order is A, D, B; the tree shows A, B (under A), D.
        val groups = of(agent("A", "ready"), agent("D", "ready"), agent("B", "ready", parent = "A"), agent("x", "busy", folder = "f2"))
        val filtered = SessionsView.filtered(groups, SessionsView.Bucket.NEEDS_YOU)
        assertEquals(1, filtered.size)
        assertEquals(listOf("A", "B", "D"), filtered.single().rows.map { it.session.id })
        assertTrue(filtered.single().rows.all { it.depth == 0 && !it.nested })
    }

    @Test
    fun aWaitingChildUnderAWorkingSourceComesAlone() {
        val groups = of(agent("src", "busy"), agent("child", "ready", parent = "src"))
        assertEquals(1, groups.single().rows.last().depth)
        val row = SessionsView.filtered(groups, SessionsView.Bucket.NEEDS_YOU).single().rows.single()
        assertEquals("child", row.session.id)
        assertEquals(0, row.depth)
        assertFalse(row.nested)
    }
}
