package com.spekterm.android.desktop

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.net.Fixture
import com.spekterm.android.net.Frames
import com.spekterm.android.net.Operations
import com.spekterm.android.net.RemoteClient
import com.spekterm.android.net.RemoteConnection
import com.spekterm.android.net.WaitState
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** `Desktop` over a real pinned connection to a scripted desktop on loopback (design D9). */
@RunWith(AndroidJUnit4::class)
class DesktopTest {
    private val server = Fixture.server()
    private val pump = CoroutineScope(Dispatchers.IO + Job())

    /** A desktop whose answers the test scripts per method; records every request. */
    private class Scripted : WebSocketListener() {
        data class Req(val id: Long, val method: String, val params: JsonObject)

        @Volatile var socket: WebSocket? = null
        val requests = CopyOnWriteArrayList<Req>()
        var handle: (Req, WebSocket) -> Unit = { req, ws -> ws.send(ok(req.id, "{}")) }

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            socket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = Frames.json.parseToJsonElement(text).jsonObject
            val req = Req((frame["id"] as JsonPrimitive).content.toLong(), (frame["m"] as JsonPrimitive).content, frame["p"]!!.jsonObject)
            requests += req
            handle(req, webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        fun response(): MockResponse = MockResponse.Builder().webSocketUpgrade(this).build()

        fun count(method: String) = requests.count { it.method == method }
    }

    companion object {
        fun ok(id: Long, result: String) = """{"t":"res","id":$id,"ok":true,"r":$result}"""
        fun err(id: Long, code: String) = """{"t":"res","id":$id,"ok":false,"e":{"code":"$code","message":"m"}}"""
        fun evt(kind: String, data: String) = """{"t":"evt","k":"$kind","d":$data}"""
        fun list(running: Boolean = true, window: Boolean = true, present: Boolean = true): String {
            val sessions = if (present && window) """[{"id":"s1","folderId":null,"target":"claude","label":"A","running":$running,"wait":"ready"}]""" else "[]"
            return """{"window":$window,"folders":[],"sessions":$sessions}"""
        }
        fun user(uuid: String, text: String) = """{"kind":"user","uuid":"$uuid","at":1,"text":"$text","meta":false}"""
        fun update(vararg entries: String, reset: Boolean) =
            evt("conversation.update", """{"sessionId":"s1","status":"ok","events":[${entries.joinToString(",")}],"truncated":false,"reset":$reset}""")
        fun wait(state: String) = evt("conversation.wait", """{"sessionId":"s1","state":"$state","pending":null}""")
    }

    @Before fun start() = runBlocking {
        Desktop.resetForTest()
        server.start()
    }

    @After fun stop() {
        pump.cancel()
        server.close()
    }

    /** Connects to the scripted desktop and feeds Desktop as the service does. */
    private suspend fun connect(desktop: Scripted, operations: Set<String> = emptySet()): RemoteConnection {
        server.enqueue(desktop.response())
        val connection = (RemoteClient.open("127.0.0.1", server.port, Fixture.fingerprint) as RemoteClient.OpenResult.Open).connection
        Desktop.attach(connection, operations)
        pump.launch {
            for (event in connection.events) Desktop.onEvent(connection, event)
            Desktop.detach(connection)
        }
        return connection
    }

    private suspend fun until(predicate: (Conversation) -> Boolean): Conversation =
        withTimeout(5_000) { Desktop.conversation.first { it != null && predicate(it) }!! }

    private fun Scripted.standard(onWatch: (Scripted.Req, WebSocket) -> Unit = { req, ws -> ws.send(wait("ready")); ws.send(update(user("u1", "hi"), reset = true)); ws.send(ok(req.id, """{"sessionId":"s1"}""")) }) {
        handle = { req, ws ->
            when (req.method) {
                "sessions.list" -> ws.send(ok(req.id, list()))
                "conversation.watch" -> onWatch(req, ws)
                else -> ws.send(ok(req.id, "{}"))
            }
        }
    }

