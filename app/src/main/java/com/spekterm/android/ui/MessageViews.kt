package com.spekterm.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spekterm.android.R
import com.spekterm.android.desktop.ConversationItems
import com.spekterm.android.desktop.MemorySink
import com.spekterm.android.desktop.Messages
import com.spekterm.android.desktop.SentFileRead
import com.spekterm.android.desktop.SentFiles
import com.spekterm.android.desktop.ToolView
import com.spekterm.android.ui.theme.spekColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A sent picture's thumbnail as the conversation screen holds it, in memory only (`tool-call-display` D11). */
sealed interface Thumb {
    data object Loading : Thumb

    /** [width]: the picture's own width in pixels, before it was decoded smaller. */
    data class Picture(val image: ImageBitmap, val width: Int = image.width) : Thumb

    /** Over [THUMB_LIMIT]: read only on a tap. */
    data class Large(val size: Long) : Thumb

    data object Failed : Thumb
}

/** Pictures up to this size are read by themselves; larger ones on a tap (D11). */
const val THUMB_LIMIT = 2L * 1024 * 1024

/** A thumbnail's longer side, in pixels. */
const val THUMB_PIXELS = 600

private val avatarColors = listOf(Color(0xFF2F5D8A), Color(0xFF3C7A52), Color(0xFF5B4A8A), Color(0xFF8A4B2F), Color(0xFF2F7A7A), Color(0xFF7A2F5D))

@Composable
private fun shownName(who: Messages.Who): String {
    val ctx = LocalToolContext.current
    return Messages.nameOf(who, ctx.list, ctx.self) ?: when (who.kind) {
        Messages.Kind.SELF -> stringResource(R.string.message_agent)
        Messages.Kind.SESSION -> stringResource(R.string.tool_to_another_session)
        Messages.Kind.SUBAGENT -> stringResource(R.string.tool_to_subagent)
        Messages.Kind.AGENT -> stringResource(R.string.message_another_agent)
    }
}

/**
 * Something exchanged between parties, as a chat app shows a message from someone else: an avatar, who sent it (and
 * to whom), the bubble, then the time and whether it was sent (D11).
 */
@Composable
internal fun MessageRow(m: ConversationItems.Message, open: MutableMap<String, Boolean>, time: String?) {
    val colors = MaterialTheme.colorScheme
    val from = shownName(m.from)
    val header = when {
        m.to != null -> stringResource(R.string.message_to, from, shownName(m.to))
        m.files.isNotEmpty() -> pluralStringResource(R.plurals.message_files, m.files.size, m.files.size, from)
        m.from.kind == Messages.Kind.SUBAGENT -> stringResource(R.string.message_report, from)
        else -> stringResource(R.string.message_from, from)
    }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 40.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(from, m.from.kind)
        Column(Modifier.weight(1f, fill = false).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(header, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Column(
                Modifier
                    .background(colors.surface, bubble)
                    .border(1.dp, colors.outlineVariant, bubble)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Body(m, open)
                if (m.files.isNotEmpty()) MessageFiles(m)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                time?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
                StatusText(m.status)
            }
        }
    }
}

private val bubble = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)

