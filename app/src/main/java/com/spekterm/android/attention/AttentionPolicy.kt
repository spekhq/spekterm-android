package com.spekterm.android.attention

import com.spekterm.android.net.Attention
import com.spekterm.android.net.Position
import com.spekterm.android.net.WatchAnswer

/**
 * Turns the desktop's attention stream into notification actions (`attention-notifications`, design D7).
 * Pure: the notifier carries the actions out, in order.
 */
object AttentionPolicy {
    sealed interface Action {
        /** Post (or replace) the notification for [key]. Only an alerting post sounds or vibrates. */
        data class Notify(val key: String, val event: Attention, val alert: Boolean) : Action

        /** Remove the notification for [key]: the user is looking at that session on the desktop. */
        data class Cancel(val key: String) : Action

        /** One notification for an incomplete answer: [count] unsuppressed events, some may be missing. */
        data class Summarize(val count: Int) : Action

        /** Remember [position] as handled. Always the last action, after the notifications are posted. */
        data class Store(val position: Position) : Action
    }

    /** A session's events replace each other; inbox items are keyed by item; anything else by position. */
    fun key(event: Attention): String = when {
        event.sessionId != null -> "session:${event.sessionId}"
        event.intake != null -> "intake:${event.intake.adapter}:${event.intake.id}"
        else -> "pos:${event.pos.epoch}:${event.pos.seq}"
    }

    fun onLive(event: Attention): List<Action> = listOf(
        if (event.suppressed) Action.Cancel(key(event)) else Action.Notify(key(event), event, alert = true),
        Action.Store(event.pos),
    )

    fun onAnswer(answer: WatchAnswer): List<Action> {
        if (answer.events.isEmpty()) return emptyList()
        // Only each key's latest event: Android silently drops bursts of updates to one notification, which
        // could leave a stale `choice` showing over the `waiting` that followed it.
        val latest = LinkedHashMap<String, Attention>()
        for (event in answer.events) {
            val key = key(event)
            latest.remove(key)
            latest[key] = event
        }
        val actions = mutableListOf<Action>()
        if (answer.complete) {
            var alerted = false
            for ((key, event) in latest) {
                if (event.suppressed) {
                    actions += Action.Cancel(key)
                } else {
                    actions += Action.Notify(key, event, alert = !alerted)
                    alerted = true
                }
            }
        } else {
            for ((key, event) in latest) if (event.suppressed) actions += Action.Cancel(key)
            val unsuppressed = answer.events.count { !it.suppressed }
            if (unsuppressed > 0) actions += Action.Summarize(unsuppressed)
        }
        actions += Action.Store(answer.events.last().pos)
        return actions
    }
}
