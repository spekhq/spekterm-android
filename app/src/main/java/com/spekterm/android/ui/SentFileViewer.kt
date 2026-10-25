package com.spekterm.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
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

/**
 * A sent file over the conversation, not a route, so the conversation keeps what is expanded and where it is (design
 * D6). Its bytes live only while it is open; a kind the app does not show is handed to another app (D9). A picture in
 * a tool result is always read into memory and shown here, never handed to another app (`tool-result-content` D2).
 */
@Composable
fun SentFileDialog(sessionId: String, ref: SentFileRef, onClose: () -> Unit) {
    val context = LocalContext.current
    val ctx = LocalToolContext.current
    val longest = with(LocalDensity.current) { maxOf(LocalConfiguration.current.screenWidthDp, LocalConfiguration.current.screenHeightDp).dp.roundToPx() }
    val name = ref.name
    val picture = ref.source == SentFileRef.Source.RESULT
    val kind = if (picture) SentFiles.Kind.IMAGE else SentFiles.kind(ref.path ?: "")
    var state by remember(ref) { mutableStateOf<ViewerState>(ViewerState.Loading(0, -1)) }
    LaunchedEffect(ref) {
        val sink = if (!picture && kind == SentFiles.Kind.OTHER) CacheSink(SentFileCache.dir(context), name) else MemorySink()
        var done = false
        try {
            val outcome = ctx.read(sessionId, ref, SentFiles.limit(kind), sink) { d, s -> state = ViewerState.Loading(d, s) }
            state = when {
                outcome is SentFileRead.Outcome.Failed -> ViewerState.Failed(outcome.reason, outcome.size, outcome.code, picture)
                picture -> {
                    val type = (outcome as SentFileRead.Outcome.Done).mediaType
                    val image = if (SentFiles.decodable(type)) withContext(Dispatchers.Default) { decode((sink as MemorySink).bytes(), longest) } else null
                    image?.let { ViewerState.Picture(it) } ?: ViewerState.PictureCannotShow
                }
                outcome is SentFileRead.Outcome.Done -> when (SentFiles.view(outcome.mediaType, kind)) {
                    SentFiles.View.HAND_OFF -> {
                        val cache = sink as? CacheSink ?: CacheSink(SentFileCache.dir(context), name).also { it.write((sink as MemorySink).bytes()) }
                        cache.close()
                        done = true
                        if (handOff(context, cache.file, outcome.mediaType)) {
                            onClose()
                            return@LaunchedEffect
                        }
                        cache.reset()
                        ViewerState.NoApp
                    }
                    SentFiles.View.IMAGE -> withContext(Dispatchers.Default) { decode((sink as MemorySink).bytes(), longest) }?.let { ViewerState.Picture(it) } ?: ViewerState.CannotShow
                    SentFiles.View.MARKDOWN -> ViewerState.Markdown((sink as MemorySink).bytes().decodeToString())
                    SentFiles.View.TEXT -> ViewerState.Lines(withContext(Dispatchers.Default) { SentFileViewer.pieces((sink as MemorySink).bytes().decodeToString()) })
                }
                else -> ViewerState.CannotShow
            }
            if (sink is MemorySink) sink.reset()
            done = true
        } finally {
            if (!done) sink.reset()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onClose) { Icon(SpekIcons.Close, stringResource(R.string.file_close)) }
                }
                HorizontalDivider()
                Box(Modifier.fillMaxSize().weight(1f)) { ViewerBody(state) }
            }
        }
    }
}

@Composable
internal fun ViewerBody(state: ViewerState) {
    when (state) {
        is ViewerState.Loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            if (state.size >= 0) Text(stringResource(R.string.file_loading, formatBytes(state.done), formatBytes(state.size)), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        is ViewerState.Picture -> ZoomableImage(state.image)
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

@Composable
private fun ZoomableImage(image: ImageBitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        image,
        null,
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
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
