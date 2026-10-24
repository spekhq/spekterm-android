package com.spekterm.android.desktop

import com.spekterm.android.desktop.ConversationItems.Message
import com.spekterm.android.desktop.Messages.Kind
import com.spekterm.android.desktop.Messages.Status
import com.spekterm.android.desktop.Messages.Who
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Origin
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import com.spekterm.android.net.ToolInput
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messages exchanged between parties, as chat (`tool-call-display` D11). */
class MessagesTest {
    private val list = SessionList(true, emptyList(), listOf(Session("s", target = "claude", label = "測試收件", peerName = "go-f975")))

    private fun input(json: String) = ToolInput.of(Json.parseToJsonElement(json))

    private fun result(text: String, error: Boolean = false) = Entry.Result("r", 0, "t", text, error)

    @Test
    fun names() {
        assertEquals("測試收件", Messages.nameOf(Who(Kind.SESSION, "go-f975"), list, "A"))
        assertEquals("api-server-8393", Messages.nameOf(Who(Kind.SESSION, "api-server-8393"), list, "A"))
        assertNull(Messages.nameOf(Who(Kind.SUBAGENT, null), list, "A"))
        assertEquals("openspec-change-reviewer", Messages.nameOf(Who(Kind.SUBAGENT, "openspec-change-reviewer"), list, "A"))
        assertEquals("A", Messages.nameOf(Who(Kind.SELF, null), list, "A"))
        assertNull(Messages.nameOf(Who(Kind.SELF, null), list, " "))
        assertEquals(Who(Kind.SUBAGENT, "x"), Messages.sender(Origin.Peer(Origin.Sender.SUBAGENT, "x")))
        assertEquals(Who(Kind.AGENT, null), Messages.sender(Origin.Peer(Origin.Sender.OTHER, null)))
    }

    @Test
    fun recipients() {
        assertEquals(Who(Kind.SESSION, "go-f975"), Messages.recipient(input("""{"to":"go-f975","message":"m"}""")))
        assertEquals(Who(Kind.SESSION, null), Messages.recipient(input("""{"to":"uds:/run/user/1001/x.sock"}""")))
        assertEquals(Who(Kind.SUBAGENT, null), Messages.recipient(input("""{"to":"a0123456789abcdef"}""")))
        assertEquals(Who(Kind.SESSION, null), Messages.recipient(input("""{"recipient_kind":"session"}""")))
        assertEquals(Who(Kind.SUBAGENT, null), Messages.recipient(input("""{"recipient_kind":"agent"}""")))
        assertNull(Messages.recipient(input("""{"message":"m"}""")))
    }

    @Test
    fun statuses() {
        assertEquals(Status.Sending, Messages.status(null))
        assertEquals(Status.Sent, Messages.status(result("""{"success":true,"message":"x"}""")))
        assertEquals(Status.NotSent("no such session"), Messages.status(result("""{"success":false,"message":"no such session"}""")))
        assertEquals(Status.NotSent(null), Messages.status(result("""{"success":false}""")))
        assertEquals(Status.NotSent("denied"), Messages.status(result("denied", error = true)))
        assertEquals(Status.Sent, Messages.status(result("plain")))
    }

    @Test
    fun longBodiesAreCut() {
        assertNull(Messages.cut((1..12).joinToString("\n") { "l$it" }))
        assertEquals((1..10).joinToString("\n") { "l$it" }, Messages.cut((1..13).joinToString("\n") { "l$it" }))
        assertEquals((1..10).joinToString("\n") { "l$it" }, Messages.cut((1..13).joinToString("\r\n") { "l$it" }))
        assertNull(Messages.cut("x".repeat(1_200)))
        assertEquals("x".repeat(1_000), Messages.cut("x".repeat(1_201)))
    }

    @Test
    fun noticeSummaries() {
        assertEquals("Background command \"Run tests\" completed (exit code 0)", Messages.summary("<task-notification>\n<task-id>b1</task-id>\n<summary>Background command \"Run tests\" completed (exit code 0)</summary>\n</task-notification>"))
        assertNull(Messages.summary("<task-notification><status>completed</status></task-notification>"))
        assertNull(Messages.summary("<summary>  </summary>"))
        assertNull(Messages.summary("<summary>unclosed"))
    }

    @Test
    fun avatarColoursAreStable() {
        val i = Messages.colorIndex("測試收件")
        assertEquals(i, Messages.colorIndex("測試收件"))
        assertTrue(i in 0 until Messages.COLORS)
        assertTrue(Messages.colorIndex("") in 0 until Messages.COLORS)
    }

    @Test
    fun aFailedFileMessageSaysSo() {
        val tool = Entry.Tool("u", 0, "t", "SendUserFile", null, input("""{"files":["/x/a.png"]}"""))
        val m = Message("k", Who(Kind.SELF, null), null, "", false, 0, tool, result("1 file could NOT be delivered", error = true), listOf("/x/a.png"))
        assertEquals(Status.NotSent("1 file could NOT be delivered"), m.status)
        assertNull(m.copy(result = result("1 file delivered")).status)
    }
}
