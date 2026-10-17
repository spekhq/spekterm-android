package com.spekterm.android.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Against a real spekterm desktop (`scan-connect-zh` tasks 2.4): attempts dropped without a frame cost nothing
 * with its per-address throttle. Skipped unless the instrumentation arguments `desktopAddresses` (comma-separated),
 * `desktopPort` and `desktopFingerprint` are given. Emulator only; the desktop's settings are not changed.
 */
@RunWith(AndroidJUnit4::class)
class DesktopRaceTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun droppedAttemptsAreNotCountedAsFailures() {
        val addresses = args.getString("desktopAddresses")?.split(',')?.filter { it.isNotBlank() }
        val port = args.getString("desktopPort")?.toIntOrNull()
        val fingerprint = args.getString("desktopFingerprint")
        // Outside the coroutine, so the runner reports a skip rather than a failure.
        assumeTrue(addresses != null && port != null && fingerprint != null)
        race(addresses!!, port!!, fingerprint!!)
    }

    private fun race(addresses: List<String>, port: Int, fingerprint: String) = runBlocking<Unit> {

        // Twelve races at once, each dropping every connection that opens — the winner too — without a frame.
        var opened = 0
        repeat(12) {
            val race = com.spekterm.android.net.race(
                addresses,
                open = { RemoteClient.open(it, port, fingerprint).attempt() },
                drop = { opened++; it.cancel() },
                stagger = 0,
            )
            race.winner?.let { (_, connection) -> opened++; connection.cancel() }
        }
        // A loser cancelled mid-handshake counts as nothing either; the ones that opened are the case at issue.
        android.util.Log.i("DesktopRaceTest", "connections opened and dropped: $opened of ${12 * addresses.size} attempts")
        org.junit.Assert.assertTrue(opened >= 12)

        // Each path reaches the desktop from its own source address: each must answer as usual, not THROTTLED.
        for (address in addresses) {
            val connection = (RemoteClient.open(address, port, fingerprint) as RemoteClient.OpenResult.Open).connection
            val answer = try {
                connection.call("hello", Frames.hello("not-a-device", "not-a-secret"))
            } finally {
                connection.cancel()
            }
            assertEquals("answer over $address", RemoteError.AUTH_FAILED, answer.error?.code)
        }
    }
}
