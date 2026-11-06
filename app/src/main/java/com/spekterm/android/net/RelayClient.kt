package com.spekterm.android.net

import com.spekterm.android.store.RelayGrant
import java.net.Proxy
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Why a relay attempt did not reach the desktop (`phone-relay` D4). */
sealed interface RelayOutcome {
    /** The team server could not be reached (TCP, TLS other than its certificate, the upgrade). */
    data class Network(val cause: Throwable?) : RelayOutcome

    /** The whole attempt took longer than [RelayClient.OPEN_TIMEOUT_MS]. */
    data object TimedOut : RelayOutcome

    /** The team server's certificate was refused: against the grant's pin when [pinned], else the system's trust. */
    data class Certificate(val pinned: Boolean) : RelayOutcome

    /** The upgrade was answered with this HTTP status (404: a server without the relay). */
    data class Http(val status: Int) : RelayOutcome

    /** `relay.open` was answered with this error. */
    data class Answer(val error: RemoteError) : RelayOutcome

    /** The team server ended the connection before the desktop's TLS completed, with this close code (or none). */
    data class Closed(val code: Int?) : RelayOutcome

    /** Something the protocol does not allow: a binary message before the answer, an answer the app cannot use. */
    data class ProtocolFault(val what: String) : RelayOutcome
}

/**
 * How the connection to the team server ended, kept apart from the desktop's own connection so its close codes never
 * reach `ConnectionPolicy` (`phone-relay` D4). Recorded once: by the team server's close or a failure, or [byPhone]
 * when the phone ended it first (the desktop's connection closed, or the race dropped it).
 */
class RelayLeg(val host: String) {
    data class End(val code: Int?, val byPhone: Boolean = false)

    private val ended = AtomicReference<End?>(null)

    val end: End? get() = ended.get()

    internal fun record(end: End) {
        ended.compareAndSet(null, end)
    }
}

/**
 * Reaches the desktop through the team server's relay (`phone-relay` D2; spekterm-server's protocol section 13): the
 * outer WebSocket to `<url>/relay`, checked before anything is sent; one `relay.open`, then nothing until it is
 * answered; then the desktop's own pinned connection, opened by [RemoteClient] over a [TunnelSocket].
 */
object RelayClient {
    /** The whole attempt; the protocol asks for at least 25 s (10 of them waiting for the desktop). */
    const val OPEN_TIMEOUT_MS = 30_000L
    const val CONNECT_TIMEOUT_MS = 5_000L
    /** TCP, TLS and the upgrade to the team server. */
    const val UPGRADE_TIMEOUT_MS = 15_000L
    /** From sending `relay.open` to its answer. */
    const val ANSWER_TIMEOUT_MS = 20_000L
    private const val OPEN_ID = 1L

    /** The outer URL: the grant's scheme, host and port with the path `/relay`, built, never concatenated. */
    fun relayUrl(grant: RelayGrant): HttpUrl {
        val authority = grant.url.removePrefix(Frames.RELAY_SCHEME)
        val base = HttpUrl.Builder().scheme("https")
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        val port = authority.removePrefix(host).removePrefix(":").toIntOrNull() ?: 443
        return base.host(host.removePrefix("[").removeSuffix("]")).port(port).addPathSegment("relay").build()
    }

    fun relayOpen(grant: RelayGrant): JsonObject = buildJsonObject {
        put("protocol", JsonArray(listOf(JsonPrimitive(Frames.PROTOCOL))))
        put("grant", grant.grant)
        put("secret", grant.secret)
    }

    /** The client for the team server: the grant's pin when it has one, else [base]'s trust (the system's). */
    fun outerClient(grant: RelayGrant, pin: Pin?, base: OkHttpClient): OkHttpClient = base.newBuilder()
        .apply { if (pin != null) sslSocketFactory(pin.sslContext().socketFactory, pin.trustManager).hostnameVerifier(pin.hostnameVerifier) }
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(UPGRADE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * One relay attempt for the desktop at [port] pinned to [fingerprint]. [base] carries the shared pool and, in
     * tests, the trust for a team server without a pin. An `Open` result carries the team server's [RelayLeg].
     */
    suspend fun open(
        grant: RelayGrant,
        fingerprint: String,
        port: Int,
        base: OkHttpClient = RemoteClient.base,
    ): RemoteClient.OpenResult {
        val attempt = Attempt(grant, port)
        try {
            val result = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                val tunnel = when (val outer = attempt.openOuter(base)) {
                    is OuterResult.Ready -> outer.tunnel
                    is OuterResult.Failed -> return@withTimeoutOrNull RemoteClient.OpenResult.Relayed(outer.outcome)
                }
                val inner = base.newBuilder()
                    .socketFactory(TunnelSocketFactory(tunnel))
                    .proxy(Proxy.NO_PROXY)
                    .retryOnConnectionFailure(false)
                    .build()
                RemoteClient.open(LOOPBACK, port, fingerprint, inner)
            }
            return when (result) {
                null -> {
                    attempt.cancel()
                    RemoteClient.OpenResult.Relayed(RelayOutcome.TimedOut)
                }
                is RemoteClient.OpenResult.Open -> result.copy(leg = attempt.leg)
                is RemoteClient.OpenResult.PinMismatch, is RemoteClient.OpenResult.Relayed -> {
                    attempt.cancel()
                    result
                }
                is RemoteClient.OpenResult.Unreachable -> {
                    // The desktop's TLS did not complete through the tunnel: say what the team server's side did.
                    val end = attempt.leg.end
                    attempt.cancel()
                    RemoteClient.OpenResult.Relayed(
                        if (end != null && !end.byPhone && end.code != null) RelayOutcome.Closed(end.code) else RelayOutcome.Network(result.cause),
                    )
                }
            }
        } catch (e: CancellationException) {
            attempt.cancel()
            throw e
        }
    }

