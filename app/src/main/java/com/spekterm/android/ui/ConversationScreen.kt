@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone

import com.spekterm.android.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
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
import com.spekterm.android.desktop.ConversationTimes
import com.spekterm.android.desktop.ConversationTimes.DayWord
import com.spekterm.android.desktop.ConversationTimes.Line
import com.spekterm.android.desktop.ConversationItems.Shown
import com.spekterm.android.desktop.Offer
import com.spekterm.android.desktop.SessionActions
import com.spekterm.android.desktop.Uploads
import com.spekterm.android.desktop.Conversation
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.InboxView
import com.spekterm.android.desktop.OpenSpecGates
import com.spekterm.android.desktop.Phase
import com.spekterm.android.desktop.Replies
import com.spekterm.android.desktop.Reply
import com.spekterm.android.desktop.Screen
import com.spekterm.android.desktop.SessionAction
import com.spekterm.android.desktop.SessionsView
import com.spekterm.android.desktop.PeerText
import com.spekterm.android.desktop.SessionNames
import com.spekterm.android.desktop.SkillInsert
import com.spekterm.android.desktop.SlashQuery
import com.spekterm.android.desktop.ToolView
import com.spekterm.android.desktop.Watch
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Operations
import com.spekterm.android.net.Origin
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
    onHandoff: (String) -> Unit,
    onOpenSpecFolder: (String) -> Unit,
    onOpenInbox: (String) -> Unit,
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
    var reporting by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    // A sent file being viewed, over the conversation so it keeps its place (`tool-call-display` D6).
    var viewing by remember(sessionId) { mutableStateOf<SentFileRef?>(null) }
    // Sent pictures read on this screen, in memory only, so scrolling away and back reads nothing again (D11).
    val thumbs = remember(sessionId) { mutableStateMapOf<String, Thumb>() }
    // Bumped when the skills appear above the field, so a list that follows the newest keeps it in view (D10).
    var panelShows by remember { mutableIntStateOf(0) }
    // Held here, not in the item, so it survives the work ending and coming back (`background-tasks` D4).
    var backgroundOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    // A first prompt from the inbox waiting in this session (`inbox` design D7).
    val inbox by Desktop.inbox.collectAsStateWithLifecycle()
    val linked = InboxView.linked(session, inbox.items, sessions, operations)
    // An older desktop's hold on replies, lifted by the user until the note changes (design D7).
    var replyAnyway by remember(sessionId, linked?.javaClass, (linked as? InboxView.Linked.Waiting)?.item?.key) { mutableStateOf(false) }

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
            // Files handed to another app go with the conversation (`tool-call-display` D9).
            SentFileCache.clear(context)
        }
    }

    val screen = Screen.of(connected, announcements.paused, sessions, c, sessionId)
    SpekScaffold(
        topBar = { scroll ->
            TopBar(
                title = session?.label?.ifEmpty { null } ?: stringResource(R.string.conversation_title_fallback),
                scroll = scroll,
                onBack = onBack,
                subtitle = c?.let { SessionsView.screenState(it, session) }?.let { state -> { StateLabel(state) } },
            ) {
                // The session's folder's OpenSpec, as its group heading offers it (`session-detail-openspec` D1, D2).
                OpenSpecGates.of(operations).folder(session, sessions.list?.folders.orEmpty())?.let { folder ->
                    IconButton(onClick = { onOpenSpecFolder(folder.id) }) { Icon(SpekIcons.File, stringResource(R.string.conversation_openspec_for, folder.name)) }
                }
                val actions = session?.let { SessionActions.of(it, operations, sessions.list) }.orEmpty()
                Box {
                    MoreButton({ menu = true })
                    SessionMenu(menu, { menu = false }, actions, actionsEnabled, onReport = { reporting = true }) {
                        if (it == SessionActions.Action.HANDOFF) onHandoff(sessionId) else dialogs.choose(it, session!!)
                    }
                }
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // A quarter of the content's actual height, which shrinks with the keyboard (`background-tasks` D4).
            val backgroundMax = maxHeight / 4
            // The skills above the field take at most as much, so the field stays in view (`tool-call-display` D10).
            val panelMax = minOf(240.dp, maxHeight / 4)
            Column(Modifier.fillMaxSize()) {
                when (screen) {
                    Screen.Paused -> Notice(stringResource(R.string.conversation_paused), Tone.Warning, stringResource(R.string.common_resume), onResume)
                    Screen.NotConnected -> Notice(stringResource(R.string.conversation_out_of_date, notConnectedReason(status, announcements).text()), Tone.Warning, stringResource(R.string.common_details), onStatus)
                    Screen.NoWindow -> Notice(stringResource(R.string.conversation_no_window), Tone.Warning)
                    else -> Unit
                }
                when (screen) {
                    Screen.Closed -> CenteredMessage(SpekIcons.Info, stringResource(R.string.conversation_closed))
                    Screen.Shell -> CenteredMessage(SpekIcons.Shell, stringResource(R.string.conversation_shell))
                    Screen.Dormant -> Dormant(sessionId)
                    else -> {
                        // What the agent last reported, while watched (`agent-usage-and-skills` D3).
                        c?.usage?.takeIf { c.watch.active && it.reported }?.let { usage ->
                            val now = rememberUsageNow(usage)
                            UsageRow(usage, now) { usageOpen = true }
                            if (usageOpen) UsageDialog(usage, now) { usageOpen = false }
                        }
                        if (screen == Screen.Content && c != null) ContentNotices(c)
                        // From the last list held, as the close offer is (`background-tasks` D4).
                        val background = session?.let { SessionsView.background(it) }.orEmpty()
                        if (background.isNotEmpty()) BackgroundTasks(background, backgroundOpen, { backgroundOpen = !backgroundOpen }, backgroundMax)
                        CompositionLocalProvider(
                            LocalToolContext provides ToolContext(sessions.list, Operations.FILES_READ in operations, sessionId, session?.label, thumbs) { viewing = it },
                        ) {
                            Entries(c, Modifier.weight(1f), panelShows)
                        }
                        c?.let { answerNote(it) }?.let { Notice(it.text(), answerTone(c), stringResource(R.string.common_dismiss), { Desktop.dismissAnswer(sessionId) }) }
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
                        linked?.let { InboxNote(it, replyAnyway, onOpenInbox) { replyAnyway = true } }
                        Composer(
                            sessionId, c, connected, announcements.paused, offer is Offer.Answerable, Operations.ATTACH in operations && session?.isAgent != false,
                            inboxPrompt = InboxView.holdsReplies(linked, replyAnyway),
                            panelMax = panelMax,
                            onPanel = { panelShows++ },
                        )
                    }
                }
            }
        }
    }
    viewing?.let { SentFileDialog(sessionId, it) { viewing = null } }
    SessionDialogsHost(dialogs, actionsEnabled)
    if (reporting) ReportProblemDialog { reporting = false }
}

