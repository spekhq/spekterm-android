package com.spekterm.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.desktop.ConversationItems
import com.spekterm.android.desktop.ConversationReducer
import com.spekterm.android.net.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Thinking shown open between tool calls, its code inert (`thinking-as-message` D1, D2). */
@RunWith(AndroidJUnit4::class)
class ThinkingRowTest {
    @get:Rule val compose = createComposeRule()

    private val ran = mutableListOf<String>()

    private fun show(vararg entries: Entry) {
        val list = entries.toList()
        val items = ConversationItems.group(list, ConversationReducer.keys(list), emptySet())
        val open = mutableStateMapOf<String, Boolean>()
        compose.setContent {
            English {
                CompositionLocalProvider(LocalToolContext provides ToolContext(null, false, runCommand = { ran += it }) {}) {
                    Column { items.forEach { ShownRow(it, open, null) } }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun call(n: Int) = Entry.Tool("c$n", 0, "t$n", "Bash", "ls")

    @Test
    fun thinkingIsShownOpenWithItsCodeInert() {
        show(
            call(1),
            Entry.Thinking("t", 0, "Next I change `remote-server.ts`, then run `! npm run deploy`.\n\n```\n! git push\n```"),
            call(2),
            call(3),
            Entry.Text("x", 0, "Or run `! npm login` yourself."),
        )
        compose.onNodeWithText("Thinking").assertExists()
        val node = compose.onNodeWithText("remote-server.ts", substring = true).fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].single()
        assertTrue(text.text, "Next I change" in text.text && "! npm run deploy" in text.text && '`' !in text.text)
        assertTrue("no command link in thinking", text.getLinkAnnotations(0, text.length).isEmpty())
        assertTrue("no Run it myself under thinking's block", compose.onAllNodesWithText("Run it myself").fetchSemanticsNodes().isEmpty())
        // The same context does offer the agent's own text's command, so the check above is not vacuous.
        val agent = compose.onNodeWithText("npm login", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val link = agent.getLinkAnnotations(0, agent.length).single().item as LinkAnnotation.Clickable
        compose.onNodeWithText("npm run deploy", substring = true).performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), ran) }
        compose.runOnIdle { link.linkInteractionListener!!.onClick(link) }
        assertEquals(listOf("npm login"), ran)
        // The calls after the thinking form their own run.
        compose.onNodeWithText("2 tool calls").assertExists()
    }
}
