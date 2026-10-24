package com.spekterm.android.desktop

import com.spekterm.android.desktop.EditDiff.Line.Added
import com.spekterm.android.desktop.EditDiff.Line.Context
import com.spekterm.android.desktop.EditDiff.Line.Fold
import com.spekterm.android.desktop.EditDiff.Line.Removed
import com.spekterm.android.desktop.SentFiles.Kind
import com.spekterm.android.desktop.SentFiles.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `EditDiff` (design D4) and `SentFiles.kind` / `view` (D6). */
class EditDiffTest {
    @Test
    fun oneLineReplacedByTwo() {
        val d = EditDiff.of("keep\nold\nend", "keep\nnew1\nnew2\nend")
        assertEquals(listOf(Context("keep"), Removed("old"), Added("new1"), Added("new2"), Context("end")), d.lines)
        assertFalse(d.unchanged)
    }

    @Test
    fun longContextFolds() {
        val lead = (1..5).joinToString("\n") { "l$it" }
        val trail = (1..4).joinToString("\n") { "t$it" }
        val d = EditDiff.of("$lead\nx\n$trail", "$lead\ny\n$trail")
        assertEquals(
            listOf(Fold(2), Context("l3"), Context("l4"), Context("l5"), Removed("x"), Added("y"), Context("t1"), Context("t2"), Context("t3"), Fold(1)),
            d.lines,
        )
    }

    @Test
    fun emptySidesAreNoLines() {
        assertEquals(listOf(Added("a"), Added("b")), EditDiff.of("", "a\nb").lines)
        assertEquals(listOf(Removed("a")), EditDiff.of("a", "").lines)
    }

    @Test
    fun crlfAndATrailingNewline() {
        assertEquals(listOf(Context("a"), Removed("b"), Added("c")), EditDiff.of("a\r\nb", "a\nc").lines)
        assertEquals(listOf(Context("a"), Removed("")), EditDiff.of("a\n", "a").lines)
    }

    @Test
    fun repeatedLinesAreNotCountedTwice() {
        assertEquals(listOf(Context("x"), Context("x"), Removed("x")), EditDiff.of("x\nx\nx", "x\nx").lines.let { listOf(it[0], it[1], it[2]) })
        assertEquals(3, EditDiff.of("x\nx\nx", "x\nx").lines.size)
        assertEquals(1, EditDiff.of("x\nx\nx", "x\nx").lines.count { it is Removed })
    }

    @Test
    fun identicalStrings() {
        val d = EditDiff.of("a\nb", "a\nb")
        assertTrue(d.unchanged)
        assertEquals(listOf(Context("a"), Context("b")), d.lines)
    }

    @Test
    fun kindsByExtension() {
        for (n in listOf("a.PNG", "b.jpg", "c.JpEg", "d.gif", "e.webp")) assertEquals(n, Kind.IMAGE, SentFiles.kind("/x/$n"))
        for (n in listOf("a.md", "b.Markdown")) assertEquals(n, Kind.MARKDOWN, SentFiles.kind(n))
        for (n in listOf("a.txt", "b.LOG", "c.json", "d.yaml", "e.yml", "f.csv")) assertEquals(n, Kind.TEXT, SentFiles.kind(n))
        for (n in listOf("a.svg", "b.pdf", "c.mp4", "d.apk", "e.zip", "noext", ".hidden", "/x.d/file")) assertEquals(n, Kind.OTHER, SentFiles.kind(n))
    }

    @Test
    fun viewsByMediaType() {
        assertEquals(View.IMAGE, SentFiles.view("image/png", Kind.IMAGE))
        assertEquals(View.IMAGE, SentFiles.view("image/jpeg", Kind.IMAGE))
        assertEquals(View.MARKDOWN, SentFiles.view("text/markdown", Kind.MARKDOWN))
        assertEquals(View.TEXT, SentFiles.view("application/json", Kind.TEXT))
        assertEquals(View.TEXT, SentFiles.view("text/plain", Kind.TEXT))
        assertEquals(View.IMAGE, SentFiles.view("application/octet-stream", Kind.IMAGE))
        assertEquals(View.HAND_OFF, SentFiles.view("image/svg+xml", Kind.OTHER))
        assertEquals(View.HAND_OFF, SentFiles.view("text/html", Kind.OTHER))
        assertEquals(View.HAND_OFF, SentFiles.view("video/mp4", Kind.OTHER))
    }
}
