package com.spekterm.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val operations by Desktop.operations.collectAsStateWithLifecycle()
    val actionsEnabled = SessionActions.enabled(connected, announcements.paused, sessions)
    val dialogs = rememberSessionDialogs()

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

    Column(Modifier.fillMaxSize()) {
        TopBar(desktopName) {
            TextButton(onClick = { picking = true }, enabled = canCreate) { Text("New") }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (groups.isNotEmpty()) {
                        val anyExpanded = groups.any { it.key !in collapsed }
                        DropdownMenuItem(
                            text = { Text(if (anyExpanded) "Collapse all" else "Expand all") },
                            onClick = {
                                menu = false
                                setCollapsed(if (anyExpanded) collapsed + groups.map { it.key } else emptySet())
                            },
                        )
                    }
                    DropdownMenuItem(text = { Text("Settings") }, onClick = {
                        menu = false
                        onStatus()
                    })
                }
            }
        }
        when {
            announcements.paused -> Notice("The connection is paused.", "Resume", onResume)
            !connected || !sessions.current -> {
                val age = sessions.receivedAt?.let { " Showing the list from ${time.format(Date(it))}." } ?: " No session list received yet."
                Notice(notConnectedReason(status, announcements) + age, "Details", onStatus)
            }
            list?.window == false -> Notice("spekterm has no open window on the desktop, so it lists no sessions. Open spekterm there.")
        }
        creation?.let { CreationNotice(it, onOpen) }
        if (list == null || (list.window && list.sessions.isEmpty())) {
            if (list != null) Text("No sessions on the desktop.", Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            for (group in groups) {
                val folded = group.key in collapsed
                stickyHeader(key = "group:${group.key}") {
                    Column {
                        GroupHeading(group, folded) { setCollapsed(if (folded) collapsed - group.key else collapsed + group.key) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (!folded) {
                    items(group.rows, key = { "session:${it.session.id}" }) { row ->
                        SessionRow(
                            row,
                            actions = SessionActions.of(row.session, operations),
                            answers = SessionActions.offerActions(row.session, operations),
                            actionsEnabled = actionsEnabled,
                            onClick = { onOpen(row.session.id) },
                            onAction = { dialogs.choose(it, row.session) },
                            onAnswer = { dialogs.answerOffer(it, row.session) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    SessionDialogsHost(dialogs, actionsEnabled)

    if (picking && list != null) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            Text(
                "Start an agent session in",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(SessionsView.choices(list), key = { it.folderId ?: "\u0000global" }) { choice ->
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            picking = false
                            Desktop.create(choice.folderId, choice.name)
                        }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(choice.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        choice.branch?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** What happened to the session being started (design D7). */
@Composable
private fun CreationNotice(c: Creation, onOpen: (String) -> Unit) {
    val dismiss = { Desktop.consumeCreation() }
    when (c) {
        is Creation.Starting -> Notice("Starting a session in ${c.name}…")
        is Creation.Opened -> if (!c.autoOpen) {
            Notice("Started a session in ${c.name}.", "Open", {
                Desktop.consumeCreation()
                onOpen(c.sessionId)
            }, "Dismiss", dismiss)
        }
        is Creation.Unlisted -> Notice("Started a session in ${c.name}; it will appear in the list.", "Dismiss", dismiss)
        is Creation.Failed -> Notice(
            when (c.reason) {
                Creation.Reason.NO_WINDOW -> "Could not start a session in ${c.name}: spekterm has no open window on the desktop."
                Creation.Reason.CREATE_FAILED -> "The desktop could not start a session in ${c.name}" + (c.message?.let { ": $it" } ?: ".")
                Creation.Reason.UNKNOWN -> "Can't tell whether a session was started in ${c.name}. If it was, it will appear in the list."
            },
            "Dismiss",
            dismiss,
        )
    }
}

/** A folder's heading: set apart from the rows, stays on top while its rows scroll, toggles collapsing (D2). */
@Composable
private fun GroupHeading(group: SessionsView.Group, folded: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (folded) "▸" else "▾", Modifier.width(20.dp), style = MaterialTheme.typography.titleSmall)
            // Name and branch take the rest of the line, so the count always sits at the end.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        group.global -> "Global"
                        group.name != null -> group.name
                        else -> "Other folders"
                    },
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                group.branch?.let {
                    Text(
                        it,
                        Modifier.widthIn(max = 140.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text("${group.rows.size}", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (folded) {
            val parts = listOfNotNull(
                group.summary.choice.takeIf { it > 0 }?.let { (if (it == 1) "1 asks for a choice" else "$it ask for a choice") to MaterialTheme.colorScheme.error },
                group.summary.offers.takeIf { it > 0 }?.let { (if (it == 1) "1 asks to close" else "$it ask to close") to MaterialTheme.colorScheme.primary },
                group.summary.ready.takeIf { it > 0 }?.let { "$it waiting for you" to MaterialTheme.colorScheme.primary },
            )
            if (parts.isNotEmpty()) {
                Row(Modifier.padding(start = 20.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    parts.forEachIndexed { i, (text, color) ->
                        if (i > 0) Text("·", style = MaterialTheme.typography.labelMedium)
                        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
            }
        }
    }
}

/** A tap opens the session; a long press opens its menu when the desktop offers an action (design D8). */
@Composable
private fun SessionRow(
    row: SessionsView.Row,
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(session.label.ifEmpty { session.id }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val second = listOfNotNull(
                        if (session.isAgent) "Agent" else "Shell",
                        session.lifecycle?.let { "handoff: $it" },
                    ).joinToString(" · ")
                    Text(second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // An offer takes the place of "Waiting for you" only; working or a choice still shows (close-offer D5).
                stateLabel(row.state).takeUnless { row.offered && row.state == RowState.READY }?.let { (text, color) ->
                    Text(text, style = MaterialTheme.typography.labelMedium, color = color ?: MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.offered) CloseOffer(answers, actionsEnabled, onAnswer, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        SessionMenu(menu, { menu = false }, actions, actionsEnabled, onAction)
    }
}

@Composable
private fun stateLabel(state: RowState): Pair<String, Color?>? = when (state) {
    RowState.DORMANT -> "Dormant" to null
    RowState.READY -> "Waiting for you" to MaterialTheme.colorScheme.primary
    RowState.BUSY -> "Working" to null
    RowState.CHOICE -> "Asks for a choice" to MaterialTheme.colorScheme.error
    RowState.NONE -> null
}

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
