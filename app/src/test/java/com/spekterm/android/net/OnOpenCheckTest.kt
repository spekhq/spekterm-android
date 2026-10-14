package com.spekterm.android.net

import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.tls.HeldCertificate
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The third pin check, on `onOpen`, before the first frame. A real server cannot present one certificate to the
 * trust manager and another to `onOpen`, so the established connection is fabricated here.
 */
class OnOpenCheckTest {
    private val fixture = CertificateFactory.getInstance("X.509")
        .generateCertificate(File("src/androidTest/assets/spekterm-cert.pem").inputStream()) as X509Certificate
    private val pin = File("src/androidTest/assets/spekterm-fingerprint.txt").readText().trim()

    private class RecordingSocket : WebSocket {
        val sent = mutableListOf<String>()
        var cancelled = false
        override fun request() = Request.Builder().url("https://127.0.0.1/v1").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }

    private fun opened(peer: X509Certificate): Response = Response.Builder()
        .request(Request.Builder().url("https://127.0.0.1/v1").build())
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .handshake(Handshake.get(TlsVersion.TLS_1_3, CipherSuite.TLS_AES_128_GCM_SHA256, listOf(peer), emptyList()))
        .build()

    @Test
    fun anotherCertificateOnOpenIsRefusedAndNothingIsSent() {
        val other = HeldCertificate.Builder().commonName("spekterm").ecdsa256().build().certificate
        val socket = RecordingSocket()
        var result: RemoteClient.OpenResult? = null
        val connection = RemoteConnection()
        connection.listener(Pin(pin)) { result = it }.onOpen(socket, opened(other))
        assertEquals(RemoteClient.OpenResult.PinMismatch("open"), result)
        assertTrue(socket.cancelled)
        assertTrue(socket.sent.isEmpty())
        assertTrue(connection.closed.isCompleted)
    }

    @Test
    fun thePinnedCertificateOnOpenIsAccepted() {
        val socket = RecordingSocket()
        var result: RemoteClient.OpenResult? = null
        RemoteConnection().listener(Pin(pin)) { result = it }.onOpen(socket, opened(fixture))
        assertTrue(result is RemoteClient.OpenResult.Open)
        assertFalse(socket.cancelled)
    }
}
