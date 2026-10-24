package com.spekterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spekterm.android.R
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.EditDiff
import com.spekterm.android.desktop.PictureCache
import com.spekterm.android.desktop.SentFileRead
import com.spekterm.android.desktop.SentFiles
import com.spekterm.android.desktop.ToolView
import com.spekterm.android.desktop.ToolView.Label
import com.spekterm.android.desktop.ToolView.Note
import com.spekterm.android.desktop.ToolView.Part
import com.spekterm.android.desktop.ToolView.Recipient
import com.spekterm.android.desktop.ToolView.Role
import com.spekterm.android.net.SessionList
import com.spekterm.android.ui.theme.spekColors
import java.text.NumberFormat

/**
 * A file the user asked to open: a sent file (the SendUserFile call, its position, its [path]), or a picture in a tool
 * result (the call, its position, no path, and the [title] it is shown by; `tool-result-content` D2).
 */
data class SentFileRef(val toolUseId: String, val index: Int, val path: String?, val source: Source = Source.SENT, val title: String? = null) {
    enum class Source { SENT, RESULT }

    /** What the viewer's title and a screen reader name it by. */
    val name: String get() = title ?: path?.let(ToolView::fileName) ?: ""
}

/**
 * What a tool call needs from its screen (`tool-call-display`): the session list, to name a session by its label;
 * whether the desktop sends files; and opening one.
 */
data class ToolContext(
    val list: SessionList?,
    val canReadFiles: Boolean,
    val sessionId: String = "",
    /** This session's name, for its agent's messages (`tool-call-display` D11). */
    val self: String? = null,
    /** Sent pictures read so far on this screen, in memory only (D11). */
    val thumbs: SnapshotStateMap<String, Thumb> = SnapshotStateMap(),
    /** Whether the desktop sends the pictures in tool results (`tool-result-content` D3). */
    val imagesReadable: Boolean = false,
    /** Result pictures read so far on this screen, in memory only, bounded (`tool-result-content` D3). */
    val pictures: PictureCache<Thumb> = PictureCache(),
    /** Reads a sent file or a result picture into a sink; the desktop's, replaced in tests. */
    val read: suspend (sessionId: String, ref: SentFileRef, limit: Long, sink: SentFileRead.Sink, progress: (Long, Long) -> Unit) -> SentFileRead.Outcome = { s, r, l, k, p ->
        when (r.source) {
            SentFileRef.Source.SENT -> Desktop.readSentFile(s, r.toolUseId, r.index, l, k, p)
            SentFileRef.Source.RESULT -> Desktop.readResultImage(s, r.toolUseId, r.index, l, k, p)
        }
    },
    val open: (SentFileRef) -> Unit,
)

val LocalToolContext = staticCompositionLocalOf { ToolContext(null, false) {} }

/** The call's one-line summary: who a message went to, then the line (design D2). */
@Composable
internal fun summaryOf(view: ToolView): String? {
    val to = view.recipient?.let { r ->
        stringResource(
            R.string.tool_to,
            when (r) {
                is Recipient.Named -> r.name
                Recipient.AnotherSession -> stringResource(R.string.tool_to_another_session)
                Recipient.Subagent -> stringResource(R.string.tool_to_subagent)
            },
        )
    }
    return listOfNotNull(to, view.line?.lineSequence()?.firstOrNull()).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun labelText(label: Label): String = stringResource(
    when (label) {
        Label.COMMAND -> R.string.tool_label_command
        Label.OUTPUT -> R.string.tool_label_output
        Label.ERROR -> R.string.tool_label_error
        Label.MESSAGE -> R.string.tool_label_message
        Label.PROMPT -> R.string.tool_label_prompt
        Label.CONTENT -> R.string.tool_label_content
        Label.PLAN -> R.string.tool_label_plan
        Label.ANSWER -> R.string.tool_label_answer
        Label.ARGUMENT -> R.string.tool_label_argument
    },
)

/** An expanded call's parts, each labeled, what the agent supplied set apart from what came back (design D3). */
@Composable
internal fun ToolParts(view: ToolView, callId: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (part in view.parts) {
            when (part) {
                is Part.Block -> if (part.numbered) {
                    ReadContent(part.text) { PartBlock(labelText(part.label), part.text, part.role, part.cut, dollar = false) }
                } else {
                    PartBlock(labelText(part.label), part.text, part.role, part.cut, dollar = part.label == Label.COMMAND)
                }
                is Part.Path -> PathLine(part.path)
                is Part.Diff -> DiffBlock(part)
                is Part.Files -> FileRows(part, callId)
                is Part.Questions -> QuestionList(part.questions)
                is Part.Notice -> NoteLine(part)
            }
        }
    }
}

