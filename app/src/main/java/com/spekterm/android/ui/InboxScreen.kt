package com.spekterm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spekterm.android.R
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.InboxFailure
import com.spekterm.android.desktop.InboxOp
import com.spekterm.android.desktop.InboxView
import com.spekterm.android.desktop.ItemAction
import com.spekterm.android.net.InboxItem
import com.spekterm.android.net.Prefill
import com.spekterm.android.net.SessionList
import com.spekterm.android.service.StatusBoard
import com.spekterm.android.store.Announcements
import java.text.DateFormat
import java.util.Date

/**
 * The desktop's inbox (`inbox` design D5): scheduled handoffs, items waiting for the user, and opened items whose first
 * prompt waits. Bodies and prompts are third-party text: plain, whole, selectable, never links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    focus: String?,
    onFocused: () -> Unit,
    announcements: Announcements,
    onBack: () -> Unit,
    onStatus: () -> Unit,
    onResume: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val inbox by Desktop.inbox.collectAsStateWithLifecycle()
    val actions by Desktop.inboxActions.collectAsStateWithLifecycle()
    val sessions by Desktop.sessions.collectAsStateWithLifecycle()
    val connected by Desktop.connected.collectAsStateWithLifecycle()
    val operations by Desktop.operations.collectAsStateWithLifecycle()
    val status by StatusBoard.status.collectAsStateWithLifecycle()
    val list = sessions.list
    val gates = InboxView.gates(operations, connected, announcements.paused, sessions)
    // The user's folder picks on this screen, by item key (design D4).
    var picks by rememberSaveable { mutableStateOf(emptyMap<String, String>()) }
    var confirming by remember { mutableStateOf<InboxItem?>(null) }

    // An accepted item's conversation opens by itself only while this screen is at the front (design D6).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> Desktop.setInboxOnScreen(true)
                Lifecycle.Event.ON_PAUSE -> Desktop.setInboxOnScreen(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            Desktop.setInboxOnScreen(false)
        }
    }
    LaunchedEffect(actions) {
        val opened = actions.entries.firstOrNull { (_, action) -> action is ItemAction.Opened && action.autoOpen } ?: return@LaunchedEffect
        Desktop.consumeOpened(opened.key)
        onOpen((opened.value as ItemAction.Opened).sessionId)
    }

    val scheduled = InboxView.scheduled(inbox.items)
    val waiting = InboxView.waiting(inbox.items)
    val opened = InboxView.opened(inbox.items, sessions)
    val shownKeys = (scheduled + waiting + opened).map { it.key }.toSet()
    // What happened to an item that is no longer shown: an accept whose item left, opened or not listed yet.
    val orphans = actions.filter { (key, action) -> key !in shownKeys && (action is ItemAction.Opened || action is ItemAction.Unlisted) }

    val listState = rememberLazyListState()
    LaunchedEffect(focus, shownKeys.contains(focus)) {
        val key = focus ?: return@LaunchedEffect
        val keys = buildList {
            if (scheduled.isNotEmpty()) add("h:scheduled")
            scheduled.forEach { add(it.key) }
            if (waiting.isNotEmpty()) add("h:waiting")
            waiting.forEach { add(it.key) }
            if (opened.isNotEmpty()) add("h:opened")
            opened.forEach { add(it.key) }
        }
        val index = keys.indexOf(key)
        if (index >= 0) {
            listState.scrollToItem(index)
            onFocused()
        }
    }

    SpekScaffold(
        topBar = { scroll ->
            TopBar(stringResource(R.string.inbox_title), scroll, onBack = onBack) {
                Box {
                    var menu by remember { mutableStateOf(false) }
                    MoreButton({ menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.status_title)) }, onClick = {
                            menu = false
                            onStatus()
                        })
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            when {
                announcements.paused -> Notice(stringResource(R.string.sessions_paused), Tone.Warning, stringResource(R.string.common_resume), onResume)
                !connected || !inbox.current -> {
                    val reason = notConnectedReason(status, announcements).text()
                    val text = inbox.receivedAt?.let { stringResource(R.string.inbox_list_from, reason, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) }
                        ?: stringResource(R.string.inbox_no_list_yet, reason)
                    Notice(text, Tone.Warning, stringResource(R.string.common_details), onStatus)
                }
                list?.window == false -> Notice(stringResource(R.string.sessions_no_window), Tone.Warning)
            }
            for ((key, action) in orphans) {
                val (text, sessionId) = when (action) {
                    is ItemAction.Opened -> stringResource(R.string.inbox_opened_link) to action.sessionId
                    is ItemAction.Unlisted -> unlistedText(action) to action.sessionId
                    else -> continue
                }
                Notice(text, Tone.Info, stringResource(R.string.inbox_open_session), { onOpen(sessionId) }, stringResource(R.string.common_dismiss), { Desktop.dismissInboxAction(key) })
            }
            if (scheduled.isEmpty() && waiting.isEmpty() && opened.isEmpty()) {
                CenteredMessage(SpekIcons.Inbox, stringResource(R.string.inbox_empty))
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                if (scheduled.isNotEmpty()) {
                    item(key = "h:scheduled") { Heading(stringResource(R.string.inbox_scheduled)) }
                    items(scheduled, key = { it.key }) { item ->
                        ScheduledCard(item, list, actions[item.key], gates, onOpen, onRun = { Desktop.inboxAccept(item, item.folderId!!) }, onCancel = { confirming = item })
                    }
                }
                if (waiting.isNotEmpty()) {
                    item(key = "h:waiting") { Heading(stringResource(R.string.inbox_waiting)) }
                    items(waiting, key = { it.key }) { item ->
                        val selection = InboxView.preselect(picks[item.key], item, list)
                        WaitingCard(
                            item, list, selection, actions[item.key], gates, onOpen,
                            onPick = { picks = picks + (item.key to it) },
                            onAccept = { folderId -> Desktop.inboxAccept(item, folderId) },
                            onDismiss = { confirming = item },
                        )
                    }
                }
                if (opened.isNotEmpty()) {
                    item(key = "h:opened") { Heading(stringResource(R.string.inbox_opened)) }
                    items(opened, key = { it.key }) { item ->
                        val session = list?.sessions?.firstOrNull { it.id == item.sessionId }
                        val prefill = session?.prefill?.takeIf { it.intake.adapter == item.adapter && it.intake.id == item.id }
                        OpenedCard(
                            item = item,
                            folderName = session?.folderId?.let { id -> list.folders.firstOrNull { it.id == id }?.name },
                            prefill = prefill,
                            running = session?.running == true,
                            canSend = com.spekterm.android.net.Operations.SUBMIT_PREFILL in operations,
                            action = actions[item.key],
                            enabled = gates.send,
                            onOpen = { item.sessionId?.let(onOpen) },
                            onSend = { prefill?.let { Desktop.submitPrefill(item.key, item.sessionId!!, it.id) } },
                        )
                    }
                }
            }
        }
    }
    // Forget picks of items that left.
    LaunchedEffect(inbox.items) {
        val keys = inbox.items.map { it.key }.toSet()
        if (picks.keys.any { it !in keys }) picks = picks.filterKeys { it in keys }
    }
    confirming?.let { item ->
        val scheduledItem = item.scheduledAt != null
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(if (scheduledItem) R.string.inbox_cancel_confirm_title else R.string.inbox_dismiss_confirm_title)) },
            text = { Text(stringResource(if (scheduledItem) R.string.inbox_cancel_confirm else R.string.inbox_dismiss_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    Desktop.inboxIgnore(item)
                }) { Text(stringResource(if (scheduledItem) R.string.inbox_cancel_scheduled else R.string.inbox_dismiss)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.inbox_keep)) } },
        )
    }
}

/** The session list's way to the inbox (`inbox` design D9), with the count of items waiting for the user. */
@Composable
fun InboxButton(count: Int, shown: Boolean, onClick: () -> Unit) {
    if (!shown) return
    val label = if (count > 0) pluralStringResource(R.plurals.inbox_entry_count, count, count) else stringResource(R.string.inbox_title)
    // The badge outside the button, whose round clip would cut it; past 99 it says "99+".
    BadgedBox(
        badge = { if (count > 0) Badge(Modifier.offset(x = (-10).dp, y = 10.dp)) { Text(if (count > 99) "99+" else count.toString()) } },
        Modifier.semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        IconButton(onClick = onClick, Modifier.testTag("inbox-button")) { Icon(SpekIcons.Inbox, null) }
    }
}

