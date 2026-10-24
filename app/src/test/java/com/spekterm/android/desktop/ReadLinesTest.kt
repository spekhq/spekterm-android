package com.spekterm.android.desktop

import com.spekterm.android.desktop.ReadLines.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Read's result, in the shapes measured in the CLI's transcripts (`tool-result-content` D4). */
class ReadLinesTest {
    private fun numbered(text: String) = ReadLines.parse(text) as Parsed.Numbered

    @Test
    fun numberedLinesKeepTheEmptyOnes() {
        val p = numbered("1\t## Why\n2\t\n3\tFrom the phone")
        assertEquals(1L, p.first)
        assertEquals(listOf("## Why", "", "From the phone"), p.lines)
        assertEquals(null, p.above)
        assertEquals(null, p.below)
        assertTrue(p.twoColumns)
    }

    @Test
    fun anOffsetAndTheOlderArrow() {
        val p = numbered("  10→a\n  11→b")
        assertEquals(10L, p.first)
        assertEquals(listOf("a", "b"), p.lines)
    }

    @Test
    fun otherShapesStayRaw() {
        for (text in listOf("1\ta\n3\tb", "PDF pages: 3\nPage 1 text", "", "1 a")) {
            assertEquals(text, Parsed.Raw(text), ReadLines.parse(text))
        }
    }

    @Test
    fun aMemoryFilesAgeNoteGoesAbove() {
        val note = "This memory is 5 days old. Memories are point-in-time observations, not live state."
        val p = numbered("<system-reminder>$note</system-reminder>\n1\t---\n2\tname: x\n3\t---")
        assertEquals(note, p.above)
        assertEquals(listOf("---", "name: x", "---"), p.lines)
    }

    @Test
    fun theEmptyFileWarningAloneIsShownAsItCame() {
        val text = "<system-reminder>Warning: the file exists but the contents are empty.</system-reminder>"
        assertEquals(Parsed.Raw(text), ReadLines.parse(text))
    }

    @Test
    fun anArtifactsSourceNoteGoesBelow() {
        val note = "This file is the saved source of artifact x at version 3 (2 lines)"
        val p = numbered("1\t<html>\n2\t</html>\n\n[$note]")
        assertEquals(listOf("<html>", "</html>"), p.lines)
        assertEquals(note, p.below)
    }

    @Test
    fun theTwoColumnBounds() {
        assertTrue(numbered("1\t" + "x".repeat(400)).twoColumns)
        assertFalse(numbered("1\t" + "x".repeat(401)).twoColumns)
        assertTrue(numbered((1..1500).joinToString("\n") { "$it\tl" }).twoColumns)
        assertFalse(numbered((1..1501).joinToString("\n") { "$it\tl" }).twoColumns)
    }

    @Test
    fun twoThousandLinesParseQuickly() {
        val text = (1..2000).joinToString("\n") { "$it\t" + "val x$it = listOf(1, 2, 3).map { it * 2 }" }
        ReadLines.parse(text)
        val start = System.nanoTime()
        val p = numbered(text)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(2000, p.lines.size)
        assertTrue("$ms ms", ms < 50)
    }
}
