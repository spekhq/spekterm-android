package com.spekterm.android.net

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TunnelSocketTest {
    /** A WebSocket that records what is sent and reports a queue size the test sets. */
    private class FakeWebSocket : WebSocket {
        val sent = mutableListOf<ByteString>()
        val queue = AtomicLong(0)
        @Volatile var cancelled = false
        @Volatile var accepting = true

        override fun request(): Request = Request.Builder().url("https://127.0.0.1/").build()
        override fun queueSize(): Long = queue.get()
        override fun send(text: String) = false
        override fun send(bytes: ByteString): Boolean {
            if (!accepting) return false
            synchronized(sent) { sent += bytes }
            return true
        }
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            cancelled = true
        }
    }

    private val outer = FakeWebSocket()
    private val socket = TunnelSocket(outer, 47615).apply { connect(null, 0) }

    @Test
    fun aLargeWriteIsCutIntoMessagesOfAtMost64KiBInOrder() {
        val data = ByteArray(300 * 1024) { (it % 251).toByte() }
        socket.getOutputStream().write(data)
        assertEquals(5, outer.sent.size)
        assertTrue(outer.sent.all { it.size <= TunnelSocket.CHUNK })
        assertArrayEquals(data, outer.sent.fold(ByteArray(0)) { all, m -> all + m.toByteArray() })
    }

    @Test
    fun aWriteWaitsWhileTheOuterQueueIsFull() {
        outer.queue.set(TunnelSocket.HIGH_WATER + 1)
        val done = CountDownLatch(1)
        thread { socket.getOutputStream().write(byteArrayOf(1, 2, 3)); done.countDown() }
        assertFalse(done.await(100, TimeUnit.MILLISECONDS))
        assertEquals(0, outer.sent.size)
        outer.queue.set(0)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, outer.sent.size)
    }

    @Test
    fun aWaitingWriteFailsWhenTheOuterWebSocketEnds() {
        outer.queue.set(TunnelSocket.HIGH_WATER + 1)
        val failed = CountDownLatch(1)
        thread {
            try {
                socket.getOutputStream().write(byteArrayOf(1))
            } catch (_: IOException) {
                failed.countDown()
            }
        }
        Thread.sleep(50)
        socket.end()
        assertTrue(failed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun readsCrossMessageBoundariesAndEndAfterTheQueuedBytes() {
        socket.deliver(byteArrayOf(1, 2, 3).toByteString())
        socket.deliver(byteArrayOf(4, 5).toByteString())
        socket.end()
        val input = socket.getInputStream()
        val buffer = ByteArray(4)
        assertEquals(3, input.read(buffer, 0, 4))
        assertEquals(2, input.read(buffer, 0, 4))
        assertArrayEquals(byteArrayOf(4, 5), buffer.copyOf(2))
        assertEquals(-1, input.read(buffer, 0, 4))
        // A part of a message, then the rest.
        val other = TunnelSocket(FakeWebSocket(), 1).apply { connect(null) }
        other.deliver(byteArrayOf(9, 8, 7).toByteString())
        assertEquals(2, other.getInputStream().read(buffer, 0, 2))
        assertEquals(1, other.getInputStream().read(buffer, 0, 4))
        assertEquals(7.toByte(), buffer[0])
    }

    @Test
    fun aReadTimesOutWhenATimeoutIsSet() {
        socket.soTimeout = 50
        try {
            socket.getInputStream().read(ByteArray(1), 0, 1)
            fail("no timeout")
        } catch (_: SocketTimeoutException) {
        }
    }

    @Test
    fun withTheQueueFullCloseAndEndReturnAtOnceAndReleaseTheReader() {
        socket.deliver(ByteArray(TunnelSocket.IN_LIMIT.toInt()).toByteString())
        val released = CountDownLatch(1)
        thread { socket.deliver(ByteArray(10).toByteString()); released.countDown() }
        assertFalse(released.await(100, TimeUnit.MILLISECONDS))
        val closer = thread { socket.close() }
        closer.join(1_000)
        assertFalse(closer.isAlive)
        assertTrue(released.await(1, TimeUnit.SECONDS))
        assertTrue(outer.cancelled)
        assertEquals(-1, socket.getInputStreamOrNull()?.read(ByteArray(1), 0, 1) ?: -1)

        val other = TunnelSocket(FakeWebSocket(), 1)
        other.deliver(ByteArray(TunnelSocket.IN_LIMIT.toInt()).toByteString())
        val reader = CountDownLatch(1)
        thread { other.deliver(ByteArray(10).toByteString()); reader.countDown() }
        assertFalse(reader.await(100, TimeUnit.MILLISECONDS))
        other.end()
        assertTrue(reader.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun aWriteAfterCloseFails() {
        val output = socket.getOutputStream()
        socket.close()
        try {
            output.write(byteArrayOf(1))
            fail("wrote after close")
        } catch (_: IOException) {
        }
        assertTrue(socket.isClosed)
    }

    @Test
    fun aRefusedSendFails() {
        outer.accepting = false
        try {
            socket.getOutputStream().write(byteArrayOf(1))
            fail("no failure")
        } catch (_: IOException) {
        }
    }

    @Test
    fun theFactoryHandsOutItsSocketOnce() {
        val factory = TunnelSocketFactory(socket)
        assertTrue(factory.createSocket() === socket)
        try {
            factory.createSocket()
            fail("a second socket")
        } catch (_: SocketException) {
        }
    }

    @Test
    fun theSocketHasNoDescriptor() {
        try {
            socket.`getFileDescriptor$`()
            fail("a descriptor")
        } catch (_: UnsupportedOperationException) {
        }
    }

    private fun TunnelSocket.getInputStreamOrNull() = try {
        inputStream
    } catch (_: SocketException) {
        null
    }
}
