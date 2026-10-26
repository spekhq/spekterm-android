package com.spekterm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spekterm.android.R
import com.spekterm.android.attention.AttentionNotifier
import com.spekterm.android.attention.AttentionPolicy
import com.spekterm.android.desktop.Conversation
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.ReplyBarView
import com.spekterm.android.desktop.Screen
import com.spekterm.android.desktop.SessionsView
import com.spekterm.android.net.Session
import com.spekterm.android.service.Notifications
import com.spekterm.android.store.Announcements
import kotlinx.coroutines.delay

/** The conversation a screen was opened from, which its reply bar replies to (`reply-from-other-screens` D1). */
class ReplyTarget(val sessionId: String, val onOpenConversation: () -> Unit, val onOpenInboxItem: (String) -> Unit)

/** [content] above the reply bar of [target]'s conversation (D6); without a [target], [content] alone. */
@Composable
fun WithReplyBar(target: ReplyTarget?, owner: String, announcements: Announcements, content: @Composable ColumnScope.() -> Unit) {
    if (target == null) {
        Column(Modifier.fillMaxSize(), content = content)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // As on the conversation: the skills above the field take at most a quarter of the room (`tool-call-display` D10).
        val panelMax = minOf(240.dp, maxHeight / 4)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().weight(1f), content = content)
            ReplyBar(target.sessionId, owner, announcements, panelMax, target.onOpenConversation, target.onOpenInboxItem)
        }
    }
}

@Composable
private fun ReplyBar(
    sessionId: String,
    owner: String,
    announcements: Announcements,
    panelMax: Dp,
    onOpenConversation: () -> Unit,
    onOpenInboxItem: (String) -> Unit,
) {
    val context = LocalContext.current
    val sessions by Desktop.sessions.collectAsStateWithLifecycle()
    val connected by Desktop.connected.collectAsStateWithLifecycle()
    val held by Desktop.conversation.collectAsStateWithLifecycle()
    val session = sessions.list?.sessions?.firstOrNull { it.id == sessionId }
    val isShell = session != null && !session.isAgent
    val c = held?.takeIf { it.sessionId == sessionId }

    // Watched while visible, as the conversation is, and looked at as it is (D3).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sessionId, owner, isShell) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!isShell) {
                    Desktop.focus(owner, sessionId)
                    Notifications.cancel(context, AttentionPolicy.sessionKey(sessionId), AttentionNotifier.ID)
                }
                Lifecycle.Event.ON_STOP -> Desktop.unfocus(owner)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            Desktop.unfocus(owner)
        }
    }

    // Labels that change with time need a clock while replies wait, as in the conversation (D4).
    val waiting = c?.replies?.isNotEmpty() == true
    val now by produceState(System.currentTimeMillis(), waiting) {
        while (waiting) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val screen = Screen.of(connected, announcements.paused, sessions, c, sessionId)
    val bar = ReplyBarView.of(session, screen, c, now)
    if (bar.kind == ReplyBarView.Kind.NONE) return
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Header(session, c, bar, onOpenConversation)
        if (bar.kind == ReplyBarView.Kind.REPLY) {
            ReplyArea(sessionId, c, session, announcements.paused, panelMax, onOpenInboxItem)
        }
    }
}

/** The session, how it stands and the reply the user waits on; tapping it opens the conversation (D4, D5). */
@Composable
private fun Header(session: Session?, c: Conversation?, bar: ReplyBarView.Bar, onOpenConversation: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val open = stringResource(R.string.reply_bar_open)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = open, onClick = onOpenConversation)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderText(session, c, bar)
        }
        Icon(SpekIcons.ChevronRight, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun RowScope.HeaderText(session: Session?, c: Conversation?, bar: ReplyBarView.Bar) {
    val colors = MaterialTheme.colorScheme
    Text(
        session?.label?.ifEmpty { null } ?: stringResource(R.string.conversation_title_fallback),
        Modifier.weight(1f, fill = false),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    when (bar.kind) {
        ReplyBarView.Kind.CLOSED -> Text(stringResource(R.string.reply_bar_closed), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        ReplyBarView.Kind.ASLEEP -> {
            StateLabel(SessionsView.RowState.DORMANT)
            Text(stringResource(R.string.reply_bar_wake), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        else -> {
            c?.let { SessionsView.screenState(it, session) }?.let { StateLabel(it) }
            bar.waited?.let { w ->
                Text(
                    stringResource(replyLabelText(w.label)),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (w.label.settled) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
