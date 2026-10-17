package com.spekterm.android.desktop

import com.spekterm.android.net.HandoffEntry
import com.spekterm.android.net.HandoffReport
import com.spekterm.android.net.SessionList

/** The handoff screen's decisions (`session-relations` design D4). */
object HandoffView {
    /** What the screen shows first, in precedence order (as [Screen] for the conversation screen). */
    enum class Shows { PAUSED, NOT_CONNECTED, NO_WINDOW, CLOSED, CONTENT }

    fun of(connected: Boolean, paused: Boolean, sessions: SessionsState, sessionId: String): Shows {
        if (paused) return Shows.PAUSED
        if (!connected) return Shows.NOT_CONNECTED
        val list = sessions.list?.takeIf { sessions.current } ?: return Shows.CONTENT
        if (!list.window) return Shows.NO_WINDOW
        if (list.sessions.none { it.id == sessionId }) return Shows.CLOSED
        return Shows.CONTENT
    }

    /**
     * The list the relations are read from: the shown one when it has a window, else [kept] — the last one that had
     * one — so a desktop without a window (macOS after closing it) never empties the screen.
     */
    fun relationsList(sessions: SessionsState, kept: SessionList?): SessionList? =
        sessions.list?.takeIf { it.window } ?: kept

    /**
     * The latest report: of the brief's and the handoff list's, the one with the later time (a missing time counts
     * as oldest; on a tie the brief's), so a brief still on its way never shows an older report than the list has.
     */
    fun latestReport(brief: HandoffReport?, entry: HandoffEntry?): HandoffReport? {
        val listed = entry?.summary?.let { HandoffReport(it, entry.reportedAt) }
        if (brief == null) return listed
        if (listed == null) return brief
        return if ((listed.reportedAt ?: Long.MIN_VALUE) > (brief.reportedAt ?: Long.MIN_VALUE)) listed else brief
    }
}