/** The agent's wait state while watched, for the top bar (D4). */
@Composable
private fun ContentNotices(c: Conversation) {
    if (c.phase == Phase.CATCHING_UP) Notice(stringResource(R.string.conversation_catching_up), Tone.Info)
    if (c.phase == Phase.UNAVAILABLE) Notice(stringResource(R.string.conversation_unavailable), Tone.Warning)
    (c.watch as? Watch.Refused)?.let { Notice(stringResource(R.string.conversation_refused, it.code), Tone.Error) }
    if (c.truncated) Notice(stringResource(R.string.conversation_truncated), Tone.Info)
}

@Composable
private fun Dormant(sessionId: String) {
    val scope = rememberCoroutineScope()
    var waking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<RemoteError?>(null) }
    CenteredMessage(SpekIcons.Dormant, stringResource(R.string.conversation_dormant)) {
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
        ) { Text(stringResource(if (waking) R.string.common_waking else R.string.common_wake)) }
        error?.let { Text(SessionAction.wakeText(it).text(), color = MaterialTheme.colorScheme.error) }
    }
}

/** Today and the phone's zone, as of the last re-read; [reads] counts them (`conversation-times` D3). */
private data class Clock(val today: LocalDate, val zone: ZoneId, val reads: Int)

/**
 * The clock the conversation's days and times are read by, re-read when the screen resumes (a delay alone runs late
 * after the phone slept), when the system's date, time or zone changes, and at the next local midnight (D3).
 */
