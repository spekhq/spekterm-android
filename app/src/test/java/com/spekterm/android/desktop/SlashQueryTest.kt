package com.spekterm.android.desktop

import com.spekterm.android.net.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `SlashQuery.query`, `SkillFilter.ranked` and `SkillInsert.atCursor` (`tool-call-display` D10). */
class SlashQueryTest {
    private fun q(text: String, c: Int) = SlashQuery.query(text, c)

    @Test
    fun theQueryRunsFromTheSlashToTheCursor() {
        assertEquals("", q("/", 1))
        assertEquals("op", q("/op", 3))
        assertEquals("o", q("/op", 2))
        assertNull(q("/op", 0))
        assertEquals("", q("/review 123", 1))
        assertEquals("rev", q("/review 123", 4))
        assertEquals("review", q("/review 123", 7))
        assertNull(q("/review 123", 8))
        assertEquals("", q("/fix the login bug", 1))
        assertEquals("x", q("/x\nmore", 2))
    }

    @Test
    fun noQueryOutsideACommandWord() {
        for (c in 0..6) assertNull("/tmp/x at $c", q("/tmp/x", c))
        assertNull(q("//", 1))
        assertNull(q("/a/b", 2))
        assertNull(q(" /x", 2))
        assertNull(q("hello /x", 8))
        assertNull(q("/op", 9))
        assertNull(SlashQuery.query("/op", 1, 3))
    }

    @Test
    fun theAgentsWhiteSpaceEndsTheWord() {
        assertEquals("op", q("/op　x", 3))
        assertNull(q("/op　x", 4))
        assertNull(q("/op x", 4))
        assertEquals("op", q("/op x", 3))
    }

    @Test
    fun namesStartingWithTheQueryComeFirst() {
        val skills = listOf(
            Skill("code-review", "Review the open diff"),
            Skill("opsx:apply", null),
            Skill("Open-thing", null),
            Skill("simplify", "then apply the fixes"),
        )
        assertEquals(listOf("Open-thing", "opsx:apply", "code-review"), SkillFilter.ranked(skills, "Op").map { it.name })
        assertEquals(listOf("code-review", "Open-thing", "opsx:apply", "simplify"), SkillFilter.ranked(skills, "").map { it.name })
    }

    @Test
    fun choosingAtTheCursor() {
        assertEquals(SkillInsert.Inserted("/x ", 3), SkillInsert.atCursor("/", 1, "x"))
        assertEquals(SkillInsert.Inserted("/opsx:apply ", 12), SkillInsert.atCursor("/op", 3, "opsx:apply"))
        assertEquals(SkillInsert.Inserted("/simplify fix the login bug", 10), SkillInsert.atCursor("/fix the login bug", 1, "simplify"))
        assertEquals(SkillInsert.Inserted("/code-review 123", 13), SkillInsert.atCursor("/review 123", 4, "code-review"))
        assertEquals(SkillInsert.Inserted("/x\nmore", 3), SkillInsert.atCursor("/review\nmore", 7, "x"))
        assertEquals(SkillInsert.Inserted("/y　x", 3), SkillInsert.atCursor("/review　x", 7, "y"))
    }
}
