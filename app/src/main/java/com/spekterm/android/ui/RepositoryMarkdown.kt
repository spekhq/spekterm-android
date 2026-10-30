package com.spekterm.android.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.rememberMarkdownState
import com.spekterm.android.desktop.OpenSpecText
import com.spekterm.android.desktop.Replies
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
fun safeAnnotator(softBreaks: Boolean, inertLinks: Boolean, commands: CommandSpans? = null): MarkdownAnnotator = markdownAnnotator(
    config = markdownAnnotatorConfig(eolAsNewLine = !softBreaks),
    annotate = { content, child ->
        val suggested = if (commands != null && child.type == MarkdownElementTypes.CODE_SPAN) codeSpanText(child, content) else null
        val command = suggested?.let(Replies::suggested)
        when {
            // Drawn as the renderer draws a code span, underlined and tappable (`run-shell-commands` D7).
            commands != null && command != null -> {
                withLink(LinkAnnotation.Clickable(COMMAND_LINK, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))) { commands.onCommand(command) }) {
                    withStyle(commands.style) {
                        append(' ')
                        append(suggested)
                        append(' ')
                    }
                }
                true
            }
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

/** Where a `! command` code span of the agent's text goes, and the code-span style it is drawn in (D7). */
class CommandSpans(val style: SpanStyle, val onCommand: (String) -> Unit)

private const val COMMAND_LINK = "command"

/** A code span's text without the backticks around it. */
private fun codeSpanText(span: ASTNode, content: String): String =
    span.getTextInNode(content).toString().let { it.trimStart('`').trimEnd('`') }

private fun webLink(link: ASTNode, content: String): Boolean {
    val destination = link.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.getTextInNode(content)?.toString()?.trim()
        ?: return false
    val scheme = destination.substringBefore(':', "").lowercase()
    return scheme == "http" || scheme == "https"
}

/**
 * Unknown block types are dropped by the renderer; only HTML blocks are shown, as text. A table shows every cell
 * whole.
 */
@Composable
fun safeComponents(): MarkdownComponents = markdownComponents(
    table = { WholeTable(it) },
    custom = { type, model -> HtmlBlock(type, model) },
)

/**
 * The renderer cuts every table cell to one line with an ellipsis; here a cell wraps onto as many lines as its text
 * needs, and a row's cells line up at the top. Columns keep the renderer's width, scrolling sideways when they do not
 * fit.
 */
@Composable
private fun WholeTable(model: MarkdownComponentModel) {
    MarkdownTable(
        content = model.content,
        node = model.node,
        style = model.typography.table,
        headerBlock = { content, header, width, style ->
            MarkdownTableHeader(content, header, width, style, verticalAlignment = Alignment.Top, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
        },
        rowBlock = { content, row, width, style ->
            MarkdownTableRow(content, row, width, style, verticalAlignment = Alignment.Top, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
        },
    )
}

@Composable
private fun HtmlBlock(type: IElementType, model: MarkdownComponentModel) {
    if (type == MarkdownElementTypes.HTML_BLOCK) {
        Text(model.node.getTextInNode(model.content).toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A repository's Markdown: an OpenSpec artifact, delta, spec or task (`openspec-browse` design D7). Loads no image,
 * opens only `http`/`https` links on tap, shows HTML as text, joins soft line breaks, and makes relative inline links
 * plain text. [immediate] false parses off the main thread (a sent file, `tool-call-display` D6). [lazy] lays its blocks out as a lazy list that fills its space (an artifact, a spec); otherwise it is
 * one block for an item of a list (a task, a delta). Parsed at once, so a re-read never passes through loading.
 * [struck] shows the text faded and struck through, as the desktop shows a done task ([OpenSpecText.struck]).
 */
@Composable
fun RepositoryMarkdown(text: String, lazy: Boolean, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(), immediate: Boolean = true, struck: Boolean = false) {
    val context = LocalContext.current
    val handler = remember(context) { SafeUriHandler(context) }
    val annotator = remember { safeAnnotator(softBreaks = true, inertLinks = true) }
    val faded = MaterialTheme.colorScheme.outline
    val body = MaterialTheme.typography.bodyLarge.let { if (struck) it.copy(color = faded, textDecoration = TextDecoration.LineThrough) else it }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Markdown(
            markdownState = rememberMarkdownState(if (struck) OpenSpecText.struck(text) else text, immediate = immediate),
            modifier = modifier,
            error = { Text(text, modifier = it, style = MaterialTheme.typography.bodyMedium) },
            imageTransformer = NoOpImageTransformerImpl(),
            animations = markdownAnimations(animateTextSize = { this }),
            annotator = annotator,
            components = safeComponents(),
            colors = if (struck) markdownColor(text = faded) else markdownColor(),
            // Documents have many headings: title sizes, not display sizes.
            typography = markdownTypography(
                h1 = MaterialTheme.typography.titleLarge,
                h2 = MaterialTheme.typography.titleMedium,
                h3 = MaterialTheme.typography.titleSmall,
                h4 = MaterialTheme.typography.titleSmall,
                h5 = MaterialTheme.typography.titleSmall,
                h6 = MaterialTheme.typography.titleSmall,
                text = body,
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
            ),
            success = if (lazy) {
                { state, components, m -> LazyMarkdownSuccess(state, components, m, contentPadding) }
            } else {
                { state, components, m -> com.mikepenz.markdown.compose.MarkdownSuccess(state, components, m) }
            },
        )
    }
}
