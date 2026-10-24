package com.spekterm.android.net

import com.spekterm.android.desktop.SessionNames
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tool entry's `input`, a user entry's `origin`, `Session.peerName` and `files.read` answers (`tool-call-display` D1). */
class ToolDetailTest {
    private fun entry(json: String): Entry? = Entry.decode(Json.parseToJsonElement(json))

    private fun tool(extra: String): Entry.Tool = entry("""{"kind":"tool","uuid":"u","at":1,"id":"t","name":"Bash","arg":"ls"$extra}""") as Entry.Tool

    private fun user(extra: String): Entry.User = entry("""{"kind":"user","uuid":"u","at":1,"text":"hi","meta":true$extra}""") as Entry.User

    @Test
    fun aToolWithoutUsableInputKeepsItsArg() {
        for (extra in listOf("", ""","input":"x"""", ""","input":[]""", ""","input":null""")) {
            val t = tool(extra)
            assertTrue(extra, t.input.isEmpty)
            assertEquals("ls", t.arg)
            assertEquals(emptyMap<String, Long>(), t.cut)
        }
    }

    @Test
    fun fieldsReadByTypeAndAMistypedOneIsNull() {
        val t = tool(""","input":{"command":"ls -l","description":"","run_in_background":"true","timeout":"30000","limit":12,"flag":true,"files":["a.png",3,"b.md"]}""")
        assertEquals("ls -l", t.input.string("command"))
        assertEquals("", t.input.string("description"))
        assertNull(t.input.text("description"))
        assertEquals(true, t.input.bool("run_in_background"))
        assertEquals(true, t.input.bool("flag"))
        assertEquals(30000L, t.input.int("timeout"))
        assertEquals(12L, t.input.int("limit"))
        assertNull(t.input.string("limit"))
        assertNull(t.input.int("command"))
        assertNull(t.input.bool("command"))
        assertNull(t.input.string("missing"))
        assertEquals(listOf("a.png", "b.md"), t.input.strings("files"))
    }

    @Test
    fun nestedQuestionsReadThroughObjects() {
        val t = tool(""","input":{"questions":[{"question":"Which?","options":[{"label":"A"},"x",{"label":"B","description":"b"}]},"skip"]}""")
        val qs = t.input.objects("questions")!!
        assertEquals(1, qs.size)
        assertEquals("Which?", qs[0].string("question"))
        assertEquals(listOf("A", "B"), qs[0].objects("options")!!.map { it.string("label") })
    }

    @Test
    fun cutFieldsKeepNumbersOnly() {
        val t = tool(""","inputTruncated":{"content":12000,"files":30,"bad":"9"}""")
        assertEquals(mapOf("content" to 12000L, "files" to 30L), t.cut)
    }

    @Test
    fun originOfEachKind() {
        assertEquals(Origin.Peer(Origin.Sender.SESSION, "spekterm-e8d8"), user(""","origin":{"kind":"peer","sender":"session","name":"spekterm-e8d8"}""").origin)
        assertEquals(Origin.Peer(Origin.Sender.SUBAGENT, null), user(""","origin":{"kind":"peer","sender":"subagent"}""").origin)
        assertEquals(Origin.Peer(Origin.Sender.OTHER, "x"), user(""","origin":{"kind":"peer","sender":"later","name":"x"}""").origin)
        assertEquals(Origin.Peer(Origin.Sender.OTHER, null), user(""","origin":{"kind":"peer","name":"  "}""").origin)
        assertEquals(Origin.TaskNotice, user(""","origin":{"kind":"task-notification"}""").origin)
        assertNull(user(""","origin":{"kind":"human"}""").origin)
        assertNull(user(""","origin":"peer"""").origin)
        assertNull(user("").origin)
    }

    @Test
    fun peerNameIsReadLeniently() {
        fun list(peer: String) = SessionList.decode(
            Json.parseToJsonElement("""{"window":true,"folders":[],"sessions":[{"id":"s","target":"claude","label":"自動更新功能"$peer}]}"""),
        )!!
        assertEquals("spekterm-e8d8", list(""","peerName":"spekterm-e8d8"""").sessions.single().peerName)
        for (bad in listOf("", ""","peerName":""""", ""","peerName":3""", ""","peerName":null""")) {
            val l = list(bad)
            assertEquals(bad, 1, l.sessions.size)
            assertNull(bad, l.sessions.single().peerName)
        }
        val named = list(""","peerName":"spekterm-e8d8"""")
        assertEquals("自動更新功能", SessionNames.label("spekterm-e8d8", named))
        assertNull(SessionNames.label("api-server-8393", named))
        assertNull(SessionNames.label("spekterm-e8d8", SessionList(true, emptyList(), emptyList())))
        assertNull(SessionNames.label(null, named))
    }

    @Test
    fun fileChunks() {
        val full = FileChunk.decode(Json.parseToJsonElement("""{"name":"a.md","size":3,"mediaType":"Text/Markdown","version":"v1","offset":0,"data":"YWJj","complete":true}"""))!!
        assertEquals("a.md", full.name)
        assertEquals("text/markdown", full.mediaType)
        assertArrayEquals("abc".toByteArray(), full.bytes)
        assertTrue(full.complete)
        val bare = FileChunk.decode(Json.parseToJsonElement("""{"size":10,"version":"v","data":"","complete":false}"""))!!
        assertEquals("application/octet-stream", bare.mediaType)
        assertEquals(0L, bare.offset)
        assertFalse(bare.complete)
        for (bad in listOf(
            """{"version":"v","data":"YWJj","complete":true}""",
            """{"size":3,"data":"YWJj","complete":true}""",
            """{"size":3,"version":"v","complete":true}""",
            """{"size":3,"version":"v","data":"!!not base64!!","complete":true}""",
            """{"size":-1,"version":"v","data":"YWJj","complete":true}""",
            """{"size":3,"version":"v","data":"YWJj"}""",
            """"x"""",
        )) assertNull(bad, FileChunk.decode(Json.parseToJsonElement(bad)))
    }
}
