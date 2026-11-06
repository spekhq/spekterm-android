package com.spekterm.android.service

import com.spekterm.android.net.CloseInfo
import com.spekterm.android.net.RelayLeg
import com.spekterm.android.net.RelayOutcome
import com.spekterm.android.net.RemoteClient
import com.spekterm.android.net.RemoteError
import com.spekterm.android.store.RelayGrant

/** What the phone does after the team server's answer or close (`phone-relay` D4; server protocol section 13.6). */
sealed interface RelayAction {
    /** Try the relay again in the next round, with the round's backoff. */
    data object Retry : RelayAction

    /** The grant is not valid any more: forget it until the desktop gives a new one. */
    data object DiscardGrant : RelayAction

    /** Do not try the relay until the grant changes or the app restarts. */
    data class SetAside(val why: AsideReason) : RelayAction

    /** Leave the relay out of rounds until [at] (ms since the epoch). */
    data class NotBefore(val at: Long) : RelayAction

    /** The team server's certificate changed: not on this network; try again on another (D4). */
    data object OffOnThisNetwork : RelayAction
}

enum class AsideReason { RELAY_OFF, UNSUPPORTED, FAULT }

/** D4's table, pure. */
object RelayRules {
    const val RATE_LIMIT_CAP_MS = 15 * 60_000L
    const val RATE_LIMIT_DEFAULT_MS = 60_000L

    fun action(outcome: RelayOutcome, now: Long): RelayAction = when (outcome) {
        is RelayOutcome.Network, RelayOutcome.TimedOut -> RelayAction.Retry
        is RelayOutcome.Certificate -> if (outcome.pinned) RelayAction.OffOnThisNetwork else RelayAction.Retry
        is RelayOutcome.Http -> if (outcome.status == 404) RelayAction.SetAside(AsideReason.RELAY_OFF) else RelayAction.Retry
        is RelayOutcome.Answer -> answer(outcome.error, now)
        is RelayOutcome.Closed -> closed(outcome.code)
        is RelayOutcome.ProtocolFault -> RelayAction.SetAside(AsideReason.FAULT)
    }

    private fun answer(error: RemoteError, now: Long): RelayAction = when (error.code) {
        "GRANT_INVALID" -> RelayAction.DiscardGrant
        "RELAY_DISABLED" -> RelayAction.SetAside(AsideReason.RELAY_OFF)
        "UNSUPPORTED_VERSION" -> RelayAction.SetAside(AsideReason.UNSUPPORTED)
        "BAD_FRAME", "BAD_PARAMS", "UNKNOWN_OPERATION" -> RelayAction.SetAside(AsideReason.FAULT)
        "RATE_LIMITED" -> RelayAction.NotBefore(retryAt(error.detailLong("retryAt"), now))
        // DESKTOP_UNAVAILABLE, DESKTOP_REFUSED, RELAY_BUSY, INTERNAL and any code this app does not know.
        else -> RelayAction.Retry
    }

    /** A close of the team server's side; 4003 follows an error answer, which decides instead (D4). */
    fun closed(code: Int?): RelayAction = when (code) {
        4004 -> RelayAction.DiscardGrant
        1003, 1009 -> RelayAction.SetAside(AsideReason.FAULT)
        else -> RelayAction.Retry
    }

    fun retryAt(retryAt: Long?, now: Long): Long = when {
        retryAt == null || retryAt <= now -> now + RATE_LIMIT_DEFAULT_MS
        else -> minOf(retryAt, now + RATE_LIMIT_CAP_MS)
    }
}

/**
 * Whether the relay is tried in a round (`phone-relay` D4). In memory, so a restart of the app clears it; everything is
 * tied to the grant it was learned with, so a new grant from the desktop clears it too. [network] is the phone's
 * default network as the service sees it (any value that compares equal for the same network).
 */
class RelayGate {
    /** Why the relay is left out of a round, for the status screen. */
    sealed interface LeftOut {
        data class Aside(val why: AsideReason) : LeftOut

        data class Until(val at: Long) : LeftOut

        data object ThisNetwork : LeftOut

        /** For the connection log. */
        fun logName(): String = when (this) {
            is Aside -> "aside:" + why.name.lowercase()
            is Until -> "until"
            ThisNetwork -> "network"
        }
    }

    private var grant: RelayGrant? = null
    private var aside: AsideReason? = null
    private var notBefore = 0L
    private var offNetwork: Any? = null
    private var offNetworkSet = false

    @Synchronized
    fun apply(action: RelayAction, grant: RelayGrant, network: Any?) {
        follow(grant)
        when (action) {
            RelayAction.Retry, RelayAction.DiscardGrant -> Unit
            is RelayAction.SetAside -> aside = action.why
            is RelayAction.NotBefore -> notBefore = action.at
            RelayAction.OffOnThisNetwork -> {
                offNetwork = network
                offNetworkSet = true
            }
        }
    }

    /** `null` when the relay is tried in a round now; otherwise why not. */
    @Synchronized
    fun leftOut(grant: RelayGrant, now: Long, network: Any?): LeftOut? {
        follow(grant)
        aside?.let { return LeftOut.Aside(it) }
        if (offNetworkSet && offNetwork == network) return LeftOut.ThisNetwork
        if (now < notBefore) return LeftOut.Until(notBefore)
        return null
    }

    fun usable(grant: RelayGrant, now: Long, network: Any?): Boolean = leftOut(grant, now, network) == null

    /** Another grant than the one the state was learned with: start afresh. */
    private fun follow(grant: RelayGrant) {
        if (grant == this.grant) return
        this.grant = grant
        aside = null
        notBefore = 0L
        offNetwork = null
        offNetworkSet = false
    }
}

/**
 * How a session ended (`phone-relay` D4): [policy] from the desktop's own close code and error only — the team server's
 * 4004 never ends the pairing — and [relay], what the team server's side says about the grant and the relay.
 */
data class SessionEnd(val policy: ConnectionPolicy.Decision, val relay: RelayAction?) {
    companion object {
        fun decide(inner: CloseInfo?, error: RemoteError?, leg: RelayLeg.End?): SessionEnd = SessionEnd(
            policy = ConnectionPolicy.decide(inner?.code, error),
            relay = leg?.takeUnless { it.byPhone }?.let { RelayRules.closed(it.code) },
        )
    }
}

/** What an attempt tells the identity tracker: the team server's outcomes say nothing about the desktop (D4). */
fun identityInput(result: RemoteClient.OpenResult): IdentityTracker.Outcome? = when (result) {
    is RemoteClient.OpenResult.Open -> IdentityTracker.Outcome.MATCH
    is RemoteClient.OpenResult.PinMismatch -> IdentityTracker.Outcome.MISMATCH
    is RemoteClient.OpenResult.Unreachable -> IdentityTracker.Outcome.UNREACHABLE
    is RemoteClient.OpenResult.Relayed -> null
}