@Composable
private fun rememberClock(): Clock {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reads by remember { mutableIntStateOf(0) }
    val clock = remember(reads) { ZoneId.systemDefault().let { Clock(LocalDate.now(it), it, reads) } }
    DisposableEffect(context, lifecycleOwner) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                reads++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reads++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    // A backstop while the screen stays on, restarted by every re-read.
    LaunchedEffect(reads) {
        delay(ConversationTimes.untilMidnight(ZonedDateTime.now(clock.zone)).toMillis() + 1_000)
        reads++
    }
    return clock
}

@Composable
private fun Entries(c: Conversation?, modifier: Modifier, panelShows: Int = 0) {
    if (c == null || (c.entries.isEmpty() && c.replies.isEmpty())) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Delivered and empty: a new session where nothing has been said yet (design D8).
            if (c?.phase == Phase.OK) {
                Text(stringResource(R.string.conversation_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val clock = rememberClock()
    val list = ConversationTimes.place(ConversationTimes.rows(c, now), clock.zone)
    // The phone's time format, 12- or 24-hour as it is set, in the clock's zone (D1).
    val context = LocalContext.current
    val timeFormat = remember(clock) { DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone(clock.zone) } }
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
    LaunchedEffect(lastEntry, lastReply, working, panelShows) { if (follow && count > 0) toNewest() }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            for (item in list) {
                when (item) {
                    // A day's heading stays pinned at the top while its items scroll under it (D5).
                    is Line.Day -> stickyHeader(key = item.key) { DayHeading(ConversationTimes.dayWord(item.date, clock.today)) }
                    is Line.Of -> item(key = item.key) { ShownRow(item.shown, open, ConversationTimes.timeOf(item.shown)?.let { timeFormat.format(Date(it)) }) }
                    is Line.Mine -> item(key = item.key) { ReplyRow(item.reply, now) }
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
            ) { Icon(SpekIcons.ArrowDown, stringResource(R.string.conversation_jump_to_newest)) }
        }
    }
}

private val rowPad = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)

/** A line that expands and collapses by a tap anywhere on it, saying which it is. */
@Composable
internal fun Modifier.toggles(open: Boolean, onToggle: () -> Unit): Modifier {
    val state = stringResource(if (open) R.string.common_expanded else R.string.common_collapsed)
    return clickable(onClick = onToggle).semantics { stateDescription = state }
}

@Composable
private fun ShownRow(shown: Shown, open: MutableMap<String, Boolean>, time: String?) {
    val expanded = open[shown.key] == true
    val toggle = { open[shown.key] = !expanded }
    when (shown) {
        is ConversationItems.Call -> CallLine(shown, expanded, toggle, rowPad)
        is ConversationItems.Run -> RunItem(shown, expanded, toggle, open)
        is ConversationItems.LoneResult -> ResultLine(shown.result, expanded, toggle, rowPad)
        is ConversationItems.Plain -> PlainRow(shown.entry, expanded, toggle, time)
        is ConversationItems.Message -> MessageRow(shown, open, time)
    }
}

