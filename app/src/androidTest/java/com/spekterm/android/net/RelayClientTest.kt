package com.spekterm.android.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.store.RelayGrant
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The relay path (`phone-relay` 2.3) against a fake team server: a TLS `MockWebServer` playing spekterm-server's
 * `/relay` (answers `relay.open`, then splices the WebSocket to the fake desktop over TCP, as the real server does).
 * Run on the emulator only.
 */
@RunWith(AndroidJUnit4::class)
class RelayClientTest {
    private val desktop = Fixture.server()
    private val team = Fixture.server()
    private val extra = mutableListOf<MockWebServer>()

    @org.junit.Before fun start() {
        desktop.start()
        team.start()
    }

    @After fun stop() {
        desktop.close()
        team.close()
        extra.forEach { it.close() }
    }

    /** The team server's side of one phone leg: records the phone's frames and message sizes. */
    private inner class TeamLeg(
        private val answer: (id: String) -> String?,
        private val answerDelayMs: Long = 0,
        private val desktopPort: () -> Int = { desktop.port },
    ) : WebSocketListener() {
        val texts = CopyOnWriteArrayList<String>()
        val biggest = AtomicInteger(0)
        @Volatile var phone: WebSocket? = null
        @Volatile private var tcp: Socket? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            phone = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            texts += text
            val id = Frames.json.parseToJsonElement(text).let { (it as kotlinx.serialization.json.JsonObject)["id"].toString() }
            thread {
                if (answerDelayMs > 0) Thread.sleep(answerDelayMs)
                val reply = answer(id) ?: return@thread
                if ("\"ok\":true" in reply) splice(webSocket)
                webSocket.send(reply)
            }
        }