/**
 * What the conversation screen says about a first prompt from the inbox (`inbox` design D7), above the composer.
 * [onReview] opens the Inbox at the item; [onReplyAnyway] lifts an older desktop's hold on replies.
 */
@Composable
fun InboxNote(linked: InboxView.Linked, replyAnyway: Boolean, onOpenItem: (String) -> Unit, onReplyAnyway: () -> Unit) {
    when (linked) {
        is InboxView.Linked.Listed -> {
            val key = linked.item?.key
            if (!linked.prefill.edited && linked.shown && key != null) {
                Notice(stringResource(R.string.conversation_inbox_listed), Tone.Warning, stringResource(R.string.conversation_inbox_review), { onOpenItem(key) })
            } else {
                Notice(
                    stringResource(R.string.conversation_inbox_finish),
                    Tone.Warning,
                    if (linked.shown && key != null) stringResource(R.string.conversation_inbox_open) else null,
                    { key?.let(onOpenItem) },
                )
            }
        }
        is InboxView.Linked.Waiting -> if (linked.canSend) {
            Notice(stringResource(R.string.conversation_inbox_filling), Tone.Info, stringResource(R.string.conversation_inbox_open), { onOpenItem(linked.item.key) })
        } else {
            Notice(
                stringResource(R.string.conversation_inbox_old),
                Tone.Warning,
                stringResource(R.string.conversation_inbox_open),
                { onOpenItem(linked.item.key) },
                if (replyAnyway) null else stringResource(R.string.conversation_inbox_reply_anyway),
                onReplyAnyway,
            )
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ItemCard(content: @Composable () -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** A line of small, quiet text. */
@Composable
private fun Meta(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The title unless the body holds it, the body whole as plain selectable text, and its length (obligation 2). */
@Composable
private fun Body(item: InboxItem) {
    if (InboxView.showTitle(item.title, item.body)) {
        SelectionContainer { Text(item.title, style = MaterialTheme.typography.titleSmall) }
    }
    SelectionContainer { Text(item.body, style = MaterialTheme.typography.bodyMedium) }
    Meta(pluralStringResource(R.plurals.inbox_characters, item.bodyLength.toInt(), item.bodyLength))
}

/** A date and time in the app's language, read when composed: a format made once at start keeps the language it began with. */
@Composable
private fun whenText(at: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale, at) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(at)) }
}

@Composable
private fun ScheduledCard(
    item: InboxItem,
    list: SessionList?,
    action: ItemAction?,
    gates: InboxView.Gates,
    onOpen: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    val runnable = InboxView.runnable(item, list)
    ItemCard {
        val at = item.scheduledAt ?: 0
        Text(
            if (at <= System.currentTimeMillis()) stringResource(R.string.inbox_due) else stringResource(R.string.inbox_opens_at, whenText(at)),
            style = MaterialTheme.typography.labelLarge,
        )
        Meta(
            listOfNotNull(
                item.originLabel.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.inbox_from, it) },
                folderName(list, item.folderId)?.takeIf { runnable }?.let { stringResource(R.string.inbox_in_folder, it) },
            ).joinToString(" · "),
        )
        Body(item)
        if (!runnable) InboxView.noFolderReason(item, InboxView.Preselection(null))?.let { Meta(noFolderText(it)) }
        Outcome(action, onOpen)
        Actions(action) {
            TextButton(onClick = onCancel, enabled = gates.ignore && !busy(action)) { Text(stringResource(R.string.inbox_cancel_scheduled)) }
            Button(onClick = onRun, enabled = gates.accept && runnable && !busy(action)) { Text(stringResource(R.string.inbox_run_now)) }
        }
    }
}

