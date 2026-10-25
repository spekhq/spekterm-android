package com.spekterm.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.abs
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.spekterm.android.R
import com.spekterm.android.desktop.CacheSink
import com.spekterm.android.desktop.MemorySink
import com.spekterm.android.desktop.SentFileRead
import com.spekterm.android.desktop.SentFiles
import com.spekterm.android.desktop.ToolView
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where files handed to another app are written, and their cleanup (`tool-call-display` D9). */
object SentFileCache {
    fun dir(context: Context): File = File(context.cacheDir, "sent")

    /** On leaving a conversation, at app start and on unpairing: nothing handed off outlives them. */
    fun clear(context: Context) = CacheSink.clear(dir(context))
}

/** What the viewer shows (design D6). */
sealed interface ViewerState {
    data class Loading(val done: Long, val size: Long) : ViewerState

    data class Picture(val image: ImageBitmap) : ViewerState

    data class Markdown(val text: String) : ViewerState

    /** Plain text in pieces of at most [SentFileViewer.PIECE] characters, one lazy item each. */
    data class Lines(val pieces: List<String>) : ViewerState

    /** [picture]: a tool result's picture, worded as one (`tool-result-content` D1). */
    data class Failed(val reason: SentFileRead.Reason, val size: Long? = null, val code: String? = null, val picture: Boolean = false) : ViewerState

    data object CannotShow : ViewerState

    data object PictureCannotShow : ViewerState

    data object NoApp : ViewerState

    /** A picture over the thumbnail limit, not read until it is the one shown (`tool-result-content` D8). */
    data class Large(val size: Long) : ViewerState

    /** Answered as a type the app never renders (SVG, HTML): for another app (D9). */
    data object Elsewhere : ViewerState
}

object SentFileViewer {
    const val PIECE = 2_000

    /** Lines, each over [PIECE] characters split, so no single text is measured wider than Compose can lay out. */
    fun pieces(text: String): List<String> = text.split('\n').flatMap { line -> if (line.length <= PIECE) listOf(line) else line.chunked(PIECE) }

    /** The largest power-of-two sample that keeps the decoded image's longer side at least [longest]. */
    fun sampleSize(width: Int, height: Int, longest: Int): Int {
        var sample = 1
        val side = maxOf(width, height)
        while (side / (sample * 2) >= longest) sample *= 2
        return sample
    }
}

/** One thing full screen swipes through: its ref, and a result picture's listed size (`tool-result-content` D8). */
data class ViewerItem(val ref: SentFileRef, val size: Long? = null)

/** An item by its source and place, its title aside: what the pager keys on and the opened one is found by (D8). */
internal fun viewerKey(ref: SentFileRef): String = "${ref.source}:${ref.toolUseId}:${ref.index}"

/** The current picture's zoom, held by the view so the pager knows not to page while zoomed in (D8). */
internal class Zoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/**
 * A sent file over the conversation, not a route, so the conversation keeps what is expanded and where it is (design
 * D6). Its bytes live only while it is open; a kind the app does not show is handed to another app (D9). A picture in
 * a tool result is always read into memory and shown here, never handed to another app (`tool-result-content` D2).
 * With [items], swiping goes through the conversation's pictures and sent files, unless [ref] is a sent file the app
 * does not show, which is handed off at once as before (`tool-result-content` D8).
 */
@Composable
fun SentFileDialog(sessionId: String, ref: SentFileRef, items: List<ViewerItem> = emptyList(), onClose: () -> Unit) {
    val start = items.indexOfFirst { viewerKey(it.ref) == viewerKey(ref) }
    val elsewhere = ref.source == SentFileRef.Source.SENT && SentFiles.kind(ref.path ?: "") == SentFiles.Kind.OTHER
    if (elsewhere || start < 0 || items.size < 2) {
        SingleFile(sessionId, ref, onClose)
    } else {
        Swiped(sessionId, items, start, onClose)
    }
}