        private fun splice(webSocket: WebSocket) {
            val socket = Socket("127.0.0.1", desktopPort())
            tcp = socket
            thread(name = "team-splice") {
                val buffer = ByteArray(16 * 1024)
                try {
                    while (true) {
                        val n = socket.getInputStream().read(buffer)
                        if (n < 0) break
                        webSocket.send(buffer.toByteString(0, n))
                    }
                } catch (_: IOException) {
                }
                webSocket.close(4010, null)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            biggest.accumulateAndGet(bytes.size) { a, b -> maxOf(a, b) }
            try {
                tcp?.getOutputStream()?.write(bytes.toByteArray())
            } catch (_: IOException) {
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            tcp?.close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            tcp?.close()
        }

        fun response(): MockResponse = MockResponse.Builder().webSocketUpgrade(this).build()
    }

    private fun ok(id: String) = """{"t":"res","id":$id,"ok":true,"r":{"protocol":1,"tunnel":"t-1"}}"""

    private fun error(id: String, code: String, detail: String = "") =
        """{"t":"res","id":$id,"ok":false,"e":{"code":"$code","message":"m"$detail}}"""

    private fun grant(fp: String? = Fixture.fingerprint, server: MockWebServer = team, host: String = "127.0.0.1") =
        RelayGrant("wss://$host:${server.port}", fp, "g-1", "the-secret")

    private fun helloDesktop(big: String = ""): DesktopSocket = DesktopSocket { method, id ->
        when (method) {
            "hello" -> """{"t":"res","id":$id,"ok":true,"r":{"protocol":1,"pad":"$big"}}"""
            else -> """{"t":"res","id":$id,"ok":true,"r":{}}"""
        }
    }

    private suspend fun open(grant: RelayGrant, port: Int = desktop.port, base: OkHttpClient = RemoteClient.base) =
        RelayClient.open(grant, Fixture.fingerprint, port, base)

    @Test
    fun theDesktopIsReachedThroughTheRelayWithItsPinAndLargeMessagesPassWhole() = runBlocking<Unit> {
        val big = "x".repeat(1024 * 1024)
        val desk = helloDesktop(big)
        desktop.enqueue(desk.response())
        val leg = TeamLeg(::ok)
        team.enqueue(leg.response())

        val result = withTimeout(30_000) { open(grant()) }
        assertTrue("$result", result is RemoteClient.OpenResult.Open)
        val opened = result as RemoteClient.OpenResult.Open
        assertEquals("127.0.0.1", opened.leg?.host)
        // relay.open carried the grant, and was the only text the phone sent to the team server.
        assertEquals(1, leg.texts.size)
        assertTrue(leg.texts[0].contains("\"m\":\"relay.open\"") && leg.texts[0].contains("the-secret"))

        val hello = withTimeout(20_000) { opened.connection.call("hello", Frames.hello("d", "s")) }
        assertTrue(hello.ok)
        assertTrue(hello.result.toString().length > 1024 * 1024)
        val sent = withTimeout(20_000) { opened.connection.call("probe", Frames.hello("y".repeat(300 * 1024), "s")) }
        assertTrue(sent.ok)
        assertTrue(desk.received.any { it.length > 300 * 1024 })
        assertTrue("biggest ${leg.biggest.get()}", leg.biggest.get() in 1..TunnelSocket.CHUNK)
        // An IP literal inside the tunnel: no server name in the desktop's handshake.
        assertEquals(emptyList<String>(), desktop.takeRequest().handshakeServerNames)
        opened.connection.close()
    }

    @Test
    fun aDirectConnectionByNameSendsTheServerName() = runBlocking<Unit> {
        // The control for the empty list above: the fake desktop does report a name when one is sent.
        desktop.enqueue(helloDesktop().response())
        val result = RemoteClient.open("localhost", desktop.port, Fixture.fingerprint)
        assertTrue("$result", result is RemoteClient.OpenResult.Open)
        assertEquals(listOf("localhost"), desktop.takeRequest().handshakeServerNames)
        (result as RemoteClient.OpenResult.Open).connection.close()
    }

    @Test
    fun anotherDesktopCertificateInsideTheRelayIsTheDesktopsPinMismatch() = runBlocking<Unit> {
        val other = otherServer()
        val desk = helloDesktop()
        other.enqueue(desk.response())
        team.enqueue(TeamLeg(::ok, desktopPort = { other.port }).response())
        val result = withTimeout(30_000) { open(grant(), port = other.port) }
        assertTrue("$result", result is RemoteClient.OpenResult.PinMismatch)
        assertTrue(desk.received.isEmpty())
    }

    @Test
    fun aTeamServerCertificateOtherThanThePinIsRefusedBeforeAnythingIsSent() = runBlocking<Unit> {
        val leg = TeamLeg(::ok)
        team.enqueue(leg.response())
        val result = withTimeout(30_000) { open(grant(fp = Fixture.wrongFingerprint)) }
        assertEquals(RemoteClient.OpenResult.Relayed(RelayOutcome.Certificate(pinned = true)), result)
        assertTrue(leg.texts.isEmpty())
    }

    @Test
    fun withoutAPinTheTeamServerIsCheckedByItsAuthorityAndHostName() = runBlocking<Unit> {
        val root = HeldCertificate.Builder().certificateAuthority(0).build()
        val serverCert = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").build()
        val caServer = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(serverCert).build().sslSocketFactory())
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        extra += caServer
        val trusting = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        val base = OkHttpClient.Builder().sslSocketFactory(trusting.sslSocketFactory(), trusting.trustManager).build()

        desktop.enqueue(helloDesktop().response())
        caServer.enqueue(TeamLeg(::ok).response())
        val accepted = withTimeout(30_000) { open(grant(fp = null, server = caServer, host = "localhost"), base = base) }
        assertTrue("$accepted", accepted is RemoteClient.OpenResult.Open)
        (accepted as RemoteClient.OpenResult.Open).connection.close()

        // The fixture's self-signed certificate is not trusted without a pin.
        val leg = TeamLeg(::ok)
        team.enqueue(leg.response())
        val refused = withTimeout(30_000) { open(grant(fp = null, host = "localhost"), base = base) }
        assertEquals(RemoteClient.OpenResult.Relayed(RelayOutcome.Certificate(pinned = false)), refused)
        assertTrue(leg.texts.isEmpty())
    }

