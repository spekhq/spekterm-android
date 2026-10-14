package com.spekterm.android.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteClientTest {
    private val server = Fixture.server()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private suspend fun open(): RemoteConnection =
        (RemoteClient.open("127.0.0.1", server.port, Fixture.fingerprint) as RemoteClient.OpenResult.Open).connection

    @Test
    fun answersARequestDeliversAnEventAndReportsTheCloseCode() = runBlocking<Unit> {
        val desktop = DesktopSocket { method, id ->
            if (method == "attention.watch") """{"t":"res","id":$id,"ok":true,"r":{"events":[],"complete":true}}""" else null
        }
        server.enqueue(desktop.response())
        val connection = open()
        val answer = connection.call("attention.watch", Frames.attentionWatch(null))
        assertEquals(true, Frames.decodeOrNull<WatchAnswer>(answer.result!!)!!.complete)

        desktop.socket!!.send(
            """{"t":"evt","k":"attention","d":{"pos":{"epoch":"e","seq":1},"kind":"waiting","at":1,"title":"T","suppressed":false}}""",
        )
        val event = withTimeout(5_000) { connection.events.receive() }
        assertEquals("attention", event.kind)

        desktop.socket!!.close(4004, "revoked")
        val info = withTimeout(5_000) { connection.closed.await() }
        assertEquals(4004, info.code)
    }

    @Test
    fun surfacesAThrottledFrameWithANullIdAndClose4030() = runBlocking<Unit> {
        val desktop = DesktopSocket { _, _ -> null }
        desktop.onOpen = { socket ->
            socket.send("""{"t":"res","id":null,"ok":false,"e":{"code":"THROTTLED","message":"too many failed attempts"}}""")
            socket.close(4030, "throttled")
        }
        server.enqueue(desktop.response())
        val connection = open()
        val info = withTimeout(5_000) { connection.closed.await() }
        assertEquals(4030, info.code)
        assertEquals(RemoteError.THROTTLED, info.error?.code)
    }

    @Test
    fun aRequestWithNoAnswerEndsTheConnectionInsteadOfCancellingTheCaller() = runBlocking<Unit> {
        // The desktop accepts the upgrade and then never answers (asleep, hung): the caller must see a closed
        // connection it can retry, not a cancellation that silently ends its coroutine.
        server.enqueue(DesktopSocket { _, _ -> null }.response())
        val connection = open()
        try {
            connection.call("hello", Frames.hello("d", "s"), timeoutMs = 500)
            fail("an unanswered request returned")
        } catch (e: ConnectionClosedException) {
            assertTrue(e.info.failure is RequestTimeoutException)
        }
        assertTrue(connection.closed.isCompleted)
    }

    @Test
    fun surfacesUnsupportedVersionWithTheDesktopsVersions() = runBlocking<Unit> {
        val desktop = DesktopSocket { method, id ->
            if (method == "hello") {
                """{"t":"res","id":$id,"ok":false,"e":{"code":"UNSUPPORTED_VERSION","message":"no","detail":{"supported":[2]}}}"""
            } else {
                null
            }
        }
        server.enqueue(desktop.response())
        val connection = open()
        val answer = connection.call("hello", Frames.hello("d", "s"))
        assertFalse(answer.ok)
        assertEquals(RemoteError.UNSUPPORTED_VERSION, answer.error!!.code)
        assertEquals(listOf(2), Frames.supportedVersions(answer.error))
        connection.close()
        withTimeout(5_000) { connection.closed.await() }
    }
}
