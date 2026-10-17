package com.spekterm.android.pairing

import com.spekterm.android.net.Attempt
import com.spekterm.android.net.RemoteClient
import com.spekterm.android.service.Unreachability
import com.spekterm.android.store.Pairing
import java.net.SocketTimeoutException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `device-pairing` "Pair with the desktop" over the race (`scan-connect-zh` D5), on virtual time. */
class PairerTest {
    private val link = (PairingLink.parse(
        "spekterm://pair?v=1&a=192.168.0.199%2C100.106.81.33&p=47615&fp=${"AB".repeat(32)}&c=ABCDEFGHIJKLMNOPQRSTUVWXY2&n=desk",
    ) as PairingLink.Result.Ok).link
    private val wifi = Unreachability.Network(Unreachability.NetworkKind.WIFI, vpn = false)

    private fun paired(address: String) = Pairer.Result.Paired(
        Pairing(desktopName = "desk", addresses = link.addresses, port = link.port, fingerprint = link.fingerprint, deviceId = "d", deviceSecret = "s", lastAddress = address),
    )

    @Test
    fun theCodeGoesOnOneConnectionOnly() = runTest {
        val sent = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val result = Pairer.pairWith(
            link,
            open = { address ->
                delay(if (address == link.addresses[0]) 300 else 50)
                Attempt.Opened("conn-$address")
            },
            drop = { dropped += it },
            exchange = { address, connection ->
                sent += connection
                paired(address)
            },
            network = { wifi },
        )
        assertEquals(1, sent.size)
        assertEquals(1, dropped.size)
        assertTrue(sent[0] != dropped[0])
        assertTrue(result is Pairer.Result.Paired)
    }

    @Test
    fun aLaterAddressAnsweringFirstPairsWithoutWaitingForTheFirst() = runTest {
        var pairedAt = -1L
        val result = Pairer.pairWith(
            link,
            open = { address ->
                if (address == link.addresses[0]) awaitCancellation()
                delay(100)
                Attempt.Opened("conn-$address")
            },
            drop = {},
            exchange = { address, _ ->
                pairedAt = currentTime
                paired(address)
            },
            network = { wifi },
        )
        assertEquals(350L, pairedAt)
        assertEquals("100.106.81.33", (result as Pairer.Result.Paired).pairing.lastAddress)
    }

    @Test
    fun noAddressOpeningListsEveryOutcome() = runTest {
        val result = Pairer.pairWith<String>(
            link,
            open = { Attempt.Failed(RemoteClient.OpenResult.Unreachable(SocketTimeoutException("connect timed out"))) },
            drop = {},
            exchange = { _, _ -> error("nothing opened") },
            network = { wifi },
        ) as Pairer.Result.Failed
        assertEquals(Pairer.Failure.UNREACHABLE, result.failure)
        assertEquals(
            listOf("192.168.0.199" to Unreachability.Outcome.TIMED_OUT, "100.106.81.33" to Unreachability.Outcome.TIMED_OUT),
            result.report!!.outcomes,
        )
        assertEquals(Unreachability.Reason.NO_ANSWER, result.report!!.reason)
    }
}
