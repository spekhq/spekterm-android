package com.spekterm.android.desktop

import com.spekterm.android.net.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `SkillFilter` and `SkillInsert` (`agent-usage-and-skills` D4). */
class SkillsTest {
    private val android = Skill("android-log", "Capture logcat of the app")
    private val review = Skill("code-review", "Review the diff; ANDROID and web")
    private val bare = Skill("Simplify")

    @Test
    fun filtersOnTheNameAndTheDescriptionInAnyCase() {
        assertTrue(SkillFilter.matches(android, "ANDROID"))
        assertTrue(SkillFilter.matches(review, "android"))
        assertFalse(SkillFilter.matches(bare, "android"))
        assertTrue(SkillFilter.matches(bare, "  "))
        assertTrue(SkillFilter.matches(bare, " simp "))
    }

    @Test
    fun sortsByNameIgnoringCase() {
        assertEquals(listOf("android-log", "code-review", "Simplify", "zz"), SkillFilter.sorted(listOf(Skill("zz"), bare, review, android)).map { it.name })
    }

    private fun insert(draft: String) = SkillInsert.insert(draft, "name")

    @Test
    fun anEmptyFieldOrABareSlashBecomesTheCommand() {
        for (draft in listOf("", "   ", "/", " / ")) {
            assertEquals(draft, SkillInsert.Inserted("/name ", 6), insert(draft))
        }
    }

    @Test
    fun textTypedBecomesTheArguments() {
        assertEquals(SkillInsert.Inserted("/name fix the login bug", 6), insert("fix the login bug"))
        // A path typed first is not a command name.
        assertEquals(SkillInsert.Inserted("/name /tmp/x.log shows an error", 6), insert("/tmp/x.log shows an error"))
    }

    @Test
    fun anotherCommandHasItsNameReplaced() {
        assertEquals(SkillInsert.Inserted("/name 123", 6), insert("/review 123"))
        assertEquals(SkillInsert.Inserted("/name x", 6), insert(" /review x"))
        assertEquals(SkillInsert.Inserted("/name ", 6), insert("/review"))
        assertEquals(SkillInsert.Inserted("/name\nmore", 6), insert("/review\nmore"))
        assertEquals(SkillInsert.Inserted("/name  two  spaces", 6), insert("/review  two  spaces"))
        assertEquals(SkillInsert.Inserted("/name\u3000x", 6), insert("/review\u3000x"))
    }
}
