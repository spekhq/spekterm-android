@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.MutableState
import com.spekterm.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import com.spekterm.android.ui.theme.spekColors
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.Rename
import com.spekterm.android.desktop.SessionAction
import com.spekterm.android.desktop.SessionActions
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The session menu's dialogs, held by a screen (the session list, the conversation screen) — never by a row, which
 * the list may drop while a request is out (design D8, D9).
 */
@Stable
class SessionDialogs(
    private val scope: CoroutineScope,
    renamingState: MutableState<Pair<String, String>?>,
    private val onDone: (SessionAction.Done) -> Unit,
) {
    /** The session being renamed: its id and its label when the dialog opened. Kept across a recreation. */
    var renaming by renamingState
    var action by mutableStateOf<SessionAction?>(null)

    fun choose(choice: SessionActions.Action, session: Session) {
        val label = session.label.ifEmpty { session.id }
        val kind = when (choice) {
            // A screen, opened by the menu's owner (`session-relations` D5).
            SessionActions.Action.HANDOFF -> return
            SessionActions.Action.RENAME -> {
                renaming = session.id to session.label
                return
            }
            SessionActions.Action.HIBERNATE -> SessionAction.Kind.HIBERNATE
            SessionActions.Action.WAKE -> SessionAction.Kind.WAKE
            SessionActions.Action.CLOSE -> SessionAction.Kind.CLOSE
        }
        // Hibernating and waking start at once, unforced; closing asks first.
        when (val start = SessionAction.start(kind, session.id, label)) {
            is SessionAction.Working -> run(start)
            else -> action = start
        }
    }

    /** Answers [session]'s close offer with [kind] (`close-offer` D3): sent at once, Close without asking again. */
    fun answerOffer(kind: SessionAction.Kind, session: Session) {
        run(SessionAction.fromOffer(kind, session.id, session.label.ifEmpty { session.id }))
    }

    /** Sends [working]'s request. The request goes on if the screen is left; its outcome is then only in the list. */
    fun run(working: SessionAction.Working) {
        action = working
        scope.launch {
            val error = when (working.kind) {
                SessionAction.Kind.HIBERNATE -> Desktop.hibernate(working.sessionId, working.force)
                SessionAction.Kind.CLOSE -> Desktop.close(working.sessionId, working.force)
                SessionAction.Kind.WAKE -> Desktop.wake(working.sessionId)
                SessionAction.Kind.KEEP -> Desktop.keep(working.sessionId)
            }
            when (val next = SessionAction.answered(working, error)) {
                is SessionAction.Done -> {
                    action = null
                    onDone(next)
                }
                else -> action = next
            }
        }
    }

    fun dismiss() {
        if (action !is SessionAction.Working) action = null
    }
}

/** The dialogs of a screen; [onDone] hears of a finished action (closing from its own screen goes back). */
@Composable
fun rememberSessionDialogs(onDone: (SessionAction.Done) -> Unit = {}): SessionDialogs {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onDone)
    // A language change recreates the activity: the rename dialog and its text come back (`scan-connect-zh` 6.1).
    val renaming = rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    return remember { SessionDialogs(scope, renaming) { latest.value(it) } }
}

/** The items of [session]'s menu; [enabled] per D8's conditions. */
@Composable
fun SessionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<SessionActions.Action>,
    enabled: Boolean,
    onReport: (() -> Unit)? = null,
    onChoose: (SessionActions.Action) -> Unit,
) {
    DropdownMenu(expanded = expanded && (actions.isNotEmpty() || onReport != null), onDismissRequest = onDismiss) {
        for (action in actions) {
            DropdownMenuItem(
                text = {
                    Text(
                        when (action) {
                            SessionActions.Action.HANDOFF -> stringResource(R.string.menu_handoff)
                            SessionActions.Action.RENAME -> stringResource(R.string.menu_rename)
                            SessionActions.Action.HIBERNATE -> stringResource(R.string.menu_hibernate)
                            SessionActions.Action.WAKE -> stringResource(R.string.common_wake)
                            SessionActions.Action.CLOSE -> stringResource(R.string.menu_close_session)
                        },
                    )
                },
                enabled = SessionActions.itemEnabled(action, enabled),
                onClick = {
                    onDismiss()
                    onChoose(action)
                },
            )
        }
        // The conversation's menu ends with Report a problem (`play-release` D11).
        onReport?.let { report ->
            if (actions.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.report_problem)) },
                onClick = {
                    onDismiss()
                    report()
                },
            )
        }
    }
}

/**
 * An agent's offer to close its own session, as the desktop's card words it (`close-offer` D5): the sentence, then
 * [answers] — Close first, Keep second — usable when [enabled].
 */
