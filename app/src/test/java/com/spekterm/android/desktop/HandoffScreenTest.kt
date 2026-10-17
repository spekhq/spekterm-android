package com.spekterm.android.desktop

import com.spekterm.android.desktop.HandoffView.Shows
import com.spekterm.android.net.HandoffEntry
import com.spekterm.android.net.HandoffReport
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoffScreenTest {
    private val withIt = SessionList(true, emptyList(), listOf(Session("s1", null, "claude", running = true)))
    private val noWindow = SessionList(false, emptyList(), emptyList())
    private val without = SessionList(true, emptyList(), emptyList())

    private fun of(connected: Boolean = true, paused: Boolean = false, list: SessionList? = withIt, current: Boolean = true) =
        HandoffView.of(connected, paused, SessionsState(list, current), "s1")

    @Test
    fun precedence() {
        assertEquals(Shows.PAUSED, of(connected = false, paused = true, list = noWindow))
        assertEquals(Shows.NOT_CONNECTED, of(connected = false, list = without))
        assertEquals(Shows.NO_WINDOW, of(list = noWindow))
        assertEquals(Shows.CLOSED, of(list = without))
        assertEquals(Shows.CONTENT, of())
        // A list not from this connection says nothing about the session.
        assertEquals(Shows.CONTENT, of(list = without, current = false))
    }

    @Test
    fun aWindowReloadKeepsTheEarlierRelations() {
        assertEquals(Shows.NO_WINDOW, of(list = noWindow))
        assertEquals(withIt, HandoffView.relationsList(SessionsState(noWindow, current = true), kept = withIt))
        assertEquals(without, HandoffView.relationsList(SessionsState(without, current = true), kept = withIt))
        assertNull(HandoffView.relationsList(SessionsState(null), kept = null))
    }

    @Test
    fun theLatestReportIsTheNewerOne() {
        val older = HandoffReport("old", 1)
        val entryNewer = HandoffEntry("s1", "done", "new", 2)
        assertEquals(HandoffReport("new", 2), HandoffView.latestReport(older, entryNewer))
        assertEquals(HandoffReport("brief", 3), HandoffView.latestReport(HandoffReport("brief", 3), entryNewer))
        assertEquals(older, HandoffView.latestReport(older, null))
        assertEquals(older, HandoffView.latestReport(older, HandoffEntry("s1", "working")))
        // A brief the desktop answered NOT_FOUND: the list's report still shows.
        assertEquals(HandoffReport("new", 2), HandoffView.latestReport(null, entryNewer))
        assertNull(HandoffView.latestReport(null, null))
        assertEquals(HandoffReport("brief", null), HandoffView.latestReport(HandoffReport("brief", null), HandoffEntry("s1", summary = "listed")))
    }
}
