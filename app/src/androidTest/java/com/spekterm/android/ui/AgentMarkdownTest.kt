package com.spekterm.android.ui

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Agent text loads nothing, keeps HTML as text, and opens only web links on tap (`conversation-view`); repository
 * text also joins soft line breaks and makes relative inline links plain text (`openspec-browse` D7).
 */
@RunWith(AndroidJUnit4::class)
class AgentMarkdownTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    @Test
    fun loadsNothingAndShowsHtmlAsText() {
        val url = server.url("/x.png").toString()
        val text = """
            ![x]($url)

            An inline <img src="$url"> tag, and a [link]($url).

            Use `Vec<T>` or Vec<T> here; <command-name>/opsx:continue</command-name>

            <div>
            block html
            </div>
        """.trimIndent()
        compose.setContent { AgentMarkdown(text) }
        compose.waitForIdle()
        Thread.sleep(1_500)
        compose.waitForIdle()
        assertEquals("nothing may be fetched", 0, server.requestCount)
        compose.onNodeWithText("Vec<T> here", substring = true).assertExists()
        compose.onNodeWithText("<command-name>/opsx:continue</command-name>", substring = true).assertExists()
        compose.onNodeWithText("block html", substring = true).assertExists()
        compose.onNodeWithText("<img", substring = true).assertExists()
    }

    @Test
    fun opensOnlyWebLinks() {
        val started = mutableListOf<Intent>()
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) {
                started += intent
            }
        }
        val handler = SafeUriHandler(context)
        handler.openUri("javascript:alert(1)")
        handler.openUri("intent://x#Intent;end")
        handler.openUri("file:///sdcard/x")
        handler.openUri("not a uri at all")
        assertEquals(0, started.size)
        handler.openUri("https://example.com/a")
        assertEquals("https://example.com/a", started.single().data.toString())
    }

    private fun hasLink(text: String): Boolean {
        val node = compose.onNodeWithText(text, substring = true).fetchSemanticsNode()
        val shown = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        val annotated = node.config[SemanticsProperties.Text]
        return annotated.any { it.getLinkAnnotations(0, it.length).isNotEmpty() || it.getStringAnnotations(0, it.length).isNotEmpty() }.also {
            assertTrue("shown: $shown", shown.contains(text))
        }
    }

    @Test
    fun repositoryTextJoinsSoftBreaksAndAgentTextKeepsThem() {
        compose.setContent {
            Column {
                RepositoryMarkdown("first wrapped\nline of a paragraph", lazy = false)
                AgentMarkdown("agent first\nagent second")
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("first wrapped line of a paragraph", substring = true).assertExists()
        compose.onNodeWithText("agent first\nagent second", substring = true).assertExists()
    }

    @Test
    fun aRelativeLinkIsPlainTextAndAWebLinkStaysALink() {
        compose.setContent {
            Column {
                RepositoryMarkdown("See [the design](design.md) first.", lazy = false)
                RepositoryMarkdown("Or [the site](https://example.com) instead.", lazy = false)
                RepositoryMarkdown("Also [a section](#d3) here.", lazy = false)
            }
        }
        compose.waitForIdle()
        assertFalse("a relative link is plain text", hasLink("See the design first."))
        assertFalse("an anchor is plain text", hasLink("Also a section here."))
        assertTrue("a web link stays a link", hasLink("the site"))
    }

    @Test
    fun repositoryTextLoadsNothingAndShowsHtmlAsText() {
        val url = server.url("/i.png").toString()
        compose.setContent { RepositoryMarkdown("![i]($url)\n\nSome <b>bold</b> markup.\n\n<script>alert(1)</script>", lazy = true) }
        compose.waitForIdle()
        Thread.sleep(1_500)
        compose.waitForIdle()
        assertEquals("nothing may be fetched", 0, server.requestCount)
        compose.onNodeWithText("<b>bold</b>", substring = true).assertExists()
        compose.onNodeWithText("<script>", substring = true).assertExists()
    }

    @Test
    fun aTableCellShowsAllItsText() {
        val long = "a long cell that cannot fit on one line of a narrow column, so it wraps instead of being cut off"
        val table = "| Item | What it says |\n| --- | --- |\n| one | $long |"
        compose.setContent {
            Column {
                AgentMarkdown(table)
                RepositoryMarkdown(table, lazy = false)
            }
        }
        compose.waitForIdle()
        val cells = compose.onAllNodesWithText(long, substring = true).fetchSemanticsNodes()
        assertEquals("agent and repository text each show the cell", 2, cells.size)
        cells.forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val layout = results.single()
            assertFalse("the cell is not cut off", layout.hasVisualOverflow)
            assertTrue("the cell wraps", layout.lineCount > 1)
            assertFalse("no ellipsis", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        }
    }
}
