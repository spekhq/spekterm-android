package com.spekterm.android.desktop

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.net.Fixture
import com.spekterm.android.net.Frames
import com.spekterm.android.net.RemoteClient
import com.spekterm.android.net.RemoteConnection
import com.spekterm.android.net.WaitState
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
    private suspend fun connect(desktop: Scripted): RemoteConnection {
        server.enqueue(desktop.response())
        val connection = (RemoteClient.open("127.0.0.1", server.port, Fixture.fingerprint) as RemoteClient.OpenResult.Open).connection
        Desktop.attach(connection)
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
}