    @Test
    fun theTeamServersAnswersSurfaceAsTheirOutcomes() = runBlocking<Unit> {
        for (code in listOf("RELAY_DISABLED", "GRANT_INVALID", "DESKTOP_REFUSED")) {
            team.enqueue(TeamLeg({ error(it, code) }).response())
            val result = withTimeout(30_000) { open(grant()) }
            assertEquals(code, ((result as RemoteClient.OpenResult.Relayed).outcome as RelayOutcome.Answer).error.code)
        }
        team.enqueue(TeamLeg({ error(it, "RATE_LIMITED", ""","detail":{"retryAt":1234}""") }).response())
        val limited = (withTimeout(30_000) { open(grant()) } as RemoteClient.OpenResult.Relayed).outcome as RelayOutcome.Answer
        assertEquals(1234L, limited.error.detailLong("retryAt"))

        team.enqueue(TeamLeg({ """{"t":"res","id":$it,"ok":true,"r":{"protocol":1}}""" }).response())
        val noTunnel = withTimeout(30_000) { open(grant()) }
        assertTrue("$noTunnel", (noTunnel as RemoteClient.OpenResult.Relayed).outcome is RelayOutcome.ProtocolFault)
    }

    @Test
    fun theAttemptWaitsForTheDesktopForTenSeconds() = runBlocking<Unit> {
        team.enqueue(TeamLeg({ error(it, "DESKTOP_UNAVAILABLE") }, answerDelayMs = 10_000).response())
        val started = System.nanoTime()
        val result = withTimeout(30_000) { open(grant()) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("answered after $elapsedMs ms", elapsedMs >= 10_000)
        assertEquals("DESKTOP_UNAVAILABLE", ((result as RemoteClient.OpenResult.Relayed).outcome as RelayOutcome.Answer).error.code)
    }

    @Test
    fun anUpgradeRefusedWithAStatusIsThatStatus() = runBlocking<Unit> {
        for (status in listOf(404, 503)) {
            team.enqueue(MockResponse.Builder().code(status).build())
            val result = withTimeout(30_000) { open(grant()) }
            assertEquals(RemoteClient.OpenResult.Relayed(RelayOutcome.Http(status)), result)
        }
    }

    @Test
    fun theTeamServersCloseIsKeptApartFromTheDesktops() = runBlocking<Unit> {
        desktop.enqueue(helloDesktop().response())
        val leg = TeamLeg(::ok)
        team.enqueue(leg.response())
        val opened = withTimeout(30_000) { open(grant()) } as RemoteClient.OpenResult.Open
        assertTrue(withTimeout(20_000) { opened.connection.call("hello", Frames.hello("d", "s")) }.ok)

        leg.phone!!.close(4004, "grant revoked")
        val info = withTimeout(10_000) { opened.connection.closed.await() }
        assertNotEquals(4004, info.code)
        assertEquals(4004, opened.leg!!.end!!.code)
        assertFalse(opened.leg!!.end!!.byPhone)
    }

    @Test
    fun theTunnelSocketHasNoDescriptor() {
        val dummy = object : WebSocket {
            override fun request() = throw UnsupportedOperationException()
            override fun queueSize() = 0L
            override fun send(text: String) = false
            override fun send(bytes: ByteString) = false
            override fun close(code: Int, reason: String?) = false
            override fun cancel() = Unit
        }
        try {
            Socket::class.java.getMethod("getFileDescriptor\$").invoke(TunnelSocket(dummy, 1))
            fail("a descriptor")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue("${e.cause}", e.cause is UnsupportedOperationException)
        }
    }

    private fun otherServer(): MockWebServer {
        val held = HeldCertificate.Builder().build()
        return MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
            start()
        }.also { extra += it }
    }
}
