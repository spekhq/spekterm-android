package com.spekterm.android.net

import androidx.test.platform.app.InstrumentationRegistry
import java.net.Socket
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Plays the desktop on the phone's loopback interface: a TLS WebSocket server presenting the fixture
 * certificate (spekterm's exact shape, `androidTest/assets/README.md`), built on the phone's own TLS provider.
 */
object Fixture {
    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().readText()

    val certificate: X509Certificate by lazy {
        CertificateFactory.getInstance("X.509").generateCertificate(asset("spekterm-cert.pem").byteInputStream()) as X509Certificate
    }

    val fingerprint: String by lazy { asset("spekterm-fingerprint.txt").trim() }

    private val key: PrivateKey by lazy {
        val base64 = asset("spekterm-key.pem").lines().filter { !it.startsWith("-----") }.joinToString("")
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))
    }

    /** Hands the fixture key straight to TLS, avoiding key-store type differences between providers. */
    private val keyManager = object : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
    }

    fun server(): MockWebServer = MockWebServer().apply {
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), null, null) }
        useHttps(context.socketFactory)
    }

    /** One fingerprint digit changed. */
    val wrongFingerprint: String by lazy {
        val last = fingerprint.last()
        fingerprint.dropLast(1) + (if (last == '0') '1' else '0')
    }

    private const val ALIAS = "spekterm"
}

/** A desktop that answers each request with [answer] and can send frames of its own. */
class DesktopSocket(private val answer: (method: String, id: Long) -> String?) : WebSocketListener() {
    @Volatile var socket: WebSocket? = null
    val received = mutableListOf<String>()
    var onOpen: (WebSocket) -> Unit = {}
    /** When set, an answer holding this separator is sent as several frames, in order. */
    var split: String? = null

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        socket = webSocket
        onOpen(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        synchronized(received) { received += text }
        val frame = Frames.json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val method = (frame["m"] as kotlinx.serialization.json.JsonPrimitive).content
        val id = (frame["id"] as kotlinx.serialization.json.JsonPrimitive).content.toLong()
        val text = answer(method, id) ?: return
        val separator = split
        if (separator == null) webSocket.send(text) else text.split(separator).forEach { webSocket.send(it) }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
    }

    fun response(): MockResponse = MockResponse.Builder().webSocketUpgrade(this).build()
}
