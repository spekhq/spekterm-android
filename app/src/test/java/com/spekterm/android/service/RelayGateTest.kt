package com.spekterm.android.service

import com.spekterm.android.net.CloseInfo
import com.spekterm.android.net.RelayLeg
import com.spekterm.android.net.RelayOutcome
import com.spekterm.android.net.RemoteClient
import com.spekterm.android.net.RemoteError
import com.spekterm.android.store.RelayGrant
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `phone-relay` D4: the team server's answers and closes, the gate, how a relayed session ends. */
class RelayGateTest {
    private val now = 1_000_000L
    private val grant = RelayGrant("wss://team.example", "AB".repeat(32), "g", "s")

    private fun answer(code: String, retryAt: Long? = null) = RelayOutcome.Answer(
        RemoteError(code, detail = retryAt?.let { JsonObject(mapOf("retryAt" to JsonPrimitive(it))) }),
    )

    private fun action(outcome: RelayOutcome) = RelayRules.action(outcome, now)

    @Test
    fun everyRowOfTheTable() {
        assertEquals(RelayAction.Retry, action(RelayOutcome.Network(IOException("refused"))))
        assertEquals(RelayAction.Retry, action(RelayOutcome.TimedOut))
        assertEquals(RelayAction.Retry, action(RelayOutcome.Certificate(pinned = false)))
        assertEquals(RelayAction.OffOnThisNetwork, action(RelayOutcome.Certificate(pinned = true)))
        assertEquals(RelayAction.SetAside(AsideReason.RELAY_OFF), action(RelayOutcome.Http(404)))
        for (status in listOf(401, 403, 502, 503)) assertEquals(RelayAction.Retry, action(RelayOutcome.Http(status)))
        assertEquals(RelayAction.SetAside(AsideReason.RELAY_OFF), action(answer("RELAY_DISABLED")))
        assertEquals(RelayAction.SetAside(AsideReason.UNSUPPORTED), action(answer("UNSUPPORTED_VERSION")))
        assertEquals(RelayAction.DiscardGrant, action(answer("GRANT_INVALID")))
        for (code in listOf("DESKTOP_UNAVAILABLE", "DESKTOP_REFUSED", "RELAY_BUSY", "INTERNAL", "SOMETHING_NEW")) {
            assertEquals(code, RelayAction.Retry, action(answer(code)))
        }
        assertEquals(RelayAction.NotBefore(now + 300_000), action(answer("RATE_LIMITED", now + 300_000)))
        for (code in listOf("BAD_FRAME", "BAD_PARAMS", "UNKNOWN_OPERATION")) {
            assertEquals(code, RelayAction.SetAside(AsideReason.FAULT), action(answer(code)))
        }
        assertEquals(RelayAction.SetAside(AsideReason.FAULT), action(RelayOutcome.ProtocolFault("binary before the answer")))
        assertEquals(RelayAction.DiscardGrant, action(RelayOutcome.Closed(4004)))
        assertEquals(RelayAction.SetAside(AsideReason.FAULT), action(RelayOutcome.Closed(1003)))
        assertEquals(RelayAction.SetAside(AsideReason.FAULT), action(RelayOutcome.Closed(1009)))
        for (code in listOf(4010, 1001, 1000, 1011, 4001, 4003, 4999, null)) {
            assertEquals("$code", RelayAction.Retry, action(RelayOutcome.Closed(code)))
        }
    }

    @Test
    fun retryAtIsCappedAndDefaulted() {
        assertEquals(RelayAction.NotBefore(now + 15 * 60_000), action(answer("RATE_LIMITED", now + 20 * 60_000)))
        assertEquals(RelayAction.NotBefore(now + 60_000), action(answer("RATE_LIMITED")))
        assertEquals(RelayAction.NotBefore(now + 60_000), action(answer("RATE_LIMITED", now - 5)))
    }

