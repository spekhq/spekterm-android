package com.spekterm.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spekterm.android.R
import com.spekterm.android.desktop.ConversationItems
import com.spekterm.android.desktop.MemorySink
import com.spekterm.android.desktop.ReadLines
import com.spekterm.android.desktop.SentFileRead
import com.spekterm.android.desktop.SentFiles
import com.spekterm.android.desktop.ToolView
import com.spekterm.android.net.Entry
import com.spekterm.android.net.ResultImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Told when a result picture has been decoded, so a list that follows the newest keeps it in view (`tool-result-content` D3). */
val LocalPictureDrawn = staticCompositionLocalOf<() -> Unit> { {} }

/** A strip tile's longer side, decoded, in pixels. */
private const val STRIP_PIXELS = 256

/** A picture of a call's result, with the title it is shown by (design D2). */
internal data class ShownPicture(val ref: SentFileRef, val image: ResultImage)

/** The pictures of [tool]'s [result]: a Read's by its file name, any other's as "<tool> · picture N". */
@Composable
internal fun picturesOf(tool: Entry.Tool, view: ToolView): List<ShownPicture> {
    if (view.pictures.isEmpty()) return emptyList()
    val file = ToolView.pictureFile(tool)
    return view.pictures.mapIndexed { i, image ->
        val title = file ?: stringResource(R.string.picture_of_call, view.title, i + 1)
        ShownPicture(SentFileRef(tool.id, i, null, SentFileRef.Source.RESULT, title), image)
    }
}

/** What full screen swipes through, as the conversation titles it (`tool-result-content` D8). */
@Composable
internal fun viewerItems(sources: List<ConversationItems.ViewerSource>): List<ViewerItem> {
    val ctx = LocalToolContext.current
    return sources.flatMap { s ->
        when (s) {
            is ConversationItems.ViewerSource.Call ->
                picturesOf(s.tool, ToolView.of(s.tool, s.result, ctx.list, ctx.imagesReadable)).map { ViewerItem(it.ref, it.image.size) }
            is ConversationItems.ViewerSource.Lone -> picturesOf(s.result, ctx.imagesReadable).map { ViewerItem(it.ref, it.image.size) }
            is ConversationItems.ViewerSource.Sent -> s.message.files.mapIndexed { i, path -> ViewerItem(SentFileRef(s.message.call!!.id, i, path)) }
        }
    }
}

/** The pictures of a result whose call is not shown. */
@Composable
internal fun picturesOf(result: Entry.Result, imagesReadable: Boolean): List<ShownPicture> =
    ToolView.pictures(result, imagesReadable).mapIndexed { i, image ->
        ShownPicture(SentFileRef(result.id, i, null, SentFileRef.Source.RESULT, stringResource(R.string.picture_of_result, i + 1)), image)
    }

/**
 * A lone call's pictures, or an expanded run's call's: at most 240 dp wide and 360 dp tall, a smaller picture at its own
 * size, two to a row (design D3).
 */
@Composable
internal fun ResultPictures(pictures: List<ShownPicture>, modifier: Modifier = Modifier) {
    if (pictures.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in pictures.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in row) {
                    Box(Modifier.weight(1f, fill = false).widthIn(max = 240.dp)) {
                        ResultPicture(p, ThumbKey.Size.FULL, Modifier)
                    }
                }
            }
        }
    }
}

/**
 * A collapsed run's strip: fixed square tiles of its pictures, the latest call's first, as many as fit up to three,
 * then "+N", which expands the run (design D3). Only the tiles shown are read.
 */
