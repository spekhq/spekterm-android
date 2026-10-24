package com.spekterm.android.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A result entry's `images` and the `images.read` frame (`tool-result-content` D1). */
class ResultImagesTest {
    private fun result(extra: String): Entry.Result =
        Entry.decode(Json.parseToJsonElement("""{"kind":"result","uuid":"u","at":1,"id":"t","text":"","error":false$extra}""")) as Entry.Result

    @Test
    fun onePicture() {
        assertEquals(listOf(ResultImage("image/png", 400000)), result(""","images":[{"mediaType":"image/png","size":400000}]""").images)
    }

    @Test
    fun twoPicturesInOrder() {
        val images = result(""","images":[{"mediaType":"image/png","size":1},{"mediaType":"image/jpeg","size":0}]""").images
        assertEquals(listOf(ResultImage("image/png", 1), ResultImage("image/jpeg", 0)), images)
    }

    @Test
    fun noListKeepsTheEntry() {
        for (extra in listOf("", ""","images":null""", ""","images":2""", ""","images":"x"""")) {
            val r = result(extra)
            assertTrue(extra, r.images.isEmpty())
            assertEquals("t", r.id)
        }
    }

    @Test
    fun oneMalformedElementDropsTheWholeList() {
        val good = """{"mediaType":"image/png","size":5}"""
        for (bad in listOf(
            """{"size":5}""",
            """{"mediaType":"image/png","size":-1}""",
            """{"mediaType":"image/png","size":1.5}""",
            """{"mediaType":"image/png","size":"5"}""",
            """{"mediaType":"image/png"}""",
            """{"mediaType":3,"size":5}""",
            "\"x\"",
            "7",
        )) {
            assertTrue(bad, result(""","images":[$good,$bad]""").images.isEmpty())
        }
    }

    @Test
    fun theFrameNamesThePicture() {
        val f = Frames.imagesRead("s", "toolu_1", 2, 524288, 524288, "v1")
        assertEquals("s", f["sessionId"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", f["toolUseId"]!!.jsonPrimitive.content)
        assertEquals(2, f["index"]!!.jsonPrimitive.int)
        assertEquals(524288L, f["offset"]!!.jsonPrimitive.long)
        assertEquals(524288, f["length"]!!.jsonPrimitive.int)
        assertEquals("v1", f["version"]!!.jsonPrimitive.content)
        assertTrue("version" !in Frames.imagesRead("s", "t", 0, 0, 524288, null))
    }
}
