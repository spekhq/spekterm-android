package com.spekterm.android.desktop

import com.spekterm.android.desktop.ConversationTimes.DayWord
import com.spekterm.android.desktop.ConversationTimes.Line
import com.spekterm.android.net.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ConversationTimesTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val taipei: ZoneId = ZoneId.of("Asia/Taipei")

    private fun ms(text: String): Long = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    // Each entry's uuid is its name, so its key is "<name>#0".
    private fun user(name: String, at: Long, meta: Boolean = false) = Entry.User(name, at, "asked", meta)
    private fun text(name: String, at: Long) = Entry.Text(name, at, "said")
    private fun thinking(name: String, at: Long) = Entry.Thinking(name, at, "hmm")
    private fun call(name: String, at: Long) = Entry.Tool(name, at, name, "Bash", "ls")
    private fun result(name: String, at: Long, id: String = "gone") = Entry.Result(name, at, id, "out", false)

    private fun reply(id: Long, anchor: String?, settled: Boolean) =
        Reply(id, "r$id", "ready", emptySet(), anchor, state = if (settled) Reply.State.MAY_NOT_HAVE_BEEN_SENT else Reply.State.SENDING)

    private fun conversation(vararg entries: Entry, replies: List<Reply> = emptyList()) =
        Conversation("s", entries = entries.toList(), replies = replies)

    /** A compact picture: D(date) a heading, the item's first uuid, m(id) a reply. */
    private fun shape(lines: List<Line>): String = lines.joinToString(" ") { line ->
        when (line) {
            is Line.Day -> "D(${line.date})"
            is Line.Mine -> "m(${line.reply.id})"
            is Line.Of -> line.key.removeSuffix("#0")
        }
    }

    private fun placed(c: Conversation, zone: ZoneId = utc) = ConversationTimes.place(ConversationTimes.rows(c, 0), zone)

    @Test
    fun onlyTheUsersOwnMessageAndTheAgentsTextShowATime() {
        val at = ms("2026-10-20T09:12:00")
        fun shownOf(vararg entries: Entry) = ConversationTimes.rows(conversation(*entries), 0).map { (it as Line.Of).shown }
        assertEquals(at, ConversationTimes.timeOf(shownOf(user("u", at)).single()))
        assertEquals(at, ConversationTimes.timeOf(shownOf(text("t", at)).single()))
        assertNull(ConversationTimes.timeOf(shownOf(user("u", at, meta = true)).single()))
        assertNull(ConversationTimes.timeOf(shownOf(thinking("h", at)).single()))
        assertNull(ConversationTimes.timeOf(shownOf(call("c", at)).single()))
        assertNull(ConversationTimes.timeOf(shownOf(call("c1", at), call("c2", at)).single()))
        assertNull(ConversationTimes.timeOf(shownOf(result("r", at)).single()))
        assertNull(ConversationTimes.timeOf(shownOf(user("u", 0)).single()))
        assertNull(ConversationTimes.timeOf(shownOf(text("t", 0)).single()))
    }

    @Test
    fun oneDayIsHeadedOnceBeforeItsFirstTimedItem() {
        val c = conversation(user("u", ms("2026-10-20T09:12:00")), call("c", ms("2026-10-20T09:13:00")), text("t", ms("2026-10-20T09:14:00")))
        assertEquals("D(2026-10-20) u c t", shape(placed(c)))
    }

    @Test
    fun twoDaysAreHeadedTwice() {
        val c = conversation(user("u", ms("2026-10-19T21:00:00")), text("t", ms("2026-10-19T21:05:00")), user("v", ms("2026-10-20T08:00:00")))
        assertEquals("D(2026-10-19) u t D(2026-10-20) v", shape(placed(c)))
    }

    @Test
    fun anItemWithoutATimeIsNotHeaded() {
        val c = conversation(user("u", 0), text("t", ms("2026-10-20T09:14:00")), text("x", 0))
        assertEquals("u D(2026-10-20) t x", shape(placed(c)))
        assertEquals("u t", shape(placed(conversation(user("u", 0), text("t", 0)))))
    }

    @Test
    fun timesGoingBackAcrossMidnightHeadTheDayAgain() {
        val lines = placed(conversation(text("a", ms("2026-10-19T23:59:00")), text("b", ms("2026-10-20T00:01:00")), user("c", ms("2026-10-19T23:58:00"))))
        assertEquals("D(2026-10-19) a D(2026-10-20) b D(2026-10-19) c", shape(lines))
        assertEquals(lines.size, lines.map { it.key }.toSet().size)
    }

    @Test
    fun aRunIsHeadedByItsFirstCallsDay() {
        val c = conversation(text("a", ms("2026-10-19T23:00:00")), call("c1", ms("2026-10-20T00:01:00")), call("c2", ms("2026-10-20T00:02:00")))
        val lines = placed(c)
        assertEquals("D(2026-10-19) a D(2026-10-20) c1", shape(lines))
        assertEquals("day:c1#0", (lines[2] as Line.Day).key)
    }

    @Test
    fun theZoneDecidesTheDate() {
        val c = conversation(text("t", ms("2026-10-19T20:00:00")))
        assertEquals("D(2026-10-19) t", shape(placed(c, utc)))
        assertEquals("D(2026-10-20) t", shape(placed(c, taipei)))
    }

    @Test
    fun repliesAreNeverHeadedAndKeepTheLastDate() {
        val yesterday = text("a", ms("2026-10-19T21:00:00"))
        val c = conversation(yesterday, text("b", ms("2026-10-20T08:00:00")), replies = listOf(reply(1, "a#0", settled = true), reply(2, null, settled = false)))
        assertEquals("D(2026-10-19) a m(1) D(2026-10-20) b m(2)", shape(placed(c)))
    }

    @Test
    fun rowsKeepTheRepliesWhereTheyWereShown() {
        val c = conversation(
            text("a", 0), text("b", 0),
            replies = listOf(reply(1, "gone#0", settled = true), reply(2, "a#0", settled = true), reply(3, "b#0", settled = false)),
        )
        assertEquals("m(1) a m(2) b m(3)", shape(ConversationTimes.rows(c, 0)))
        // A settled reply whose anchor is gone stays first, before the first heading.
        val timed = conversation(text("a", ms("2026-10-20T08:00:00")), replies = listOf(reply(1, "gone#0", settled = true)))
        assertEquals("m(1) D(2026-10-20) a", shape(placed(timed)))
    }

    @Test
    fun dayWords() {
        val today = LocalDate.parse("2026-10-20")
        assertEquals(DayWord.Today, ConversationTimes.dayWord(today, today))
        assertEquals(DayWord.Yesterday, ConversationTimes.dayWord(today.minusDays(1), today))
        assertEquals(DayWord.Date(today.minusDays(2)), ConversationTimes.dayWord(today.minusDays(2), today))
        assertEquals(DayWord.Date(today.plusDays(1)), ConversationTimes.dayWord(today.plusDays(1), today))
    }

    @Test
    fun untilMidnightReachesTheNextDaysStart() {
        assertEquals(Duration.ofSeconds(60), ConversationTimes.untilMidnight(LocalDateTime.parse("2026-10-20T23:59:00").atZone(taipei)))
        val santiago = ZoneId.of("America/Santiago")
        // A 23-hour day, starting at 01:00, and a 25-hour day.
        assertEquals(Duration.ofHours(12), ConversationTimes.untilMidnight(LocalDateTime.parse("2026-09-06T12:00:00").atZone(santiago)))
        assertEquals(Duration.ofHours(13), ConversationTimes.untilMidnight(LocalDateTime.parse("2026-04-04T12:00:00").atZone(santiago)))
        val saoPaulo = ZoneId.of("America/Sao_Paulo")
        assertEquals(Duration.ofHours(12), ConversationTimes.untilMidnight(LocalDateTime.parse("2018-11-04T12:00:00").atZone(saoPaulo)))
    }
}