@Composable
private fun Avatar(name: String, kind: Messages.Kind) {
    Box(
        Modifier.size(32.dp).background(avatarColors[Messages.colorIndex(name)], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name.trim().firstOrNull()?.uppercase()
        if (initial != null) {
            Text(initial, style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Medium)
        } else {
            Icon(if (kind == Messages.Kind.SUBAGENT) SpekIcons.Agent else SpekIcons.Shell, null, Modifier.size(16.dp), tint = Color.White)
        }
    }
}

@Composable
private fun Body(m: ConversationItems.Message, open: MutableMap<String, Boolean>) {
    if (m.body.isBlank()) {
        // A SendMessage with no text only asks to be told when the other session is idle.
        if (m.call?.name == ConversationItems.SEND_MESSAGE) {
            Text(stringResource(R.string.tool_note_idle_only), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val key = "all:${m.key}"
    val all = open[key] == true
    val cut = Messages.cut(m.body)
    val shown = if (cut != null && !all) cut + "…" else m.body
    SelectionContainer {
        if (m.markdown) AgentMarkdown(shown) else Text(shown, style = MaterialTheme.typography.bodyMedium)
    }
    if (cut != null) {
        TextButton(onClick = { open[key] = !all }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(stringResource(if (all) R.string.message_show_less else R.string.message_show_all))
        }
    }
}

@Composable
private fun StatusText(status: Messages.Status?) {
    when (status) {
        null -> Unit
        Messages.Status.Sending -> Text(stringResource(R.string.message_sending), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Messages.Status.Sent -> Text(stringResource(R.string.tool_note_sent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is Messages.Status.NotSent -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(SpekIcons.Error, null, Modifier.size(14.dp), tint = spekColors.danger)
            Text(
                status.reason?.let { stringResource(R.string.tool_note_not_sent_because, it) } ?: stringResource(R.string.tool_note_not_sent),
                style = MaterialTheme.typography.labelMedium,
                color = spekColors.danger,
            )
        }
    }
}

/** The files in a message: pictures shown, others as cards, each opening in the viewer or another app (D11). */
@Composable
private fun MessageFiles(m: ConversationItems.Message) {
    val ctx = LocalToolContext.current
    val callId = m.call?.id ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        m.files.forEachIndexed { index, path ->
            val kind = SentFiles.kind(path)
            val ref = SentFileRef(callId, index, path)
            if (kind == SentFiles.Kind.IMAGE && ctx.canReadFiles) {
                Picture(ref)
            } else {
                FileCard(ref, kind, null)
            }
        }
        if (!ctx.canReadFiles) {
            Text(stringResource(R.string.tool_files_cannot_send), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A sent picture: read by itself up to [THUMB_LIMIT], once per screen, while it is composed (D11). */
@Composable
private fun Picture(ref: SentFileRef) {
    val ctx = LocalToolContext.current
    val key = "${ref.toolUseId}#${ref.index}"
    val thumb = ctx.thumbs[key]
    LaunchedEffect(key) {
        if (ctx.thumbs[key] != null) return@LaunchedEffect
        ctx.thumbs[key] = Thumb.Loading
        try {
            ctx.thumbs[key] = loadThumb(ctx, ref)
        } catch (e: CancellationException) {
            // Scrolled away mid-read: read again when it comes back.
            ctx.thumbs.remove(key)
            throw e
        }
    }
    when (thumb) {
        is Thumb.Picture -> Image(
            thumb.image,
            ref.name,
            Modifier
                .widthIn(max = 298.dp)
                .fillMaxWidth()
                .aspectRatio(thumb.image.width.toFloat() / thumb.image.height.coerceAtLeast(1))
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                .clickable(role = Role.Image) { ctx.open(ref) },
            contentScale = ContentScale.Crop,
        )
        is Thumb.Large -> FileCard(ref, SentFiles.Kind.IMAGE, stringResource(R.string.file_photo_large, formatBytes(thumb.size)))
        Thumb.Failed -> FileCard(ref, SentFiles.Kind.IMAGE, stringResource(R.string.file_photo_failed)) { ctx.thumbs.remove(key) }
        else -> Box(Modifier.widthIn(max = 298.dp).fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

private suspend fun loadThumb(ctx: ToolContext, ref: SentFileRef): Thumb {
    val sink = MemorySink()
    return when (val outcome = ctx.read(ctx.sessionId, ref, THUMB_LIMIT, sink) { _, _ -> }) {
        is SentFileRead.Outcome.Done -> withContext(Dispatchers.Default) { decode(sink.bytes(), THUMB_PIXELS) }?.let { Thumb.Picture(it) } ?: Thumb.Failed
        is SentFileRead.Outcome.Failed -> if (outcome.reason == SentFileRead.Reason.TOO_LARGE && outcome.size != null) Thumb.Large(outcome.size) else Thumb.Failed
    }.also { sink.reset() }
}

/** A sent file as a card: its name and what a tap does. [retry] replaces opening, for a picture that failed. */
@Composable
private fun FileCard(ref: SentFileRef, kind: SentFiles.Kind, subtitle: String?, retry: (() -> Unit)? = null) {
    val ctx = LocalToolContext.current
    val colors = MaterialTheme.colorScheme
    val line = subtitle ?: when (kind) {
        SentFiles.Kind.MARKDOWN -> stringResource(R.string.file_kind_markdown)
        SentFiles.Kind.TEXT -> stringResource(R.string.file_kind_text)
        SentFiles.Kind.IMAGE -> stringResource(R.string.tool_file_open)
        SentFiles.Kind.OTHER -> stringResource(R.string.file_kind_other)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(colors.surfaceContainerLow, MaterialTheme.shapes.medium)
            .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.medium)
            .then(if (ctx.canReadFiles) Modifier.clickable(role = Role.Button) { retry?.invoke() ?: ctx.open(ref) } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (kind == SentFiles.Kind.IMAGE) SpekIcons.Image else SpekIcons.File, null, Modifier.size(22.dp), tint = colors.primary)
        Column(Modifier.weight(1f)) {
            Text(ref.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (ctx.canReadFiles) Text(line, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

/** A background task's notice standing alone: one short centred line saying what finished (D11). */
@Composable
internal fun NoticeLine(text: String, expanded: Boolean, toggle: () -> Unit) {
    val summary = Messages.summary(text) ?: stringResource(R.string.peer_task_notice)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .toggles(expanded, toggle)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(SpekIcons.Succeeded, null, Modifier.size(14.dp), tint = spekColors.working)
            Text(summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) SelectionContainer(Modifier.padding(top = 4.dp)) { Text(text, style = MaterialTheme.typography.bodySmall) }
    }
}
