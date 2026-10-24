package com.spekterm.android.desktop

import com.spekterm.android.desktop.ToolView.Label
import com.spekterm.android.desktop.ToolView.Note
import com.spekterm.android.desktop.ToolView.Part
import com.spekterm.android.desktop.ToolView.Recipient
import com.spekterm.android.desktop.ToolView.Role
import com.spekterm.android.net.Entry
import com.spekterm.android.net.ResultImage
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import com.spekterm.android.net.ToolInput
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ToolView.of` for every tool of design D2, shaped as the transcripts record them (`tool-call-display`). */
class ToolViewTest {
    private fun call(name: String, input: String?, arg: String? = "arg", cut: Map<String, Long> = emptyMap()) =
        Entry.Tool("u", 0, "t", name, arg, input?.let { ToolInput.of(Json.parseToJsonElement(it)) } ?: ToolInput.EMPTY, cut)

    private fun ok(text: String) = Entry.Result("r", 0, "t", text, false)

    private fun failed(text: String) = Entry.Result("r", 0, "t", text, true)

    private fun view(name: String, input: String?, result: Entry.Result? = null, list: SessionList? = null, arg: String? = "arg", cut: Map<String, Long> = emptyMap()) =
        ToolView.of(call(name, input, arg, cut), result, list)

    private fun blocks(v: ToolView) = v.parts.filterIsInstance<Part.Block>()

    private val sessions = SessionList(true, emptyList(), listOf(Session("s", target = "claude", label = "自動更新功能", peerName = "spekterm-e8d8")))

    @Test
    fun bash() {
        val v = view("Bash", """{"command":"ls -l","description":"List files","run_in_background":"true"}""", ok("a\nb"))
        assertEquals("List files", v.line)
        assertEquals(listOf(Part.Block(Label.COMMAND, "ls -l", Role.INPUT), Part.Notice(Note.BACKGROUND), Part.Block(Label.OUTPUT, "a\nb", Role.OUTPUT)), v.parts)
        assertEquals("ls -l", view("Bash", """{"command":"ls -l"}""").line)
        assertEquals(Part.Block(Label.ERROR, "boom", Role.ERROR), blocks(view("Bash", """{"command":"x"}""", failed("boom"))).last())
    }

    @Test
    fun sendMessageToAListedSessionShowsItsLabel() {
        val v = view("SendMessage", """{"to":"spekterm-e8d8","recipient_kind":"name","summary":"archive","message":"Tell e8d8\n**done**"}""", ok("""{"success":true,"message":"x","msg_id":"m"}"""), sessions)
        assertEquals(Recipient.Named("自動更新功能"), v.recipient)
        assertEquals("archive", v.line)
        assertEquals(listOf(Part.Block(Label.MESSAGE, "Tell e8d8\n**done**", Role.INPUT), Part.Notice(Note.SENT)), v.parts)
    }

    @Test
    fun sendMessageRecipients() {
        fun r(input: String) = view("SendMessage", input, list = sessions).recipient
        assertEquals(Recipient.Named("api-server-8393"), r("""{"to":"api-server-8393","message":"m"}"""))
        assertEquals(Recipient.AnotherSession, r("""{"recipient_kind":"session","message":"m"}"""))
        assertEquals(Recipient.Subagent, r("""{"recipient_kind":"agent","message":"m"}"""))
        assertNull(r("""{"message":"m"}"""))
        assertEquals(Recipient.AnotherSession, r("""{"to":"uds:/run/user/1001/cc-socks/x.sock","message":"m"}"""))
        assertEquals(Recipient.Subagent, r("""{"to":"a0123456789abcdef","message":"m"}"""))
        val uds = view("SendMessage", """{"to":"uds:/run/user/1001/cc-socks/x.sock","message":"m"}""")
        assertFalse(uds.toString().contains("uds:"))
    }

    @Test
    fun sendMessageLinesAndResults() {
        assertEquals("first", view("SendMessage", """{"to":"x","message":"\n first \nsecond"}""").line)
        val idle = view("SendMessage", """{"to":"x","notify_when_idle":true}""")
        assertEquals(listOf(Part.Notice(Note.IDLE_ONLY)), idle.parts)
        assertEquals(Part.Notice(Note.NOT_SENT, "no such session"), view("SendMessage", """{"to":"x","message":"m"}""", ok("""{"success":false,"message":"no such session"}""")).parts.last())
        assertEquals(Part.Block(Label.OUTPUT, "plain", Role.OUTPUT), view("SendMessage", """{"to":"x","message":"m"}""", ok("plain")).parts.last())
        assertEquals(Part.Block(Label.ERROR, "denied", Role.ERROR), view("SendMessage", """{"to":"x","message":"m"}""", failed("denied")).parts.last())
    }

    @Test
    fun editShowsTheFileAndItsDiffAndTheResultOnlyWhenFailed() {
        val v = view("Edit", """{"file_path":"/h/memory/mac-update-check-pending.md","old_string":"a","new_string":"b\nc","replace_all":true}""", ok("The file has been updated successfully."))
        assertEquals("mac-update-check-pending.md", v.line)
        assertEquals(Part.Path("/h/memory/mac-update-check-pending.md"), v.parts[0])
        assertEquals(Part.Notice(Note.REPLACE_ALL), v.parts[1])
        assertEquals(listOf(EditDiff.Line.Removed("a"), EditDiff.Line.Added("b"), EditDiff.Line.Added("c")), (v.parts[2] as Part.Diff).diff.lines)
        assertEquals(3, v.parts.size)
        assertTrue(view("Edit", """{"file_path":"/x","old_string":"a","new_string":"b"}""", failed("no match")).parts.last() == Part.Block(Label.ERROR, "no match", Role.ERROR))
        assertTrue((view("Edit", """{"file_path":"/x","old_string":"a","new_string":"b"}""", cut = mapOf("new_string" to 9000)).parts.last() as Part.Diff).cut)
    }

    @Test
    fun writeAndRead() {
        val w = view("Write", """{"file_path":"/x/a.kt","content":"fun a()"}""", ok("File created"), cut = mapOf("content" to 20000))
        assertEquals("a.kt", w.line)
        assertEquals(listOf(Part.Path("/x/a.kt"), Part.Block(Label.CONTENT, "fun a()", Role.INPUT, 20000)), w.parts)
        assertEquals("a.kt:10–59", view("Read", """{"file_path":"/x/a.kt","offset":10,"limit":50}""").line)
        assertEquals("a.kt:10–", view("Read", """{"file_path":"/x/a.kt","offset":"10"}""").line)
        assertEquals("a.kt", view("Read", """{"file_path":"/x/a.kt"}""", ok("1 x")).line)
        assertEquals("TODO", view("Grep", """{"pattern":"TODO","path":"/x"}""").line)
    }

    @Test
    fun agentHidesTheLaunchNoticeButNotOtherText() {
        val launched = view("Agent", """{"description":"Review","subagent_type":"openspec-change-reviewer","prompt":"Review it","run_in_background":true}""", ok("Async agent launched successfully. agentId: a1"))
        assertEquals("openspec-change-reviewer · Review", launched.line)
        assertEquals(listOf(Part.Block(Label.PROMPT, "Review it", Role.INPUT), Part.Notice(Note.BACKGROUND)), launched.parts)
        assertEquals(Part.Block(Label.OUTPUT, "# Report", Role.OUTPUT), view("Agent", """{"description":"d","prompt":"p"}""", ok("# Report")).parts.last())
        assertEquals(Part.Block(Label.ERROR, "x", Role.ERROR), view("Agent", """{"description":"d","prompt":"p"}""", failed("x")).parts.last())
        assertTrue(blocks(view("Monitor", """{"description":"Wait","command":"until x"}""", ok("Monitor started (task b1)"))).none { it.role == Role.OUTPUT })
        assertEquals(Part.Block(Label.ERROR, "x", Role.ERROR), view("Monitor", """{"description":"Wait","command":"c"}""", failed("x")).parts.last())
    }

    @Test
    fun skillAndOthers() {
        assertEquals("/openspec-apply-change inbox", view("Skill", """{"skill":"openspec-apply-change","args":"inbox"}""").line)
        assertEquals("/simplify", view("Skill", """{"skill":"simplify"}""").line)
        val search = view("ToolSearch", """{"query":"select:SendMessage"}""", ok(""))
        assertEquals("select:SendMessage", search.line)
        assertTrue(search.parts.isEmpty())
        assertEquals("https://x", view("WebFetch", """{"url":"https://x","prompt":"p"}""").line)
        assertEquals("b1", view("TaskStop", """{"task_id":"b1"}""").line)
        val wake = view("ScheduleWakeup", """{"delaySeconds":420,"reason":"wait for replay"}""", ok("Next wakeup"))
        assertEquals("wait for replay", wake.line)
        assertEquals(listOf(Part.Notice(Note.DELAY_MINUTES, "7")), wake.parts)
        assertEquals(Part.Block(Label.ERROR, "x", Role.ERROR), view("ScheduleWakeup", """{"delaySeconds":60}""", failed("x")).parts.last())
        assertEquals("0 9 * * *", view("CronCreate", """{"cron":"0 9 * * *","prompt":"p"}""").line)
        assertEquals("quickstart", view("Artifact", """{"action":"quickstart"}""").line)
        assertEquals("Step one", view("ExitPlanMode", """{"plan":"\nStep one\nStep two"}""").line)
    }

    @Test
    fun sendUserFileAndQuestions() {
        val f = view("SendUserFile", """{"files":["/x/a.png","/x/b.md"]}""", ok("2 files delivered"), cut = mapOf("files" to 25))
        assertEquals("a.png, b.md", f.line)
        assertEquals(listOf(Part.Files(listOf("/x/a.png", "/x/b.md"), 25)), f.parts)
        assertEquals("New login", view("SendUserFile", """{"files":["/x/a.png"],"caption":"New login"}""").line)
        val q = view("AskUserQuestion", """{"questions":[{"question":"Which?","options":[{"label":"A"},{"label":"B"}]},{"question":"Also?","options":[]}]}""", ok("answered"))
        assertEquals("Which?", q.line)
        assertEquals(Part.Questions(listOf(ToolView.Question("Which?", listOf("A", "B")), ToolView.Question("Also?", emptyList()))), q.parts[0])
        assertEquals(Part.Block(Label.ANSWER, "answered", Role.OUTPUT), q.parts[1])
    }

    @Test
    fun mcpAndUnknownToolsShowTheirArgument() {
        val m = view("mcp__newrelic__execute_nrql_query", null, ok("rows"), arg = "SELECT 1")
        assertEquals("newrelic · execute_nrql_query", m.title)
        assertEquals("SELECT 1", m.line)
        assertEquals(listOf(Part.Block(Label.ARGUMENT, "SELECT 1", Role.INPUT), Part.Block(Label.OUTPUT, "rows", Role.OUTPUT)), m.parts)
        assertEquals("claude-in-chrome · computer", ToolView.title("mcp__claude-in-chrome__computer"))
        assertEquals("mcp__x", ToolView.title("mcp__x"))
    }

    @Test
    fun everyKnownToolWithoutInputLooksAsBefore() {
        for (name in listOf("Bash", "SendMessage", "Edit", "Write", "Read", "Grep", "Agent", "Skill", "SendUserFile", "AskUserQuestion", "ToolSearch", "WebFetch", "Monitor", "TaskStop", "ScheduleWakeup", "CronCreate", "Artifact", "ExitPlanMode")) {
            val v = view(name, null, ok("out"), arg = "the arg")
            assertEquals(name, ToolView(name, null, "the arg", listOf(Part.Block(Label.ARGUMENT, "the arg", Role.INPUT), Part.Block(Label.OUTPUT, "out", Role.OUTPUT))), v)
        }
    }

    @Test
    fun aMissingFieldFallsBackToTheArgument() {
        assertEquals("ls", view("Bash", """{"run_in_background":true}""", arg = "ls").line)
        assertEquals("x.md", view("Edit", """{"old_string":"a","new_string":"b"}""", arg = "x.md").line)
    }

    private val png = ResultImage("image/png", 400000)

    private fun withPictures(text: String, error: Boolean = false, vararg images: ResultImage) = Entry.Result("r", 0, "t", text, error, images.toList())

    private fun readable(name: String, input: String?, result: Entry.Result?, arg: String? = "arg", imagesReadable: Boolean = true) =
        ToolView.of(call(name, input, arg), result, null, imagesReadable)

    @Test
    fun aReadPictureShowsThePictureAndNoOutput() {
        val v = readable("Read", """{"file_path":"/x/sp.png"}""", withPictures("", false, png))
        assertEquals(listOf(png), v.pictures)
        assertEquals(listOf(Part.Path("/x/sp.png")), v.parts)
    }

    @Test
    fun aFailedReadShowsNoPicture() {
        val v = readable("Read", """{"file_path":"/x/sp.png"}""", withPictures("boom", true, png))
        assertTrue(v.pictures.isEmpty())
        assertEquals(listOf(Part.Block(Label.ERROR, "boom", Role.ERROR)), blocks(v))
    }

    @Test
    fun anMcpScreenshotShowsItsTextAndItsPicture() {
        val v = readable("mcp__claude-in-chrome__computer", null, withPictures("Took a screenshot", false, png), arg = "screenshot")
        assertEquals(listOf(png), v.pictures)
        assertEquals(Part.Block(Label.OUTPUT, "Took a screenshot", Role.OUTPUT), blocks(v).last())
    }

    @Test
    fun anOlderDesktopShowsNoPictureAndSaysWhy() {
        val png = readable("Read", """{"file_path":"/x/sp.png"}""", withPictures("", false, png), imagesReadable = false)
        assertTrue(png.pictures.isEmpty())
        assertEquals(listOf(Part.Path("/x/sp.png"), Part.Notice(Note.PICTURES_NEED_DESKTOP)), png.parts)
        val md = readable("Read", """{"file_path":"/x/notes.md"}""", ok(""), imagesReadable = false)
        assertTrue(md.parts.none { it is Part.Notice })
        val newer = readable("Read", """{"file_path":"/x/sp.png"}""", ok(""))
        assertTrue(newer.parts.none { it is Part.Notice })
    }

    @Test
    fun aReadsTextIsMarkedNumbered() {
        val v = readable("Read", """{"file_path":"/x/a.kt"}""", ok("1\tfun a()"))
        assertEquals(Part.Block(Label.OUTPUT, "1\tfun a()", Role.OUTPUT, numbered = true), blocks(v).single())
        assertFalse(blocks(readable("Grep", """{"pattern":"x"}""", ok("a"))).single().numbered)
    }
}