@Composable
private fun SingleFile(sessionId: String, ref: SentFileRef, onClose: () -> Unit) {
    val context = LocalContext.current
    val ctx = LocalToolContext.current
    val longest = screenLongest()
    val name = ref.name
    val picture = ref.source == SentFileRef.Source.RESULT
    val kind = if (picture) SentFiles.Kind.IMAGE else SentFiles.kind(ref.path ?: "")
    var state by remember(ref) { mutableStateOf<ViewerState>(ViewerState.Loading(0, -1)) }
    LaunchedEffect(ref) {
        if (!picture && kind == SentFiles.Kind.OTHER) {
            state = handOffRead(context, ctx, sessionId, ref) { d, s -> state = ViewerState.Loading(d, s) } ?: run {
                onClose()
                return@LaunchedEffect
            }
            return@LaunchedEffect
        }
        state = readShown(ctx, sessionId, ref, kind, SentFiles.limit(kind), longest) { d, s -> state = ViewerState.Loading(d, s) }
        if (state == ViewerState.Elsewhere) {
            state = handOffRead(context, ctx, sessionId, ref) { d, s -> state = ViewerState.Loading(d, s) } ?: run {
                onClose()
                return@LaunchedEffect
            }
        }
    }
    val zoom = remember(ref) { Zoom() }
    ViewerFrame(name, null, onClose) { ViewerBody(state, zoom) }
}

@Composable
private fun Swiped(sessionId: String, items: List<ViewerItem>, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start) { items.size }
    val zoom = remember { Zoom() }
    // Back at 1× whenever the pager comes to rest elsewhere (D8).
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { zoom.reset() } }
    val current = pager.currentPage
    ViewerFrame(items[current].ref.name, current + 1 to items.size, onClose) {
        HorizontalPager(
            pager,
            Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = zoom.scale == 1f,
            key = { viewerKey(items[it].ref) },
        ) { page ->
            SwipedPage(sessionId, items[page], near = abs(page - pager.currentPage) <= 1, settled = page == pager.settledPage, zoom.takeIf { page == pager.currentPage })
        }
    }
}

/**
 * One item of the pager, read by its distance from the current page, never because it is composed (D8): a picture,
 * Markdown or text within one page; a large picture only once settled on; any other file only on its button.
 */
@Composable
private fun SwipedPage(sessionId: String, item: ViewerItem, near: Boolean, settled: Boolean, zoom: Zoom?) {
    val ctx = LocalToolContext.current
    val ref = item.ref
    val result = ref.source == SentFileRef.Source.RESULT
    val kind = if (result) SentFiles.Kind.IMAGE else SentFiles.kind(ref.path ?: "")
    if (kind == SentFiles.Kind.OTHER) return OfferPage(sessionId, ref)
    val longest = screenLongest()
    val thumb = ctx.thumbs["${ref.toolUseId}#${ref.index}"]
    var state by remember { mutableStateOf<ViewerState>(ViewerState.Loading(0, -1)) }
    val large = when {
        result -> item.size?.takeIf { it > THUMB_LIMIT }
        thumb is Thumb.Large -> thumb.size
        else -> (state as? ViewerState.Large)?.size
    }
    val limit = when {
        !near -> null
        large != null && !settled -> null
        kind == SentFiles.Kind.IMAGE && !result && !settled -> THUMB_LIMIT
        else -> SentFiles.limit(kind)
    }
    LaunchedEffect(limit) {
        if (limit == null || (state !is ViewerState.Loading && state !is ViewerState.Large)) return@LaunchedEffect
        state = ViewerState.Loading(0, -1)
        state = readShown(ctx, sessionId, ref, kind, limit, longest, smallFirst = limit == THUMB_LIMIT) { d, s -> state = ViewerState.Loading(d, s) }
    }
    if (state == ViewerState.Elsewhere) return OfferPage(sessionId, ref)
    val shown = if (large != null && limit == null && state is ViewerState.Loading) ViewerState.Large(large) else state
    ViewerBody(shown, zoom)
}

/** A sent file the app does not show, reached by swiping: named, with a button that hands it off (D8). */
@Composable
private fun OfferPage(sessionId: String, ref: SentFileRef) {
    val context = LocalContext.current
    val ctx = LocalToolContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ViewerState?>(null) }
    state?.let { return ViewerBody(it, null) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(SpekIcons.File, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(ref.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        ref.path?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && '/' !in it }?.let {
            Text(it.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = {
            scope.launch { state = handOffRead(context, ctx, sessionId, ref) { d, s -> state = ViewerState.Loading(d, s) } }
        }) { Text(stringResource(R.string.file_open_elsewhere)) }
    }
}

/** The view's frame: the title, its place among the items when there are several, and Close. */
@Composable
private fun ViewerFrame(title: String, place: Pair<Int, Int>?, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    place?.let { (n, of) ->
                        val description = stringResource(R.string.picture_position_description, n, of)
                        Text(
                            stringResource(R.string.picture_position, n, of),
                            Modifier.padding(horizontal = 8.dp).semantics { contentDescription = description },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onClose) { Icon(SpekIcons.Close, stringResource(R.string.file_close)) }
                }
                HorizontalDivider()
                Box(Modifier.fillMaxSize().weight(1f)) { content() }
            }
        }
    }
}