/** One labeled part: the label and the text are one node for a screen reader, the label first, read once. */
@Composable
private fun PartBlock(label: String, text: String, role: Role, cut: Long?, dollar: Boolean) {
    val colors = MaterialTheme.colorScheme
    val shown = if (dollar) "$ $text" else text
    val description = stringResource(R.string.tool_part_description, label, text)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (role == Role.ERROR) colors.error else colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        val body = @Composable {
            Box(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                SelectionContainer {
                    Text(
                        shown,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (role == Role.ERROR) colors.error else colors.onSurface,
                    )
                }
            }
        }
        if (role == Role.INPUT) {
            // What the agent supplied: a bar on its leading edge and a raised surface (not color alone: the bar and label).
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.surfaceContainerHigh, MaterialTheme.shapes.small)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(colors.primary))
                Box(Modifier.weight(1f)) { body() }
            }
        } else {
            Box(Modifier.fillMaxWidth().background(colors.surfaceContainer, MaterialTheme.shapes.small)) { body() }
        }
        cut?.let { CutLine(stringResource(R.string.tool_cut_chars, NumberFormat.getIntegerInstance().format(it))) }
    }
}

@Composable
private fun CutLine(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun PathLine(path: String) {
    val label = stringResource(R.string.tool_label_path)
    val description = stringResource(R.string.tool_part_description, label, path)
    SelectionContainer(Modifier.clearAndSetSemantics { contentDescription = description }) {
        Text(path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An Edit's change: removed and added lines marked by `- ` and `+ ` and tinted, context unmarked (design D4). */
@Composable
private fun DiffBlock(part: Part.Diff) {
    val colors = MaterialTheme.colorScheme
    val spek = spekColors
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.tool_label_change), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .background(colors.surfaceContainer, MaterialTheme.shapes.small)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            if (part.diff.unchanged && !part.cut) {
                Text(stringResource(R.string.tool_diff_unchanged), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp))
            }
            SelectionContainer {
                Column {
                    for (line in part.diff.lines) {
                        val (text, tint) = when (line) {
                            is EditDiff.Line.Context -> "  " + line.text to null
                            is EditDiff.Line.Removed -> "- " + line.text to spek.danger
                            is EditDiff.Line.Added -> "+ " + line.text to spek.working
                            is EditDiff.Line.Fold -> pluralStringResource(R.plurals.tool_diff_fold, line.count, line.count) to null
                        }
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (line is EditDiff.Line.Fold) colors.onSurfaceVariant else colors.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (tint != null) Modifier.background(tint.copy(alpha = 0.16f)) else Modifier)
                                .padding(horizontal = 10.dp),
                        )
                    }
                }
            }
        }
        if (part.cut) CutLine(stringResource(R.string.tool_cut))
    }
}

/** A SendUserFile call's files: each opens in the app or with another app, through the desktop (design D6, D9). */
@Composable
private fun FileRows(part: Part.Files, callId: String) {
    val ctx = LocalToolContext.current
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        part.files.forEachIndexed { index, path ->
            val kind = SentFiles.kind(path)
            val name = ToolView.fileName(path)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (kind == SentFiles.Kind.IMAGE) SpekIcons.Image else SpekIcons.File, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                }
                if (ctx.canReadFiles) {
                    TextButton(onClick = { ctx.open(SentFileRef(callId, index, path)) }) {
                        Text(stringResource(if (kind == SentFiles.Kind.OTHER) R.string.tool_file_open_with else R.string.tool_file_open))
                    }
                }
            }
        }
        part.cutItems?.let { CutLine(stringResource(R.string.tool_cut_items, NumberFormat.getIntegerInstance().format(it))) }
        if (!ctx.canReadFiles && part.files.isNotEmpty()) CutLine(stringResource(R.string.tool_files_cannot_send))
    }
}

@Composable
private fun QuestionList(questions: List<ToolView.Question>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (q in questions) {
            Text(q.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            for (o in q.options) Text("• $o", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun NoteLine(part: Part.Notice) {
    val text = when (part.note) {
        Note.BACKGROUND -> stringResource(R.string.tool_note_background)
        Note.SENT -> stringResource(R.string.tool_note_sent)
        Note.NOT_SENT -> part.value?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.tool_note_not_sent_because, it) } ?: stringResource(R.string.tool_note_not_sent)
        Note.IDLE_ONLY -> stringResource(R.string.tool_note_idle_only)
        Note.REPLACE_ALL -> stringResource(R.string.tool_note_replace_all)
        Note.PICTURES_NEED_DESKTOP -> stringResource(R.string.tool_note_pictures_need_desktop)
        Note.DELAY_MINUTES -> part.value?.toIntOrNull()?.let { pluralStringResource(R.plurals.tool_note_delay, it, it) } ?: ""
    }
    if (text.isEmpty()) return
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (part.note == Note.NOT_SENT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