    @Test
    fun eventsSentBeforeTheWatchAnswerAreApplied() = runBlocking<Unit> {
        val desktop = Scripted().apply { standard() }
        connect(desktop)
        Desktop.focus("o1", "s1")
        val c = until { it.watch == Watch.Watching && it.entries.isNotEmpty() }
        assertEquals(WaitState.READY, c.wait)
        assertEquals(listOf("u1"), c.entries.map { it.uuid })
    }

    @Test
    fun anOldUpdateRedeliveringHeldEntriesShowsNoDuplicates() = runBlocking<Unit> {
        val desktop = Scripted().apply { standard() }
        connect(desktop)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching && it.entries.isNotEmpty() }
        desktop.socket!!.send(update(user("u1", "hi"), user("u2", "more"), reset = false))
        val c = until { it.entries.size >= 2 }
        delay(300)
        assertEquals(listOf("u1", "u2"), Desktop.conversation.value!!.entries.map { it.uuid })
        assertEquals(c.entries.size, Desktop.conversation.value!!.entries.size)
    }

    @Test
    fun aListBeforeTheWatchAnswerSendsNoSecondWatch() = runBlocking<Unit> {
        val desktop = Scripted().apply {
            standard { req, ws ->
                ws.send(evt("sessions.changed", list()))
                ws.send(wait("busy"))
                Thread.sleep(300)
                ws.send(ok(req.id, """{"sessionId":"s1"}"""))
            }
        }
        connect(desktop)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        delay(300)
        assertEquals(1, desktop.count("conversation.watch"))
    }

