package com.spekterm.android.desktop

import com.spekterm.android.desktop.SessionsView.RowState
import com.spekterm.android.net.SessionList

/**
 * The sessions each list shows newly ready and looked at (`session-relations` D8): a running agent ready with
 * `seen: true` now that was not so in the previous list of this connection. Pure; [reset] on every new connection,
 * so its first list counts every such session.
 */
class SeenTransitions {
    private var before = emptySet<String>()

    fun next(list: SessionList): List<String> {
        val now = list.sessions.filter { SessionsView.state(it) == RowState.IDLE }.map { it.id }
        val turned = now.filter { it !in before }
        before = now.toSet()
        return turned
    }

    fun reset() {
        before = emptySet()
    }
}
