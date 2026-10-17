package com.spekterm.android.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The staggered race against the loopback desktop (`scan-connect-zh` D5, tasks 2.2). Emulator only. */
@RunWith(AndroidJUnit4::class)
class AddressRaceDeviceTest {
    private val server = Fixture.server()

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    @Test
    fun anUnroutableFirstAddressDoesNotHoldUpTheSecond() = runBlocking<Unit> {
        val desktop = DesktopSocket { method, id -> if (method == "hello") """{"t":"res","id":$id,"ok":true,"r":{}}""" else null }
        server.enqueue(desktop.response())
        val started = System.nanoTime()
        val race = race(
            listOf("10.255.255.1", "127.0.0.1"),
            open = { RemoteClient.open(it, server.port, Fixture.fingerprint).attempt() },
            drop = RemoteConnection::cancel,
        )
        val (address, connection) = race.winner!!
        val hello = connection.call("hello", Frames.hello("device", "secret"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        connection.cancel()
        assertEquals("127.0.0.1", address)
        assertTrue(hello.ok)
        assertTrue("authenticated after $elapsedMs ms", elapsedMs < 1_500)
        // The stagger let the second through while the first was still trying, not a fast failure of the first.
        assertEquals(listOf("10.255.255.1"), race.cancelled)
    }
}