    @Test
    fun aReconnectAppliesTheNewListAndResumesFromTheLastEntry() = runBlocking<Unit> {
        val first = Scripted().apply { standard() }
        val connection = connect(first)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching && it.entries.isNotEmpty() }
        connection.cancel()
        until { it.phase == Phase.DISCONNECTED }
        val second = Scripted().apply {
            standard { req, ws ->
                ws.send(wait("ready"))
                ws.send(update(user("u2", "new"), reset = false))
                ws.send(ok(req.id, """{"sessionId":"s1"}"""))
            }
        }
        connect(second)
        val c = until { it.watch == Watch.Watching && it.entries.size == 2 }
        assertEquals("u1", (second.requests.first { it.method == "conversation.watch" }.params["afterUuid"] as JsonPrimitive).content)
        assertEquals(listOf("u1", "u2"), c.entries.map { it.uuid })
        assertTrue(Desktop.sessions.value.current)
    }

    @Test
    fun sendOutcomesReachTheReplyState() = runBlocking<Unit> {
        var answer = """{"outcome":"submitted"}"""
        var error: String? = null
        val desktop = Scripted().apply {
            standard()
            val watchHandle = handle
            handle = { req, ws ->
                if (req.method == "conversation.send") ws.send(error?.let { err(req.id, it) } ?: ok(req.id, answer)) else watchHandle(req, ws)
            }
        }
        connect(desktop)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching && it.wait == WaitState.READY }
        assertTrue(Desktop.send("s1", "hello"))
        until { it.replies.singleOrNull()?.state == Reply.State.SUBMITTED }
        desktop.socket!!.send(update(user("u9", "hello"), reset = false))
        until { it.replies.isEmpty() }

        answer = """{"outcome":"filled-not-submitted"}"""
        assertTrue(Desktop.send("s1", "typed"))
        until { it.replies.singleOrNull()?.state == Reply.State.NOT_SUBMITTED }

        error = "WAIT_STATE"
        assertTrue(Desktop.send("s1", "refused"))
        val c = until { it.draft == "refused" }
        assertEquals(1, c.replies.size)

        error = "INTERNAL"
        assertTrue(Desktop.send("s1", "unknown"))
        until { it.replies.any { r -> r.state == Reply.State.MAY_NOT_HAVE_BEEN_SENT } }
    }

    @Test
    fun theWindowEndingThenComingBackRewatchesOrShowsDormant() = runBlocking<Unit> {
        val desktop = Scripted().apply { standard() }
        connect(desktop)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        desktop.socket!!.send(evt("conversation.ended", """{"sessionId":"s1","reason":"window"}"""))
        desktop.socket!!.send(evt("sessions.changed", list(window = false)))
        until { it.phase == Phase.WINDOW_GONE }
        // The window is back, and the reload stopped the session: dormant, not "no window".
        desktop.socket!!.send(evt("sessions.changed", list(running = false)))
        val dormant = until { it.phase != Phase.WINDOW_GONE }
        assertEquals(Screen.Dormant, Screen.of(true, false, Desktop.sessions.value, dormant, "s1"))
        // Woken on the desktop: watched again.
        desktop.socket!!.send(evt("sessions.changed", list(running = true)))
        until { it.watch == Watch.Watching }
        assertEquals(2, desktop.count("conversation.watch"))
    }

    @Test
    fun aSessionStoppingUnwatchesAndStartingRewatches() = runBlocking<Unit> {
        val desktop = Scripted().apply { standard() }
        connect(desktop)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        desktop.socket!!.send(evt("sessions.changed", list(running = false)))
        until { it.watch == Watch.Idle }
        withTimeout(5_000) { while (desktop.count("conversation.unwatch") == 0) delay(20) }
        desktop.socket!!.send(evt("sessions.changed", list(running = true)))
        until { it.watch == Watch.Watching }
        assertEquals(2, desktop.count("conversation.watch"))
    }

    @Test
    fun onlyTheOwnerUnfocusesAndLeavingUnwatches() = runBlocking<Unit> {
        val desktop = Scripted().apply { standard() }
        connect(desktop)
        Desktop.focus("old", "s1")
        Desktop.focus("new", "s1")
        until { it.watch == Watch.Watching }
        Desktop.unfocus("old")
        delay(300)
        assertEquals(0, desktop.count("conversation.unwatch"))
        assertEquals("s1", Desktop.visibleSession)
        Desktop.unfocus("new")
        withTimeout(5_000) { while (desktop.count("conversation.unwatch") == 0) delay(20) }
        assertNull(Desktop.visibleSession)
    }

    @Test
    fun anEventOfAnEndedConnectionIsIgnored() = runBlocking<Unit> {
        val first = Scripted().apply { standard() }
        val old = connect(first)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        val second = Scripted().apply { standard() }
        connect(second)
        // The old connection's late frame must not change what the new one shows.
        Desktop.onEvent(old, com.spekterm.android.net.ServerFrame.Event("sessions.changed", Frames.json.parseToJsonElement(list(present = false)), seq = 99))
        delay(300)
        assertTrue(Desktop.sessions.value.list!!.sessions.any { it.id == "s1" })
    }

    // ---- Starting a session (`folders-and-new-sessions` design D6–D7) ----

    private fun listWith(id: String, running: Boolean) =
        """{"window":true,"folders":[],"sessions":[{"id":"$id","folderId":null,"target":"claude","label":"N","running":$running}]}"""

    /** A desktop that lists nothing and answers `sessions.create` with [onCreate]. */
    private fun creating(onCreate: (Scripted.Req, WebSocket) -> Unit) = Scripted().apply {
        handle = { req, ws ->
            when (req.method) {
                "sessions.list" -> ws.send(ok(req.id, """{"window":true,"folders":[],"sessions":[]}"""))
                "sessions.create" -> onCreate(req, ws)
                else -> ws.send(ok(req.id, "{}"))
            }
        }
    }

    private suspend fun creationUntil(predicate: (Creation?) -> Boolean): Creation? =
        withTimeout(5_000) { Desktop.creation.first(predicate) }

    @Test
    fun anAnswerThenAListHoldingItRunningOpensIt() = runBlocking<Unit> {
        val desktop = creating { req, ws ->
            ws.send(ok(req.id, """{"sessionId":"n1"}"""))
            pump.launch {
                delay(200)
                ws.send(evt("sessions.changed", listWith("n1", running = true)))
            }
        }
        connect(desktop)
        Desktop.setListOnScreen(true)
        Desktop.create(null, "Global")
        assertEquals(Creation.Opened("n1", "Global", autoOpen = true), creationUntil { it is Creation.Opened })
        val sent = desktop.requests.single { it.method == "sessions.create" }.params
        assertEquals(kotlinx.serialization.json.JsonNull, sent["folderId"])
        assertEquals("claude", (sent["target"] as JsonPrimitive).content)
    }

    @Test
    fun aListBeforeTheAnswerOpensItWithoutWaiting() = runBlocking<Unit> {
        val desktop = creating { req, ws ->
            ws.send(evt("sessions.changed", listWith("n1", running = true)))
            ws.send(ok(req.id, """{"sessionId":"n1"}"""))
        }
        connect(desktop)
        Desktop.createWaitMs = 60_000
        Desktop.create("f1", "alpha")
        assertEquals(Creation.Opened("n1", "alpha", autoOpen = false), creationUntil { it is Creation.Opened })
    }

    @Test
    fun aListedButNotRunningSessionOpensOnlyOnceRunning() = runBlocking<Unit> {
        val desktop = creating { req, ws ->
            ws.send(evt("sessions.changed", listWith("n1", running = false)))
            ws.send(ok(req.id, """{"sessionId":"n1"}"""))
        }
        connect(desktop)
        Desktop.create("f1", "alpha")
        delay(500)
        // Waiting for its list is not a failure.
        assertEquals(Creation.Starting("alpha"), Desktop.creation.value)
        desktop.socket!!.send(evt("sessions.changed", listWith("n1", running = true)))
        assertTrue(creationUntil { it is Creation.Opened } is Creation.Opened)
    }

    @Test
    fun noListInTimeIsUnlisted() = runBlocking<Unit> {
        val desktop = creating { req, ws -> ws.send(ok(req.id, """{"sessionId":"n1"}""")) }
        connect(desktop)
        Desktop.createWaitMs = 500
        Desktop.create("f1", "alpha")
        assertEquals(Creation.Unlisted("alpha"), creationUntil { it is Creation.Unlisted })
    }

    @Test
    fun aDetachWhileWaitingIsUnlisted() = runBlocking<Unit> {
        val desktop = creating { req, ws -> ws.send(ok(req.id, """{"sessionId":"n1"}""")) }
        val connection = connect(desktop)
        Desktop.createWaitMs = 60_000
        Desktop.create("f1", "alpha")
        creationUntil { it is Creation.Starting }
        delay(300)
        connection.cancel()
        assertEquals(Creation.Unlisted("alpha"), creationUntil { it is Creation.Unlisted })
    }

    @Test
    fun aDetachBeforeTheAnswerIsUnknownAndNotRetried() = runBlocking<Unit> {
        val first = creating { _, _ -> }
        val connection = connect(first)
        Desktop.create("f1", "alpha")
        withTimeout(5_000) { while (first.count("sessions.create") == 0) delay(20) }
        connection.cancel()
        assertEquals(Creation.Failed("alpha", Creation.Reason.UNKNOWN), creationUntil { it is Creation.Failed })
        val second = creating { req, ws -> ws.send(ok(req.id, """{"sessionId":"n2"}""")) }
        connect(second)
        withTimeout(5_000) { Desktop.sessions.first { it.current } }
        delay(500)
        assertEquals(0, second.count("sessions.create"))
    }

    @Test
    fun aSecondCreateWhileStartingSendsNothing() = runBlocking<Unit> {
        val desktop = creating { _, _ -> }
        connect(desktop)
        Desktop.create("f1", "alpha")
        creationUntil { it is Creation.Starting }
        Desktop.create("f2", "beta")
        delay(500)
        assertEquals(1, desktop.count("sessions.create"))
        assertEquals(Creation.Starting("alpha"), Desktop.creation.value)
    }

    @Test
    fun clearingDuringACreateWritesNothingAfterwards() = runBlocking<Unit> {
        var answer: (() -> Unit)? = null
        val desktop = creating { req, ws -> answer = { ws.send(ok(req.id, """{"sessionId":"n1"}""")) } }
        connect(desktop)
        Desktop.create("f1", "alpha")
        withTimeout(5_000) { while (answer == null) delay(20) }
        Desktop.clear()
        delay(300)
        answer!!()
        delay(500)
        assertNull(Desktop.creation.value)
    }

    @Test
    fun theDesktopsRefusalIsShownWithItsReason() = runBlocking<Unit> {
        val desktop = creating { req, ws -> ws.send("""{"t":"res","id":${req.id},"ok":false,"e":{"code":"CREATE_FAILED","message":"folder gone"}}""") }
        connect(desktop)
        Desktop.create("f1", "alpha")
        assertEquals(Creation.Failed("alpha", Creation.Reason.CREATE_FAILED, "folder gone"), creationUntil { it is Creation.Failed })
    }

    // ---- `prompts-attachments-rename` ----

    private val all = setOf(
        Operations.ANSWER, Operations.ATTACH, Operations.RENAME, Operations.HIBERNATE, Operations.CLOSE, Operations.WAKE,
    )

    private fun promptWait(id: String) = evt(
        "conversation.wait",
        """{"sessionId":"s1","state":"awaiting-choice","pending":{"tool":"Bash","arg":"ls"},"prompt":{"id":"$id","kind":"question","answerable":true,"open":1,"questions":[{"question":"q","header":"h","multiSelect":true,"options":[{"label":"a"},{"label":"b"}]}]}}""",
    )

    /** A standard desktop that answers [method] with [answer] (or nothing when it returns null). */
    private fun Scripted.answering(method: String, answer: (Scripted.Req, WebSocket) -> Unit) {
        standard { req, ws ->
            ws.send(promptWait("p1"))
            ws.send(update(user("u1", "hi"), reset = true))
            ws.send(ok(req.id, """{"sessionId":"s1"}"""))
        }
        val base = handle
        handle = { req, ws -> if (req.method == method) answer(req, ws) else base(req, ws) }
    }

    @Test
    fun operationsAreKeptAcrossADetachAndForgottenByClear() = runBlocking<Unit> {
        val connection = connect(Scripted().apply { standard() }, all)
        assertEquals(all, Desktop.operations.value)
        connection.cancel()
        withTimeout(5_000) { Desktop.connected.first { !it } }
        assertEquals(all, Desktop.operations.value)
        Desktop.clear()
        withTimeout(5_000) { Desktop.operations.first { it.isEmpty() } }
    }

    @Test
    fun anAnswerNamesThePromptAndRecordsItsOutcome() = runBlocking<Unit> {
        var reply: (() -> Unit)? = null
        val desktop = Scripted().apply { answering("conversation.answer") { req, ws -> reply = { ws.send(ok(req.id, """{"outcome":"partial"}""")) } } }
        connect(desktop, all)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching && it.prompt?.id == "p1" }
        assertFalse("another prompt id sends nothing", Desktop.answer("s1", "p9", "deny"))
        assertTrue(Desktop.answer("s1", "p1", "answer", listOf(listOf(0, 1))))
        assertFalse("one answer in flight per prompt", Desktop.answer("s1", "p1", "deny"))
        withTimeout(5_000) { while (reply == null) delay(20) }
        val sent = desktop.requests.single { it.method == "conversation.answer" }.params
        assertEquals("p1", (sent["promptId"] as JsonPrimitive).content)
        assertEquals("answer", (sent["decision"] as JsonPrimitive).content)
        assertEquals("[[0,1]]", sent["selections"].toString())
        reply!!()
        until { it.answer?.outcome == AnswerOutcome.Partial }
    }

    @Test
    fun aDetachDuringAnAnswerIsUnknown() = runBlocking<Unit> {
        val desktop = Scripted().apply { answering("conversation.answer") { _, _ -> } }
        val connection = connect(desktop, all)
        Desktop.focus("o1", "s1")
        until { it.prompt?.id == "p1" && it.watch == Watch.Watching }
        assertTrue(Desktop.answer("s1", "p1", "deny"))
        withTimeout(5_000) { while (desktop.count("conversation.answer") == 0) delay(20) }
        connection.cancel()
        until { it.answer?.outcome == AnswerOutcome.Unknown }
    }

    @Test
    fun leavingWhileAnAnswerIsInFlightStillRecordsItsOutcome() = runBlocking<Unit> {
        var reply: (() -> Unit)? = null
        val desktop = Scripted().apply { answering("conversation.answer") { req, ws -> reply = { ws.send(ok(req.id, """{"outcome":"sent"}""")) } } }
        connect(desktop, all)
        Desktop.focus("o1", "s1")
        until { it.prompt?.id == "p1" && it.watch == Watch.Watching }
        assertTrue(Desktop.answer("s1", "p1", "deny"))
        withTimeout(5_000) { while (reply == null) delay(20) }
        Desktop.unfocus("o1")
        until { it.watch == Watch.Idle }
        reply!!()
        until { it.answer?.outcome == AnswerOutcome.Done }
        // Back on screen with a new prompt: it can be answered.
        desktop.handle.let { base -> desktop.handle = { req, ws -> if (req.method == "conversation.watch") { ws.send(promptWait("p2")); ws.send(ok(req.id, """{"sessionId":"s1"}""")) } else base(req, ws) } }
        Desktop.focus("o2", "s1")
        until { it.prompt?.id == "p2" && it.watch == Watch.Watching }
        assertTrue(Desktop.answer("s1", "p2", "deny"))
    }

    // ---- Attachments ----

    private val context get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    private fun fileOf(bytes: Int): File = File(context.cacheDir, "test-" + System.nanoTime()).apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

    private fun Scripted.Req.long(key: String) = (params[key] as JsonPrimitive).content.toLong()

    private fun Scripted.Req.decoded() = java.util.Base64.getDecoder().decode((params["data"] as JsonPrimitive).content).size

    /** Answers chunks as a desktop holding upload "a1" would. */
    private fun putOk(req: Scripted.Req): String {
        val received = req.long("offset") + req.decoded()
        return ok(req.id, """{"attachmentId":"a1","received":$received,"complete":${received == req.long("size")}}""")
    }

    private suspend fun attach(file: File, name: String = "a.bin"): Long {
        val id = Desktop.addAttachments("s1", listOf(name)).single()
        Desktop.prepared(id, file, name, file.length(), converted = false)
        return id
    }

    @Test
    fun aFileUploadsInChunksWithTheIdFromTheSecondOn() = runBlocking<Unit> {
        val desktop = Scripted().apply { answering("attachments.put") { req, ws -> ws.send(putOk(req)) } }
        connect(desktop, all)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        attach(fileOf(150 * 1024))
        until { it.attachments.singleOrNull()?.state is AttachmentState.Ready }
        val puts = desktop.requests.filter { it.method == "attachments.put" }
        assertEquals(listOf(0L, 65536L, 131072L), puts.map { it.long("offset") })
        assertEquals(listOf(65536, 65536, 22528), puts.map { it.decoded() })
        assertNull(puts[0].params["attachmentId"])
        assertEquals(listOf("a1", "a1"), puts.drop(1).map { (it.params["attachmentId"] as JsonPrimitive).content })
    }

    @Test
    fun anUploadResumesFromWhereTheDesktopSaysAfterAReconnect() = runBlocking<Unit> {
        var drop = true
        val first = Scripted().apply {
            answering("attachments.put") { req, ws ->
                // Takes the first chunk, then the connection drops before the second is answered.
                if (req.long("offset") == 0L) ws.send(putOk(req)) else if (drop) { drop = false; ws.cancel() }
            }
        }
        connect(first, all)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        attach(fileOf(150 * 1024))
        until { (it.attachments.single().state as? AttachmentState.Uploading)?.unanswered == 1 }
        val second = Scripted().apply {
            answering("attachments.put") { req, ws ->
                if (req.long("offset") == 65536L) {
                    ws.send("""{"t":"res","id":${req.id},"ok":false,"e":{"code":"BAD_PARAMS","message":"m","detail":{"received":131072}}}""")
                } else {
                    ws.send(putOk(req))
                }
            }
        }
        connect(second, all)
        until { it.attachments.single().state is AttachmentState.Ready }
        assertEquals(listOf(65536L, 131072L), second.requests.filter { it.method == "attachments.put" }.map { it.long("offset") })
    }

    @Test
    fun notFoundMidUploadStartsAgainWithoutAnId() = runBlocking<Unit> {
        var lost = false
        val desktop = Scripted().apply {
            answering("attachments.put") { req, ws ->
                if (req.long("offset") == 65536L && !lost) {
                    lost = true
                    ws.send(err(req.id, "NOT_FOUND"))
                } else {
                    ws.send(putOk(req))
                }
            }
        }
        connect(desktop, all)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        attach(fileOf(150 * 1024))
        until { it.attachments.single().state is AttachmentState.Ready }
        val puts = desktop.requests.filter { it.method == "attachments.put" }
        assertEquals(listOf(0L, 65536L, 0L, 65536L, 131072L), puts.map { it.long("offset") })
        assertNull(puts[2].params["attachmentId"])
    }

    @Test
    fun tooManyFailsWithTheLimit() = runBlocking<Unit> {
        val desktop = Scripted().apply {
            answering("attachments.put") { req, ws -> ws.send("""{"t":"res","id":${req.id},"ok":false,"e":{"code":"TOO_MANY","message":"m","detail":{"limit":10}}}""") }
        }
        connect(desktop, all)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        attach(fileOf(10))
        assertEquals(AttachmentState.Failed("TOO_MANY", 10), until { it.attachments.single().state is AttachmentState.Failed }.attachments.single().state)
    }

    @Test
    fun focusingAnotherSessionDropsTheAttachmentAndItsFile() = runBlocking<Unit> {
        val desktop = Scripted().apply { answering("attachments.put") { _, _ -> } }
        connect(desktop, all)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        val file = fileOf(10)
        attach(file)
        until { it.attachments.isNotEmpty() }
        Desktop.focus("o2", "s2")
        withTimeout(5_000) { while (file.exists()) delay(20) }
    }

    @Test
    fun aSendCarriesTheIdsAndIsConfirmedByWhatTheDesktopWrote() = runBlocking<Unit> {
        // Kept free of newlines and quotes, which the scripted frames do not escape.
        val written = "look (The user attached 1 file from their phone; read it with the Read tool: `/s/a1-a.bin`)"
        val desktop = Scripted().apply {
            standard()
            val base = handle
            handle = { req, ws ->
                when (req.method) {
                    "attachments.put" -> ws.send(putOk(req))
                    "conversation.send" -> ws.send(ok(req.id, """{"outcome":"submitted","text":"$written"}"""))
                    else -> base(req, ws)
                }
            }
        }
        connect(desktop, all)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching && it.wait == WaitState.READY }
        val file = fileOf(10)
        attach(file)
        until { it.attachments.singleOrNull()?.state is AttachmentState.Ready }
        assertTrue(Desktop.send("s1", "look"))
        until { it.replies.singleOrNull()?.state == Reply.State.SUBMITTED }
        val sent = desktop.requests.single { it.method == "conversation.send" }.params
        assertEquals("[\"a1\"]", sent["attachments"].toString())
        withTimeout(5_000) { while (file.exists()) delay(20) }
        desktop.socket!!.send(update(user("u9", written), reset = false))
        until { it.replies.isEmpty() }
    }

    // ---- The session menu ----

    @Test
    fun renameHibernateAndCloseSendTheirPayloads() = runBlocking<Unit> {
        val desktop = Scripted().apply {
            standard()
            val base = handle
            handle = { req, ws ->
                when (req.method) {
                    "sessions.rename" -> ws.send(err(req.id, "NO_WINDOW"))
                    "sessions.hibernate" -> ws.send(
                        if (req.params["force"] == null) """{"t":"res","id":${req.id},"ok":false,"e":{"code":"SESSION_BUSY","message":"m","detail":{"reason":"working"}}}""" else ok(req.id, """{"sessionId":"s1"}"""),
                    )
                    "sessions.close" -> ws.send(ok(req.id, """{"sessionId":"s1"}"""))
                    else -> base(req, ws)
                }
            }
        }
        connect(desktop, all)
        withTimeout(5_000) { Desktop.sessions.first { it.current } }
        assertEquals("NO_WINDOW", Desktop.rename("s1", "")!!.code)
        assertEquals("", (desktop.requests.single { it.method == "sessions.rename" }.params["name"] as JsonPrimitive).content)
        val busy = Desktop.hibernate("s1", force = false)!!
        assertEquals("SESSION_BUSY", busy.code)
        assertEquals("working", busy.detailString("reason"))
        assertNull(Desktop.hibernate("s1", force = true))
        val hibernates = desktop.requests.filter { it.method == "sessions.hibernate" }.map { it.params.keys }
        assertEquals(listOf(setOf("sessionId"), setOf("sessionId", "force")), hibernates)
        assertNull(Desktop.close("s1", force = false))
        assertEquals(setOf("sessionId"), desktop.requests.single { it.method == "sessions.close" }.params.keys)
    }

    // ---- `close-offer` ----

    @Test
    fun keepSendsItsPayloadAndTheOfferFollowsTheList() = runBlocking<Unit> {
        assertEquals(Desktop.NOT_CONNECTED, Desktop.keep("s1")!!.code)
        var refuse = false
        val desktop = Scripted().apply {
            standard()
            val base = handle
            handle = { req, ws ->
                when (req.method) {
                    "sessions.keep" -> ws.send(if (refuse) err(req.id, "TIMEOUT") else ok(req.id, """{"sessionId":"s1"}"""))
                    else -> base(req, ws)
                }
            }
        }
        connect(desktop, all + Operations.KEEP)
        withTimeout(5_000) { Desktop.sessions.first { it.current } }
        val offered = """{"window":true,"folders":[],"sessions":[{"id":"s1","folderId":null,"target":"claude","label":"A","running":true,"wait":"ready","closeOffered":true}]}"""
        desktop.socket!!.send(evt("sessions.changed", offered))
        withTimeout(5_000) { Desktop.sessions.first { it.list?.sessions?.singleOrNull()?.closeOffered == true } }
        assertNull(Desktop.keep("s1"))
        assertEquals(setOf("sessionId"), desktop.requests.single { it.method == "sessions.keep" }.params.keys)
        // Keeping changes nothing held: the list that drops the mark does.
        assertTrue(Desktop.sessions.value.list!!.sessions.single().closeOffered)
        desktop.socket!!.send(evt("sessions.changed", list()))
        withTimeout(5_000) { Desktop.sessions.first { it.list?.sessions?.singleOrNull()?.closeOffered == false } }
        refuse = true
        assertEquals("TIMEOUT", Desktop.keep("s1")!!.code)
    }

    @Test
    fun hibernatedIsDormantNotWindowGoneAndClosedDropsTheAttachments() = runBlocking<Unit> {
        val desktop = Scripted().apply { answering("attachments.put") { req, ws -> ws.send(putOk(req)) } }
        connect(desktop, all)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        desktop.socket!!.send(evt("conversation.ended", """{"sessionId":"s1","reason":"hibernated"}"""))
        val hibernated = until { it.watch == Watch.Idle }
        assertTrue(hibernated.notRunning)
        assertEquals(Phase.IDLE, hibernated.phase)

        val file = fileOf(10)
        attach(file)
        until { it.attachments.singleOrNull()?.state is AttachmentState.Ready }
        // A list without the session arrives before the end: the attachments go all the same.
        desktop.socket!!.send(evt("sessions.changed", list(present = false)))
        until { it.closed && it.attachments.isEmpty() }
        withTimeout(5_000) { while (file.exists()) delay(20) }
    }

    @Test
    fun closedEndsTheWatchAndDropsTheAttachments() = runBlocking<Unit> {
        val desktop = Scripted().apply { answering("attachments.put") { req, ws -> ws.send(putOk(req)) } }
        connect(desktop, all)
        Desktop.focus("o1", "s1")
        until { it.watch == Watch.Watching }
        val file = fileOf(10)
        attach(file)
        until { it.attachments.singleOrNull()?.state is AttachmentState.Ready }
        desktop.socket!!.send(evt("conversation.ended", """{"sessionId":"s1","reason":"closed"}"""))
        val closed = until { it.closed }
        assertEquals(Screen.Closed, Screen.of(true, false, Desktop.sessions.value, closed, "s1"))
        withTimeout(5_000) { while (file.exists()) delay(20) }
    }
}
