package com.spekterm.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
class SessionDialogs(private val scope: CoroutineScope, private val onDone: (SessionAction.Done) -> Unit) {
    /** The session being renamed: its id and its label when the dialog opened. */
    var renaming by mutableStateOf<Pair<String, String>?>(null)
    var action by mutableStateOf<SessionAction?>(null)

    fun choose(choice: SessionActions.Action, session: Session) {
        val label = session.label.ifEmpty { session.id }
        val kind = when (choice) {
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
    return remember { SessionDialogs(scope) { latest.value(it) } }
}

/** The items of [session]'s menu; [enabled] per D8's conditions. */
@Composable
fun SessionMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<SessionActions.Action>, enabled: Boolean, onChoose: (SessionActions.Action) -> Unit) {
    DropdownMenu(expanded = expanded && actions.isNotEmpty(), onDismissRequest = onDismiss) {
        for (action in actions) {
            DropdownMenuItem(
                text = {
                    Text(
                        when (action) {
                            SessionActions.Action.RENAME -> "Rename"
                            SessionActions.Action.HIBERNATE -> "Hibernate"
                            SessionActions.Action.WAKE -> "Wake"
                            SessionActions.Action.CLOSE -> "Close session"
                        },
                    )
                },
                enabled = enabled,
                onClick = {
                    onDismiss()
                    onChoose(action)
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "The agent asked to close this session.",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        for (answer in answers) {
            TextButton(enabled = enabled, onClick = { onAnswer(answer) }) {
                Text(if (answer == SessionAction.Kind.CLOSE) "Close" else "Keep")
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
    var text by remember(sessionId) { mutableStateOf(label) }
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
    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text("Rename session") },
        text = {
            Column {
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
                error?.let { Text(renameError(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = enabled && !working && text.isNotBlank(), onClick = { send(text) }) { Text(if (working) "Renaming…" else "Rename") }
        },
        dismissButton = {
            Column {
                TextButton(enabled = enabled && !working, onClick = { send("") }) { Text("Clear name") }
                TextButton(enabled = !working, onClick = onClose) { Text("Cancel") }
            }
        },
    )
}

private fun renameError(e: RemoteError): String = when (e.code) {
    RemoteError.NOT_FOUND -> "This session no longer exists."
    RemoteError.NO_WINDOW -> "spekterm has no open window on the desktop."
    RemoteError.TIMEOUT -> "The desktop did not answer in time; the name may still change."
    Desktop.NOT_CONNECTED -> "Not connected to the desktop."
    else -> "Could not rename it (${e.code})."
}

@Composable
private fun ActionDialog(action: SessionAction, enabled: Boolean, dialogs: SessionDialogs) {
    val name = "“${action.label}”"
    val close = { dialogs.dismiss() }
    when (action) {
        is SessionAction.Confirm -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Close $name?") },
            text = { Text("It leaves the desktop for good and can't be reopened. Attachments not sent yet are removed.") },
            confirmButton = { TextButton(enabled = enabled, onClick = { dialogs.run(SessionAction.confirmed(action) as SessionAction.Working) }) { Text("Close session") } },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        is SessionAction.Working -> AlertDialog(
            onDismissRequest = {},
            text = {
                Text(
                    when (action.kind) {
                        SessionAction.Kind.HIBERNATE -> "Hibernating $name…"
                        SessionAction.Kind.CLOSE -> "Closing $name…"
                        SessionAction.Kind.WAKE -> "Waking $name…"
                        SessionAction.Kind.KEEP -> "Keeping $name…"
                    },
                )
            },
            confirmButton = {},
        )
        is SessionAction.Busy -> AlertDialog(
            onDismissRequest = close,
            title = { Text("$name is busy") },
            text = {
                Column {
                    Text(SessionAction.busyText(action.reason))
                    Text(
                        if (action.kind == SessionAction.Kind.CLOSE) {
                            "Close it anyway? What it is doing is stopped, and the session leaves the desktop for good."
                        } else {
                            "Hibernate it anyway? What it is doing is stopped."
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = enabled, onClick = { dialogs.run(SessionAction.agreed(action) as SessionAction.Working) }) {
                    Text(if (action.kind == SessionAction.Kind.CLOSE) "Close anyway" else "Hibernate anyway")
                }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )
        is SessionAction.Failed -> AlertDialog(
            onDismissRequest = close,
            text = { Text(action.text) },
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
        )
        is SessionAction.Done -> Unit
    }
}
