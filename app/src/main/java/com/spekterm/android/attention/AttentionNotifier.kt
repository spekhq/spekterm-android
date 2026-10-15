package com.spekterm.android.attention

import android.content.Context
import androidx.core.app.NotificationCompat
import com.spekterm.android.R
import com.spekterm.android.attention.AttentionPolicy.Action
import com.spekterm.android.log.ConnectionLog
import com.spekterm.android.net.Attention
import com.spekterm.android.service.Notifications
import com.spekterm.android.service.Status
import com.spekterm.android.service.StatusBoard
import com.spekterm.android.store.PositionStore
import com.spekterm.android.ui.MainActivity

/**
 * Carries out [AttentionPolicy] actions (design D7). One notification per key, posted with `tag = key` so keys
 * never collide; text exactly as received; the position stored only after the notifications are posted.
 */
class AttentionNotifier(
    private val context: Context,
    /** The pairing whose position this notifier stores. */
    private val deviceId: String,
    private val positions: PositionStore,
    private val log: ConnectionLog,
    private val now: () -> Long = System::currentTimeMillis,
    /** False once the owning service is stopping: nothing more is posted or stored. */
    private val alive: () -> Boolean = { true },
) {
    /**
     * [events] are the events behind [actions], for the log; [source] is `live` or `answer`; [visible] is the
     * session whose conversation was on screen when the policy decided.
     */
    fun apply(actions: List<Action>, events: List<Attention>, source: String, visible: String? = null) {
        val receivedAt = now()
        val outcome = mutableMapOf<Attention, String>()
        for (action in actions) {
            if (!alive()) return
            when (action) {
                is Action.Notify -> {
                    post(action.key, action.event, action.alert)
                    outcome[action.event] = "notified"
                }
                is Action.Cancel -> Notifications.cancel(context, action.key, ID)
                is Action.Summarize -> summarize(action.count)
                is Action.Store -> positions.save(deviceId, action.position)
            }
        }
        val summarized = actions.any { it is Action.Summarize }
        for (event in events) {
            val result = outcome[event] ?: when {
                event.suppressed -> "suppressed"
                visible != null && event.sessionId == visible && !summarized -> "visible"
                summarized -> "summarized"
                else -> "replaced"
            }
            log.write(
                ConnectionLog.Kind.ATTENTION,
                "epoch" to event.pos.epoch,
                "seq" to event.pos.seq,
                "kind" to event.kind,
                "at" to event.at,
                "delayMs" to (receivedAt - event.at),
                "source" to source,
                "outcome" to result,
            )
            StatusBoard.recordEvent(Status.RecentEvent(event.kind, event.title, receivedAt, result))
        }
    }

    private fun post(key: String, event: Attention, alert: Boolean) {
        val headline = when (event.kind) {
            Attention.WAITING -> "Waiting for your input"
            Attention.CHOICE -> "Asks for a choice" + (event.tool?.let { ": $it" } ?: "")
            Attention.HANDOFF_DONE -> "Handoff finished"
            Attention.INTAKE -> "New inbox item"
            else -> "Needs your attention"
        }
        val title = listOfNotNull(event.title.takeIf { it.isNotEmpty() }, event.folderName).joinToString(" · ")
        val body = listOfNotNull(headline, event.summary).joinToString("\n")
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { headline })
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(event.at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // A session's notification opens its conversation; others open the session list (design D8).
            .setContentIntent(event.sessionId?.let { Notifications.openSession(context, it) } ?: Notifications.openApp(context, MainActivity.ACTION_OPEN_SESSIONS))
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
        Notifications.post(context, key, ID, builder.build())
    }

    private fun summarize(count: Int) {
        val text = (if (count == 1) "1 event" else "$count events") + " while you were away — some may have been missed."
        Notifications.post(
            context,
            SUMMARY_TAG,
            ID,
            NotificationCompat.Builder(context, Notifications.CHANNEL_ATTENTION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Agents needed you")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(Notifications.openApp(context, MainActivity.ACTION_OPEN_SESSIONS))
                .build(),
        )
    }

    companion object {
        const val ID = 1
        const val SUMMARY_TAG = "summary"
    }
}
