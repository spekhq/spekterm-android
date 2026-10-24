package com.spekterm.android.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The result pictures' cache: what is shown stays, the rest is bounded (`tool-result-content` D3). */
class PictureCacheTest {
    private fun keys(n: Int) = (1..n).map { "p$it" }

    @Test
    fun pinnedPicturesAreAllKeptAndUnpinnedOnesBounded() {
        val cache = PictureCache<Int>()
        for (k in keys(30)) {
            cache.pin(k)
            cache[k] = 1
        }
        assertEquals(30, keys(30).count { cache[it] != null })
        for (k in keys(30)) cache.unpin(k)
        assertEquals(keys(30).drop(6), keys(30).filter { cache[it] != null })
    }

    @Test
    fun aPinnedPictureIsNeverEvicted() {
        val cache = PictureCache<Int>(capacity = 2)
        cache.pin("shown")
        cache["shown"] = 0
        for (k in keys(5)) cache[k] = 1
        assertEquals(0, cache["shown"])
        assertEquals(listOf("p4", "p5"), keys(5).filter { cache[it] != null })
    }

    @Test
    fun anEvictedPictureIsAMissWhenShownAgain() {
        val cache = PictureCache<Int>(capacity = 1)
        cache["a"] = 1
        cache["b"] = 2
        cache.pin("a")
        assertNull(cache["a"])
    }

    @Test
    fun twoPinsNeedTwoUnpins() {
        val cache = PictureCache<Int>(capacity = 0)
        cache.pin("a")
        cache.pin("a")
        cache["a"] = 1
        cache.unpin("a")
        assertEquals(1, cache["a"])
        cache.unpin("a")
        assertNull(cache["a"])
    }
}
