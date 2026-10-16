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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.spekterm.android.attach.Attach
import com.spekterm.android.desktop.AttachNote
import com.spekterm.android.desktop.Attachment
import com.spekterm.android.desktop.AttachmentState
import com.spekterm.android.desktop.Answers
import com.spekterm.android.desktop.Blocked
import com.spekterm.android.desktop.Offer
import com.spekterm.android.desktop.SessionActions
import com.spekterm.android.desktop.Uploads
import com.spekterm.android.desktop.Conversation
import com.spekterm.android.desktop.ConversationReducer
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.Phase
import com.spekterm.android.desktop.Replies
import com.spekterm.android.desktop.Reply
import com.spekterm.android.desktop.Screen
import com.spekterm.android.desktop.SessionAction
import com.spekterm.android.desktop.Watch
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Operations
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
    val operations by Desktop.operations.collectAsStateWithLifecycle()
    val actionsEnabled = SessionActions.enabled(connected, announcements.paused, sessions)
    // Closing the session from its own screen returns to the list (design D9).
    val dialogs = rememberSessionDialogs { if (it.kind == SessionAction.Kind.CLOSE) onBack() }
    var menu by remember { mutableStateOf(false) }

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
        ) {
            val actions = session?.let { SessionActions.of(it, operations) }.orEmpty()
            if (actions.isNotEmpty()) {
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮") }
                    SessionMenu(menu, { menu = false }, actions, actionsEnabled) { dialogs.choose(it, session!!) }
                }
            }
        }
        when (screen) {
            Screen.Paused -> Notice("The connection is paused. What is shown may be out of date.", "Resume", onResume)
            Screen.NotConnected -> Notice(notConnectedReason(status, announcements) + " What is shown may be out of date.", "Details", onStatus)
            Screen.NoWindow -> Notice("spekterm has no open window on the desktop. What is shown may be out of date.")
            else -> Unit
        }
        when (screen) {
            Screen.Closed -> Message("This session is no longer on the desktop.")
            Screen.Shell -> Message("Shell sessions need the terminal view, which the app does not have yet.")
            Screen.Dormant -> Dormant(sessionId)
            else -> {
                if (screen == Screen.Content && c != null) ContentNotices(c)
                Entries(c, Modifier.weight(1f))
                c?.let { answerNote(it) }?.let { Notice(it, "Dismiss", { Desktop.dismissAnswer(sessionId) }) }
                val offer = c?.prompt?.let { Answers.offer(it, operations, connected, announcements.paused, c.watch, c.answer) }
                if (offer != null) PromptCard(sessionId, c.prompt!!, offer)
                // The agent's offer to close the session, from the last list held (close-offer D5).
                if (session != null && SessionActions.offered(session)) {
                    CloseOffer(
                        SessionActions.offerActions(session, operations),
                        actionsEnabled,
                        { dialogs.answerOffer(it, session) },
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(start = 12.dp, end = 4.dp),
                    )
                }
                Composer(sessionId, c, connected, announcements.paused, offer is Offer.Answerable, Operations.ATTACH in operations && session?.isAgent != false)
            }
        }
    }
    SessionDialogsHost(dialogs, actionsEnabled)
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
        error?.let { Text(SessionAction.wakeText(it), color = MaterialTheme.colorScheme.error) }
    }
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
        if (reply.attachments.isNotEmpty()) {
            Text("📎 " + reply.attachments.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Composer(sessionId: String, c: Conversation?, connected: Boolean, paused: Boolean, answerable: Boolean, canAttach: Boolean) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The draft is held by Desktop across leaving the screen; plain `remember`, never saved instance state.
    var text by remember(sessionId) { mutableStateOf(c?.draft ?: "") }
    LaunchedEffect(c?.draftReturns) { if (c != null && c.draftReturns > 0) text = c.draft }
    val inFlight = c?.replies?.any { it.state == Reply.State.SENDING } == true
    val attachments = c?.attachments.orEmpty()
    val blocked = Replies.blocked(connected, paused, c?.watch ?: Watch.Idle, c?.wait ?: WaitState.UNKNOWN, c?.pending, inFlight, text, attachments)
    // The photo picker refuses a maximum below 2, so it is always built for 10; extra picks are dropped (design D5).
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Uploads.MAX_ATTACHMENTS)) { uris ->
        Attach.add(context, sessionId, uris, "photo")
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        Attach.add(context, sessionId, uris, "file")
    }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        c?.attachNote?.let { Notice(attachNoteText(it), "Dismiss", { Desktop.dismissAttachNote(sessionId) }) }
        for (attachment in attachments) AttachmentRow(attachment)
        blockedText(blocked, answerable)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canAttach) {
                Box {
                    TextButton(enabled = connected && !paused && c?.closed != true, onClick = { picking = true }) { Text("Attach") }
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        DropdownMenuItem(text = { Text("Photo") }, onClick = {
                            picking = false
                            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        DropdownMenuItem(text = { Text("File") }, onClick = {
                            picking = false
                            files.launch(arrayOf("*/*"))
                        })
                    }
                }
            }
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

/** One attachment in the composer: name, size, how far its upload came, and what can be done with it. */
@Composable
private fun AttachmentRow(a: Attachment) {
    val failed = a.state as? AttachmentState.Failed
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text("📎 " + a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(sizeText(a.size).takeIf { a.size > 0 }, attachmentStateText(a)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (failed != null && a.file != null) TextButton(onClick = { Desktop.retryAttachment(a.localId) }) { Text("Retry") }
        TextButton(onClick = { Desktop.removeAttachment(a.localId) }) { Text("Remove") }
    }
}

private fun sizeText(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

private fun attachmentStateText(a: Attachment): String = when (val s = a.state) {
    AttachmentState.Preparing -> "Preparing…"
    AttachmentState.Queued -> "Waiting to upload"
    is AttachmentState.Uploading -> "Uploading ${if (a.size > 0) (s.received * 100 / a.size) else 0}%"
    is AttachmentState.Ready -> "Ready"
    is AttachmentState.Failed -> failedText(s)
}

private fun failedText(f: AttachmentState.Failed): String = when (f.code) {
    RemoteError.TOO_LARGE -> "The desktop refused it: over its limit" + (f.limit?.let { " of ${sizeText(it)}" } ?: "") + "."
    RemoteError.TOO_MANY -> "The desktop holds ${f.limit ?: Uploads.MAX_ATTACHMENTS} attachments not sent yet for this session; removed ones count for 24 hours."
    RemoteError.UNSUPPORTED_TYPE -> "The desktop does not accept this kind of file."
    RemoteError.NO_CONVERSATION -> "Only an agent session takes attachments."
    RemoteError.UNKNOWN_OPERATION -> "The desktop does not accept attachments."
    Uploads.NO_ANSWER -> "The connection is too slow or unstable to upload this. Retry when it is better."
    Uploads.GONE -> "No longer on the desktop. Retry to upload it again."
    Uploads.FILE -> "The file could not be read."
    else -> "Could not upload it (${f.code})."
}

private fun attachNoteText(note: AttachNote): String = when (note) {
    AttachNote.TOO_MANY_PICKED -> "A reply can carry at most ${Uploads.MAX_ATTACHMENTS} attachments; the rest were not added."
    AttachNote.KIND -> "Only photos, PDF and text files can be sent."
    AttachNote.SIZE -> "Larger than the desktop's 10 MB limit."
    AttachNote.EMPTY -> "The file is empty."
    AttachNote.UNREADABLE -> "The file could not be read."
    AttachNote.SEND_NOT_FOUND -> "The session is not running, or an attachment is no longer on the desktop."
}

private fun blockedText(blocked: Blocked?, answerable: Boolean): String? = when (blocked) {
    null, Blocked.Empty -> null
    Blocked.Paused -> "The connection is paused. Resume it to reply."
    Blocked.NotConnected -> "Not connected. You can reply once the phone reconnects."
    Blocked.NotWatching -> "Catching up with the conversation…"
    is Blocked.AwaitingChoice -> if (answerable) "Answer the choice above." else {
        // Only what the event carries; an event without the request says so plainly, as the desktop does.
        val asked = listOfNotNull(blocked.pending?.tool, blocked.pending?.arg).filter { it.isNotBlank() }.joinToString(" — ")
        "The agent asks for a choice" + (if (asked.isEmpty()) "" else ": $asked") + ". Answer it on the desktop."
    }
    Blocked.Unknown -> "spekterm can't tell whether the agent can take a message now. Reply on the desktop."
    Blocked.InFlight -> "Sending…"
    Blocked.Attaching -> "Uploading attachments…"
    Blocked.AttachmentFailed -> "Retry or remove the attachment that failed to send."
    Blocked.TooLong -> "Too long to send."
}
