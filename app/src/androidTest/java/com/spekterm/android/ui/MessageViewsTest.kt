package com.spekterm.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.desktop.ConversationItems
import com.spekterm.android.desktop.ConversationReducer
import com.spekterm.android.desktop.MemorySink
import com.spekterm.android.desktop.SentFileRead
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Origin
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import com.spekterm.android.net.ToolInput
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exchanged messages shown as chat (`tool-call-display` D11). */
@RunWith(AndroidJUnit4::class)
class MessageViewsTest {
    @get:Rule val compose = createComposeRule()

    private val list = SessionList(true, emptyList(), listOf(Session("s2", target = "claude", label = "Test inbox", peerName = "go-f975")))

    private fun input(json: String) = ToolInput.of(Json.parseToJsonElement(json))

    private fun items(vararg entries: Entry) = ConversationItems.group(entries.toList(), ConversationReducer.keys(entries.toList()), emptySet())

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun show(entries: List<Entry>, ctx: ToolContext = ToolContext(list, true, "s1", "A") {}) {
        val open = mutableStateMapOf<String, Boolean>()
        compose.setContent {
            English {
                CompositionLocalProvider(LocalToolContext provides ctx) {
                    Column {
                        for (m in ConversationItems.group(entries, ConversationReducer.keys(entries), emptySet())) {
                            if (m is ConversationItems.Message) MessageRow(m, open, "21:24")
                            if (m is ConversationItems.Plain && m.entry is Entry.User) NoticeLine((m.entry as Entry.User).text, false) {}
                        }
                    }
                }
            }
        }
    }

    @Test
    fun aPeerMessageIsShownWithoutATap() {
        val text = "Another Claude session sent a message:\n<cross-session-message from=\"uds:/run/user/1001/x.sock\" from-name=\"go-f975\">\nGot it while working\n</cross-session-message>"
        show(listOf(Entry.User("u", 0, text, true, Origin.Peer(Origin.Sender.SESSION, "go-f975"))))
        assertTrue(shown("Test inbox"))
        assertTrue(shown("Got it while working"))
        assertFalse(shown("uds:"))
    }

    @Test
    fun theAgentsMessageSaysWhereAndWhetherItWent() {
        val send = Entry.Tool("u", 0, "t", "SendMessage", null, input("""{"to":"go-f975","recipient_kind":"name","message":"Test message"}"""))
        show(listOf(send, Entry.Result("r", 0, "t", """{"success":true}""", false)))
        assertTrue(shown("A to Test inbox"))
        assertTrue(shown("Test message"))
        assertTrue(shown("Sent"))
    }

    @Test
    fun aMessageNotSentSaysWhy() {
        val send = Entry.Tool("u", 0, "t", "SendMessage", null, input("""{"to":"gone","message":"m"}"""))
        show(listOf(send, Entry.Result("r", 0, "t", """{"success":false,"message":"no such session"}""", false)))
        assertTrue(shown("Not sent: no such session"))
    }

    @Test
    fun aLongReportShowsAllOnATap() {
        val report = (1..30).joinToString("\n") { "line $it" }
        show(listOf(Entry.User("u", 0, report, true, Origin.Peer(Origin.Sender.SUBAGENT, "openspec-change-reviewer"))))
        assertTrue(shown("Report from openspec-change-reviewer"))
        assertFalse(shown("line 30"))
        compose.onNodeWithText("Show all").performClick()
        assertTrue(shown("line 30"))
    }

    @Test
    fun aNoticeShowsItsSummary() {
        show(listOf(Entry.User("u", 0, "<task-notification><summary>Background command \"Run tests\" completed (exit code 0)</summary></task-notification>", true, Origin.TaskNotice)))
        assertTrue(shown("Run tests"))
    }

    @Test
    fun theMessageIsReadInOrder() {
        val send = Entry.Tool("u", 0, "t", "SendMessage", null, input("""{"to":"go-f975","message":"Body text"}"""))
        show(listOf(send, Entry.Result("r", 0, "t", """{"success":true}""", false)))
        val merged = compose.onNodeWithText("Body text", substring = true).fetchSemanticsNode()
        var node = merged
        while (node.config.getOrNull(SemanticsProperties.Text)?.size ?: 0 < 3 && node.parent != null) node = node.parent!!
        val texts = node.config[SemanticsProperties.Text].map { it.text }
        assertTrue(texts.toString(), texts.indexOfFirst { "A to Test inbox" in it } < texts.indexOfFirst { "Body text" in it })
        assertTrue(texts.toString(), texts.indexOfFirst { "Body text" in it } < texts.indexOfFirst { it == "Sent" })
    }

    @Test
    fun picturesAreReadOnceAndLargeOnesWaitForATap() {
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(800, 600, Bitmap.Config.RGB_565).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val reads = mutableListOf<Int>()
        val thumbs = mutableStateMapOf<String, Thumb>()
        val ctx = ToolContext(list, true, "s1", "A", thumbs, read = { _, ref, limit, sink, _ ->
            reads += ref.index
            if (ref.index == 1) {
                SentFileRead.Outcome.Failed(SentFileRead.Reason.TOO_LARGE, 6_400_000)
            } else {
                assertEquals(THUMB_LIMIT, limit)
                sink.write(png)
                SentFileRead.Outcome.Done("a.png", "image/png", png.size.toLong())
            }
        }) {}
        val tool = Entry.Tool("u", 0, "t", "SendUserFile", null, input("""{"files":["/x/a.png","/x/big.jpg","/x/notes.md"],"caption":"Look"}"""))
        var visible by mutableStateOf(true)
        val open = mutableStateMapOf<String, Boolean>()
        compose.setContent {
            English {
                CompositionLocalProvider(LocalToolContext provides ctx) {
                    if (visible) MessageRow(items(tool).single() as ConversationItems.Message, open, null)
                }
            }
        }
        compose.waitUntil(5_000) { thumbs["t#0"] is Thumb.Picture && thumbs["t#1"] is Thumb.Large }
        assertTrue(shown("A sent 3 files"))
        assertTrue(shown("Look"))
        assertTrue(shown("6.1 MB"))
        assertTrue(shown("notes.md"))
        assertEquals(listOf(0, 1), reads.sorted())
        visible = false
        compose.waitForIdle()
        visible = true
        compose.waitForIdle()
        assertEquals("read once per screen", 2, reads.size)
    }
}
