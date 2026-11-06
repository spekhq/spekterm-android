package com.spekterm.android.net

import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.net.SocketFactory
import kotlin.concurrent.withLock
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * A connected socket whose bytes are the binary messages of the team server's relay WebSocket [outer] (`phone-relay`
 * D1). OkHttp opens the desktop's pinned TLS and WebSocket on it exactly as on a TCP socket.
 *
 * Writes are cut into messages of at most [CHUNK] bytes and wait while [outer] holds more than [HIGH_WATER] unsent.
 * Received messages ([deliver], from the outer reader thread) wait in a queue of at most [IN_LIMIT] bytes; when it is
 * full the outer reader waits, so TCP pushes back on the server. Every failure is an [IOException]: OkHttp rethrows
 * anything else on its dispatcher thread, which would end the process.
 */
class TunnelSocket(
    private val outer: WebSocket,
    private val urlPort: Int,
    private val onClose: () -> Unit = {},
) : Socket() {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayDeque<ByteArray>()
    private var queued = 0L
    private var head = 0
    /** The outer WebSocket ended: reads drain what is queued, then end. */
    private var ended = false
    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile private var timeout = 0

    /** A binary message from [outer]. Waits while the queue is full; dropped once the socket is closed or ended. */
    fun deliver(bytes: ByteString) {
        if (bytes.size == 0) return
        lock.withLock {
            while (!closed && !ended && queue.isNotEmpty() && queued + bytes.size > IN_LIMIT) {
                try {
                    changed.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            if (closed || ended) return
            queue.addLast(bytes.toByteArray())
            queued += bytes.size
            changed.signalAll()
        }
    }

    /** [outer] closed or failed: wakes every waiter; reads end after the queued bytes, writes fail. */
    fun end() {
        lock.withLock {
            ended = true
            changed.signalAll()
        }
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        if (closed) throw SocketException("relay tunnel closed")
        connected = true
    }

    override fun isConnected() = connected
    override fun isBound() = connected
    override fun isClosed() = closed
    override fun isInputShutdown() = closed
    override fun isOutputShutdown() = closed

    override fun setSoTimeout(timeout: Int) {
        this.timeout = timeout
    }

    override fun getSoTimeout() = timeout
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun getTcpNoDelay() = true
    override fun setKeepAlive(on: Boolean) = Unit
    override fun getKeepAlive() = false
    override fun setSoLinger(on: Boolean, linger: Int) = Unit
    override fun getSoLinger() = -1
    override fun setSendBufferSize(size: Int) = Unit
    override fun setReceiveBufferSize(size: Int) = Unit
    override fun getInetAddress(): InetAddress = LOOPBACK
    override fun getPort() = urlPort
    override fun getLocalAddress(): InetAddress = LOOPBACK
    override fun getLocalPort() = 0
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(LOOPBACK, urlPort)
    override fun getLocalSocketAddress(): SocketAddress = InetSocketAddress(LOOPBACK, 0)
    override fun shutdownInput() = Unit
    override fun shutdownOutput() = Unit

    /**
     * libcore's hidden `Socket.getFileDescriptor$()`. Conscrypt wraps a socket that has a descriptor in an `SSLSocket`
     * working on that descriptor; this socket has none, so it says so, and Conscrypt uses its engine-based socket
     * (`phone-relay` D1). Kept through R8 by `proguard-rules.pro`.
     */
    @Suppress("unused", "FunctionName")
    fun `getFileDescriptor$`(): FileDescriptor = throw UnsupportedOperationException("a relay tunnel has no descriptor")

    override fun close() {
        if (closed) return
        closed = true
        onClose()
        outer.cancel()
        lock.withLock {
            queue.clear()
            queued = 0
            changed.signalAll()
        }
    }

    override fun toString() = "TunnelSocket(port=$urlPort, closed=$closed)"

    private val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            lock.withLock {
                val deadline = if (timeout > 0) System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout.toLong()) else 0L
                while (queue.isEmpty()) {
                    if (closed || ended) return -1
                    try {
                        if (deadline == 0L) {
                            changed.await()
                        } else {
                            val left = deadline - System.nanoTime()
                            if (left <= 0) throw SocketTimeoutException("relay read timed out")
                            changed.awaitNanos(left)
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedIOException("relay read interrupted")
                    }
                }
                val current = queue.first()
                val n = minOf(len, current.size - head)
                System.arraycopy(current, head, b, off, n)
                head += n
                if (head == current.size) {
                    queue.removeFirst()
                    head = 0
                }
                queued -= n
                changed.signalAll()
                return n
            }
        }

        override fun available(): Int = lock.withLock { queued.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }

        override fun close() = this@TunnelSocket.close()
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            val end = off + len
            while (at < end) {
                awaitRoom()
                val n = minOf(CHUNK, end - at)
                if (!outer.send(b.toByteString(at, n))) throw IOException("relay closed")
                at += n
            }
        }

        override fun close() = this@TunnelSocket.close()
    }

    /** Waits while [outer] holds more than [HIGH_WATER] unsent; fails once the socket is closed or [outer] ended. */
    private fun awaitRoom() {
        lock.withLock {
            while (true) {
                if (closed || ended) throw IOException("relay closed")
                if (outer.queueSize() <= HIGH_WATER) return
                try {
                    // OkHttp says nothing when its queue drains; look again shortly, or at once on close or end.
                    changed.await(ROOM_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("relay write interrupted")
                }
            }
        }
    }

    override fun getInputStream(): InputStream {
        if (closed) throw SocketException("relay tunnel closed")
        return input
    }

    override fun getOutputStream(): OutputStream {
        if (closed) throw SocketException("relay tunnel closed")
        return output
    }

    companion object {
        /** The largest message sent to the team server (its protocol section 13.2 asks for at most 64 KiB). */
        const val CHUNK = 64 * 1024
        /** Unsent bytes in the outer WebSocket above which writes wait (OkHttp closes it past 16 MiB). */
        const val HIGH_WATER = 256 * 1024L
        /** Received bytes waiting for the inner reader above which the outer reader waits. */
        const val IN_LIMIT = 1024 * 1024L
        const val ROOM_POLL_MS = 5L
        private val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    }
}

/** Hands OkHttp its one [socket], once; a tunnel carries one connection (`phone-relay` D1). */
class TunnelSocketFactory(private val socket: TunnelSocket) : SocketFactory() {
    private val handed = AtomicBoolean(false)

    override fun createSocket(): Socket =
        if (handed.compareAndSet(false, true)) socket else throw SocketException("a relay tunnel carries one connection")

    override fun createSocket(host: String?, port: Int): Socket = refuse()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = refuse()
    override fun createSocket(host: InetAddress?, port: Int): Socket = refuse()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = refuse()

    private fun refuse(): Nothing = throw SocketException("a relay tunnel is connected by OkHttp only")
}
