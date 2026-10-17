@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
import com.spekterm.android.desktop.ConversationItems
import com.spekterm.android.desktop.ConversationItems.Shown
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
import com.spekterm.android.desktop.SessionsView.RowState
import com.spekterm.android.desktop.Watch
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Operations
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.WaitState
import com.spekterm.android.service.Notifications
import com.spekterm.android.service.StatusBoard
import com.spekterm.android.store.Announcements
import com.spekterm.android.ui.theme.spekColors
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
    SpekScaffold(
        topBar = { scroll ->
            TopBar(
                title = session?.label?.ifEmpty { null } ?: "Session",
                scroll = scroll,
                onBack = onBack,
                subtitle = c?.let { waitState(it) }?.let { state -> { StateLabel(state) } },
            ) {
                val actions = session?.let { SessionActions.of(it, operations) }.orEmpty()
                if (actions.isNotEmpty()) {
                    Box {
                        MoreButton({ menu = true })
                        SessionMenu(menu, { menu = false }, actions, actionsEnabled) { dialogs.choose(it, session!!) }
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Paused -> Notice("The connection is paused. What is shown may be out of date.", Tone.Warning, "Resume", onResume)
                Screen.NotConnected -> Notice(notConnectedReason(status, announcements) + " What is shown may be out of date.", Tone.Warning, "Details", onStatus)
                Screen.NoWindow -> Notice("spekterm has no open window on the desktop. What is shown may be out of date.", Tone.Warning)
                else -> Unit
            }
            when (screen) {
                Screen.Closed -> CenteredMessage(SpekIcons.Info, "This session is no longer on the desktop.")
                Screen.Shell -> CenteredMessage(SpekIcons.Shell, "Shell sessions need the terminal view, which the app does not have yet.")
                Screen.Dormant -> Dormant(sessionId)
                else -> {
                    if (screen == Screen.Content && c != null) ContentNotices(c)
                    Entries(c, Modifier.weight(1f))
                    c?.let { answerNote(it) }?.let { Notice(it, answerTone(c), "Dismiss", { Desktop.dismissAnswer(sessionId) }) }
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
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
                                .border(1.dp, spekColors.offer, MaterialTheme.shapes.large)
                                .padding(start = 14.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                    Composer(sessionId, c, connected, announcements.paused, offer is Offer.Answerable, Operations.ATTACH in operations && session?.isAgent != false)
                }
            }
        }
    }
    SessionDialogsHost(dialogs, actionsEnabled)
}

/** The agent's wait state while watched, for the top bar (D4). */
private fun waitState(c: Conversation): RowState? = when {
    c.watch != Watch.Watching -> null
    c.wait == WaitState.READY -> RowState.READY
    c.wait == WaitState.BUSY -> RowState.BUSY
    c.wait == WaitState.AWAITING_CHOICE -> RowState.CHOICE
    else -> null
}

@Composable
private fun ContentNotices(c: Conversation) {
    if (c.phase == Phase.CATCHING_UP) Notice("Catching up with the conversation…", Tone.Info)
    if (c.phase == Phase.UNAVAILABLE) Notice("The conversation can't be read right now. Check it on the desktop.", Tone.Warning)
    (c.watch as? Watch.Refused)?.let { Notice("Could not open the conversation (${it.code}).", Tone.Error) }
    if (c.truncated) Notice("Older entries are not shown.", Tone.Info)
}

@Composable
private fun Dormant(sessionId: String) {
    val scope = rememberCoroutineScope()
    var waking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<RemoteError?>(null) }
    CenteredMessage(SpekIcons.Dormant, "This session is dormant.") {
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

/** One row of the list: a shown item, or a reply of the user's not (yet) in the conversation. */
private sealed interface Item {
    val key: String

    data class Of(val shown: Shown) : Item {
        override val key: String get() = shown.key
    }

    data class Mine(val reply: Reply) : Item {
        override val key: String get() = "reply:${reply.id}"
    }
}

/**
 * Shown items in order, settled replies right after the item carrying their anchor (D7); replies whose anchor is
 * gone first, pending ones at the end.
 */
private fun items(c: Conversation, now: Long): List<Item> {
    val keys = ConversationReducer.keys(c.entries)
    val known = keys.toSet()
    val settled = c.replies.filter { it.label(now).settled }.groupBy { it.anchor?.takeIf { a -> a in known } }
    val out = mutableListOf<Item>()
    settled[null]?.forEach { out += Item.Mine(it) }
    for (shown in ConversationItems.group(c.entries, keys, settled.keys.filterNotNull().toSet())) {
        out += Item.Of(shown)
        for (anchor in shown.anchorsAfter) settled[anchor]?.forEach { out += Item.Mine(it) }
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
    // What is open, by item key: a call that becomes a run's first keeps it, and it survives scrolling away (D7).
    val open = remember(c.sessionId) { mutableStateMapOf<String, Boolean>() }
    // Follow the newest entry while at the bottom; stay put once the user has scrolled up (design D5).
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.canScrollForward }.collect { (scrolling, more) -> if (scrolling) follow = !more }
    }
    val working = c.wait == WaitState.BUSY && c.watch == Watch.Watching
    // Counted from the list being built: layoutInfo is a frame behind (D7).
    val count = list.size + if (working) 1 else 0
    // A growing run keeps its key, so follow the entries and replies themselves.
    val lastEntry = c.entries.lastOrNull()?.uuid to c.entries.size
    val lastReply = c.replies.lastOrNull()?.id
    // An offset past the end is clamped, so an expanded run taller than the screen still shows its newest call.
    suspend fun toNewest() = state.scrollToItem(count - 1, Int.MAX_VALUE)
    LaunchedEffect(lastEntry, lastReply, working) { if (follow && count > 0) toNewest() }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(list, key = { it.key }) { item ->
                when (item) {
                    is Item.Of -> ShownRow(item.shown, open)
                    is Item.Mine -> ReplyRow(item.reply, now)
                }
            }
            if (working) item(key = "working") { WorkingRow() }
        }
        if (!follow) {
            SmallFloatingActionButton(
                onClick = {
                    follow = true
                    scope.launch { if (count > 0) toNewest() }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) { Icon(SpekIcons.ArrowDown, "Jump to newest") }
        }
    }
}

private val rowPad = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)

