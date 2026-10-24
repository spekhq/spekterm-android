package com.spekterm.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.desktop.PeerText
import com.spekterm.android.desktop.ToolView
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Origin
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import com.spekterm.android.net.ToolInput
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A call's labeled parts and who sent a message, on screen (`tool-call-display` D3, D5, D7). */
@RunWith(AndroidJUnit4::class)
class ToolViewsTest {
    @get:Rule val compose = createComposeRule()
    private val server = MockWebServer()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private val list = SessionList(true, emptyList(), listOf(Session("s", target = "claude", label = "Auto update", peerName = "spekterm-e8d8")))

    private fun call(name: String, input: String) = Entry.Tool("u", 0, "t", name, "arg", ToolInput.of(Json.parseToJsonElement(input)))

    /** Every node's content description, in tree order. */
    private fun descriptions(): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
            n.children.forEach(::walk)
        }
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    private fun texts(): List<String> = compose.onAllNodesWithText("", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }

    @Test
    fun aCommandIsLabeledBeforeItsOutput() {
        val view = ToolView.of(call("Bash", """{"command":"ls -l"}"""), Entry.Result("r", 0, "t", "a.txt", false))
        compose.setContent { English { ToolParts(view, "t") } }
        val d = descriptions()
        val command = d.indexOfFirst { it.startsWith("Command, ls -l") }
        val output = d.indexOfFirst { it.startsWith("Output, a.txt") }
        assertTrue(d.toString(), command >= 0 && output > command)
        // What a screen reader reads (the merged tree): the label only inside the part's description.
        assertTrue("the label is not read on its own", compose.onAllNodesWithText("Command").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aFailedCommandIsAnError() {
        val view = ToolView.of(call("Bash", """{"command":"x"}"""), Entry.Result("r", 0, "t", "boom", true))
        compose.setContent { English { ToolParts(view, "t") } }
        assertTrue(descriptions().any { it.startsWith("Error, boom") })
    }

    @Test
    fun aMessageNamesTheSessionAndIsPlainText() {
        val view = ToolView.of(
            call("SendMessage", """{"to":"spekterm-e8d8","recipient_kind":"name","message":"**done**"}"""),
            Entry.Result("r", 0, "t", """{"success":true,"message":"x"}""", false),
            list,
        )
        compose.setContent { English { Column { summaryOf(view)?.let { Text(it) }; ToolParts(view, "t") } } }
        val t = texts()
        assertTrue(t.toString(), t.any { it.startsWith("→ Auto update") })
        assertTrue(descriptions().any { it == "Message, **done**" })
        assertTrue(t.contains("Sent"))
        assertFalse(t.any { "{\"success\"" in it })
    }

    @Test
    fun whoSentAMessage() {
        compose.setContent {
            English {
                CompositionLocalProvider(LocalToolContext provides ToolContext(list, false) {}) {
                    Column {
                        Text(injectedLabel(Origin.Peer(Origin.Sender.SESSION, "spekterm-e8d8")))
                        Text(injectedLabel(Origin.Peer(Origin.Sender.SESSION, "api-server-8393")))
                        Text(injectedLabel(Origin.Peer(Origin.Sender.SUBAGENT, null)))
                        Text(injectedLabel(Origin.TaskNotice))
                    }
                }
            }
        }
        val t = texts()
        assertTrue(t.toString(), t.containsAll(listOf("From Auto update", "From api-server-8393", "Report from a subagent", "Background task notice")))
    }

    @Test
    fun aReportLoadsNothingAndShowsItsHeadingAndNoId() {
        val url = server.url("/x.png").toString()
        val raw = "Another Claude session sent a message:\n<agent-message from=\"a616e7cb896affbf6\">\n[Subagent hand-back] The report follows:\n  # Title\n  \n  ![x]($url)\n</agent-message>"
        compose.setContent { AgentMarkdown(PeerText.strip(raw)) }
        compose.waitForIdle()
        Thread.sleep(1_500)
        compose.waitForIdle()
        assertEquals("nothing may be fetched", 0, server.requestCount)
        val t = texts()
        assertTrue(t.toString(), t.contains("Title"))
        assertFalse(t.any { "a616e7cb896affbf6" in it || "#" in it })
    }
}