@Composable
private fun PlainRow(entry: Entry, expanded: Boolean, toggle: () -> Unit, time: String?) {
    val colors = MaterialTheme.colorScheme
    when (entry) {
        // A background task's notice on its own: one short line (`tool-call-display` D11).
        is Entry.User -> if (entry.origin == Origin.TaskNotice) {
            NoticeLine(entry.text, expanded, toggle)
        } else if (entry.meta) {
            // Who sent it, when the desktop says; a peer's body is an agent's text, without the CLI's wrapper (D7).
            Muted(injectedLabel(entry.origin), expanded, toggle) {
                if (entry.origin is Origin.Peer) {
                    SelectionContainer { AgentMarkdown(remember(entry.text) { PeerText.strip(entry.text) }) }
                } else {
                    SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodySmall) }
                }
            }
        } else {
            // The user's own message: a quiet bubble at the end, said to be theirs for a screen reader (D7).
            val you = stringResource(R.string.conversation_you)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), contentAlignment = Alignment.CenterEnd) {
                val maxWidth = maxWidth
                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        Modifier
                            .widthIn(max = maxWidth)
                            .semantics { contentDescription = you }
                            .background(colors.surfaceContainerHigh, MaterialTheme.shapes.large)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) { SelectionContainer { AgentMarkdown(entry.text) } }
                    time?.let { TimeLine(it, Modifier.padding(end = 4.dp, top = 2.dp)) }
                }
            }
        }
        is Entry.Text -> Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            SelectionContainer { AgentMarkdown(entry.text) }
            time?.let { TimeLine(it, Modifier.padding(top = 2.dp)) }
        }
        is Entry.Thinking -> Muted(stringResource(R.string.conversation_thinking), expanded, toggle) {
            SelectionContainer {
                Text(entry.text, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant)
            }
        }
        // Calls and results are shown by ConversationItems; these never reach here.
        is Entry.Tool, is Entry.Result -> Unit
    }
}

