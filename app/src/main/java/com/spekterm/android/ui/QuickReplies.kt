package com.spekterm.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spekterm.android.R
import com.spekterm.android.app
import com.spekterm.android.desktop.QuickReplies

/**
 * The height the conversation and its reply area need for the quick replies to show; below it (landscape with the
 * keyboard open, typically) the row is hidden so the reply field stays in view (`quick-replies` D3). Measured on a
 * 412 dp wide phone: about 470 dp in portrait with the keyboard, 276 dp in landscape without it, far less with it;
 * the design's first guess of 360 dp hid the row in landscape even without a keyboard.
 */
internal val QUICK_ROOM = 240.dp

/** The defaults in the app's language, read at composition so a language change shows them translated (D2). */
@Composable
internal fun defaultQuickReplies(): List<String> = listOf(
    stringResource(R.string.quick_reply_default_close),
    stringResource(R.string.quick_reply_default_risk),
    stringResource(R.string.quick_reply_default_remaining),
)

/** The quick replies as the user set them, or the defaults while not set. */
@Composable
internal fun rememberQuickReplies(): List<String> {
    val set by LocalContext.current.app.quickReplies.collectAsStateWithLifecycle()
    return set ?: defaultQuickReplies()
}

/**
 * One chip per sentence, on one line scrolling sideways, directly above the reply field (D3). Each shows its sentence
 * on one line; its text, the whole sentence, is what a screen reader reads.
 */
@Composable
internal fun QuickReplyRow(sentences: List<String>, enabled: (String) -> Boolean, onQuick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (sentence in sentences) {
            SuggestionChip(
                onClick = { onQuick(sentence) },
                label = { Text(sentence, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                enabled = enabled(sentence),
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}

/**
 * The quick replies in Settings (D4): each sentence with edit, up, down and remove; Add below 12; Restore defaults
 * after a confirmation. [set] is the stored list, `null` while not set; the first change saves the whole list as shown.
 */
@Composable
fun QuickRepliesSection(set: List<String>?, onSet: (List<String>) -> Unit, onReset: () -> Unit) {
    val shown = set ?: defaultQuickReplies()
    // The index being edited; shown.size for a new one.
    var editing by remember { mutableStateOf<Int?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.quick_replies_intro), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (shown.isEmpty()) {
            Text(stringResource(R.string.quick_replies_none), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        shown.forEachIndexed { i, sentence ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(sentence, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { editing = i }) { Icon(SpekIcons.Edit, stringResource(R.string.quick_reply_edit, sentence)) }
                IconButton(onClick = { onSet(shown.move(i, i - 1)) }, enabled = i > 0) {
                    Icon(SpekIcons.ArrowUp, stringResource(R.string.quick_reply_move_up, sentence))
                }
                IconButton(onClick = { onSet(shown.move(i, i + 1)) }, enabled = i < shown.lastIndex) {
                    Icon(SpekIcons.ArrowDown, stringResource(R.string.quick_reply_move_down, sentence))
                }
                IconButton(onClick = { onSet(shown.filterIndexed { j, _ -> j != i }) }) {
                    Icon(SpekIcons.Delete, stringResource(R.string.quick_reply_remove, sentence))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (shown.size < QuickReplies.MAX_COUNT) {
                OutlinedButton(onClick = { editing = shown.size }) { Text(stringResource(R.string.quick_reply_add)) }
            } else {
                Text(stringResource(R.string.quick_reply_full), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            TextButton(onClick = { restoring = true }) { Text(stringResource(R.string.quick_reply_restore)) }
        }
    }
    editing?.let { i ->
        val others = shown.filterIndexed { j, _ -> j != i }
        QuickReplyDialog(shown.getOrNull(i).orEmpty(), others, isNew = i >= shown.size, onDismiss = { editing = null }) { sentence ->
            editing = null
            onSet(if (i >= shown.size) shown + sentence else shown.mapIndexed { j, s -> if (j == i) sentence else s })
        }
    }
    if (restoring) {
        AlertDialog(
            onDismissRequest = { restoring = false },
            title = { Text(stringResource(R.string.quick_reply_restore_title)) },
            text = { Text(stringResource(R.string.quick_reply_restore_body)) },
            confirmButton = {
                TextButton(onClick = {
                    restoring = false
                    onReset()
                }) { Text(stringResource(R.string.quick_reply_restore_confirm)) }
            },
            dismissButton = { TextButton(onClick = { restoring = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** One sentence, on one line, at most [QuickReplies.MAX_CHARS]; Save waits for a valid one and says why not (D4). */
@Composable
private fun QuickReplyDialog(initial: String, others: List<String>, isNew: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val problem = QuickReplies.problem(text, others)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.quick_reply_add_title else R.string.quick_reply_edit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(QuickReplies.MAX_CHARS) },
                singleLine = true,
                isError = problem != null && text.isNotEmpty(),
                supportingText = problem?.let { { Text(problemText(it)) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(QuickReplies.normalized(text)) }, enabled = problem == null) { Text(stringResource(R.string.quick_reply_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun problemText(p: QuickReplies.Problem): String = stringResource(
    when (p) {
        QuickReplies.Problem.EMPTY -> R.string.quick_reply_problem_empty
        QuickReplies.Problem.LINE_BREAK -> R.string.quick_reply_problem_line_break
        QuickReplies.Problem.TOO_LONG -> R.string.quick_reply_problem_too_long
        QuickReplies.Problem.BARE_COMMAND -> R.string.quick_reply_problem_bare_command
        QuickReplies.Problem.DUPLICATE -> R.string.quick_reply_problem_duplicate
    },
)

private fun List<String>.move(from: Int, to: Int): List<String> =
    toMutableList().also { it.add(to, it.removeAt(from)) }
