package com.spekterm.android.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * The rules of the phone's quick replies and of their stored list (`quick-replies` D2). Pure, so the rules are tested
 * on the JVM; the store only reads and writes the file.
 */
object QuickReplies {
    /** UTF-16 units, as the desktop's JavaScript counts. */
    const val MAX_CHARS = 200

    const val MAX_COUNT = 12

    /** Why a sentence cannot be kept, in the order the editor says it. */
    enum class Problem { EMPTY, LINE_BREAK, TOO_LONG, BARE_COMMAND, DUPLICATE }

    /** A sentence as it is kept: trimmed as the desktop trims a reply. */
    fun normalized(text: String): String = Replies.jsTrim(text)

    /**
     * Why [text], once [normalized], cannot be kept beside [others] (the rest of the list), or `null`. A control
     * character is checked before the reply's cleaning, which would remove it silently.
     */
    fun problem(text: String, others: List<String> = emptyList()): Problem? {
        val t = normalized(text)
        return when {
            t.isEmpty() -> Problem.EMPTY
            t.any(::breaksLine) -> Problem.LINE_BREAK
            Replies.jsTrim(Replies.clean(t)).length > MAX_CHARS -> Problem.TOO_LONG
            ModelEffort.bare(t) -> Problem.BARE_COMMAND
            others.any { normalized(it) == t } -> Problem.DUPLICATE
            else -> null
        }
    }

    /** A line break or any other control character (C0, DEL, C1, and the Unicode line and paragraph separators). */
    private fun breaksLine(c: Char): Boolean = c.isISOControl() || c == ' ' || c == ' '

    /** Whether [list] may be kept as it is: every sentence valid, at most [MAX_COUNT], no two equal after trimming. */
    fun valid(list: List<String>): Boolean =
        list.size <= MAX_COUNT && list.indices.all { i -> problem(list[i], list.subList(0, i)) == null }

    /**
     * The list in [json] (`{"replies": [..]}`), trimmed, or `null` when it is not that shape or breaks any rule: the
     * whole list is then not set, never repaired in part.
     */
    fun parse(json: String): List<String>? {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (_: Exception) {
            return null
        }
        val replies = ((root as? JsonObject)?.get("replies") as? JsonArray) ?: return null
        val list = replies.map { e -> (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
        return list.map(::normalized).takeIf { valid(list) }
    }

    /** What [parse] reads back, for a list already [valid]; trimmed. */
    fun encode(list: List<String>): String = buildJsonObject {
        putJsonArray("replies") { list.forEach { add(JsonPrimitive(normalized(it))) } }
    }.toString()
}
