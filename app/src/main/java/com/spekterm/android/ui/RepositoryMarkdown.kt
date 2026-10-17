package com.spekterm.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.rememberMarkdownState
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

/**
 * The safety settings every Markdown text shares (`conversation-view` design D5; `openspec-browse` D7, obligation
 * 14): HTML is put back as the text it is, and with [inertLinks] an inline link whose destination is not
 * `http`/`https` is its text, with no link style and nothing to tap. [softBreaks] joins the lines of a paragraph, as
 * the desktop renders repository files; agent text keeps every line break.
 */
fun safeAnnotator(softBreaks: Boolean, inertLinks: Boolean): MarkdownAnnotator = markdownAnnotator(
    config = markdownAnnotatorConfig(eolAsNewLine = !softBreaks),
    annotate = { content, child ->
        when {
            child.type == MarkdownTokenTypes.HTML_TAG -> {
                append(child.getTextInNode(content).toString())
                true
            }
            // An image is never loaded; in repository text it is its alt text, not the space the renderer keeps.
            inertLinks && child.type == MarkdownElementTypes.IMAGE -> {
                val alt = Regex("^!\\[(.*?)]").find(child.getTextInNode(content).toString())?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                append("[image" + (alt?.let { ": $it" } ?: "") + "]")
                true
            }
            inertLinks && child.type == MarkdownElementTypes.INLINE_LINK && !webLink(child, content) -> {
                val label = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                append(label?.getTextInNode(content)?.toString()?.removePrefix("[")?.removeSuffix("]") ?: child.getTextInNode(content).toString())
                true
            }
            else -> false
        }
    },
)

private fun webLink(link: ASTNode, content: String): Boolean {
    val destination = link.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.getTextInNode(content)?.toString()?.trim()
        ?: return false
    val scheme = destination.substringBefore(':', "").lowercase()
    return scheme == "http" || scheme == "https"
}

/** Unknown block types are dropped by the renderer; only HTML blocks are shown, as text. */
@Composable
fun safeComponents(): MarkdownComponents = markdownComponents(custom = { type, model -> HtmlBlock(type, model) })

@Composable
private fun HtmlBlock(type: IElementType, model: MarkdownComponentModel) {
    if (type == MarkdownElementTypes.HTML_BLOCK) {
        Text(model.node.getTextInNode(model.content).toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A repository's Markdown: an OpenSpec artifact, delta, spec or task (`openspec-browse` design D7). Loads no image,
 * opens only `http`/`https` links on tap, shows HTML as text, joins soft line breaks, and makes relative inline links
 * plain text. [lazy] lays its blocks out as a lazy list that fills its space (an artifact, a spec); otherwise it is
 * one block for an item of a list (a task, a delta). Parsed at once, so a re-read never passes through loading.
 */
@Composable
fun RepositoryMarkdown(text: String, lazy: Boolean, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val handler = remember(context) { SafeUriHandler(context) }
    val annotator = remember { safeAnnotator(softBreaks = true, inertLinks = true) }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(
            markdownState = rememberMarkdownState(text, immediate = true),
            modifier = modifier,
            error = { Text(text, modifier = it, style = MaterialTheme.typography.bodyMedium) },
            imageTransformer = NoOpImageTransformerImpl(),
            animations = markdownAnimations(animateTextSize = { this }),
            annotator = annotator,
            components = safeComponents(),
            // Documents have many headings: title sizes, not display sizes.
            typography = markdownTypography(
                h1 = MaterialTheme.typography.titleLarge,
                h2 = MaterialTheme.typography.titleMedium,
                h3 = MaterialTheme.typography.titleSmall,
                h4 = MaterialTheme.typography.titleSmall,
                h5 = MaterialTheme.typography.titleSmall,
                h6 = MaterialTheme.typography.titleSmall,
            ),
            success = if (lazy) {
                { state, components, m -> LazyMarkdownSuccess(state, components, m, contentPadding) }
            } else {
                { state, components, m -> com.mikepenz.markdown.compose.MarkdownSuccess(state, components, m) }
            },
        )
    }
}