/** When a message was recorded, under it (`conversation-times` D1). */
@Composable
private fun TimeLine(time: String, modifier: Modifier) {
    Text(time, modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The heading before a day's first item: "Today", "Yesterday" or the date (D3). Pinned over the entries while its
 * day scrolls, so it is an opaque pill that stands out from them, with the row around it see-through (D5).
 */
@Composable
internal fun DayHeading(word: DayWord) {
    val text = when (word) {
        DayWord.Today -> stringResource(R.string.conversation_today)
        DayWord.Yesterday -> stringResource(R.string.conversation_yesterday)
        is DayWord.Date -> remember(word.date) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(word.date) }
    }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
        ) {
            Text(
                text,
                Modifier.semantics { heading() }.padding(horizontal = 14.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** What an injected message is, by where the desktop says it came from (`tool-call-display` D7). */
@Composable
internal fun injectedLabel(origin: Origin?): String {
    val list = LocalToolContext.current.list
    return when (origin) {
        is Origin.Peer -> when (origin.sender) {
            Origin.Sender.SESSION -> origin.name?.let { stringResource(R.string.peer_from, SessionNames.label(it, list) ?: it) } ?: stringResource(R.string.peer_from_session)
            Origin.Sender.SUBAGENT -> origin.name?.let { stringResource(R.string.peer_report_from, it) } ?: stringResource(R.string.peer_report)
            Origin.Sender.OTHER -> origin.name?.let { stringResource(R.string.peer_from, SessionNames.label(it, list) ?: it) } ?: stringResource(R.string.peer_from_other)
        }
        Origin.TaskNotice -> stringResource(R.string.peer_task_notice)
        null -> stringResource(R.string.conversation_injected)
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
            call.failed -> Icon(SpekIcons.Error, stringResource(R.string.conversation_call_failed), Modifier.size(16.dp), tint = spek.danger)
            else -> Icon(SpekIcons.Succeeded, stringResource(R.string.conversation_call_succeeded), Modifier.size(16.dp), tint = spek.working)
        }
    }
}

@Composable
private fun CallLine(call: ConversationItems.Call, expanded: Boolean, toggle: () -> Unit, modifier: Modifier) {
    val list = LocalToolContext.current.list
    // What the call did, by its tool (`tool-call-display` D2).
    val view = remember(call.tool, call.result, list) { ToolView.of(call.tool, call.result, list) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().toggles(expanded, toggle).padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CallMark(call)
            Text(view.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            summaryOf(view)?.let {
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
        if (expanded) Box(Modifier.padding(start = 26.dp, bottom = 4.dp)) { ToolParts(view, call.tool.id) }
    }
}

@Composable
private fun ResultLine(result: Entry.Result, expanded: Boolean, toggle: () -> Unit, modifier: Modifier) {
    val spek = spekColors
    Column(modifier) {
        Row(Modifier.fillMaxWidth().toggles(expanded, toggle).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Chevron(expanded)
            Text(
                stringResource(if (result.error) R.string.conversation_result_failed else R.string.conversation_result),
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
                    Text(pluralStringResource(R.plurals.conversation_run_calls, run.calls.size, run.calls.size), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    if (run.failed > 0) {
                        Text(" · " + pluralStringResource(R.plurals.conversation_run_failed, run.failed, run.failed), style = MaterialTheme.typography.labelLarge, color = spekColors.danger)
                    }
                }
                if (!expanded) {
                    val list = LocalToolContext.current.list
                    val latest = remember(run.latest.tool, run.latest.result, list) { ToolView.of(run.latest.tool, run.latest.result, list) }
                    Text(
                        listOfNotNull(latest.title, summaryOf(latest)).joinToString(" "),
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
                        is ConversationItems.Notice -> Muted(stringResource(R.string.peer_task_notice), partOpen, partToggle) {
                            SelectionContainer { Text(part.entry.text, style = MaterialTheme.typography.bodySmall) }
                        }
                        is ConversationItems.Thinking -> Muted(stringResource(R.string.conversation_thinking), partOpen, partToggle) {
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
        Text(stringResource(R.string.conversation_working), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReplyRow(reply: Reply, now: Long) {
    val label = reply.label(now)
    val text = stringResource(
        when (label) {
            Reply.Label.SENDING -> R.string.conversation_sending
            Reply.Label.NOT_YET_CONFIRMED -> R.string.conversation_reply_not_yet_confirmed
            Reply.Label.WAITING_FOR_AGENT -> R.string.conversation_reply_waiting_for_agent
            Reply.Label.NOT_IN_CONVERSATION_YET -> R.string.conversation_reply_not_in_conversation_yet
            Reply.Label.NOT_SHOWN_AFTER_WORK -> R.string.conversation_reply_not_shown_after_work
            Reply.Label.COMMAND_NOT_SHOWN -> R.string.conversation_reply_command_not_shown
            Reply.Label.MAY_NOT_HAVE_BEEN_SENT -> R.string.conversation_reply_may_not_have_been_sent
            Reply.Label.NOT_SUBMITTED -> R.string.conversation_reply_not_submitted
        },
    )
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
                    Icon(SpekIcons.Attach, stringResource(R.string.conversation_attachments), Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                    Text(reply.attachments.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun Composer(
    sessionId: String,
    c: Conversation?,
    connected: Boolean,
    paused: Boolean,
    answerable: Boolean,
    canAttach: Boolean,
    inboxPrompt: Boolean = false,
    panelMax: Dp = 240.dp,
    onPanel: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // The draft is held by Desktop across leaving the screen; plain `remember`, never saved instance state. A value,
    // not a string, so a chosen skill can place the cursor and a typed slash be read at it (`tool-call-display` D10).
    var field by remember(sessionId) { mutableStateOf((c?.draft ?: "").let { TextFieldValue(it, TextRange(it.length)) }) }
    val text = field.text
    LaunchedEffect(c?.draftReturns) { if (c != null && c.draftReturns > 0) field = TextFieldValue(c.draft, TextRange(c.draft.length)) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // Back closes the skills until the text changes (D10).
    var closedFor by remember(sessionId) { mutableStateOf<String?>(null) }
    // The session's skills, once the desktop said anything about them this watch, offered from a typed slash (D10).
    val skills = c?.skills?.takeIf { c.watch.active }
    val query = SlashQuery.query(field.text, field.selection.start, field.selection.end)
    val slash = skills != null && query != null && focused && connected && !paused && !c.closed && closedFor != field.text
    BackHandler(enabled = slash) { closedFor = field.text }
    LaunchedEffect(slash) { if (slash) onPanel() }
    val inFlight = c?.replies?.any { it.state == Reply.State.SENDING } == true
    val attachments = c?.attachments.orEmpty()
    val blocked = Replies.blocked(connected, paused, c?.watch ?: Watch.Idle, c?.wait ?: WaitState.UNKNOWN, c?.pending, inFlight, text, attachments, inboxPrompt)
    // The photo picker refuses a maximum below 2, so it is always built for 10; extra picks are dropped (design D5).
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Uploads.MAX_ATTACHMENTS)) { uris ->
        Attach.add(context, sessionId, uris, "photo")
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        Attach.add(context, sessionId, uris, "file")
    }
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        c?.attachNote?.let { Notice(attachNoteText(it).text(), attachNoteTone(it), stringResource(R.string.common_dismiss), { Desktop.dismissAttachNote(sessionId) }) }
        // A reply the desktop refused because a first prompt from the inbox waits there (`inbox` design D7).
        if (c?.prefillRefused == true && blocked != Blocked.InboxPrompt) {
            Text(stringResource(R.string.conversation_prefill_refused), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        blockedText(blocked, answerable)?.let {
            Text(it.text(), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        // Past the agent CLI's limit, a command is taken as pasted text (D6); sending stays allowed.
        if (Replies.pastedCommand(text, attachments.map { it.name })) {
            Text(stringResource(R.string.conversation_long_command), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outlineVariant)
        Column(Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (slash) {
                SlashPanel(skills!!, query!!, panelMax) { skill ->
                    val inserted = SkillInsert.atCursor(field.text, field.selection.start, skill.name)
                    field = TextFieldValue(inserted.text, TextRange(inserted.cursor))
                    // Set by the program, so not through onValueChange: kept for leaving the screen.
                    Desktop.setDraft(sessionId, inserted.text)
                    focus.requestFocus()
                }
            }
            if (attachments.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (attachment in attachments) AttachmentCard(attachment)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canAttach) {
                    Box {
                        IconButton(enabled = connected && !paused && c?.closed != true, onClick = { picking = true }) { Icon(SpekIcons.Attach, stringResource(R.string.conversation_attach)) }
                        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_photo)) }, leadingIcon = { Icon(SpekIcons.Image, null) }, onClick = {
                                picking = false
                                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.conversation_file)) }, leadingIcon = { Icon(SpekIcons.File, null) }, onClick = {
                                picking = false
                                files.launch(arrayOf("*/*"))
                            })
                        }
                    }
                }
                TextField(
                    value = field,
                    onValueChange = {
                        if (it.text != field.text) Desktop.setDraft(sessionId, it.text)
                        field = it
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { focused = it.isFocused },
                    placeholder = { Text(stringResource(if (skills != null) R.string.conversation_reply_hint_skills else R.string.conversation_reply_hint)) },
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
                        field = TextFieldValue("")
                        scope.launch { if (!Desktop.send(sessionId, sending)) field = TextFieldValue(sending, TextRange(sending.length)) }
                    },
                    modifier = Modifier.padding(bottom = 4.dp),
                ) { Icon(SpekIcons.Send, stringResource(R.string.conversation_send)) }
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
                listOfNotNull(formatBytes(a.size).takeIf { a.size > 0 }, attachmentStateText(a).text()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed != null) colors.error else colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (failed != null && a.file != null) {
                TextButton(onClick = { Desktop.retryAttachment(a.localId) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(stringResource(R.string.common_retry)) }
            }
        }
        IconButton(onClick = { Desktop.removeAttachment(a.localId) }) { Icon(SpekIcons.Close, stringResource(R.string.conversation_remove_attachment), Modifier.size(18.dp)) }
    }
}

private fun attachmentIcon(name: String): ImageVector =
    if (name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif")) SpekIcons.Image else SpekIcons.File

/** D5: too many picked is a warning; a file that cannot go is an error. */
private fun attachNoteTone(note: AttachNote): Tone = if (note == AttachNote.TOO_MANY_PICKED) Tone.Warning else Tone.Error


private fun attachmentStateText(a: Attachment): UiText = when (val s = a.state) {
    AttachmentState.Preparing -> UiText.Res(R.string.conversation_attachment_preparing)
    AttachmentState.Queued -> UiText.Res(R.string.conversation_attachment_queued)
    is AttachmentState.Uploading -> UiText.Res(R.string.conversation_attachment_uploading, (if (a.size > 0) (s.received * 100 / a.size) else 0).toInt())
    is AttachmentState.Ready -> UiText.Res(R.string.conversation_attachment_ready)
    is AttachmentState.Failed -> failedText(s)
}

private fun failedText(f: AttachmentState.Failed): UiText = when (f.code) {
    RemoteError.TOO_LARGE -> f.limit?.let { UiText.Res(R.string.conversation_upload_too_large_of, formatBytes(it)) } ?: UiText.Res(R.string.conversation_upload_too_large)
    RemoteError.TOO_MANY -> UiText.Res(R.string.conversation_upload_too_many, (f.limit ?: Uploads.MAX_ATTACHMENTS).toInt())
    RemoteError.UNSUPPORTED_TYPE -> UiText.Res(R.string.conversation_upload_unsupported_type)
    RemoteError.NO_CONVERSATION -> UiText.Res(R.string.conversation_upload_no_conversation)
    RemoteError.UNKNOWN_OPERATION -> UiText.Res(R.string.conversation_upload_unknown_operation)
    Uploads.NO_ANSWER -> UiText.Res(R.string.conversation_upload_no_answer)
    Uploads.GONE -> UiText.Res(R.string.conversation_upload_gone)
    Uploads.FILE -> UiText.Res(R.string.conversation_file_unreadable)
    else -> UiText.Res(R.string.conversation_upload_failed, f.code)
}

private fun attachNoteText(note: AttachNote): UiText = when (note) {
    AttachNote.TOO_MANY_PICKED -> UiText.Res(R.string.conversation_note_too_many_picked, Uploads.MAX_ATTACHMENTS)
    AttachNote.KIND -> UiText.Res(R.string.conversation_note_kind)
    AttachNote.SIZE -> UiText.Res(R.string.conversation_note_size)
    AttachNote.EMPTY -> UiText.Res(R.string.conversation_note_empty)
    AttachNote.UNREADABLE -> UiText.Res(R.string.conversation_file_unreadable)
    AttachNote.SEND_NOT_FOUND -> UiText.Res(R.string.conversation_note_send_not_found)
}

private fun blockedText(blocked: Blocked?, answerable: Boolean): UiText? = when (blocked) {
    null, Blocked.Empty -> null
    Blocked.Paused -> UiText.Res(R.string.conversation_blocked_paused)
    Blocked.NotConnected -> UiText.Res(R.string.conversation_blocked_not_connected)
    Blocked.NotWatching -> UiText.Res(R.string.conversation_catching_up)
    is Blocked.AwaitingChoice -> if (answerable) UiText.Res(R.string.conversation_blocked_answer_above) else {
        // Only what the event carries; an event without the request says so plainly, as the desktop does.
        val asked = listOfNotNull(blocked.pending?.tool, blocked.pending?.arg).filter { it.isNotBlank() }.joinToString(" — ")
        if (asked.isEmpty()) UiText.Res(R.string.conversation_blocked_choice) else UiText.Res(R.string.conversation_blocked_choice_asked, asked)
    }
    Blocked.Unknown -> UiText.Res(R.string.conversation_blocked_unknown)
    Blocked.InFlight -> UiText.Res(R.string.conversation_sending)
    Blocked.Attaching -> UiText.Res(R.string.conversation_blocked_attaching)
    Blocked.AttachmentFailed -> UiText.Res(R.string.conversation_blocked_attachment_failed)
    Blocked.TooLong -> UiText.Res(R.string.conversation_blocked_too_long)
    // The inbox note above the composer says why (`inbox` design D7).
    Blocked.InboxPrompt -> null
}
