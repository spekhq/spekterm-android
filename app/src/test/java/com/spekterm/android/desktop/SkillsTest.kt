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
}