    private sealed interface OuterResult {
        class Ready(val tunnel: TunnelSocket) : OuterResult

        class Failed(val outcome: RelayOutcome) : OuterResult
    }

    private class Attempt(private val grant: RelayGrant, private val port: Int) {
        val leg = RelayLeg(grant.host)
        @Volatile private var socket: WebSocket? = null
        @Volatile private var tunnel: TunnelSocket? = null

        /** Ends the team server's side from the phone: never with a close handshake, which waits 60 s on a dead network. */
        fun cancel() {
            leg.record(RelayLeg.End(null, byPhone = true))
            tunnel?.end()
            socket?.cancel()
        }

        suspend fun openOuter(base: OkHttpClient): OuterResult {
            val pin = grant.fp?.let { Pin(it) }
            val answer = CompletableDeferred<OuterResult>()
            socket = outerClient(grant, pin, base).newWebSocket(Request.Builder().url(relayUrl(grant)).build(), Listener(pin, answer))
            val result = answer.await()
            if (result is OuterResult.Failed) socket?.cancel()
            return result
        }

        inner class Listener(private val pin: Pin?, private val answer: CompletableDeferred<OuterResult>) : WebSocketListener() {
            @Volatile private var spliced = false

            private fun fail(outcome: RelayOutcome) {
                answer.complete(OuterResult.Failed(outcome))
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                // The third check of the team server's pin, on the established connection, before anything is sent.
                if (pin != null && !pin.matches(response.handshake)) {
                    webSocket.cancel()
                    return fail(RelayOutcome.Certificate(pinned = true))
                }
                if (!webSocket.send(Frames.request(OPEN_ID, "relay.open", relayOpen(grant)))) fail(RelayOutcome.Network(null))
                // Then nothing until the answer; the whole attempt is bounded by OPEN_TIMEOUT_MS.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Text after the answer is never sent by the server; the splice carries binary only.
                if (spliced || answer.isCompleted) return
                val frame = Frames.decode(text) as? ServerFrame.Response
                if (frame == null || frame.id != OPEN_ID) return fail(RelayOutcome.ProtocolFault("unexpected frame"))
                frame.error?.let { return fail(RelayOutcome.Answer(it)) }
                val result = frame.result as? JsonObject
                val protocol = (result?.get("protocol") as? JsonPrimitive)?.intOrNull
                val tunnelId = (result?.get("tunnel") as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                if (protocol != Frames.PROTOCOL || tunnelId.isNullOrEmpty()) return fail(RelayOutcome.ProtocolFault("unusable answer"))
                val opened = TunnelSocket(webSocket, port) { leg.record(RelayLeg.End(null, byPhone = true)) }
                tunnel = opened
                spliced = true
                if (!answer.complete(OuterResult.Ready(opened))) opened.close()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!spliced) {
                    webSocket.cancel()
                    return fail(RelayOutcome.ProtocolFault("binary before the answer"))
                }
                tunnel?.deliver(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                leg.record(RelayLeg.End(code))
                tunnel?.end()
                webSocket.cancel()
                fail(RelayOutcome.Closed(code))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                leg.record(RelayLeg.End(code))
                tunnel?.end()
                fail(RelayOutcome.Closed(code))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                leg.record(RelayLeg.End(null))
                tunnel?.end()
                val status = response?.code
                fail(
                    when {
                        pin?.refusedACertificate == true -> RelayOutcome.Certificate(pinned = true)
                        pin == null && isCertificateFailure(t) -> RelayOutcome.Certificate(pinned = false)
                        status != null && status != 101 -> RelayOutcome.Http(status)
                        else -> RelayOutcome.Network(t)
                    },
                )
            }
        }
    }

    /** A refused certificate or host name, as the platform's trust reports it. */
    fun isCertificateFailure(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.take(10).any { it is CertificateException || it is SSLPeerUnverifiedException }

    /** The desktop's URL inside the tunnel: an IP literal, so nothing is resolved and no SNI is sent (D1). */
    const val LOOPBACK = "127.0.0.1"
}
