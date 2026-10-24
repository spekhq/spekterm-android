package com.spekterm.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** How a result picture is keyed and how many tiles a collapsed run shows (`tool-result-content` D3). */
class PictureLayoutTest {
    @Test
    fun aSentFileAndAResultPictureAreHeldApart() {
        val sent = SentFileRef("t", 0, "/x/a.png")
        val result = SentFileRef("t", 0, null, SentFileRef.Source.RESULT, "a.png")
        assertNotEquals(ThumbKey.of(sent, ThumbKey.Size.FULL), ThumbKey.of(result, ThumbKey.Size.FULL))
        assertNotEquals(ThumbKey.of(result, ThumbKey.Size.STRIP), ThumbKey.of(result, ThumbKey.Size.FULL))
        assertEquals("t#0", ThumbKey.of(sent, ThumbKey.Size.FULL))
    }

    @Test
    fun theStripCount() {
        assertEquals(Strip.Count(3, 2), Strip.count(366f, 5))
        assertEquals(Strip.Count(3, 0), Strip.count(366f, 3))
        assertEquals(Strip.Count(1, 4), Strip.count(200f, 5))
        assertEquals(Strip.Count(0, 1), Strip.count(60f, 1))
        assertEquals(Strip.Count(3, 1), Strip.count(1000f, 4))
    }
}