@Composable
internal fun PictureStrip(pictures: List<ShownPicture>, expand: () -> Unit, modifier: Modifier = Modifier) {
    if (pictures.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val count = Strip.count(maxWidth.value, pictures.size)
        Row(horizontalArrangement = Arrangement.spacedBy(Strip.GAP.dp)) {
            for (p in pictures.take(count.shown)) ResultPicture(p, ThumbKey.Size.STRIP, Modifier.size(Strip.TILE.dp))
            if (count.more > 0) {
                val more = pluralStringResource(R.plurals.picture_more, count.more, count.more)
                Box(
                    Modifier
                        .size(Strip.MORE.dp, Strip.TILE.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                        .clickable(role = Role.Button, onClick = expand)
                        .clearAndSetSemantics {
                            contentDescription = more
                            role = Role.Button
                            onClick { expand(); true }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+${count.more}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * One picture: read by itself up to [THUMB_LIMIT], decided by the size the desktop listed, and held in the screen's
 * result-picture cache, pinned while it is composed (design D3). A tap opens it full screen.
 */
@Composable
private fun ResultPicture(p: ShownPicture, size: ThumbKey.Size, modifier: Modifier) {
    val ctx = LocalToolContext.current
    val drawn = LocalPictureDrawn.current
    val key = ThumbKey.of(p.ref, size)
    DisposableEffect(key) {
        ctx.pictures.pin(key)
        onDispose { ctx.pictures.unpin(key) }
    }
    val thumb = ctx.pictures[key]
    LaunchedEffect(key) {
        if (ctx.pictures[key] != null) return@LaunchedEffect
        if (p.image.size > THUMB_LIMIT) {
            ctx.pictures[key] = Thumb.Large(p.image.size)
            return@LaunchedEffect
        }
        ctx.pictures[key] = Thumb.Loading
        try {
            ctx.pictures[key] = loadPicture(ctx, p, if (size == ThumbKey.Size.STRIP) STRIP_PIXELS else THUMB_PIXELS)
        } catch (e: CancellationException) {
            // Scrolled away mid-read: read again when it comes back.
            ctx.pictures.remove(key)
            throw e
        }
    }
    LaunchedEffect(thumb is Thumb.Picture) { if (thumb is Thumb.Picture) drawn() }
    val strip = size == ThumbKey.Size.STRIP
    // A large or failed picture's line is read with its name: the tile shows only part of it.
    val state = when (thumb) {
        is Thumb.Large -> stringResource(R.string.picture_large, formatBytes(thumb.size))
        Thumb.Failed -> stringResource(R.string.picture_failed)
        else -> null
    }
    val description = listOfNotNull(stringResource(R.string.picture_description, p.ref.name), state).joinToString(", ")
    val colors = MaterialTheme.colorScheme
    val shape = if (strip) MaterialTheme.shapes.small else MaterialTheme.shapes.medium
    val open = { ctx.open(p.ref) }
    val node = Modifier
        .background(colors.surfaceContainer, shape)
        .clickable(role = Role.Image, onClick = open)
        .clearAndSetSemantics {
            contentDescription = description
            role = Role.Image
            onClick { open(); true }
        }
    when (thumb) {
        is Thumb.Picture -> Image(
            thumb.image,
            null,
            // A portrait screenshot narrows to stay within the height, so it never fills the screen; a small picture
            // keeps its own size rather than being blown up.
            modifier.then(if (strip) Modifier else Modifier.widthIn(max = with(LocalDensity.current) { thumb.width.toDp() }).heightIn(max = 360.dp).aspectRatio(thumb.image.width.toFloat() / thumb.image.height.coerceAtLeast(1), matchHeightConstraintsFirst = true)).then(node),
            contentScale = ContentScale.Crop,
        )
        is Thumb.Large, Thumb.Failed -> Box(
            modifier.then(if (strip) Modifier else Modifier.aspectRatio(4f / 3f)).then(node).padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(if (thumb is Thumb.Large) SpekIcons.Image else SpekIcons.Info, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                val line = if (thumb is Thumb.Large && strip) formatBytes(thumb.size) else if (strip) null else state
                line?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        else -> Box(modifier.then(if (strip) Modifier else Modifier.aspectRatio(4f / 3f)).then(node), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

private suspend fun loadPicture(ctx: ToolContext, p: ShownPicture, pixels: Int): Thumb {
    if (!SentFiles.decodable(p.image.mediaType)) return Thumb.Failed
    val sink = MemorySink()
    return when (val outcome = ctx.read(ctx.sessionId, p.ref, THUMB_LIMIT, sink) { _, _ -> }) {
        is SentFileRead.Outcome.Done -> if (SentFiles.decodable(outcome.mediaType)) {
            withContext(Dispatchers.Default) { sink.bytes().let { b -> decode(b, pixels)?.let { Thumb.Picture(it, pixelWidth(b)) } } } ?: Thumb.Failed
        } else {
            Thumb.Failed
        }
        is SentFileRead.Outcome.Failed -> if (outcome.reason == SentFileRead.Reason.TOO_LARGE && outcome.size != null) Thumb.Large(outcome.size) else Thumb.Failed
    }.also { sink.reset() }
}

/**
 * A Read's result (design D4): the file's lines under "Content" with their numbers in a muted gutter, the CLI's notes
 * set apart; any other shape as it came, under [output]. Parsed only when drawn.
 */
@Composable
internal fun ReadContent(text: String, output: @Composable () -> Unit) {
    val parsed = remember(text) { ReadLines.parse(text) }
    if (parsed !is ReadLines.Parsed.Numbered) {
        output()
        return
    }
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.tool_label_content)
    val description = stringResource(R.string.tool_part_description, label, remember(parsed) { parsed.lines.joinToString("\n") })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        parsed.above?.let { NoteText(it) }
        Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .background(colors.surfaceContainer, MaterialTheme.shapes.small)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                if (parsed.twoColumns) {
                    val numbers = remember(parsed) { parsed.lines.indices.joinToString("\n") { (parsed.first + it).toString() } }
                    val lines = remember(parsed) { parsed.lines.joinToString("\n") }
                    Row {
                        Text(numbers, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.padding(end = 10.dp))
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            SelectionContainer { Text(lines, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, softWrap = false) }
                        }
                    }
                } else {
                    // Beyond Compose's text bounds: wrapped "n  line" rows, in pieces no wider than it lays out.
                    val pieces = remember(parsed) { wrappedPieces(parsed) }
                    SelectionContainer {
                        Column { for (piece in pieces) Text(piece, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
        }
        parsed.below?.let { NoteText(it) }
    }
}

/** The numbered lines as "n  line", joined into texts of at most [SentFileViewer.PIECE] characters. */
internal fun wrappedPieces(parsed: ReadLines.Parsed.Numbered): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    parsed.lines.forEachIndexed { i, line ->
        for (piece in SentFileViewer.pieces("${parsed.first + i}  $line")) {
            if (current.isNotEmpty() && current.length + 1 + piece.length > SentFileViewer.PIECE) {
                out += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(piece)
        }
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}

@Composable
private fun NoteText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
