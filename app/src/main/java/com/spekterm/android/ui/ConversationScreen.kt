package com.spekterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spekterm.android.attention.AttentionNotifier
import com.spekterm.android.attention.AttentionPolicy
import com.spekterm.android.desktop.Blocked
import com.spekterm.android.desktop.Conversation
import com.spekterm.android.desktop.ConversationReducer
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.Phase
import com.spekterm.android.desktop.Replies
import com.spekterm.android.desktop.Reply
import com.spekterm.android.desktop.Screen
import com.spekterm.android.desktop.Watch
import com.spekterm.android.net.Entry
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.WaitState
import com.spekterm.android.service.Notifications
import com.spekterm.android.service.StatusBoard
import com.spekterm.android.store.Announcements
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One agent session's conversation and its composer (`conversation-view`, `conversation-reply`; design D5, D6).
 * [owner] identifies this screen instance (its back-stack entry): only it can release the focus it takes.
 */
@Composable
fun ConversationScreen(
    sessionId: String,
    owner: String,
    announcements: Announcements,
    onBack: () -> Unit,
    onStatus: () -> Unit,
    onResume: () -> Unit,
) {
    val context = LocalContext.current
    val sessions by Desktop.sessions.collectAsStateWithLifecycle()
    val connected by Desktop.connected.collectAsStateWithLifecycle()
    val held by Desktop.conversation.collectAsStateWithLifecycle()
    val status by StatusBoard.status.collectAsStateWithLifecycle()
    val session = sessions.list?.sessions?.firstOrNull { it.id == sessionId }
    val isShell = session != null && !session.isAgent
    val c = held?.takeIf { it.sessionId == sessionId }

    // Watch only while visible (design D3); opening it clears its notification (design D8).
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

    val screen = Screen.of(connected, announcements.paused, sessions, c, sessionId)
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(
            title = session?.label?.ifEmpty { null } ?: "Session",
            subtitle = c?.let { waitText(it) },
            onBack = onBack,
        )
        when (screen) {
            Screen.Paused -> Notice("The connection is paused. What is shown may be out of date.", "Resume", onResume)
            Screen.NotConnected -> Notice(notConnectedReason(status, announcements) + " What is shown may be out of date.", "Details", onStatus)
            Screen.NoWindow -> Notice("spekterm has no open window on the desktop. What is shown may be out of date.")
            else -> Unit
        }
        when (screen) {
            Screen.Closed -> Message("This session was closed on the desktop.")
            Screen.Shell -> Message("Shell sessions need the terminal view, which the app does not have yet.")
            Screen.Dormant -> Dormant(sessionId)
            else -> {
                if (screen == Screen.Content && c != null) ContentNotices(c)
                Entries(c, Modifier.weight(1f))
                Composer(sessionId, c, connected, announcements.paused)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

private fun waitText(c: Conversation): String? = when {
    c.watch != Watch.Watching -> null
    c.wait == WaitState.READY -> "Waiting for you"
    c.wait == WaitState.BUSY -> "Working"
    c.wait == WaitState.AWAITING_CHOICE -> "Asks for a choice"
    else -> null
}

@Composable
private fun ContentNotices(c: Conversation) {
    if (c.phase == Phase.CATCHING_UP) Notice("Catching up with the conversation…")
    if (c.phase == Phase.UNAVAILABLE) Notice("The conversation can't be read right now. Check it on the desktop.")
    (c.watch as? Watch.Refused)?.let { Notice("Could not open the conversation (${it.code}).") }
    if (c.truncated) Notice("Older entries are not shown.")
}

@Composable
private fun Dormant(sessionId: String) {
    val scope = rememberCoroutineScope()
    var waking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<RemoteError?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("This session is dormant.", style = MaterialTheme.typography.bodyLarge)
        Button(
            enabled = !waking,
            onClick = {
                waking = true
                error = null
                scope.launch {
                    error = Desktop.wake(sessionId)
                    waking = false
                }
            },
        ) { Text(if (waking) "Waking…" else "Wake") }
        error?.let { Text(wakeError(it), color = MaterialTheme.colorScheme.error) }
    }
}

private fun wakeError(e: RemoteError): String = when (e.code) {
    RemoteError.NO_WINDOW -> "spekterm has no open window on the desktop. Open it there first."
    RemoteError.CREATE_FAILED -> "The desktop could not start the session." + e.message.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    RemoteError.TIMEOUT -> "The desktop did not answer in time. Try again."
    RemoteError.NOT_FOUND -> "This session no longer exists."
    Desktop.NOT_CONNECTED -> "Not connected to the desktop."
    else -> "Could not wake it (${e.code})."
}

/** One row of the list: an entry, or a reply of the user's not (yet) in the conversation. */
private sealed interface Item {
    val key: String

    data class Of(override val key: String, val entry: Entry) : Item

    data class Mine(val reply: Reply) : Item {
        override val key: String get() = "reply:${reply.id}"
    }
}

/** Entries in order; settled replies after the entry that was last when they were sent, pending ones at the end. */
private fun items(c: Conversation, now: Long): List<Item> {
    val keys = ConversationReducer.keys(c.entries)
    val settled = c.replies.filter { it.label(now).settled }.groupBy { it.anchor?.takeIf { a -> a in keys } }
    val out = mutableListOf<Item>()
    settled[null]?.forEach { out += Item.Mine(it) }
    c.entries.forEachIndexed { i, entry ->
        out += Item.Of(keys[i], entry)
        settled[keys[i]]?.forEach { out += Item.Mine(it) }
    }
    c.replies.filter { !it.label(now).settled }.forEach { out += Item.Mine(it) }
    return out
}

@Composable
private fun Entries(c: Conversation?, modifier: Modifier) {
    if (c == null || (c.entries.isEmpty() && c.replies.isEmpty())) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Delivered and empty: a new session where nothing has been said yet (design D8).
            if (c?.phase == Phase.OK) {
                Text("No messages yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    // Labels that change with time ("not in the conversation yet" after 30 s) need a clock while replies wait.
    val now by produceState(System.currentTimeMillis(), c.replies.isNotEmpty()) {
        while (c.replies.isNotEmpty()) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val list = items(c, now)
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Follow the newest entry while at the bottom; stay put once the user has scrolled up (design D5).
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.canScrollForward }.collect { (scrolling, more) -> if (scrolling) follow = !more }
    }
    val working = c.wait == WaitState.BUSY && c.watch == Watch.Watching
    val count = list.size + if (working) 1 else 0
    val last = list.lastOrNull()?.key
    LaunchedEffect(last, working) { if (follow && count > 0) state.scrollToItem(count - 1) }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(list, key = { it.key }) { item ->
                when (item) {
                    is Item.Of -> EntryRow(item.key, item.entry)
                    is Item.Mine -> ReplyRow(item.reply, now)
                }
            }
            if (working) {
                item(key = "working") { Text("Working…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (!follow) {
            Button(
                onClick = {
                    follow = true
                    scope.launch { if (count > 0) state.scrollToItem(count - 1) }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            ) { Text("Newest") }
        }
    }
}

@Composable
private fun EntryRow(key: String, entry: Entry) {
    var open by remember(key) { mutableStateOf(false) }
    val pad = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    when (entry) {
        is Entry.User -> if (entry.meta) {
            Column(pad.clickable { open = !open }) {
                Text("Injected by the agent" + if (open) "" else " ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (open) SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            Column(
                pad.padding(start = 32.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Text("You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                SelectionContainer { AgentMarkdown(entry.text) }
            }
        }
        is Entry.Text -> Box(pad) { SelectionContainer { AgentMarkdown(entry.text) } }
        is Entry.Thinking -> Column(pad.clickable { open = !open }) {
            Text("Thinking" + if (open) "" else " ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (open) SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        is Entry.Tool -> Row(pad.clickable { open = !open }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            entry.arg?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (open) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is Entry.Result -> Column(pad.clickable { open = !open }) {
            Text(
                (if (entry.error) "Failed" else "Result") + if (open) "" else " ›",
                style = MaterialTheme.typography.labelMedium,
                color = if (entry.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open) {
                Box(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
            }
        }
    }
}

@Composable
private fun ReplyRow(reply: Reply, now: Long) {
    val label = reply.label(now)
    val text = when (label) {
        Reply.Label.SENDING -> "Sending…"
        Reply.Label.NOT_YET_CONFIRMED -> "Not yet confirmed"
        Reply.Label.WAITING_FOR_AGENT -> "Waiting for the agent to take it"
        Reply.Label.NOT_IN_CONVERSATION_YET -> "Not in the conversation yet — check the desktop"
        Reply.Label.NOT_SHOWN_AFTER_WORK -> "Sent while the agent worked — not in the conversation; check the desktop"
        Reply.Label.MAY_NOT_HAVE_BEEN_SENT -> "May not have been sent — check the desktop"
        Reply.Label.NOT_SUBMITTED -> "Typed into the agent's input but not sent — the agent's state changed (for example, a choice appeared). Finish it on the desktop."
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 44.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (label.settled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        // A settled reply is collapsed to one line, so it never takes the room of newer entries.
        Text(
            reply.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (label.settled) 1 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Composer(sessionId: String, c: Conversation?, connected: Boolean, paused: Boolean) {
    val scope = rememberCoroutineScope()
    // The draft is held by Desktop across leaving the screen; plain `remember`, never saved instance state.
    var text by remember(sessionId) { mutableStateOf(c?.draft ?: "") }
    LaunchedEffect(c?.draftReturns) { if (c != null && c.draftReturns > 0) text = c.draft }
    val inFlight = c?.replies?.any { it.state == Reply.State.SENDING } == true
    val blocked = Replies.blocked(connected, paused, c?.watch ?: Watch.Idle, c?.wait ?: WaitState.UNKNOWN, c?.pending, inFlight, text)
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blockedText(blocked)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    Desktop.setDraft(sessionId, it)
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Reply to the agent") },
                minLines = 1,
                maxLines = 6,
            )
            TextButton(
                enabled = blocked == null,
                onClick = {
                    val sending = text
                    text = ""
                    scope.launch { if (!Desktop.send(sessionId, sending)) text = sending }
                },
            ) { Text("Send") }
        }
    }
}

private fun blockedText(blocked: Blocked?): String? = when (blocked) {
    null, Blocked.Empty -> null
    Blocked.Paused -> "The connection is paused. Resume it to reply."
    Blocked.NotConnected -> "Not connected. You can reply once the phone reconnects."
    Blocked.NotWatching -> "Catching up with the conversation…"
    is Blocked.AwaitingChoice -> {
        // Only what the event carries; an event without the request says so plainly, as the desktop does.
        val asked = listOfNotNull(blocked.pending?.tool, blocked.pending?.arg).filter { it.isNotBlank() }.joinToString(" — ")
        "The agent asks for a choice" + (if (asked.isEmpty()) "" else ": $asked") + ". Answer it on the desktop."
    }
    Blocked.Unknown -> "spekterm can't tell whether the agent can take a message now. Reply on the desktop."
    Blocked.InFlight -> "Sending…"
    Blocked.TooLong -> "Too long to send."
}