    @Test
    fun theGateLeavesTheRelayOutAndANewGrantClearsIt() {
        val gate = RelayGate()
        assertTrue(gate.usable(grant, now, "wifi"))
        gate.apply(RelayAction.SetAside(AsideReason.RELAY_OFF), grant, "wifi")
        assertEquals(RelayGate.LeftOut.Aside(AsideReason.RELAY_OFF), gate.leftOut(grant, now + 10 * 3_600_000, "mobile"))
        val renewed = grant.copy(secret = "s2")
        assertTrue(gate.usable(renewed, now, "wifi"))

        gate.apply(RelayAction.NotBefore(now + 60_000), renewed, "wifi")
        assertEquals(RelayGate.LeftOut.Until(now + 60_000), gate.leftOut(renewed, now, "wifi"))
        assertTrue(gate.usable(renewed, now + 60_000, "wifi"))

        gate.apply(RelayAction.Retry, renewed, "wifi")
        gate.apply(RelayAction.DiscardGrant, renewed, "wifi")
        assertTrue(gate.usable(renewed, now + 60_000, "wifi"))
    }

    @Test
    fun aChangedCertificateLeavesTheRelayOutOnThatNetworkOnly() {
        val gate = RelayGate()
        gate.apply(RelayAction.OffOnThisNetwork, grant, "hotel-wifi")
        assertEquals(RelayGate.LeftOut.ThisNetwork, gate.leftOut(grant, now + 24 * 3_600_000, "hotel-wifi"))
        assertNull(gate.leftOut(grant, now, "mobile"))
    }

    @Test
    fun theTeamServers4004DiscardsTheGrantAndNeverUnpairs() {
        val drop = CloseInfo(null, "", IOException("unexpected end of stream"), null)
        val end = SessionEnd.decide(drop, null, RelayLeg.End(4004))
        assertEquals(ConnectionPolicy.Decision.Retry(), end.policy)
        assertEquals(RelayAction.DiscardGrant, end.relay)
    }

    @Test
    fun theDesktops4004StillUnpairs() {
        val end = SessionEnd.decide(CloseInfo(4004, "revoked", null, null), null, RelayLeg.End(null, byPhone = true))
        assertEquals(ConnectionPolicy.Decision.Unpair, end.policy)
        assertNull(end.relay)
    }

    @Test
    fun bothSidesDecideTheirOwnPart() {
        val end = SessionEnd.decide(CloseInfo(4005, "remote access off", null, null), null, RelayLeg.End(4004))
        assertEquals(ConnectionPolicy.Decision.Retry(remoteOff = true), end.policy)
        assertEquals(RelayAction.DiscardGrant, end.relay)
    }

    @Test
    fun theDesktopsThrottleThroughTheRelayWaits() {
        val end = SessionEnd.decide(CloseInfo(4030, "", null, null), RemoteError("THROTTLED"), RelayLeg.End(null, byPhone = true))
        assertEquals(ConnectionPolicy.Decision.Retry(throttled = true), end.policy)
        assertEquals(RelayAction.Retry, SessionEnd.decide(CloseInfo(null, "", IOException(), null), null, RelayLeg.End(4010)).relay)
        assertNull(SessionEnd.decide(CloseInfo(1000, "", null, null), null, null).relay)
    }

    @Test
    fun theTeamServersOutcomesSayNothingAboutTheDesktopsIdentity() {
        assertNull(identityInput(RemoteClient.OpenResult.Relayed(RelayOutcome.Certificate(pinned = true))))
        assertNull(identityInput(RemoteClient.OpenResult.Relayed(RelayOutcome.Certificate(pinned = false))))
        assertNull(identityInput(RemoteClient.OpenResult.Relayed(RelayOutcome.TimedOut)))
        assertEquals(IdentityTracker.Outcome.MISMATCH, identityInput(RemoteClient.OpenResult.PinMismatch("trust")))
        assertEquals(IdentityTracker.Outcome.UNREACHABLE, identityInput(RemoteClient.OpenResult.Unreachable(null)))
    }
}