/** A line that expands and collapses by a tap anywhere on it, saying which it is. */
private fun Modifier.toggles(open: Boolean, onToggle: () -> Unit): Modifier =
    clickable(onClick = onToggle).semantics { stateDescription = if (open) "Expanded" else "Collapsed" }

@Composable
private fun ShownRow(shown: Shown, open: MutableMap<String, Boolean>) {
    val expanded = open[shown.key] == true
    val toggle = { open[shown.key] = !expanded }
    when (shown) {
        is ConversationItems.Call -> CallLine(shown, expanded, toggle, rowPad)
        is ConversationItems.Run -> RunItem(shown, expanded, toggle, open)
        is ConversationItems.LoneResult -> ResultLine(shown.result, expanded, toggle, rowPad)
        is ConversationItems.Plain -> PlainRow(shown.entry, expanded, toggle)
    }
}

@Composable
private fun PlainRow(entry: Entry, expanded: Boolean, toggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (entry) {
        is Entry.User -> if (entry.meta) {
            Muted("Injected by the agent", expanded, toggle) {
                SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            // The user's own message: a quiet bubble at the end, said to be theirs for a screen reader (D7).
            BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier
                        .widthIn(max = maxWidth)
                        .semantics { contentDescription = "You" }
                        .background(colors.surfaceContainerHigh, MaterialTheme.shapes.large)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) { SelectionContainer { AgentMarkdown(entry.text) } }
            }
        }
        is Entry.Text -> Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) { SelectionContainer { AgentMarkdown(entry.text) } }
        is Entry.Thinking -> Muted("Thinking", expanded, toggle) {
            SelectionContainer {
                Text(entry.text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant)
            }
        }
        // Calls and results are shown by ConversationItems; these never reach here.
        is Entry.Tool, is Entry.Result -> Unit
    }
}

