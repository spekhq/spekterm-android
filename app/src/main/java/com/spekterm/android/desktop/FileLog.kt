package com.spekterm.android.desktop

/**
 * What the log gets for a file or picture read (`tool-result-content` D1): where it came from, the outcome, the
 * desktop's error code and the size; never a name, path, media type or byte.
 */
object FileLog {
    enum class Source { SENT, RESULT }

    fun fields(source: Source, outcome: SentFileRead.Outcome): List<Pair<String, Any?>> {
        val head = "source" to source.name.lowercase()
        return when (outcome) {
            is SentFileRead.Outcome.Done -> listOf(head, "outcome" to "done", "size" to outcome.size)
            is SentFileRead.Outcome.Failed -> listOf(head, "outcome" to outcome.reason.name.lowercase(), "error" to outcome.code, "size" to outcome.size)
        }
    }
}
