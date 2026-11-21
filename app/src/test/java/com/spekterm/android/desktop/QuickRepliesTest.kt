package com.spekterm.android.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rules of a quick reply and of the stored list (`quick-replies` D2). */
class QuickRepliesTest {
    private fun stored(vararg replies: String) = QuickReplies.encode(replies.toList())
    private fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""
    private fun raw(vararg replies: String) = """{"replies":[${replies.joinToString(",") { quoted(it) }}]}"""

    @Test
    fun eachMalformedListIsNotSet() {
        val cases = mapOf(
            "not JSON" to "not json",
            "not the shape" to """{"list":["a"]}""",
            "not an object" to """["a"]""",
            "replies not a list" to """{"replies":"a"}""",
            "a non-string" to """{"replies":["a",3]}""",
            "a null" to """{"replies":["a",null]}""",
            "empty after trim" to raw("a", "   "),
            "a line break" to raw("one\ntwo"),
            "a tab" to raw("one\ttwo"),
            "a line separator" to raw("one\u2028two"),
            "201 units" to raw("x".repeat(201)),
            "a bare /model" to raw("/model"),
            "a bare /effort" to raw(" /effort "),
            "13 entries" to raw(*Array(13) { "q$it" }),
            "two equal after trimming" to raw("same", " same "),
        )
        for ((name, json) in cases) assertNull(name, QuickReplies.parse(json))
    }

    @Test
    fun aValidListRoundTripsTrimmed() {
        val list = listOf("Can this session be closed now?", "會有什麼風險或我要特別決策的嗎？", "x".repeat(200), "/model opus", "/effort high")
        assertEquals(list, QuickReplies.parse(stored(*list.toTypedArray())))
        assertEquals(listOf("padded"), QuickReplies.parse(raw("  padded\u3000")))
        // Twelve is allowed; a surrogate pair counts as two units, as JavaScript counts.
        assertEquals(12, QuickReplies.parse(raw(*Array(12) { "q$it" }))!!.size)
        assertEquals(listOf("😀".repeat(100)), QuickReplies.parse(raw("😀".repeat(100))))
        assertNull(QuickReplies.parse(raw("😀".repeat(100) + "x")))
    }

    @Test
    fun anEmptyListIsKept() = assertEquals(emptyList<String>(), QuickReplies.parse(stored()))

    @Test
    fun theEditorSaysWhy() {
        assertEquals(QuickReplies.Problem.EMPTY, QuickReplies.problem("  "))
        assertEquals(QuickReplies.Problem.LINE_BREAK, QuickReplies.problem("a\nb"))
        assertEquals(QuickReplies.Problem.TOO_LONG, QuickReplies.problem("x".repeat(201)))
        assertEquals(QuickReplies.Problem.BARE_COMMAND, QuickReplies.problem("/model"))
        assertEquals(QuickReplies.Problem.DUPLICATE, QuickReplies.problem(" hi ", listOf("hi")))
        assertNull(QuickReplies.problem("/model opus", listOf("hi")))
    }

}