@Composable
private fun WaitingCard(
    item: InboxItem,
    list: SessionList?,
    selection: InboxView.Preselection,
    action: ItemAction?,
    gates: InboxView.Gates,
    onOpen: (String) -> Unit,
    onPick: (String) -> Unit,
    onAccept: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val expired = InboxView.expired(item)
    ItemCard {
        Meta(
            listOfNotNull(
                item.actor.takeIf { it.isNotEmpty() },
                item.originLabel.takeIf { it.isNotEmpty() },
                item.occurredAt.takeIf { it > 0 }?.let { whenText(it) },
            ).joinToString(" · "),
        )
        if (expired) {
            Text(stringResource(R.string.inbox_expired), style = MaterialTheme.typography.bodyMedium)
        } else {
            Body(item)
            FolderPicker(list, selection, enabled = !busy(action), onPick)
            InboxView.noFolderReason(item, selection)?.let { Meta(noFolderText(it)) }
        }
        Outcome(action, onOpen)
        Actions(action) {
            TextButton(onClick = onDismiss, enabled = gates.ignore && !busy(action)) { Text(stringResource(R.string.inbox_dismiss)) }
            if (!expired) {
                Button(onClick = { selection.folderId?.let(onAccept) }, enabled = gates.accept && selection.folderId != null && !busy(action)) {
                    Text(stringResource(R.string.inbox_accept))
                }
            }
        }
    }
}

@Composable
private fun FolderPicker(list: SessionList?, selection: InboxView.Preselection, enabled: Boolean, onPick: (String) -> Unit) {
    val folders = list?.folders.orEmpty()
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.inbox_opens_in), style = MaterialTheme.typography.bodyMedium)
        Box {
            val label = stringResource(R.string.inbox_choose_folder_label)
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled && folders.isNotEmpty(),
                modifier = Modifier.semantics { contentDescription = label },
            ) {
                Text(folderName(list, selection.folderId) ?: stringResource(R.string.inbox_choose_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(SpekIcons.ExpandMore, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (folder in folders) {
                    DropdownMenuItem(text = { Text(folder.name.ifEmpty { folder.id }) }, onClick = {
                        open = false
                        onPick(folder.id)
                    })
                }
            }
        }
    }
}

