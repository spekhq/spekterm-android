package com.spekterm.android.desktop

/**
 * An Edit's change as lines (`tool-call-display` D4): the common leading and trailing lines as context, the old middle
 * removed, then the new middle added. The suffix is counted only after the prefix, so repeated lines never count twice.
 */
object EditDiff {
    const val CONTEXT = 3

    sealed interface Line {
        data class Context(val text: String) : Line

        data class Removed(val text: String) : Line

        data class Added(val text: String) : Line

        /** [count] context lines not shown. */
        data class Fold(val count: Int) : Line
    }

    /** [unchanged]: the two strings hold the same lines. */
    data class Diff(val lines: List<Line>, val unchanged: Boolean)

    /** Lines split at `\r\n` and `\n`; an empty string is no line. */
    fun split(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split("\r\n", "\n")

    fun of(old: String, new: String): Diff {
        val a = split(old)
        val b = split(new)
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val out = mutableListOf<Line>()
        val lead = a.subList(0, p)
        if (lead.size > CONTEXT) out += Line.Fold(lead.size - CONTEXT)
        lead.takeLast(CONTEXT).mapTo(out) { Line.Context(it) }
        a.subList(p, a.size - s).mapTo(out) { Line.Removed(it) }
        b.subList(p, b.size - s).mapTo(out) { Line.Added(it) }
        val trail = a.subList(a.size - s, a.size)
        trail.take(CONTEXT).mapTo(out) { Line.Context(it) }
        if (trail.size > CONTEXT) out += Line.Fold(trail.size - CONTEXT)
        return Diff(out, unchanged = p + s == a.size && a.size == b.size)
    }
}
