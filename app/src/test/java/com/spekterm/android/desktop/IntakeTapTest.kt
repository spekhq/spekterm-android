package com.spekterm.android.desktop

import com.spekterm.android.desktop.IntakeTap.Route
import com.spekterm.android.net.InboxItem
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import org.junit.Assert.assertEquals
import org.junit.Test

/** `inbox` task 2.2: where an inbox item's notification opens (design D9). */
class IntakeTapTest {
    private val pending = InboxItem("m", "slack", InboxItem.State.PENDING)
    private val accepted = InboxItem("h", "handoff", InboxItem.State.ACCEPTED, sessionId = "s1")
    private fun inbox(vararg items: InboxItem, current: Boolean = true) = InboxState(items.toList(), current = current)
    private fun sessions(vararg ids: String, current: Boolean = true, window: Boolean = true) =
        SessionsState(SessionList(window, emptyList(), ids.map { Session(it, "f1", Session.CLAUDE, running = true) }), current = current)

    @Test
    fun aPendingItemOpensTheInboxAtIt() {
        assertEquals(Route.Inbox("slack/m"), IntakeTap.route("slack/m", inbox(pending), sessions()))
        assertEquals(Route.Inbox("slack/m"), IntakeTap.route("slack/m", inbox(pending, current = false), SessionsState()))
    }

    @Test
    fun anAcceptedItemOpensItsConversationOnlyOnceItsSessionIsListed() {
        assertEquals(Route.Conversation("s1"), IntakeTap.route("handoff/h", inbox(accepted), sessions("s1")))
        assertEquals(Route.Sessions, IntakeTap.route("handoff/h", inbox(accepted), sessions("s2")))
        assertEquals(Route.Sessions, IntakeTap.route("handoff/h", inbox(accepted), sessions("s1", current = false)))
        assertEquals(Route.Sessions, IntakeTap.route("handoff/h", inbox(accepted), sessions("s1", window = false)))
    }

    @Test
    fun anItemNotHeldOpensTheSessionListOrANotCurrentInbox() {
        assertEquals(Route.Sessions, IntakeTap.route("handoff/x", inbox(pending), sessions()))
        assertEquals(Route.Inbox(null), IntakeTap.route("handoff/x", inbox(current = false), SessionsState()))
    }
}
