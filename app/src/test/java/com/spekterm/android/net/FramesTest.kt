package com.spekterm.android.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun encodesARequest() {
        val frame = parse(Frames.request(7, "hello", Frames.hello("dev-1", "s3cret")))
        assertEquals("req", frame["t"]!!.jsonPrimitive.content)
        assertEquals(7L, frame["id"]!!.jsonPrimitive.long)
        assertEquals("hello", frame["m"]!!.jsonPrimitive.content)
        val p = frame["p"]!!.jsonObject
        assertEquals(listOf(1L), p["protocol"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals("dev-1", p["deviceId"]!!.jsonPrimitive.content)
        assertEquals("s3cret", p["deviceSecret"]!!.jsonPrimitive.content)
    }

    @Test
    fun encodesPair() {
        val p = parse(Frames.request(1, "pair", Frames.pair("ABC", "Pixel", "Pixel 7")))["p"]!!.jsonObject
        assertEquals("ABC", p["code"]!!.jsonPrimitive.content)
        assertEquals("Pixel", p["deviceName"]!!.jsonPrimitive.content)
        assertEquals("Pixel 7", p["deviceModel"]!!.jsonPrimitive.content)
    }

    @Test
    fun encodesAttentionWatchWithAndWithoutAPosition() {
        assertEquals(JsonObject(emptyMap()), Frames.attentionWatch(null))
        val after = Frames.attentionWatch(Position("a1b2", 41))["after"]!!.jsonObject
        assertEquals("a1b2", after["epoch"]!!.jsonPrimitive.content)
        assertEquals(41L, after["seq"]!!.jsonPrimitive.long)
    }

    @Test
    fun decodesAnOkResponse() {
        val frame = Frames.decode("""{"t":"res","id":3,"ok":true,"r":{"protocol":1,"server":{"name":"desk","version":"0.9.0"},"extra":true}}""")
            as ServerFrame.Response
        assertEquals(3L, frame.id)
        assertTrue(frame.ok)
        val hello = Frames.decodeOrNull<HelloResult>(frame.result!!)!!
        assertEquals(1, hello.protocol)
        assertEquals("desk", hello.server.name)
    }

    @Test
    fun decodesAPairResultAndKeepsTheSecretOutOfToString() {
        val frame = Frames.decode("""{"t":"res","id":1,"ok":true,"r":{"protocol":1,"server":{"name":"d","version":"1"},"deviceId":"D","deviceSecret":"S3CR3T"}}""")
            as ServerFrame.Response
        val pair = Frames.decodeOrNull<PairResult>(frame.result!!)!!
        assertEquals("D", pair.deviceId)
        assertEquals("S3CR3T", pair.deviceSecret)
        assertFalse(pair.toString().contains("S3CR3T"))
    }

    @Test
    fun decodesAnErrorWithDetail() {
        val frame = Frames.decode("""{"t":"res","id":1,"ok":false,"e":{"code":"UNSUPPORTED_VERSION","message":"no","detail":{"supported":[1,2]}}}""")
            as ServerFrame.Response
        assertFalse(frame.ok)
        assertEquals(RemoteError.UNSUPPORTED_VERSION, frame.error!!.code)
        assertEquals(listOf(1, 2), Frames.supportedVersions(frame.error))
    }

    @Test
    fun decodesAThrottledFrameWithANullId() {
        // A throttled address is answered at accept time, before any request (`remote-server.ts`).
        val frame = Frames.decode("""{"t":"res","id":null,"ok":false,"e":{"code":"THROTTLED","message":"too many failed attempts"}}""")
            as ServerFrame.Response
        assertNull(frame.id)
        assertEquals(RemoteError.THROTTLED, frame.error!!.code)
    }

    @Test
    fun decodesAnAttentionEventAndIgnoresUnknownKeys() {
        val frame = Frames.decode(
            """{"t":"evt","k":"attention","d":{"pos":{"epoch":"e1","seq":5},"kind":"choice","at":1760000000000,""" +
                """"sessionId":"s1","folderName":"spekterm","title":"Fix it","tool":"Bash","suppressed":false,"future":1}}""",
        ) as ServerFrame.Event
        assertEquals("attention", frame.kind)
        val event = Frames.decodeOrNull<Attention>(frame.data)!!
        assertEquals(Position("e1", 5), event.pos)
        assertEquals(Attention.CHOICE, event.kind)
        assertEquals("Bash", event.tool)
        assertEquals("s1", event.sessionId)
        assertFalse(event.suppressed)
    }

    @Test
    fun decodesAWatchAnswer() {
        val frame = Frames.decode(
            """{"t":"res","id":4,"ok":true,"r":{"events":[{"pos":{"epoch":"e","seq":1},"kind":"intake","at":1,""" +
                """"intake":{"adapter":"file","id":"x"},"title":"New item","suppressed":false}],"complete":false}}""",
        ) as ServerFrame.Response
        val answer = Frames.decodeOrNull<WatchAnswer>(frame.result!!)!!
        assertFalse(answer.complete)
        assertEquals(IntakeRef("file", "x"), answer.events.single().intake)
    }

    @Test
    fun refusesWhatIsNotAServerFrame() {
        assertNull(Frames.decode("not json"))
        assertNull(Frames.decode("[]"))
        assertNull(Frames.decode("""{"t":"req","id":1,"m":"x"}"""))
        assertNull(Frames.decode("""{"t":"evt"}"""))
        assertNotNull(Frames.decode("""{"t":"res","id":1,"ok":false}""")) // malformed error still surfaces
    }
}
