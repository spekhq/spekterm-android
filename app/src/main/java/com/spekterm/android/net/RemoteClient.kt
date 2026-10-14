package com.spekterm.android.net

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume

/**
 * One pinned WebSocket to the desktop (`desktop-connection`, design D2 and D4).
 *
 * The client never sends a frame before the pin has been confirmed three times: by the trust manager, by the
 * host name verifier, and on the established connection in `onOpen`.
 */
object RemoteClient {
    const val CONNECT_TIMEOUT_MS = 5_000L
    /**
     * Bounds the whole open — TCP, TLS and the upgrade. `connectTimeout` covers only TCP, and the read timeout is
     * 0 for a WebSocket, so without this a desktop that stalls mid-handshake holds the attempt for minutes.
     * OkHttp ends the call timeout at the upgrade, so it does not limit the open WebSocket.
     */
    const val OPEN_TIMEOUT_MS = 10_000L
    const val PING_INTERVAL_MS = 30_000L

    /** Shared dispatcher and pool; each connection derives its own client carrying its pin. */
    val base: OkHttpClient by lazy { OkHttpClient() }

    sealed interface OpenResult {
        data class Open(val connection: RemoteConnection) : OpenResult

        /** A certificate was presented and it is not the pinned one. Nothing was sent. */
        data class PinMismatch(val refusedBy: String? = null) : OpenResult

        /** No TLS connection could be made (refused, timed out, no route). */
        data class Unreachable(val cause: Throwable?) : OpenResult
    }

    fun url(address: String, port: Int): HttpUrl =
        HttpUrl.Builder().scheme("https").host(address).port(port).addPathSegment("v1").build()

    fun clientFor(pin: Pin, base: OkHttpClient = this.base): OkHttpClient = base.newBuilder()
        .sslSocketFactory(pin.sslContext().socketFactory, pin.trustManager)
        .hostnameVerifier(pin.hostnameVerifier)
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun open(address: String, port: Int, fingerprint: String, base: OkHttpClient = this.base): OpenResult {
        val pin = Pin(fingerprint)
        val client = clientFor(pin, base)
        val request = try {
            Request.Builder().url(url(address, port)).build()
        } catch (e: IllegalArgumentException) {
            // An address OkHttp cannot put in a URL; the link parser refuses these, this is the last guard.
            return OpenResult.Unreachable(e)
        }
        val connection = RemoteConnection()
        return suspendCancellableCoroutine { continuation ->
            val socket = client.newWebSocket(request, connection.listener(pin) { result ->
                if (continuation.isActive) {
                    // Cancelled just after resuming: close what was opened rather than leave it to the
                    // desktop's 10 s authentication deadline (which counts toward throttling).
                    continuation.resume(result) { _, value, _ -> (value as? OpenResult.Open)?.connection?.cancel() }
                } else {
                    (result as? OpenResult.Open)?.connection?.cancel()
                }
            })
            continuation.invokeOnCancellation { socket.cancel() }
        }
    }
}

/** How a connection ended. [error] is the last error not answering a request of ours (`id: null`). */
data class CloseInfo(val code: Int?, val reason: String, val failure: Throwable?, val error: RemoteError?)

class RequestTimeoutException(method: String) : IOException("no answer to $method in time")

class ConnectionClosedException(val info: CloseInfo) : IOException("connection closed (${info.code ?: info.failure})")

class RemoteConnection internal constructor() {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<ServerFrame.Response>>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var unsolicitedError: RemoteError? = null
    @Volatile private var closeInfo: CloseInfo? = null

    /** Server events, in arrival order. Closed when the connection ends. */
    val events = Channel<ServerFrame.Event>(Channel.UNLIMITED)

    /** Completes once, when the connection ends for any reason. */
    val closed = CompletableDeferred<CloseInfo>()

    /** Sends a request and waits for its answer (`ok` or an error). Throws [ConnectionClosedException]. */
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 20_000): ServerFrame.Response {
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<ServerFrame.Response>()
        pending[id] = answer
        closeInfo?.let { pending.remove(id); throw ConnectionClosedException(it) }
        val sent = socket?.send(Frames.request(id, method, params)) ?: false
        if (!sent) {
            pending.remove(id)
            throw ConnectionClosedException(closeInfoNow())
        }
        val result = try {
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            pending.remove(id)
        }
        if (result != null) return result
        // No answer in time: the connection is not usable. End it and report it as closed, never as a
        // cancellation (which would silently end the caller's coroutine).
        socket?.cancel()
        val info = CloseInfo(null, "no answer to $method", RequestTimeoutException(method), unsolicitedError)
        finish(info)
        throw ConnectionClosedException(closeInfo ?: info)
    }

    /** A normal close (1000). */
    fun close() {
        socket?.close(1000, null)
    }

    /** Ends the connection with no close handshake. */
    fun cancel() {
        socket?.cancel()
    }

    private fun closeInfoNow() = closeInfo ?: CloseInfo(null, "", null, unsolicitedError)

    @Synchronized
    private fun finish(info: CloseInfo) {
        if (closeInfo != null) return
        closeInfo = info
        closed.complete(info)
        events.close()
        val exception = ConnectionClosedException(info)
        pending.values.forEach { it.completeExceptionally(exception) }
        pending.clear()
    }

    internal fun listener(pin: Pin, onOpenResult: (RemoteClient.OpenResult) -> Unit) = object : WebSocketListener() {
        private var opened = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // The third check, on the established connection, before anything is sent.
            if (!pin.matches(response.handshake)) {
                webSocket.cancel()
                finish(CloseInfo(null, "pin mismatch on open", null, null))
                onOpenResult(RemoteClient.OpenResult.PinMismatch(pin.refusedBy))
                return
            }
            socket = webSocket
            opened = true
            onOpenResult(RemoteClient.OpenResult.Open(this@RemoteConnection))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val frame = Frames.decode(text)) {
                is ServerFrame.Response -> {
                    val waiter = frame.id?.let { pending.remove(it) }
                    if (waiter != null) waiter.complete(frame) else if (frame.error != null) unsolicitedError = frame.error
                }
                is ServerFrame.Event -> events.trySend(frame)
                null -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 1005 ("no status") may not be sent back; answer it with a normal close.
            webSocket.close(if (code == 1005) 1000 else code, null)
            finish(CloseInfo(code, reason, null, unsolicitedError))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(CloseInfo(code, reason, null, unsolicitedError))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            finish(CloseInfo(null, t.message ?: "", t, unsolicitedError))
            if (!opened) {
                onOpenResult(
                    if (pin.refusedACertificate) RemoteClient.OpenResult.PinMismatch(pin.refusedBy) else RemoteClient.OpenResult.Unreachable(t),
                )
            }
        }
    }
}