@Composable
internal fun OpenedCard(
    item: InboxItem,
    folderName: String?,
    prefill: Prefill?,
    running: Boolean,
    canSend: Boolean,
    action: ItemAction?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onSend: () -> Unit,
) {
    ItemCard {
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                folderName?.let { stringResource(R.string.inbox_opened_in, it) } ?: stringResource(R.string.inbox_opened),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            item.originLabel.takeIf { it.isNotEmpty() }?.let { Meta(stringResource(R.string.inbox_from, it)) }
        }
        Body(item)
        when {
            prefill != null -> {
                Text(stringResource(R.string.inbox_prompt_label), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(prefill.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                if (prefill.edited) Meta(stringResource(R.string.inbox_prompt_edited))
            }
            running -> Meta(stringResource(if (canSend) R.string.inbox_prompt_filling else R.string.inbox_prompt_on_desktop))
        }
        Outcome(action, onOpen = { onOpen() })
        if (prefill != null && !prefill.edited && canSend) {
            Actions(action) {
                Button(onClick = onSend, enabled = enabled && !busy(action)) { Text(stringResource(R.string.inbox_send_prompt)) }
            }
        }
    }
}

/** The card's buttons, right-aligned, with progress while an action on it is under way. */
@Composable
private fun Actions(action: ItemAction?, buttons: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        if (busy(action)) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        buttons()
    }
}

private fun busy(action: ItemAction?): Boolean = action is ItemAction.InFlight || action is ItemAction.Opening

/** What the last action on an item came to (design D6, D8). */
@Composable
private fun Outcome(action: ItemAction?, onOpen: (String) -> Unit) {
    when (action) {
        null, is ItemAction.InFlight -> Unit
        is ItemAction.Failed -> Text(failureText(action.failure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        is ItemAction.Opening -> Meta(stringResource(R.string.inbox_opening))
        is ItemAction.Opened -> if (!action.autoOpen) LinkLine(stringResource(R.string.inbox_opened_link)) { onOpen(action.sessionId) }
        is ItemAction.Unlisted -> LinkLine(unlistedText(action)) { onOpen(action.sessionId) }
        is ItemAction.Submitted -> Meta(stringResource(if (action.confirmed) R.string.inbox_sent_confirmed else R.string.inbox_sent_unconfirmed))
    }
}

@Composable
private fun LinkLine(text: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Meta(text, Modifier.weight(1f, fill = false))
        TextButton(onClick = onOpen) { Text(stringResource(R.string.inbox_open_session)) }
    }
}

@Composable
private fun unlistedText(action: ItemAction.Unlisted): String =
    stringResource(if (action.connectionEnded) R.string.inbox_unlisted_connection else R.string.inbox_unlisted)

@Composable
private fun failureText(failure: InboxFailure): String = when (failure.reason) {
    InboxFailure.Reason.CHOOSE_FOLDER -> stringResource(R.string.inbox_fail_choose_folder)
    InboxFailure.Reason.COULD_NOT_OPEN -> failure.message?.let { stringResource(R.string.inbox_fail_could_not_open_detail, it) } ?: stringResource(R.string.inbox_fail_could_not_open)
    InboxFailure.Reason.NO_LONGER_IN_INBOX -> stringResource(R.string.inbox_fail_gone)
    InboxFailure.Reason.NO_WINDOW -> stringResource(R.string.inbox_fail_no_window)
    InboxFailure.Reason.TIMEOUT -> stringResource(R.string.inbox_fail_timeout)
    InboxFailure.Reason.NOT_CONNECTED -> stringResource(R.string.common_not_connected)
    InboxFailure.Reason.SESSION_NOT_RUNNING -> stringResource(R.string.inbox_fail_not_running)
    InboxFailure.Reason.PROMPT_GONE -> stringResource(R.string.inbox_fail_prompt_gone)
    InboxFailure.Reason.PROMPT_EDITED -> stringResource(R.string.inbox_prompt_edited)
    InboxFailure.Reason.AGENT_NOT_READY -> stringResource(R.string.inbox_fail_agent_not_ready, failure.message ?: "")
    InboxFailure.Reason.FAILED -> stringResource(R.string.inbox_fail_other, failure.code ?: "")
}

@Composable
private fun noFolderText(reason: InboxView.NoFolder): String = stringResource(
    when (reason) {
        InboxView.NoFolder.CHOSEN_GONE -> R.string.inbox_reason_chosen_gone
        InboxView.NoFolder.RULE_FOLDER_GONE -> R.string.inbox_reason_rule_gone
        InboxView.NoFolder.CANNOT_TELL -> R.string.inbox_reason_cannot_tell
    },
)

private fun folderName(list: SessionList?, folderId: String?): String? =
    folderId?.let { id -> list?.folders?.firstOrNull { it.id == id }?.let { it.name.ifEmpty { it.id } } }
