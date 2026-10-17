package com.spekterm.android.desktop

import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import org.junit.Assert.assertEquals
import org.junit.Test

class SeenTransitionsTest {
    private fun s(id: String, wait: String = "ready", seen: Boolean? = null, running: Boolean = true, target: String = "claude") =
        Session(id, null, target, running = running, wait = wait, seen = seen)

    private fun list(vararg sessions: Session) = SessionList(true, emptyList(), sessions.toList())

    @Test
    fun unseenThenSeenGivesTheSessionOnce() {
        val t = SeenTransitions()
        assertEquals(emptyList<String>(), t.next(list(s("a", seen = false))))
        assertEquals(listOf("a"), t.next(list(s("a", seen = true))))
        assertEquals(emptyList<String>(), t.next(list(s("a", seen = true))))
    }

    @Test
    fun theFirstListOfAConnectionCountsEverySeenSession() {
        val t = SeenTransitions()
        assertEquals(listOf("a"), t.next(list(s("a", seen = true), s("b", seen = false))))
        t.reset()
        assertEquals(listOf("a"), t.next(list(s("a", seen = true))))
    }

    @Test
    fun seenAgainAfterAnotherTurnGivesItAgain() {
        val t = SeenTransitions()
        t.next(list(s("a", seen = true)))
        assertEquals(emptyList<String>(), t.next(list(s("a", wait = "busy"))))
        assertEquals(listOf("a"), t.next(list(s("a", seen = true))))
    }

    @Test
    fun withoutSeenOrNotReadyNothingCounts() {
        val t = SeenTransitions()
        // A desktop older than `seen`.
        assertEquals(emptyList<String>(), t.next(list(s("a"))))
        assertEquals(emptyList<String>(), t.next(list(s("b", wait = "busy"))))
        assertEquals(emptyList<String>(), t.next(list(s("c", seen = true, running = false), s("d", seen = true, target = "shell"))))
    }
}
