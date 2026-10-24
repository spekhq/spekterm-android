package com.spekterm.android.desktop

import com.spekterm.android.desktop.ConversationItems.Call
import com.spekterm.android.desktop.ConversationItems.LoneResult
import com.spekterm.android.desktop.ConversationItems.Plain
import com.spekterm.android.desktop.ConversationItems.Run
import com.spekterm.android.desktop.ConversationItems.Shown
import com.spekterm.android.desktop.ConversationItems.Thinking
import com.spekterm.android.desktop.ConversationItems.Notice
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Origin
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
        assertEquals("R(c1,c2) P(n) P(p) R(c3,c4)", shape(group(call("c1"), call("c2"), notice("n"), peer("p"), call("c3"), call("c4"))))
    }

    @Test
    fun aNoticeAfterOneCallMakesNoRunOfOne() {
        assertEquals("C(c1) P(n) P(t)", shape(group(call("c1"), notice("n"), text("t"))))
        assertEquals("P(n) C(c1)", shape(group(notice("n"), call("c1"))))
    }

    @Test
    fun aPeerMessageEndsARun() {
        assertEquals("C(c1) P(p) C(c2)", shape(group(call("c1"), peer("p"), call("c2"))))
    }

    @Test
    fun aRunWithANoticeKeepsItsKeyAsItGrows() {
        val before = group(call("c1"), notice("n"), call("c2")).single() as Run
        val after = group(call("c1"), notice("n"), call("c2"), call("c3")).single() as Run
        assertEquals(before.key, after.key)
        assertEquals(3, after.calls.size)
        assertEquals(0, after.failed)
    }
}