@Composable
private fun screenLongest(): Int =
    with(LocalDensity.current) { maxOf(LocalConfiguration.current.screenWidthDp, LocalConfiguration.current.screenHeightDp).dp.roundToPx() }

/**
 * A picture, Markdown or text file read into memory and made ready to show. [smallFirst]: a sent picture read at the
 * thumbnail limit, so one too large says its size instead of failing (D8).
 */
private suspend fun readShown(
    ctx: ToolContext,
    sessionId: String,
    ref: SentFileRef,
    kind: SentFiles.Kind,
    limit: Long,
    longest: Int,
    smallFirst: Boolean = false,
    progress: (Long, Long) -> Unit,
): ViewerState {
    val picture = ref.source == SentFileRef.Source.RESULT
    val sink = MemorySink()
    try {
        val outcome = ctx.read(sessionId, ref, limit, sink, progress)
        return when {
            outcome is SentFileRead.Outcome.Failed && smallFirst && outcome.reason == SentFileRead.Reason.TOO_LARGE && outcome.size != null -> ViewerState.Large(outcome.size)
            outcome is SentFileRead.Outcome.Failed -> ViewerState.Failed(outcome.reason, outcome.size, outcome.code, picture)
            picture -> {
                val type = (outcome as SentFileRead.Outcome.Done).mediaType
                val image = if (SentFiles.decodable(type)) withContext(Dispatchers.Default) { decode(sink.bytes(), longest) } else null
                image?.let { ViewerState.Picture(it) } ?: ViewerState.PictureCannotShow
            }
            outcome is SentFileRead.Outcome.Done -> when (SentFiles.view(outcome.mediaType, kind)) {
                SentFiles.View.IMAGE -> withContext(Dispatchers.Default) { decode(sink.bytes(), longest) }?.let { ViewerState.Picture(it) } ?: ViewerState.CannotShow
                SentFiles.View.MARKDOWN -> ViewerState.Markdown(sink.bytes().decodeToString())
                SentFiles.View.TEXT -> ViewerState.Lines(withContext(Dispatchers.Default) { SentFileViewer.pieces(sink.bytes().decodeToString()) })
                SentFiles.View.HAND_OFF -> ViewerState.Elsewhere
            }
            else -> ViewerState.CannotShow
        }
    } finally {
        sink.reset()
    }
}

/** A sent file read into the private cache and handed to another app (D9); `null` once handed off, else what to say. */
private suspend fun handOffRead(context: Context, ctx: ToolContext, sessionId: String, ref: SentFileRef, progress: (Long, Long) -> Unit): ViewerState? {
    val cache = CacheSink(SentFileCache.dir(context), ref.name)
    var kept = false
    try {
        return when (val outcome = ctx.read(sessionId, ref, SentFiles.limit(SentFiles.Kind.OTHER), cache, progress)) {
            is SentFileRead.Outcome.Failed -> ViewerState.Failed(outcome.reason, outcome.size, outcome.code)
            is SentFileRead.Outcome.Done -> {
                cache.close()
                if (handOff(context, cache.file, outcome.mediaType)) {
                    kept = true
                    null
                } else {
                    ViewerState.NoApp
                }
            }
        }
    } finally {
        if (!kept) cache.reset()
    }
}

