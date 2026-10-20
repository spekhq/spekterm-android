package com.spekterm.android.ui

import com.spekterm.android.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import com.spekterm.android.service.Status
import com.spekterm.android.ui.theme.spekColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spekterm.android.app
import com.spekterm.android.desktop.Creation
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.net.Operations
import com.spekterm.android.desktop.InboxView
import com.spekterm.android.desktop.OpenSpecGates
import com.spekterm.android.desktop.Relations
import com.spekterm.android.desktop.SessionAction
import com.spekterm.android.desktop.SessionActions
import com.spekterm.android.desktop.SessionsView
import com.spekterm.android.desktop.SessionsView.RowState
import com.spekterm.android.service.StatusBoard
import com.spekterm.android.store.Announcements
import com.spekterm.android.store.CollapsedStore
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The paired app's main screen: the desktop's sessions by folder, collapsible, and starting a new one
 * (`session-list`, `session-create`; design D2–D7).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    desktopName: String,
    deviceId: String,
    announcements: Announcements,
    onOpen: (String) -> Unit,
    onStatus: () -> Unit,
    onResume: () -> Unit,
    onOpenSpecFolders: () -> Unit,
    onOpenSpecFolder: (String) -> Unit,
    onHandoff: (String) -> Unit,
    onInbox: () -> Unit,
) {
    val sessions by Desktop.sessions.collectAsStateWithLifecycle()
    val connected by Desktop.connected.collectAsStateWithLifecycle()
    val creation by Desktop.creation.collectAsStateWithLifecycle()
    val status by StatusBoard.status.collectAsStateWithLifecycle()
    val list = sessions.list
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val store = LocalContext.current.app.collapsed
    var collapsed by remember(deviceId) { mutableStateOf(store.load(deviceId)) }
    val writer = remember(deviceId) { CollapsedWriter(store, deviceId) }
    fun setCollapsed(keys: Set<String>) {
        collapsed = keys
        writer.save(keys)
    }
    val canCreate = SessionsView.canCreate(connected, announcements.paused, sessions, creation)
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val groups = list?.let { SessionsView.groups(it) }.orEmpty()
    val existing = remember(list) { list?.let { Relations.existing(it) }.orEmpty() }
    val operations by Desktop.operations.collectAsStateWithLifecycle()
    val inbox by Desktop.inbox.collectAsStateWithLifecycle()
    val actionsEnabled = SessionActions.enabled(connected, announcements.paused, sessions)
    val dialogs = rememberSessionDialogs()
    val openspec = OpenSpecGates.of(operations)

    // A created session opens by itself only while this screen is at the front (design D7).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> Desktop.setListOnScreen(true)
                Lifecycle.Event.ON_PAUSE -> Desktop.setListOnScreen(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            Desktop.setListOnScreen(false)
        }
    }
    LaunchedEffect(creation) {
        val c = creation
        if (c is Creation.Opened && c.autoOpen) {
            Desktop.consumeCreation()
            onOpen(c.sessionId)
        }
    }
    LaunchedEffect(canCreate) { if (!canCreate) picking = false }

    val listState = rememberLazyListState()
    // Read through derivedStateOf: the button changes only when this flips, not on every scrolled row.
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    SpekScaffold(
        topBar = { scroll ->
            TopBar(desktopName, scroll) {
                InboxButton(InboxView.count(inbox.items), Operations.INBOX_LIST in operations || inbox.items.isNotEmpty(), onInbox)
                Box {
                    MoreButton({ menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (groups.isNotEmpty()) {
                            val anyExpanded = groups.any { it.key !in collapsed }
                            DropdownMenuItem(
                                text = { Text(if (anyExpanded) stringResource(R.string.sessions_collapse_all) else stringResource(R.string.sessions_expand_all)) },
                                onClick = {
                                    menu = false
                                    setCollapsed(if (anyExpanded) collapsed + groups.map { it.key } else emptySet())
                                },
                            )
                        }
                        // Also reaches folders with no session, which have no heading (`openspec-browse` D8).
                        if (openspec.menu(list?.folders.orEmpty())) {
                            DropdownMenuItem(text = { Text("OpenSpec") }, onClick = {
                                menu = false
                                onOpenSpecFolders()
                            })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.status_title)) }, onClick = {
                            menu = false
                            onStatus()
                        })
                    }
                }
            }
        },
        floatingActionButton = {
            if (list != null) NewSessionButton(canCreate, expanded = atTop) { picking = true }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            when {
                announcements.paused -> Notice(stringResource(R.string.sessions_paused), Tone.Warning, stringResource(R.string.common_resume), onResume)
                !connected || !sessions.current -> {
                    val reason = notConnectedReason(status, announcements).text()
                    val text = sessions.receivedAt?.let { stringResource(R.string.sessions_list_from, reason, time.format(Date(it))) }
                        ?: stringResource(R.string.sessions_no_list_yet, reason)
                    Notice(text, Tone.Warning, stringResource(R.string.common_details), onStatus)
                }
                list?.window == false -> Notice(stringResource(R.string.sessions_no_window), Tone.Warning)
            }
            creation?.let { CreationNotice(it, onOpen) }
            if (list == null) {
                // Only while a list is on its way; otherwise the notice above says why there is none (D6).
                val coming = !announcements.paused && (status.state == Status.State.CONNECTING || status.state == Status.State.CONNECTED)
                if (coming) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            if (list.window && list.sessions.isEmpty()) {
                CenteredMessage(SpekIcons.Shell, stringResource(R.string.sessions_empty)) {
                    if (canCreate) Text(stringResource(R.string.sessions_empty_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
                for (group in groups) {
                    val folded = group.key in collapsed
                    stickyHeader(key = "group:${group.key}", contentType = "heading") {
                        GroupHeading(
                            group,
                            folded,
                            onOpenSpec = group.folderId?.takeIf { openspec.heading(group) }?.let { id -> { onOpenSpecFolder(id) } },
                        ) { setCollapsed(if (folded) collapsed - group.key else collapsed + group.key) }
                    }
                    if (!folded) {
                        items(group.rows, key = { "session:${it.session.id}" }, contentType = { "session" }) { row ->
                            SessionRow(
                                row,
                                relation = Relations.line(row.session, row.nested, list, existing)?.text(),
                                children = Relations.childrenText(row.session, list, existing)?.text(),
                                actions = SessionActions.of(row.session, operations, list),
                                answers = SessionActions.offerActions(row.session, operations),
                                actionsEnabled = actionsEnabled,
                                onClick = { onOpen(row.session.id) },
                                onAction = { if (it == SessionActions.Action.HANDOFF) onHandoff(row.session.id) else dialogs.choose(it, row.session) },
                                onAnswer = { dialogs.answerOffer(it, row.session) },
                            )
                        }
                    }
                }
            }
        }
    }

    SessionDialogsHost(dialogs, actionsEnabled)

    if (picking && list != null) {
        val globalName = stringResource(R.string.sessions_global)
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            Text(
                stringResource(R.string.sessions_start_in),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(SessionsView.choices(list, globalName), key = { it.folderId ?: "\u0000global" }) { choice ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            picking = false
                            Desktop.create(choice.folderId, choice.name)
                        }.heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(SpekIcons.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(choice.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            choice.branch?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Starting a session (D6). Always shown, as `session-create` wants the offer kept while it can't be used: then it is
 * dimmed, ignores taps and says it is disabled. Collapsed to its icon, the icon carries the label.
 */
@Composable
private fun NewSessionButton(enabled: Boolean, expanded: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ExtendedFloatingActionButton(
        text = { Text(stringResource(R.string.sessions_new)) },
        icon = { Icon(SpekIcons.Add, if (expanded) null else stringResource(R.string.sessions_new)) },
        onClick = { if (enabled) onClick() },
        expanded = expanded,
        containerColor = if (enabled) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (enabled) colors.onPrimaryContainer else colors.outline,
        modifier = if (enabled) Modifier else Modifier.semantics { disabled() },
    )
}

/** What happened to the session being started (design D7). */
@Composable
private fun CreationNotice(c: Creation, onOpen: (String) -> Unit) {
    val dismiss = { Desktop.consumeCreation() }
    when (c) {
        is Creation.Starting -> Notice(stringResource(R.string.sessions_starting, c.name), Tone.Info)
        is Creation.Opened -> if (!c.autoOpen) {
            Notice(stringResource(R.string.sessions_started, c.name), Tone.Info, stringResource(R.string.sessions_open), {
                Desktop.consumeCreation()
                onOpen(c.sessionId)
            }, stringResource(R.string.common_dismiss), dismiss)
        }
        is Creation.Unlisted -> Notice(stringResource(R.string.sessions_started_unlisted, c.name), Tone.Info, stringResource(R.string.common_dismiss), dismiss)
        is Creation.Failed -> Notice(
            when (c.reason) {
                Creation.Reason.NO_WINDOW -> stringResource(R.string.sessions_create_no_window, c.name)
                Creation.Reason.CREATE_FAILED -> c.message?.let { stringResource(R.string.sessions_create_failed_message, c.name, it) }
                    ?: stringResource(R.string.sessions_create_failed, c.name)
                Creation.Reason.UNKNOWN -> stringResource(R.string.sessions_create_unknown, c.name)
            },
            // Not knowing is not a failure (D5).
            if (c.reason == Creation.Reason.UNKNOWN) Tone.Warning else Tone.Error,
            stringResource(R.string.common_dismiss),
            dismiss,
        )
    }
}

/** A folder's heading: set apart from the rows, stays on top while its rows scroll, toggles collapsing (D2, D6). */
@Composable
private fun GroupHeading(group: SessionsView.Group, folded: Boolean, onOpenSpec: (() -> Unit)?, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spek = spekColors
    val turn by animateFloatAsState(if (folded) -90f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable(onClick = onToggle)
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SpekIcons.ExpandMore, if (folded) stringResource(R.string.sessions_expand) else stringResource(R.string.sessions_collapse), Modifier.size(20.dp).rotate(turn), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                // Name and branch take the rest of the line, so the count always sits at the end.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            group.global -> stringResource(R.string.sessions_global)
                            group.name != null -> group.name
                            else -> stringResource(R.string.sessions_other_folders)
                        },
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    group.branch?.let {
                        Text(
                            it,
                            Modifier.widthIn(max = 140.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Its own click target: the heading's tap stays collapsing (`openspec-browse` D8).
                if (onOpenSpec != null) {
                    IconButton(onClick = onOpenSpec, Modifier.padding(start = 4.dp).size(40.dp)) {
                        Icon(SpekIcons.File, stringResource(R.string.conversation_openspec_for, group.name ?: ""), Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    }
                }
                Text(
                    "${group.rows.size}",
                    Modifier
                        .padding(start = if (onOpenSpec != null) 4.dp else 12.dp)
                        .background(colors.surfaceContainerHigh, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            if (folded) {
                val parts = listOfNotNull(
                    group.summary.choice.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.sessions_summary_choice, it, it) to spek.attention },
                    group.summary.offers.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.sessions_summary_offers, it, it) to spek.offer },
                    group.summary.ready.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.sessions_summary_ready, it, it) to spek.attention },
                )
                if (parts.isNotEmpty()) {
                    Row(Modifier.padding(start = 28.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        parts.forEachIndexed { i, (text, color) ->
                            if (i > 0) Text("·", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

/** A tap opens the session; a long press opens its menu when the desktop offers an action (design D8). */
@Composable
private fun SessionRow(
    row: SessionsView.Row,
    relation: String?,
    children: String?,
    actions: List<SessionActions.Action>,
    answers: List<SessionAction.Kind>,
    actionsEnabled: Boolean,
    onClick: () -> Unit,
    onAction: (SessionActions.Action) -> Unit,
    onAnswer: (SessionAction.Kind) -> Unit,
) {
    val session = row.session
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (actions.isEmpty()) null else {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu = true
                        }
                    },
                )
                .heightIn(min = 56.dp)
                // Nested under its source as on the desktop's rail (`session-relations` D1).
                .padding(start = 16.dp + INDENT * row.depth, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(
                    if (session.isAgent) SpekIcons.Agent else SpekIcons.Shell,
                    if (session.isAgent) stringResource(R.string.sessions_agent) else stringResource(R.string.sessions_shell),
                    Modifier.size(20.dp),
                    // Faint: it tells agent from shell, but most rows are agents and it must not compete with the state.
                    tint = MaterialTheme.colorScheme.outline,
                )
                Column(Modifier.weight(1f)) {
                    Text(session.label.ifEmpty { session.id }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val background = SessionsView.backgroundLine(SessionsView.backgroundCounts(SessionsView.background(session)))?.text()
                    for (line in listOfNotNull(relation, children, background)) {
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // An offer takes the place of "Waiting for you" only; working or a choice still shows (close-offer D5).
                if (!(row.offered && (row.state == RowState.READY || row.state == RowState.IDLE))) StateLabel(row.state)
            }
            if (row.offered) CloseOffer(answers, actionsEnabled, onAnswer, Modifier.fillMaxWidth().padding(start = 34.dp, top = 4.dp))
        }
        SessionMenu(menu, { menu = false }, actions, actionsEnabled, onChoose = onAction)
    }
}

/** One level of a handoff tree. */
private val INDENT = 16.dp

/** Writes the collapsed groups off the main thread, one write at a time, always the latest set (design D3). */
@OptIn(ExperimentalCoroutinesApi::class)
private class CollapsedWriter(private val store: CollapsedStore, private val deviceId: String) {
    private val latest = AtomicReference<Set<String>?>(null)

    fun save(keys: Set<String>) {
        latest.set(keys)
        scope.launch { latest.getAndSet(null)?.let { runCatching { store.save(deviceId, it) } } }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    }
}
