package com.spekterm.android.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Rename` (design D8): the desktop's cleaning and the unchanged check. */
class RenameTest {
    @Test
    fun renameCleansLikeTheDesktop() {
        assertEquals("Fix login", Rename.clean("  Fix\n login\u0007 "))
        val long = "x".repeat(199) + "🎉" + "y"
        assertEquals("x".repeat(199) + "🎉", Rename.clean(long))
        assertTrue(Rename.unchanged("A", "A"))
        assertTrue(Rename.unchanged("  A  ", "A"))
        assertFalse(Rename.unchanged("B", "A"))
        assertFalse("clearing is always sent", Rename.unchanged("", ""))
        assertTrue(Rename.unchanged(long, "x".repeat(199) + "🎉"))
    }
}