/** A muted, expandable line: thinking, an injected message. */
@Composable
private fun Muted(label: String, expanded: Boolean, toggle: () -> Unit, body: @Composable () -> Unit) {
    Column(rowPad) {
        Row(Modifier.toggles(expanded, toggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Chevron(expanded)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) Box(Modifier.padding(start = 22.dp, bottom = 4.dp)) { body() }
    }
}

@Composable
private fun Chevron(expanded: Boolean) {
    Icon(
        if (expanded) SpekIcons.ExpandMore else SpekIcons.ChevronRight,
        null,
        Modifier.padding(end = 4.dp).size(18.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A call's mark: succeeded, failed, or no result yet (D7). */
@Composable
private fun CallMark(call: ConversationItems.Call) {
    val spek = spekColors
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        when {
            call.result == null -> StatusDot(spek.dormant, hollow = true)
            call.failed -> Icon(SpekIcons.Error, "Failed", Modifier.size(16.dp), tint = spek.danger)
            else -> Icon(SpekIcons.Succeeded, "Succeeded", Modifier.size(16.dp), tint = spek.working)
        }
    }
}

@Composable
private fun CallLine(call: ConversationItems.Call, expanded: Boolean, toggle: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().toggles(expanded, toggle).padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CallMark(call)
            Text(call.tool.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            call.tool.arg?.let {
                Text(
                    it,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 26.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                call.tool.arg?.let { Mono(it) }
                call.result?.let { Mono(it.text, it.error) }
            }
        }
    }
}

@Composable
private fun ResultLine(result: Entry.Result, expanded: Boolean, toggle: () -> Unit, modifier: Modifier) {
    val spek = spekColors
    Column(modifier) {
        Row(Modifier.fillMaxWidth().toggles(expanded, toggle).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Chevron(expanded)
            Text(
                if (result.error) "Failed" else "Result",
                style = MaterialTheme.typography.labelMedium,
                color = if (result.error) spek.danger else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) Box(Modifier.padding(start = 22.dp, bottom = 4.dp)) { Mono(result.text, result.error) }
    }
}

/** Plain monospace text in a box of its own that scrolls (`conversation-view`: shown as plain text). */
@Composable
private fun Mono(text: String, error: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A run of calls: "7 tool calls · 1 failed" and the latest call; expanded, every part, inside this one item (D7). */
@Composable
private fun RunItem(run: ConversationItems.Run, expanded: Boolean, toggle: () -> Unit, open: MutableMap<String, Boolean>) {
    val colors = MaterialTheme.colorScheme
    Column(rowPad) {
        Row(Modifier.fillMaxWidth().toggles(expanded, toggle).padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
            Chevron(expanded)
            Column(Modifier.weight(1f)) {
                Row {
                    Text("${run.calls.size} tool calls", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    if (run.failed > 0) {
                        Text(" · ${run.failed} failed", style = MaterialTheme.typography.labelLarge, color = spekColors.danger)
                    }
                }
                if (!expanded) {
                    Text(
                        listOfNotNull(run.latest.tool.name, run.latest.tool.arg).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp)) {
                for (part in run.parts) {
                    val key = "part:${part.key}"
                    val partOpen = open[key] == true
                    val partToggle = { open[key] = !partOpen }
                    when (part) {
                        is ConversationItems.Call -> CallLine(part, partOpen, partToggle, Modifier.fillMaxWidth())
                        is ConversationItems.Thinking -> Muted("Thinking", partOpen, partToggle) {
                            Text(part.entry.text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** The agent works, from the wait state only: nothing here says how far a message got (`conversation-view`). */
@Composable
private fun WorkingRow() {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = spekColors.working)
        Text("Working", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val colors = MaterialTheme.colorScheme
    // The user's bubble, outlined: not (yet) part of the conversation (D7).
    Box(Modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .semantics(mergeDescendants = true) {}
                .background(colors.surfaceContainerHigh, MaterialTheme.shapes.large)
                .border(1.dp, colors.outline, MaterialTheme.shapes.large)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelSmall, color = if (label.settled) colors.error else colors.onSurfaceVariant)
            // A settled reply is collapsed to one line, so it never takes the room of newer entries.
            Text(
                reply.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (label.settled) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if (reply.attachments.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(SpekIcons.Attach, "Attachments", Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                    Text(reply.attachments.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Composer(sessionId: String, c: Conversation?, connected: Boolean, paused: Boolean, answerable: Boolean, canAttach: Boolean) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
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
    Column(Modifier.fillMaxWidth()) {
        c?.attachNote?.let { Notice(attachNoteText(it), attachNoteTone(it), "Dismiss", { Desktop.dismissAttachNote(sessionId) }) }
        blockedText(blocked, answerable)?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outlineVariant)
        Column(Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (attachments.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (attachment in attachments) AttachmentCard(attachment)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canAttach) {
                    Box {
                        IconButton(enabled = connected && !paused && c?.closed != true, onClick = { picking = true }) { Icon(SpekIcons.Attach, "Attach") }
                        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                            DropdownMenuItem(text = { Text("Photo") }, leadingIcon = { Icon(SpekIcons.Image, null) }, onClick = {
                                picking = false
                                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(SpekIcons.File, null) }, onClick = {
                                picking = false
                                files.launch(arrayOf("*/*"))
                            })
                        }
                    }
                }
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        Desktop.setDraft(sessionId, it)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Reply to the agent") },
                    minLines = 1,
                    maxLines = 6,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = colors.surfaceContainerHigh,
                        unfocusedContainerColor = colors.surfaceContainerHigh,
                        disabledContainerColor = colors.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                FilledIconButton(
                    enabled = blocked == null,
                    onClick = {
                        val sending = text
                        text = ""
                        scope.launch { if (!Desktop.send(sessionId, sending)) text = sending }
                    },
                    modifier = Modifier.padding(bottom = 4.dp),
                ) { Icon(SpekIcons.Send, "Send") }
            }
        }
    }
}

/** One attachment in the composer: name, size, how far its upload came, and what can be done with it (D8). */
@Composable
private fun AttachmentCard(a: Attachment) {
    val failed = a.state as? AttachmentState.Failed
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.widthIn(max = 220.dp).background(colors.surfaceContainerHigh, MaterialTheme.shapes.medium).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(attachmentIcon(a.name), null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f, fill = false).padding(vertical = 6.dp)) {
            Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(sizeText(a.size).takeIf { a.size > 0 }, attachmentStateText(a)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed != null) colors.error else colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (failed != null && a.file != null) {
                TextButton(onClick = { Desktop.retryAttachment(a.localId) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Retry") }
            }
        }
        IconButton(onClick = { Desktop.removeAttachment(a.localId) }) { Icon(SpekIcons.Close, "Remove attachment", Modifier.size(18.dp)) }
    }
}

private fun attachmentIcon(name: String): ImageVector =
    if (name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif")) SpekIcons.Image else SpekIcons.File

/** D5: too many picked is a warning; a file that cannot go is an error. */
private fun attachNoteTone(note: AttachNote): Tone = if (note == AttachNote.TOO_MANY_PICKED) Tone.Warning else Tone.Error

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
