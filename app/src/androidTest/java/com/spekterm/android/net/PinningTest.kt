package com.spekterm.android.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Question 1 of the spike, on the phone's own TLS stack: does it accept spekterm's extension-less certificate
 * when trust is the pinned fingerprint — and refuse everything else before a byte of ours is sent?
 */
@RunWith(AndroidJUnit4::class)
class PinningTest {
    private val server = Fixture.server()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    private fun hello(method: String, id: Long) =
        if (method == "hello") """{"t":"res","id":$id,"ok":true,"r":{"protocol":1,"server":{"name":"fixture","version":"0"}}}""" else null

    @Test
    fun theFixtureHasNoExtensionsAndNoAddress() {
        val cert = Fixture.certificate
        assertEquals(3, cert.version)
        assertNull(cert.subjectAlternativeNames)
        assertTrue(cert.criticalExtensionOIDs.isNullOrEmpty())
        assertTrue(cert.nonCriticalExtensionOIDs.isNullOrEmpty())
    }

    @Test
    fun theRightPinCompletesTheHandshakeAndAFrameRoundTrips() = runBlocking<Unit> {
        val desktop = DesktopSocket(::hello)
        server.enqueue(desktop.response())
        val opened = RemoteClient.open("127.0.0.1", server.port, Fixture.fingerprint)
        val connection = (opened as? RemoteClient.OpenResult.Open)?.connection ?: return@runBlocking fail("not open: $opened")
        val answer = connection.call("hello", Frames.hello("d", "s"))
        assertTrue(answer.ok)
        assertEquals("fixture", Frames.decodeOrNull<HelloResult>(answer.result!!)!!.server.name)
        connection.close()
        withTimeout(5_000) { connection.closed.await() }
    }

    @Test
    fun aPinOffByOneDigitFailsTheHandshakeAndSendsNothing() = runBlocking<Unit> {
        val desktop = DesktopSocket(::hello)
        server.enqueue(desktop.response())
        val opened = RemoteClient.open("127.0.0.1", server.port, Fixture.wrongFingerprint)
        assertTrue("$opened", opened is RemoteClient.OpenResult.PinMismatch)
        // No HTTP request — so no upgrade, no frame, no credential — reached the server.
        assertNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals(0, server.requestCount)
        assertTrue(desktop.received.isEmpty())
    }

    @Test
    fun controlThePlatformsOwnTrustRefusesTheDesktop() {
        // Without the pin, the phone's default trust (CA chain and host name) refuses this certificate: the pin
        // is what makes the connection possible, not a hole in the platform's checks.
        server.enqueue(DesktopSocket(::hello).response())
        val request = Request.Builder().url(RemoteClient.url("127.0.0.1", server.port)).build()
        try {
            OkHttpClient().newCall(request).execute().close()
            fail("the platform's default trust accepted the self-signed certificate")
        } catch (_: SSLException) {
        }
    }

    @Test
    fun controlTheDefaultHostNameVerifierRefusesTheAddress() = runBlocking<Unit> {
        // The pinning trust manager with OkHttp's default host name verifier: a certificate with no SAN fails it
        // for every address (`docs/remote-protocol.md` section 2) — hence the pinning verifier.
        server.enqueue(DesktopSocket(::hello).response())
        val pin = Pin(Fixture.fingerprint)
        val client = OkHttpClient.Builder().sslSocketFactory(pin.sslContext().socketFactory, pin.trustManager).build()
        val request = Request.Builder().url(RemoteClient.url("127.0.0.1", server.port)).build()
        try {
            client.newCall(request).execute().close()
            fail("the default host name verifier accepted a certificate without a SAN")
        } catch (e: SSLException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("not verified", ignoreCase = true))
        }
    }
}
