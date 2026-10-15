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

    @Test
    fun decodesASessionListAndSkipsAMalformedSession() {
        val list = SessionList.decode(
            Json.parseToJsonElement(
                """{"window":true,"folders":[{"id":"f1","name":"repo","branch":"main","pinned":false,"hasOpenSpec":true,"extra":1}],
                "sessions":[
                  {"id":"s1","folderId":"f1","target":"claude","label":"A","running":true,"wait":"ready","lifecycle":"working","lineage":{"parentId":"p","title":"T"}},
                  {"id":"s2","folderId":null,"target":"shell","label":"B","running":false},
                  {"folderId":"f1","target":"claude"}
                ]}""",
            ),
        )!!
        assertTrue(list.window)
        assertEquals("repo", list.folders.single().name)
        assertEquals(listOf("s1", "s2"), list.sessions.map { it.id })
        assertEquals("ready", list.sessions[0].wait)
        assertEquals("working", list.sessions[0].lifecycle)
        assertEquals("T", list.sessions[0].lineage!!.title)
        assertNull(list.sessions[1].folderId)
        assertFalse(list.sessions[1].isAgent)
        assertNull(SessionList.decode(Json.parseToJsonElement("""{"sessions":[]}""")))
    }

    @Test
    fun decodesEveryEntryKindAndSkipsAnUnknownOne() {
        val update = ConversationUpdate.decode(
            Json.parseToJsonElement(
                """{"sessionId":"s1","status":"ok","truncated":true,"reset":false,"events":[
                  {"kind":"user","uuid":"u1","at":1,"text":"hi","meta":false},
                  {"kind":"text","uuid":"u2","at":2,"text":"**hello**"},
                  {"kind":"thinking","uuid":"u2","at":2,"text":"hmm"},
                  {"kind":"tool","uuid":"u2","at":2,"id":"t1","name":"Bash","arg":"ls"},
                  {"kind":"future","uuid":"u3","at":3},
                  {"kind":"result","uuid":"u4","at":4.0,"id":"t1","text":"out","error":true},
                  {"kind":"user","uuid":"u5","at":5,"text":"<x>","meta":true,"newKey":1}
                ]}""",
            ),
        )!!
        assertEquals("s1", update.sessionId)
        assertTrue(update.truncated)
        assertFalse(update.reset)
        assertEquals(6, update.events.size)
        assertEquals(Entry.User("u1", 1, "hi", false), update.events[0])
        assertEquals(Entry.Tool("u2", 2, "t1", "Bash", "ls"), update.events[3])
        assertEquals(Entry.Result("u4", 4, "t1", "out", true), update.events[4])
        assertTrue((update.events[5] as Entry.User).meta)
    }

    @Test
    fun decodesWaitEndedAndSendResult() {
        val wait = Frames.decodeOrNull<ConversationWait>(
            Json.parseToJsonElement("""{"sessionId":"s1","state":"awaiting-choice","pending":{"tool":"Bash","arg":"touch /tmp/x"}}"""),
        )!!
        assertEquals("awaiting-choice", WaitState.of(wait.state))
        assertEquals(Pending("Bash", "touch /tmp/x"), wait.pending)
        assertNull(Frames.decodeOrNull<ConversationWait>(Json.parseToJsonElement("""{"sessionId":"s1","state":"ready","pending":null}"""))!!.pending)
        assertEquals(WaitState.UNKNOWN, WaitState.of("something-new"))
        assertEquals("window", Frames.decodeOrNull<ConversationEnded>(Json.parseToJsonElement("""{"sessionId":"s1","reason":"window"}"""))!!.reason)
        assertEquals(SendResult.FILLED_NOT_SUBMITTED, Frames.decodeOrNull<SendResult>(Json.parseToJsonElement("""{"outcome":"filled-not-submitted"}"""))!!.outcome)
    }

    @Test
    fun encodesTheConversationPayloads() {
        val watch = parse(Frames.request(1, "conversation.watch", Frames.conversationWatch("s1", null)))["p"]!!.jsonObject
        assertEquals(setOf("sessionId"), watch.keys)
        val resume = parse(Frames.request(1, "conversation.watch", Frames.conversationWatch("s1", "u9")))["p"]!!.jsonObject
        assertEquals("u9", resume["afterUuid"]!!.jsonPrimitive.content)
        val send = parse(Frames.request(1, "conversation.send", Frames.conversationSend("s1", "a\nb")))["p"]!!.jsonObject
        assertEquals("a\nb", send["text"]!!.jsonPrimitive.content)
        assertEquals("s1", parse(Frames.request(1, "sessions.wake", Frames.sessionsWake("s1")))["p"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content)
    }

    // ---- `addresses-and-mid-turn-replies` ----

    private fun result(text: String) = (Frames.decode(text) as ServerFrame.Response).result

    @Test
    fun readsTheAddressesOfAHelloAndAPairAnswer() {
        val hello = result("""{"t":"res","id":1,"ok":true,"r":{"protocol":1,"server":{"name":"spekterm","version":"0.2.5"},"addresses":["192.168.50.22","fd7a::1","desk.ts.net"],"later":{"x":1}}}""")
        assertEquals(listOf("192.168.50.22", "fd7a::1", "desk.ts.net"), Frames.addresses(hello))
        // Unknown fields, `addresses` included, do not stop the typed answer from decoding.
        assertEquals(1, Frames.decodeOrNull<HelloResult>(hello!!)!!.protocol)
        val pair = result("""{"t":"res","id":1,"ok":true,"r":{"protocol":1,"server":{},"addresses":["10.0.0.2"],"deviceId":"d","deviceSecret":"s","later":true}}""")
        assertEquals(listOf("10.0.0.2"), Frames.addresses(pair))
        assertEquals("d", Frames.decodeOrNull<PairResult>(pair!!)!!.deviceId)
    }

    @Test
    fun anAnswerWithoutAddressesHasNoList() {
        assertNull(Frames.addresses(result("""{"t":"res","id":1,"ok":true,"r":{"protocol":1,"server":{}}}""")))
        assertNull(Frames.addresses(result("""{"t":"res","id":1,"ok":true,"r":{"protocol":1,"addresses":"10.0.0.2"}}""")))
        assertNull(Frames.addresses(null))
        assertEquals(emptyList<String>(), Frames.addresses(result("""{"t":"res","id":1,"ok":true,"r":{"addresses":[]}}""")))
    }

    @Test
    fun oddAddressesAreLeftOutAndTheRestKept() {
        val r = result("""{"t":"res","id":1,"ok":true,"r":{"protocol":1,"addresses":[" 192.168.1.5 ",7,null,"","foo bar","[fd7a::1]","fd7a::1","fe80::1%wlan0","host:8080","desk.ts.net"]}}""")
        assertEquals(listOf("192.168.1.5", "fd7a::1", "desk.ts.net"), Frames.addresses(r))
        // An odd element does not lose the answer.
        assertEquals(1, Frames.decodeOrNull<HelloResult>(r!!)!!.protocol)
    }

    @Test
    fun readsAnAddressesChangedEventAndIgnoresUnknownEventsAndFields() {
        val changed = Frames.decode("""{"t":"evt","k":"addresses.changed","d":{"addresses":["10.0.0.3"],"reason":"later"}}""") as ServerFrame.Event
        assertEquals("addresses.changed", changed.kind)
        assertEquals(listOf("10.0.0.3"), Frames.addresses(changed.data))
        val unknown = Frames.decode("""{"t":"evt","k":"something.new","d":{"x":[1,2]},"extra":1}""")
        assertTrue(unknown is ServerFrame.Event)
        assertEquals("something.new", (unknown as ServerFrame.Event).kind)
        assertNull(Frames.decode("""{"t":"later","k":"x"}"""))
    }
}
