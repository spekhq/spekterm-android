package com.spekterm.android.service

import com.spekterm.android.service.Unreachability.AddressKind
import com.spekterm.android.service.Unreachability.Network
import com.spekterm.android.service.Unreachability.NetworkKind
import com.spekterm.android.service.Unreachability.Outcome
import com.spekterm.android.service.Unreachability.Reason
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `scan-connect-zh` D6 and the spec's rules. */
class UnreachabilityTest {
    private val mobile = Network(NetworkKind.MOBILE, vpn = false)
    private val wifi = Network(NetworkKind.WIFI, vpn = false)
    private val maintainer = listOf(
        "192.168.0.199",
        "2001:b011:2020:b721:1a2b:3c4d:5e6f:7081",
        "2001:b011:2020:b721:9a8b:7c6d:5e4f:3021",
        "100.106.81.33",
        "fd7a:115c:a1e0::6432:5121",
    )

    private fun all(addresses: List<String>, outcome: Outcome) = addresses.map { it to outcome }

    @Test
    fun outcomesFromExceptionChains() {
        assertEquals(Outcome.TIMED_OUT, Unreachability.outcome(SocketTimeoutException("failed to connect to /192.168.0.199 (port 47615) after 5000ms")))
        assertEquals(Outcome.TIMED_OUT, Unreachability.outcome(InterruptedIOException("timeout")))
        assertEquals(
            Outcome.REFUSED,
            Unreachability.outcome(ConnectException("Failed to connect to /192.168.0.199:47615").initCause(IOException("isConnected failed: ECONNREFUSED (Connection refused)"))),
        )
        assertEquals(Outcome.NO_ROUTE, Unreachability.outcome(NoRouteToHostException("No route to host")))
        assertEquals(Outcome.NO_ROUTE, Unreachability.outcome(ConnectException("Failed to connect").initCause(IOException("connect failed: ENETUNREACH (Network is unreachable)"))))
        assertEquals(Outcome.NAME_NOT_FOUND, Unreachability.outcome(UnknownHostException("Unable to resolve host \"desk.example\"")))
        assertEquals(Outcome.FAILED, Unreachability.outcome(IOException("something else")))
        assertEquals(Outcome.FAILED, Unreachability.outcome(null))
        // OkHttp keeps the other routes' failures as suppressed exceptions.
        assertEquals(Outcome.REFUSED, Unreachability.outcome(IOException("x").apply { addSuppressed(IOException("ECONNREFUSED")) }))
    }

    @Test
    fun addressKinds() {
        val expected = mapOf(
            "192.168.50.22" to AddressKind.LOCAL,
            "10.1.2.3" to AddressKind.LOCAL,
            "172.20.0.1" to AddressKind.LOCAL,
            "172.32.0.1" to AddressKind.OTHER,
            "169.254.1.1" to AddressKind.LOCAL,
            "fe80::1" to AddressKind.LOCAL,
            "fd00::1" to AddressKind.LOCAL,
            "100.106.81.33" to AddressKind.TAILSCALE,
            "100.128.0.1" to AddressKind.OTHER,
            "fd7a:115c:a1e0::6432:5121" to AddressKind.TAILSCALE,
            "[fd7a:115c:a1e0::6432:5121]" to AddressKind.TAILSCALE,
            "2001:b011:2020:b721::1" to AddressKind.GLOBAL_V6,
            "desk.tailnet.ts.net" to AddressKind.TAILSCALE,
            "desk.local" to AddressKind.LOCAL,
            "8.8.8.8" to AddressKind.OTHER,
            "example.org" to AddressKind.OTHER,
        )
        for ((address, kind) in expected) assertEquals(address, kind, Unreachability.kind(address))
    }

    @Test
    fun noNetworkComesFirst() {
        val report = Unreachability.report(all(maintainer, Outcome.NO_ROUTE), Network(NetworkKind.NONE, vpn = false))
        assertEquals(Reason.NO_NETWORK, report.reason)
    }

    @Test
    fun aMismatchedCertificateBeatsARefusal() {
        val outcomes = listOf("192.168.0.199" to Outcome.REFUSED, "100.106.81.33" to Outcome.CERTIFICATE)
        val report = Unreachability.report(outcomes, wifi)
        assertEquals(Reason.NOT_THE_DESKTOP, report.reason)
        assertEquals("100.106.81.33", report.address)
    }

    @Test
    fun aRefusalNamesTheFirstAddressThatRefused() {
        val outcomes = listOf("192.168.0.199" to Outcome.TIMED_OUT, "100.106.81.33" to Outcome.REFUSED, "10.0.0.2" to Outcome.REFUSED)
        val report = Unreachability.report(outcomes, mobile)
        assertEquals(Reason.REFUSED, report.reason)
        assertEquals("100.106.81.33", report.address)
    }

    @Test
    fun theMaintainersAddressesOnMobileDataAreOffTheNetwork() {
        assertEquals(Reason.OFF_NETWORK, Unreachability.report(all(maintainer, Outcome.TIMED_OUT), mobile).reason)
    }

    @Test
    fun theSameOnWifiIsNoAnswerWithTheTailscaleHint() {
        val report = Unreachability.report(all(maintainer, Outcome.TIMED_OUT), wifi)
        assertEquals(Reason.NO_ANSWER, report.reason)
        assertTrue(report.tailscaleHint)
    }

    @Test
    fun aVpnRemovesTheTailscaleHintAndOffNetwork() {
        val vpnMobile = Network(NetworkKind.MOBILE, vpn = true)
        val report = Unreachability.report(all(maintainer, Outcome.TIMED_OUT), vpnMobile)
        assertEquals(Reason.NO_ANSWER, report.reason)
        assertFalse(report.tailscaleHint)
    }

    @Test
    fun lanAndGlobalV6OnWifiIsNoAnswerWithoutHint() {
        val addresses = listOf("192.168.0.199", "2001:b011:2020:b721::1")
        val report = Unreachability.report(listOf(addresses[0] to Outcome.NO_ROUTE, addresses[1] to Outcome.TIMED_OUT), wifi)
        assertEquals(Reason.NO_ANSWER, report.reason)
        assertFalse(report.tailscaleHint)
    }

    @Test
    fun anOtherAddressIsNeverOffTheNetwork() {
        val outcomes = all(listOf("192.168.0.199", "desk.example.org"), Outcome.TIMED_OUT)
        assertNotEquals(Reason.OFF_NETWORK, Unreachability.report(outcomes, mobile).reason)
        assertEquals(Reason.NO_ANSWER, Unreachability.report(outcomes, mobile).reason)
    }

    @Test
    fun mixedFailuresAreGeneric() {
        val outcomes = listOf("192.168.0.199" to Outcome.TIMED_OUT, "desk.example.org" to Outcome.NAME_NOT_FOUND)
        assertEquals(Reason.UNREACHABLE, Unreachability.report(outcomes, wifi).reason)
    }
}
