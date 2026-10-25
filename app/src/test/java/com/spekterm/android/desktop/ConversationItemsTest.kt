package com.spekterm.android.desktop

import com.spekterm.android.desktop.ConversationItems.Call
import com.spekterm.android.desktop.ConversationItems.LoneResult
import com.spekterm.android.desktop.ConversationItems.Plain
import com.spekterm.android.desktop.ConversationItems.Run
import com.spekterm.android.desktop.ConversationItems.Shown
import com.spekterm.android.desktop.ConversationItems.Thinking
import com.spekterm.android.desktop.ConversationItems.Message
import com.spekterm.android.desktop.ConversationItems.Notice
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Origin
import com.spekterm.android.net.ToolInput
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationItemsTest {
    // Each entry's uuid is its name, so its key is "<name>#0".
    private fun call(name: String, id: String = name) = Entry.Tool(name, 0, id, "Bash", "ls $name")
    private fun result(name: String, id: String, error: Boolean = false) = Entry.Result(name, 0, id, "out", error)
    private fun thinking(name: String) = Entry.Thinking(name, 0, "hmm")
    private fun text(name: String) = Entry.Text(name, 0, "said")
    private fun user(name: String) = Entry.User(name, 0, "asked", meta = false)
    private fun notice(name: String) = Entry.User(name, 0, "<task-notification>…", meta = true, origin = Origin.TaskNotice)
    private fun send(name: String, input: String? = """{"to":"x","message":"hi"}""") =
        Entry.Tool(name, 0, name, "SendMessage", null, input?.let { ToolInput.of(Json.parseToJsonElement(it)) } ?: ToolInput.EMPTY)
    private fun files(name: String) = Entry.Tool(name, 0, name, "SendUserFile", null, ToolInput.of(Json.parseToJsonElement("""{"files":["/x/a.png"],"caption":"look"}""")))
    private fun peer(name: String) = Entry.User(name, 0, "hi", meta = true, origin = Origin.Peer(Origin.Sender.SESSION, "x"))

    private fun group(vararg entries: Entry, anchors: Set<String> = emptySet()): List<Shown> {
        val list = entries.toList()
        return ConversationItems.group(list, ConversationReducer.keys(list), anchors.map { "$it#0" }.toSet())
    }

    /** A compact picture of the items: C(c1) a call, R(c1,c2) a run's calls, P(name) plain, L(name) a lone result; "<" carries anchors. */
    private fun shape(items: List<Shown>): String = items.joinToString(" ") { item ->
        val body = when (item) {
            is Call -> "C(${item.tool.uuid})"
            is Run -> "R(" + item.parts.joinToString(",") {
                when (it) {
                    is Call -> it.tool.uuid
                    is Thinking -> "t:" + it.entry.uuid
                    is Notice -> "n:" + it.entry.uuid
                }
            } + ")"
            is Plain -> "P(${item.entry.uuid})"
            is LoneResult -> "L(${item.result.uuid})"
            is Message -> "M(${item.key.removeSuffix("#0")})"
            is ConversationItems.CommandOutput -> "O(${item.entry.uuid})"
        }
        body + item.anchorsAfter.joinToString("") { "<" + it.removeSuffix("#0") }
    }

    @Test
    fun aCallTakesItsResult() {
        val items = group(call("c1"), result("r1", "c1"))
        assertEquals("C(c1)", shape(items))
        assertEquals("r1", (items[0] as Call).result!!.uuid)
    }

    @Test
    fun parallelCallsArePairedByIdAndFormARun() {
        val items = group(call("a"), call("b"), result("rb", "b"), result("ra", "a", error = true))
        val run = items.single() as Run
        assertEquals("ra", run.calls[0].result!!.uuid)
        assertEquals("rb", run.calls[1].result!!.uuid)
        assertEquals(1, run.failed)
    }

    @Test
    fun unpairedResultsStandAlone() {
        // No call at all, an empty id, and a result before its call.
        assertEquals("L(r0) P(x) L(r1) P(y) L(r2) C(c2)", shape(group(result("r0", "gone"), text("x"), result("r1", ""), text("y"), result("r2", "c2"), call("c2"))))
        val items = group(result("r2", "c2"), call("c2"))
        assertNull((items[1] as Call).result)
    }

    @Test
    fun sameIdIsPairedInCallOrder() {
        val run = group(call("c1", "dup"), call("c2", "dup"), result("r1", "dup"), result("r2", "dup")).single() as Run
        assertEquals(listOf("r1", "r2"), run.calls.map { it.result!!.uuid })
    }

    @Test
    fun sevenCallsWithThinkingBetweenAreOneRun() {
        val entries = (1..7).flatMap { n ->
            listOfNotNull(call("c$n"), result("r$n", "c$n", error = n == 4), thinking("t$n").takeIf { n < 7 })
        }
        val run = group(*entries.toTypedArray()).single() as Run
        assertEquals(7, run.calls.size)
        assertEquals(6, run.parts.count { it is Thinking })
        assertEquals(1, run.failed)
        assertEquals("c7", run.latest.tool.uuid)
        assertEquals("c1#0", run.key)
    }

    @Test
    fun aSingleCallIsNotARun() {
        assertEquals("P(x) C(c1) P(y)", shape(group(text("x"), call("c1"), result("r1", "c1"), text("y"))))
    }

    @Test
    fun otherEntriesEndARunAndKeepTheirOrder() {
        val injected = Entry.User("m", 0, "injected", meta = true)
        assertEquals(
            "R(c1,c2) P(u) R(c3,c4) P(m) C(c5) P(x)",
            shape(group(call("c1"), call("c2"), user("u"), call("c3"), call("c4"), injected, call("c5"), text("x"))),
        )
    }

    @Test
    fun aLoneResultEndsARunAndTrailingThinkingFollowsIt() {
        assertEquals("R(c1,c2) P(t) L(rx)", shape(group(call("c1"), call("c2"), thinking("t"), result("rx", "gone"))))
        assertEquals("R(c1,c2) P(t)", shape(group(call("c1"), call("c2"), thinking("t"))))
    }

    @Test
    fun aRunKeepsItsKeyAsItGrows() {
        val before = group(call("c1"), call("c2")).single()
        val after = group(call("c1"), call("c2"), call("c3")).single()
        assertEquals(before.key, after.key)
    }

    // D7's worked cases.

    @Test
    fun anchorOnThinkingAfterACall() {
        assertEquals("C(c1) P(t)<t C(c2)", shape(group(call("c1"), result("r1", "c1"), thinking("t"), call("c2"), anchors = setOf("t"))))
    }

    @Test
    fun anchorOnAConsumedResultAfterHeldThinking() {
        assertEquals("C(c1) P(t)<r1", shape(group(call("c1"), thinking("t"), result("r1", "c1"), anchors = setOf("r1"))))
    }

    @Test
    fun anchorOnAnEarlierCallsResultAfterARun() {
        assertEquals(
            "R(c1,c2) P(t)<r0 C(c3)",
            shape(group(call("c0"), text("x"), call("c1"), result("r1", "c1"), call("c2"), result("r2", "c2"), thinking("t"), result("r0", "c0"), call("c3"), anchors = setOf("r0")).drop(2)),
        )
    }

    @Test
    fun anchorOnACall() {
        assertEquals("C(c1)<c1 C(c2)", shape(group(call("c1"), result("r1", "c1"), call("c2"), anchors = setOf("c1"))))
    }

    @Test
    fun anchorOnARunsCallSplitsIt() {
        assertEquals("R(c1,c2)<c2 C(c3)", shape(group(call("c1"), call("c2"), call("c3"), anchors = setOf("c2"))))
    }

    @Test
    fun anchorOnAParallelCallsResultGoesAfterWhatCameBefore() {
        assertEquals(
            "C(a) P(x) R(b,c)<ra",
            shape(group(call("a"), text("x"), call("b"), call("c"), result("ra", "a"), anchors = setOf("ra"))),
        )
    }

    @Test
    fun anchorOnText() {
        assertEquals("P(x)<x C(c1)", shape(group(text("x"), call("c1"), anchors = setOf("x"))))
    }

    @Test
    fun anchorOnAConsumedResultWithNothingForming() {
        // c1's result arrives after the text has already closed c1.
        assertEquals("C(c1) P(x)<r1", shape(group(call("c1"), text("x"), result("r1", "c1"), anchors = setOf("r1"))))
    }

    @Test
    fun everyEntryIsShownOnce() {
        val entries = listOf(user("u"), thinking("t0"), call("c1"), thinking("t1"), call("c2"), result("r1", "c1"), result("r2", "c2"), text("x"))
        val items = ConversationItems.group(entries, ConversationReducer.keys(entries), emptySet())
        val shown = items.flatMap { item ->
            when (item) {
                is Call -> listOfNotNull(item.tool, item.result)
                is Run -> item.parts.flatMap { if (it is Call) listOfNotNull(it.tool, it.result) else listOf((it as Thinking).entry) }
                is Plain -> listOf(item.entry)
                is LoneResult -> listOf(item.result)
                is Message -> listOfNotNull(item.call, item.result)
                is ConversationItems.CommandOutput -> listOf(item.entry)
            }
        }
        assertEquals(entries.toSet(), shown.toSet())
        assertTrue(shown.size == entries.size)
    }

    @Test
    fun aNoticeBetweenCallsJoinsTheRun() {
        val items = group(call("c1"), notice("n"), call("c2"))
        assertEquals("R(c1,n:n,c2)", shape(items))
        assertEquals(2, (items.single() as Run).calls.size)
    }

    @Test
    fun aNoticeBeforeAPeerMessageStaysOutsideTheRun() {
        assertEquals("R(c1,c2) P(n) M(p) R(c3,c4)", shape(group(call("c1"), call("c2"), notice("n"), peer("p"), call("c3"), call("c4"))))
    }

    @Test
    fun aNoticeAfterOneCallMakesNoRunOfOne() {
        assertEquals("C(c1) P(n) P(t)", shape(group(call("c1"), notice("n"), text("t"))))
        assertEquals("P(n) C(c1)", shape(group(notice("n"), call("c1"))))
    }

    @Test
    fun aPeerMessageEndsARunAsAMessage() {
        assertEquals("C(c1) M(p) C(c2)", shape(group(call("c1"), peer("p"), call("c2"))))
        val m = group(peer("p")).single() as Message
        assertEquals(Messages.Who(Messages.Kind.SESSION, "x"), m.from)
        assertEquals("hi", m.body)
        assertTrue(m.markdown)
    }

    @Test
    fun theAgentsMessagesAndFilesAreMessages() {
        assertEquals("C(c1) M(s) C(c2)", shape(group(call("c1"), send("s"), result("rs", "s"), call("c2"))))
        val m = group(send("s"), Entry.Result("rs", 0, "s", """{"success":true}""", false)).single() as Message
        assertEquals(Messages.Who(Messages.Kind.SELF, null), m.from)
        assertEquals(Messages.Who(Messages.Kind.SESSION, "x"), m.to)
        assertEquals(Messages.Status.Sent, m.status)
        val f = group(files("f")).single() as Message
        assertEquals(listOf("/x/a.png"), f.files)
        assertEquals("look", f.body)
        assertEquals("R(c1,c2) M(f)", shape(group(call("c1"), call("c2"), files("f"))))
    }

    @Test
    fun withoutInputTheyStayCalls() {
        assertEquals("R(c1,s,c2)", shape(group(call("c1"), send("s", null), call("c2"))))
    }

    @Test
    fun aRunWithANoticeKeepsItsKeyAsItGrows() {
        val before = group(call("c1"), notice("n"), call("c2")).single() as Run
        val after = group(call("c1"), notice("n"), call("c2"), call("c3")).single() as Run
        assertEquals(before.key, after.key)
        assertEquals(3, after.calls.size)
        assertEquals(0, after.failed)
    }

    private fun note(name: String, text: String = "[Image: original 1080x2400, displayed at 900x2000. Multiply coordinates by 1.20 to map to original image.]", meta: Boolean = true) =
        Entry.User(name, 0, text, meta = meta)

    @Test
    fun theClisPictureNoteIsSkippedInsideARun() {
        assertEquals("R(c1,c2,c3)", shape(group(call("c1"), result("r1", "c1"), note("n"), call("c2"), call("c3"))))
        assertEquals("C(c1) P(n) R(c2,c3)", shape(group(call("c1"), result("r1", "c1"), note("n", meta = false), call("c2"), call("c3"))))
        assertEquals("C(c1) P(n) C(c2)", shape(group(call("c1"), note("n", "[Image: something else]"), call("c2"))))
        for (scale in listOf("1.2", "2")) {
            val text = "[Image: original 1080x2400, displayed at 540x1200. Multiply coordinates by $scale to map to original image.]"
            assertEquals(scale, "R(c1,c2)", shape(group(call("c1"), note("n", text), call("c2"))))
        }
    }

    @Test
    fun aReplyAnchoredAtThePictureNoteClosesTheRun() {
        val items = group(call("c0"), call("c1"), result("r1", "c1"), note("n"), call("c2"), call("c3"), anchors = setOf("n"))
        assertEquals("R(c0,c1)<n R(c2,c3)", shape(items))
    }

    private fun stdout(name: String, inner: String, tag: String = "local-command-stdout", meta: Boolean = false) =
        Entry.User(name, 0, "<$tag>$inner</$tag>", meta)
    private fun caveat(name: String) =
        Entry.User(name, 0, "<local-command-caveat>Caveat: The messages below were generated by the user while running local commands.</local-command-caveat>", meta = true)

    @Test
    fun aCommandsOutputIsShownAsIts() {
        val items = group(stdout("o1", "Set effort level to low (saved as your default for new sessions): Quick"), stdout("o2", "boom\n", tag = "local-command-stderr"))
        assertEquals("Set effort level to low (saved as your default for new sessions): Quick", (items[0] as ConversationItems.CommandOutput).text)
        assertEquals("boom", (items[1] as ConversationItems.CommandOutput).text)
    }

    @Test
    fun terminalEscapesAreRemoved() {
        val items = group(stdout("o1", "\u001b[2mCompacted (ctrl+o to see full summary)\u001b[22m"))
        assertEquals("Compacted (ctrl+o to see full summary)", (items[0] as ConversationItems.CommandOutput).text)
    }

    @Test
    fun theCaveatIsNotShown() {
        assertEquals("P(u1)", shape(group(caveat("cv"), user("u1"))))
    }

    @Test
    fun anythingElseWithTheMarkersStaysAsRecorded() {
        val nonMetaCaveat = Entry.User("a", 0, "<local-command-caveat>x</local-command-caveat>", meta = false)
        val among = Entry.User("b", 0, "see <local-command-stdout>x</local-command-stdout> above", meta = false)
        val two = Entry.User("c", 0, "<local-command-stdout>x</local-command-stdout><local-command-stdout>y</local-command-stdout>", meta = false)
        val items = group(nonMetaCaveat, among, two, stdout("d", "x", meta = true))
        assertEquals("P(a) P(b) P(c) P(d)", shape(items))
    }

    @Test
    fun aCaveatBetweenTwoCallsStillSplitsThem() {
        assertEquals("C(c1) C(c2)", shape(group(call("c1"), caveat("cv"), call("c2"))))
    }

    @Test
    fun aCommandsOutputBetweenTwoCallsSplitsThemAsTheUserEntryDid() {
        assertEquals("C(c1) O(o1) C(c2)", shape(group(call("c1"), stdout("o1", "x"), call("c2"))))
    }

    @Test
    fun aReplyAnchoredOnTheCaveatKeepsItsPlace() {
        assertEquals("P(u1)<cv P(u2)", shape(group(user("u1"), caveat("cv"), user("u2"), anchors = setOf("cv"))))
        assertEquals("P(u2)<cv", shape(group(caveat("cv"), user("u2"), anchors = setOf("cv"))))
    }

    private fun pic(name: String, id: String, error: Boolean = false) =
        Entry.Result(name, 0, id, "", error, listOf(com.spekterm.android.net.ResultImage("image/png", 10)))

    private fun sources(items: List<Shown>, imagesReadable: Boolean = true, canReadFiles: Boolean = true): String =
        ConversationItems.viewerSources(items, imagesReadable, canReadFiles).joinToString(" ") {
            when (it) {
                is ConversationItems.ViewerSource.Call -> "C(${it.tool.id})"
                is ConversationItems.ViewerSource.Lone -> "L(${it.result.id})"
                is ConversationItems.ViewerSource.Sent -> "S(${it.message.call?.id})"
            }
        }

    @Test
    fun fullScreenGoesThroughPicturesAndSentFilesInOrder() {
        // tool-result-content D8: a lone call, a sent message, a run (one call without pictures), a lone result.
        val items = group(
            call("c1"), pic("r1", "c1"), text("t1"),
            files("f1"), text("t2"),
            call("c2"), pic("r2", "c2"), call("c3"), result("r3", "c3"), text("t3"),
            pic("r4", "lone"),
        )
        assertEquals("C(c1) S(f1) C(c2) L(lone)", sources(items))
    }

    @Test
    fun fullScreenLeavesOutWhatCannotBeShown() {
        assertEquals("", sources(group(call("c1"), pic("r1", "c1", error = true))))
        val items = group(call("c1"), pic("r1", "c1"), text("t1"), files("f1"), send("m1"))
        assertEquals("S(f1)", sources(items, imagesReadable = false))
        assertEquals("C(c1)", sources(items, canReadFiles = false))
    }

    @Test
    fun aResultSeenTwiceIsGoneThroughOnce() {
        assertEquals("C(x)", sources(group(call("x"), pic("r1", "x"), pic("r2", "x"))))
    }
}
