package com.spekterm.android.net

import android.annotation.SuppressLint
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.X509ExtendedTrustManager
import okhttp3.Handshake

/**
 * Trust in the desktop is its certificate's SHA-256 fingerprint and nothing else (`desktop-connection`,
 * design D2): no certificate authority, no host name. The certificate is self-signed with no extensions and no
 * SAN, and the address is whatever works, so the platform's checks would refuse it; ours accept exactly the
 * pinned certificate and refuse everything else.
 *
 * The pin is checked in the trust manager, again in the host name verifier, and once more on the established
 * connection before the first frame ([matches] on the [Handshake]).
 */
class Pin(fingerprintHex: String) {
    private val expected: ByteArray = hexToBytes(fingerprintHex)

    init {
        require(expected.size == 32) { "a pin is 32 bytes of SHA-256" }
    }

    /**
     * Set once a certificate was presented and did not match, so a failed connection can be told apart from an
     * unreachable address. One [Pin] per connection attempt.
     */
    @Volatile
    var refusedACertificate = false
        private set

    /** Which check refused, for the log and the findings: `trust`, `hostname` or `open`. */
    @Volatile
    var refusedBy: String? = null
        private set

    fun matches(certificate: Certificate?, check: String = "trust"): Boolean {
        val equal = certificate != null &&
            MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(certificate.encoded), expected)
        if (!equal) {
            if (certificate != null) refusedACertificate = true
            if (refusedBy == null) refusedBy = check + if (certificate == null) " (no certificate)" else ""
        }
        return equal
    }

    /**
     * The leaf of this connection's TLS session, as the host name verifier read it from the session itself.
     *
     * OkHttp's [Handshake] lists no peer certificates here (measured on a Pixel 7, OkHttp 5.5): it "cleans" the
     * chain against the trust manager's accepted issuers, which are deliberately none, and reports an empty list
     * when no chain can be built. So the check on open falls back to the session's own leaf.
     */
    @Volatile
    var sessionLeaf: Certificate? = null
        private set

    /** The check made on `onOpen`, before anything is sent: the leaf of the established connection. */
    fun matches(handshake: Handshake?): Boolean =
        matches(handshake?.peerCertificates?.firstOrNull() ?: sessionLeaf, "open")

    // Deliberate: this trust manager accepts exactly one certificate, by its fingerprint, and refuses all else.
    @SuppressLint("CustomX509TrustManager")
    val trustManager: X509ExtendedTrustManager = object : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>?) {
            if (chain.isNullOrEmpty() || !matches(chain[0])) {
                throw CertificateException("the desktop's certificate does not match the pinned fingerprint")
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = refuseClient()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = refuseClient()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = refuseClient()

        private fun refuseClient(): Nothing = throw CertificateException("the app is never a TLS server")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Compares the session's leaf to the pin, whatever the host name; refuses otherwise. */
    val hostnameVerifier = HostnameVerifier { _: String?, session: SSLSession? ->
        try {
            val leaf = session?.peerCertificates?.firstOrNull()
            sessionLeaf = leaf
            matches(leaf, "hostname")
        } catch (_: Exception) {
            matches(null, "hostname")
        }
    }

    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.replace(":", "")
            require(clean.length % 2 == 0 && clean.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) { "not hex" }
            return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}
