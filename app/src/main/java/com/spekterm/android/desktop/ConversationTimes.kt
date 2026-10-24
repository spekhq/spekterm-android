package com.spekterm.android.desktop

import com.spekterm.android.desktop.ConversationItems.Shown
import com.spekterm.android.net.Entry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When the conversation's messages were recorded, and where its days begin (`conversation-times` D1–D3). Times are
 * the desktop's `Entry.at`, epoch milliseconds, 0 when it had none.
 */
object ConversationTimes {
    /** One line of the conversation screen's list, in its order. */
    sealed interface Line {
        val key: String

        data class Of(val shown: Shown) : Line {
            override val key: String get() = shown.key
        }

        data class Mine(val reply: Reply) : Line {
            override val key: String get() = "reply:${reply.id}"
        }

        /** The heading of a day, put before the item it is keyed by. */
        data class Day(val date: LocalDate, override val key: String) : Line
    }

    sealed interface DayWord {
        data object Today : DayWord

        data object Yesterday : DayWord

        data class Date(val date: LocalDate) : DayWord
    }

    /** The time shown under an item: only the user's own message and the agent's text, and only a known one (D1). */
    fun timeOf(shown: Shown): Long? {
        if (shown is ConversationItems.Message) return shown.at.takeIf { it > 0 }
        val entry = (shown as? ConversationItems.Plain)?.entry ?: return null
        val shows = (entry is Entry.User && !entry.meta) || entry is Entry.Text
        return entry.at.takeIf { shows && it > 0 }
    }

    /** The time an item counts for its day: its first entry's, a run's first call's (D2). */
    fun startOf(shown: Shown): Long = when (shown) {
        is ConversationItems.Call -> shown.tool.at
        is ConversationItems.Run -> (shown.parts.first() as ConversationItems.Call).tool.at
        is ConversationItems.LoneResult -> shown.result.at
        is ConversationItems.Plain -> shown.entry.at
        is ConversationItems.Message -> shown.at
    }

    /**
     * Shown items in order, settled replies right after the item carrying their anchor (`ui-polish` D7); replies
     * whose anchor is gone first, pending ones at the end.
     */
    fun rows(c: Conversation, now: Long): List<Line> {
        val keys = ConversationReducer.keys(c.entries)
        val known = keys.toSet()
        val settled = c.replies.filter { it.label(now).settled }.groupBy { it.anchor?.takeIf { a -> a in known } }
        val out = mutableListOf<Line>()
        settled[null]?.forEach { out += Line.Mine(it) }
        for (shown in ConversationItems.group(c.entries, keys, settled.keys.filterNotNull().toSet())) {
            out += Line.Of(shown)
            for (anchor in shown.anchorsAfter) settled[anchor]?.forEach { out += Line.Mine(it) }
        }
        c.replies.filter { !it.label(now).settled }.forEach { out += Line.Mine(it) }
        return out
    }

    /**
     * [lines] with a day heading before the first item with a time and before each item with a time whose date
     * differs from the last one seen; a day is headed again when times go back across midnight (D2). Replies and
     * items without a time are never headed and leave the last date as it was.
     */
    fun place(lines: List<Line>, zone: ZoneId): List<Line> {
        val out = ArrayList<Line>(lines.size + 4)
        var last: LocalDate? = null
        for (line in lines) {
            val at = (line as? Line.Of)?.let { startOf(it.shown) } ?: 0L
            if (at > 0) {
                val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                if (date != last) out += Line.Day(date, "day:${line.key}")
                last = date
            }
            out += line
        }
        return out
    }

    fun dayWord(date: LocalDate, today: LocalDate): DayWord = when (date) {
        today -> DayWord.Today
        today.minusDays(1) -> DayWord.Yesterday
        else -> DayWord.Date(date)
    }

    /** From [now] to the start of the next local day, which is not always 24 hours away (D3). */
    fun untilMidnight(now: ZonedDateTime): Duration {
        val next = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        return Duration.between(now, next)
    }
}