@Composable
fun CloseOffer(answers: List<SessionAction.Kind>, enabled: Boolean, onAnswer: (SessionAction.Kind) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.menu_offer),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = spekColors.offer,
        )
        for (answer in answers) {
            if (answer == SessionAction.Kind.CLOSE) {
                OutlinedButton(enabled = enabled, onClick = { onAnswer(answer) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(stringResource(R.string.menu_offer_close)) }
            } else {
                TextButton(enabled = enabled, onClick = { onAnswer(answer) }) { Text(stringResource(R.string.menu_offer_keep)) }
            }
        }
    }
}

/** Shows whichever dialog [dialogs] holds. */
@Composable
fun SessionDialogsHost(dialogs: SessionDialogs, enabled: Boolean) {
    dialogs.renaming?.let { (sessionId, label) -> RenameDialog(sessionId, label, enabled) { dialogs.renaming = null } }
    dialogs.action?.let { action -> ActionDialog(action, enabled, dialogs) }
}

@Composable
private fun RenameDialog(sessionId: String, label: String, enabled: Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(sessionId) { mutableStateOf(label) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<RemoteError?>(null) }
    fun send(name: String) {
        if (name.isNotEmpty() && Rename.unchanged(name, label)) return onClose()
        working = true
        error = null
        scope.launch {
            val e = Desktop.rename(sessionId, name)
            working = false
            if (e == null) onClose() else error = e
        }
    }
    // Its own button row: "Clear name" apart at the start, Cancel and Rename at the end (D12).
    BasicAlertDialog(onDismissRequest = { if (!working) onClose() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.menu_rename_title), style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = text,
                    // One line: newlines cannot be typed; at most 200 characters, as the desktop keeps.
                    onValueChange = { v ->
                        val line = v.replace("\n", "").replace("\r", "")
                        if (line.codePointCount(0, line.length) <= Rename.MAX_CODE_POINTS) text = line
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(renameError(it).text(), color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = enabled && !working, onClick = { send("") }) { Text(stringResource(R.string.menu_clear_name)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !working, onClick = onClose) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(enabled = enabled && !working && text.isNotBlank(), onClick = { send(text) }) { Text(if (working) stringResource(R.string.menu_renaming) else stringResource(R.string.menu_rename)) }
                }
            }
        }
    }
}

private fun renameError(e: RemoteError): UiText = when (e.code) {
    RemoteError.NOT_FOUND -> UiText.Res(R.string.session_gone)
    RemoteError.NO_WINDOW -> UiText.Res(R.string.session_no_window)
    RemoteError.TIMEOUT -> UiText.Res(R.string.menu_rename_timeout)
    Desktop.NOT_CONNECTED -> UiText.Res(R.string.common_not_connected)
    else -> UiText.Res(R.string.menu_rename_failed, e.code)
}

@Composable
private fun ActionDialog(action: SessionAction, enabled: Boolean, dialogs: SessionDialogs) {
    val name = "“${action.label}”"
    val close = { dialogs.dismiss() }
    when (action) {
        is SessionAction.Confirm -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.menu_close_title, name)) },
            text = { Text(stringResource(R.string.menu_close_body)) },
            confirmButton = {
                TextButton(enabled = enabled, onClick = { dialogs.run(SessionAction.confirmed(action) as SessionAction.Working) }) {
                    Text(stringResource(R.string.menu_close_session), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } },
        )
        is SessionAction.Working -> AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        when (action.kind) {
                            SessionAction.Kind.HIBERNATE -> stringResource(R.string.menu_hibernating, name)
                            SessionAction.Kind.CLOSE -> stringResource(R.string.menu_closing, name)
                            SessionAction.Kind.WAKE -> stringResource(R.string.menu_waking, name)
                            SessionAction.Kind.KEEP -> stringResource(R.string.menu_keeping, name)
                        },
                    )
                }
            },
            confirmButton = {},
        )
        is SessionAction.Busy -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.menu_busy_title, name)) },
            text = {
                Column {
                    Text(SessionAction.busyText(action.reason).text())
                    Text(
                        if (action.kind == SessionAction.Kind.CLOSE) stringResource(R.string.menu_busy_close) else stringResource(R.string.menu_busy_hibernate),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = enabled, onClick = { dialogs.run(SessionAction.agreed(action) as SessionAction.Working) }) {
                    Text(if (action.kind == SessionAction.Kind.CLOSE) stringResource(R.string.menu_close_anyway) else stringResource(R.string.menu_hibernate_anyway), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_cancel)) } },
        )
        is SessionAction.Failed -> AlertDialog(
            onDismissRequest = close,
            text = { Text(action.text.text()) },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.common_ok)) } },
        )
        is SessionAction.Done -> Unit
    }
}