@Composable
internal fun ViewerBody(state: ViewerState, zoom: Zoom? = null) {
    when (state) {
        is ViewerState.Loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            if (state.size >= 0) Text(stringResource(R.string.file_loading, formatBytes(state.done), formatBytes(state.size)), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        is ViewerState.Large -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(SpekIcons.Image, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatBytes(state.size), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        is ViewerState.Picture -> ZoomableImage(state.image, zoom)
        is ViewerState.Markdown -> RepositoryMarkdown(state.text, lazy = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), immediate = false)
        is ViewerState.Lines -> SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                itemsIndexed(state.pieces) { _, piece -> Text(piece, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
        is ViewerState.Failed -> CenteredMessage(SpekIcons.Info, failedText(state))
        ViewerState.CannotShow -> CenteredMessage(SpekIcons.Info, stringResource(R.string.file_cannot_show))
        ViewerState.PictureCannotShow -> CenteredMessage(SpekIcons.Info, stringResource(R.string.picture_cannot_show))
        ViewerState.NoApp -> CenteredMessage(SpekIcons.Info, stringResource(R.string.file_no_app))
        ViewerState.Elsewhere -> Unit
    }
}

@Composable
private fun failedText(f: ViewerState.Failed): String = if (f.picture) pictureFailedText(f) else when (f.reason) {
    SentFileRead.Reason.NOT_SENT -> stringResource(R.string.file_not_sent)
    SentFileRead.Reason.GONE -> stringResource(R.string.file_gone)
    SentFileRead.Reason.CHANGED -> stringResource(R.string.file_changed)
    SentFileRead.Reason.TOO_LARGE -> f.size?.let { stringResource(R.string.file_too_large, formatBytes(it)) } ?: stringResource(R.string.conversation_upload_too_large)
    SentFileRead.Reason.NOT_CONNECTED -> stringResource(R.string.file_not_connected)
    SentFileRead.Reason.SESSION_GONE -> stringResource(R.string.file_session_gone)
    SentFileRead.Reason.NO_IMAGE, SentFileRead.Reason.OTHER -> stringResource(R.string.file_other, f.code ?: "?")
}

/** A result picture's failure, never in a sent file's words (`tool-result-content` D1). */
@Composable
private fun pictureFailedText(f: ViewerState.Failed): String = when (f.reason) {
    SentFileRead.Reason.NO_IMAGE -> stringResource(R.string.picture_no_image)
    SentFileRead.Reason.CHANGED -> stringResource(R.string.picture_changed)
    SentFileRead.Reason.TOO_LARGE -> f.size?.let { stringResource(R.string.picture_too_large, formatBytes(it)) } ?: stringResource(R.string.picture_other, f.code ?: "?")
    SentFileRead.Reason.NOT_CONNECTED -> stringResource(R.string.file_not_connected)
    SentFileRead.Reason.SESSION_GONE -> stringResource(R.string.file_session_gone)
    SentFileRead.Reason.NOT_SENT, SentFileRead.Reason.GONE, SentFileRead.Reason.OTHER -> stringResource(R.string.picture_other, f.code ?: "?")
}

/**
 * A picture the user can zoom. Its own gesture loop, not `detectTransformGestures`, which takes every move: at 1× only
 * two fingers are taken, so one finger swipes the pager; zoomed in, one finger pans (D8). Without [zoom] it stays 1×.
 */
@Composable
private fun ZoomableImage(image: ImageBitmap, zoom: Zoom?) {
    Image(
        image,
        null,
        Modifier
            .fillMaxSize()
            .pointerInput(zoom) {
                if (zoom == null) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2 || zoom.scale > 1f) {
                            val scale = (zoom.scale * event.calculateZoom()).coerceIn(1f, 8f)
                            zoom.offset = if (scale == 1f) Offset.Zero else zoom.offset + event.calculatePan()
                            zoom.scale = scale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(scaleX = zoom?.scale ?: 1f, scaleY = zoom?.scale ?: 1f, translationX = zoom?.offset?.x ?: 0f, translationY = zoom?.offset?.y ?: 0f),
        contentScale = ContentScale.Fit,
    )
}

/** Decoded no larger than the screen's longer side (design D6); `null` when the bytes are not an image. */
internal fun decode(bytes: ByteArray, longest: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = SentFileViewer.sampleSize(bounds.outWidth, bounds.outHeight, longest) }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/** The picture's own width in pixels, read from its header; 0 when the bytes are not an image. */
internal fun pixelWidth(bytes: ByteArray): Int {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return bounds.outWidth.coerceAtLeast(0)
}

/** The intent that hands [file] to another app, read-only through the app's provider (design D9). */
internal fun handOffIntent(context: Context, file: File, mediaType: String): Intent {
    val type = mediaType.takeUnless { it == "application/octet-stream" }
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: "application/octet-stream"
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Offers [file] to another app; `false` when no app opens its type. */
private fun handOff(context: Context, file: File, mediaType: String): Boolean {
    val view = handOffIntent(context, file, mediaType)
    if (context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) return false
    return try {
        context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
