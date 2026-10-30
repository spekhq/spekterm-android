package com.spekterm.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.utils.codeSpanStyle

/**
 * Agent and user text as Markdown (`conversation-view`, design D5). The text is agent- or third-party
 * controlled, so:
 *
 * - **Nothing is fetched**: the no-op image transformer, passed explicitly, loads no image.
 * - **Links** open only on tap, only `http`/`https`, in the browser; anything else returns silently.
 * - **HTML is text**: the renderer drops HTML tags and blocks; here they are put back as the text they are, so
 *   `Vec<T>` and a slash command's `<command-name>` markup are not lost.
 * - **No transition**: the renderer's default `animateContentSize` would last longer for longer text.
 * - **Parsed at once**, not asynchronously, so an entry never shows empty first and fills in after a delay that
 *   grows with its length.
 */
@Composable
fun AgentMarkdown(text: String, modifier: Modifier = Modifier, onCommand: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val handler = remember(context) { SafeUriHandler(context) }
    val typography = chatTypography()
    val colors = markdownColor()
    // The renderer's code-span style reads these, which it otherwise provides only inside itself.
    CompositionLocalProvider(LocalUriHandler provides handler, LocalMarkdownColors provides colors, LocalMarkdownTypography provides typography) {
        // A `! command` code span of the agent's own text goes into the command field (`run-shell-commands` D7).
        val codeStyle = if (onCommand != null) typography.codeSpanStyle else null
        val commands = remember(codeStyle, onCommand) { if (onCommand != null && codeStyle != null) CommandSpans(codeStyle, onCommand) else null }
        Markdown(
            markdownState = rememberMarkdownState(text, immediate = true),
            modifier = modifier,
            // Should parsing ever fail, the text is still shown, as plain text.
            error = { Text(text, modifier = it, style = MaterialTheme.typography.bodyMedium) },
            imageTransformer = NoOpImageTransformerImpl(),
            animations = markdownAnimations(animateTextSize = { this }),
            // A line break in agent or user text is kept: agents and people do not hard-wrap their lines, and
            // joining them with spaces breaks poems, lists without markers, and CJK text.
            annotator = remember(commands) { safeAnnotator(softBreaks = false, inertLinks = false, commands = commands) },
            // Called for every block type the renderer does not know; only HTML blocks are shown.
            components = safeComponents(onCommand),
            colors = colors,
            // A chat message's headings stand out from its text without dwarfing the conversation: no display sizes.
            typography = typography,
        )
    }
}

@Composable
private fun chatTypography() = MaterialTheme.typography.let { t ->
    val h3 = t.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    markdownTypography(
        h1 = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        h2 = t.titleMedium.copy(fontSize = t.titleLarge.fontSize * 0.85f, lineHeight = t.titleLarge.lineHeight * 0.85f, fontWeight = FontWeight.SemiBold),
        h3 = h3,
        h4 = h3,
        h5 = h3,
        h6 = h3,
    )
}

/** Opens `http`/`https` links in the browser; ignores every other scheme without throwing. */
class SafeUriHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return
        if (parsed.scheme?.lowercase() !in setOf("http", "https")) return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, parsed).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }
}
