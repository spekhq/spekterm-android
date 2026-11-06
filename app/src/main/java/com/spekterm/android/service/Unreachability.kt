package com.spekterm.android.service

import com.spekterm.android.net.RaceResult
import com.spekterm.android.net.RelayOutcome
import com.spekterm.android.net.Route
import com.spekterm.android.net.RemoteClient
import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why the desktop could not be reached in a round (`desktop-connection`, "Say why the desktop cannot be reached";
 * `scan-connect-zh` D6). Pure: the service and the pairing screen supply the network.
 */
object Unreachability {
    /** What one address came to in a round. */
    enum class Outcome { TIMED_OUT, REFUSED, NO_ROUTE, NAME_NOT_FOUND, CERTIFICATE, FAILED, NOT_TRIED }

    enum class AddressKind { LOCAL, TAILSCALE, GLOBAL_V6, OTHER }

    enum class NetworkKind { NONE, WIFI, MOBILE, OTHER }

    data class Network(val kind: NetworkKind, val vpn: Boolean)

    enum class Reason { NO_NETWORK, NOT_THE_DESKTOP, REFUSED, DESKTOP_OFFLINE, OFF_NETWORK, NO_ANSWER, UNREACHABLE }

    /** What the team server's relay came to in a round (`phone-relay` D6). */
    enum class RelayResult {
        TIMED_OUT, REFUSED, NO_ROUTE, NAME_NOT_FOUND, FAILED,
        /** The connection inside the relay presented another certificate than the pairing's: not the desktop. */
        DESKTOP_CERTIFICATE,
        /** `DESKTOP_UNAVAILABLE`: the team server reached no desktop either. */
        DESKTOP_OFFLINE,
        DESKTOP_REFUSED, BUSY, RATE_LIMITED, RELAY_OFF, GRANT_INVALID,
        /** The team server's certificate failed the system's check (no pin in the grant). */
        CERTIFICATE,
        /** The team server's certificate is not the grant's pin. */
        CERTIFICATE_CHANGED,
    }

    /**
     * The relay in a round: the team server's [host], what it came to ([result], `null` while trying or when it was not
     * started), whether it was [started], and why it was left out of the round ([leftOut]).
     */
    data class RelayRow(val host: String, val result: RelayResult? = null, val started: Boolean = false, val leftOut: RelayGate.LeftOut? = null)

    /**
     * A round in which no address connected. [address] is the one a reason names: the first that refused, or the
     * first that presented another certificate. [tailscaleHint]: some address is on Tailscale and no VPN was on.
     */
    data class Report(
        val outcomes: List<Pair<String, Outcome>>,
        val network: Network,
        val reason: Reason,
        val address: String? = null,
        val tailscaleHint: Boolean = false,
        /** The relay, when the phone held a grant: after the addresses, with its result or why it was left out. */
        val relay: RelayRow? = null,
    ) {
        /** Not the desktop because of the connection inside the relay, not a direct address. */
        val throughRelay: Boolean get() = reason == Reason.NOT_THE_DESKTOP && address == null && relay?.result == RelayResult.DESKTOP_CERTIFICATE
    }

    fun outcome(result: RemoteClient.OpenResult): Outcome = when (result) {
        is RemoteClient.OpenResult.PinMismatch -> Outcome.CERTIFICATE
        is RemoteClient.OpenResult.Unreachable -> outcome(result.cause)
        is RemoteClient.OpenResult.Open -> Outcome.FAILED
        is RemoteClient.OpenResult.Relayed -> Outcome.FAILED
    }

    /** What a relay attempt came to, for the status screen and the reason (`phone-relay` D6). */
    fun relayResult(result: RemoteClient.OpenResult): RelayResult = when (result) {
        is RemoteClient.OpenResult.PinMismatch -> RelayResult.DESKTOP_CERTIFICATE
        is RemoteClient.OpenResult.Unreachable -> addressLike(outcome(result.cause))
        is RemoteClient.OpenResult.Open -> RelayResult.FAILED
        is RemoteClient.OpenResult.Relayed -> when (val o = result.outcome) {
            is RelayOutcome.Network -> addressLike(outcome(o.cause))
            RelayOutcome.TimedOut -> RelayResult.TIMED_OUT
            is RelayOutcome.Certificate -> if (o.pinned) RelayResult.CERTIFICATE_CHANGED else RelayResult.CERTIFICATE
            is RelayOutcome.Http -> if (o.status == 404) RelayResult.RELAY_OFF else RelayResult.FAILED
            is RelayOutcome.Answer -> when (o.error.code) {
                "DESKTOP_UNAVAILABLE" -> RelayResult.DESKTOP_OFFLINE
                "DESKTOP_REFUSED" -> RelayResult.DESKTOP_REFUSED
                "RELAY_BUSY" -> RelayResult.BUSY
                "RATE_LIMITED" -> RelayResult.RATE_LIMITED
                "RELAY_DISABLED" -> RelayResult.RELAY_OFF
                "GRANT_INVALID" -> RelayResult.GRANT_INVALID
                else -> RelayResult.FAILED
            }
            is RelayOutcome.Closed -> if (o.code == 4004) RelayResult.GRANT_INVALID else RelayResult.FAILED
            is RelayOutcome.ProtocolFault -> RelayResult.FAILED
        }
    }

    private fun addressLike(outcome: Outcome): RelayResult = when (outcome) {
        Outcome.TIMED_OUT -> RelayResult.TIMED_OUT
        Outcome.REFUSED -> RelayResult.REFUSED
        Outcome.NO_ROUTE -> RelayResult.NO_ROUTE
        Outcome.NAME_NOT_FOUND -> RelayResult.NAME_NOT_FOUND
        else -> RelayResult.FAILED
    }

    /**
     * By class and errno text along the cause chain (and suppressed exceptions, where OkHttp keeps the other
     * routes' failures), so it runs on the JVM: `android.system.ErrnoException` is not on the test classpath.
     */
    fun outcome(cause: Throwable?): Outcome {
        val chain = generateSequence(cause) { it.cause }.take(10).flatMap { sequenceOf(it) + it.suppressed.asSequence() }.toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }
        return when {
            chain.any { it is UnknownHostException } -> Outcome.NAME_NOT_FOUND
            "ECONNREFUSED" in text || "Connection refused" in text -> Outcome.REFUSED
            chain.any { it is NoRouteToHostException } || "EHOSTUNREACH" in text || "ENETUNREACH" in text -> Outcome.NO_ROUTE
            chain.any { it is SocketTimeoutException } || "ETIMEDOUT" in text -> Outcome.TIMED_OUT
            chain.any { it is InterruptedIOException && it.message == "timeout" } -> Outcome.TIMED_OUT
            else -> Outcome.FAILED
        }
    }

    fun kind(address: String): AddressKind {
        val host = address.removePrefix("[").removeSuffix("]").lowercase()
        ipv4(host)?.let { (a, b) ->
            return when {
                a == 100 && b and 0xc0 == 64 -> AddressKind.TAILSCALE
                a == 10 || (a == 172 && b and 0xf0 == 16) || (a == 192 && b == 168) || (a == 169 && b == 254) -> AddressKind.LOCAL
                else -> AddressKind.OTHER
            }
        }
        if (':' in host) {
            // A literal: InetAddress parses it without a lookup.
            val bytes = (runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet6Address)?.address ?: return AddressKind.OTHER
            val b = bytes.map { it.toInt() and 0xff }
            return when {
                b.take(6) == listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0) -> AddressKind.TAILSCALE
                (b[0] == 0xfe && b[1] and 0xc0 == 0x80) || b[0] and 0xfe == 0xfc -> AddressKind.LOCAL
                b[0] and 0xe0 == 0x20 -> AddressKind.GLOBAL_V6
                else -> AddressKind.OTHER
            }
        }
        return when {
            host.endsWith(".ts.net") -> AddressKind.TAILSCALE
            host.endsWith(".local") -> AddressKind.LOCAL
            else -> AddressKind.OTHER
        }
    }

    /** The report of a pairing round [race] (direct addresses only) in which nothing opened, in the order tried. */
    fun report(order: List<String>, race: RaceResult<String, *>, network: Network): Report =
        report(order.map { it to (race.finished[it]?.let(::outcome) ?: Outcome.NOT_TRIED) }, network)

    /**
     * The report of a connection round over [routes] in which nothing opened: the direct addresses in the order tried,
     * then the relay ([relay], `null` when the phone holds no grant) with its result.
     */
    fun report(routes: List<Route>, race: RaceResult<Route, *>, network: Network, relay: RelayRow?): Report {
        val outcomes = routes.filterIsInstance<Route.Direct>().map { it.address to (race.finished[it]?.let(::outcome) ?: Outcome.NOT_TRIED) }
        val relayRoute = routes.filterIsInstance<Route.Relay>().firstOrNull()
        val row = relay?.let { row ->
            val finished = relayRoute?.let { race.finished[it] }
            row.copy(result = finished?.let(::relayResult), started = relayRoute != null && relayRoute !in race.notTried)
        }
        return report(outcomes, network, row)
    }

    /**
     * The spec's rules, the first that applies. Address kinds, refusals and "no answer" look at the direct addresses
     * only; "not the desktop" also counts the connection inside the relay (`phone-relay` D6).
     */
    fun report(outcomes: List<Pair<String, Outcome>>, network: Network, relay: RelayRow? = null): Report {
        val kinds = outcomes.map { kind(it.first) }
        val noOther = kinds.none { it == AddressKind.OTHER }
        fun first(outcome: Outcome) = outcomes.firstOrNull { it.second == outcome }?.first
        fun report(reason: Reason, address: String? = null, tailscaleHint: Boolean = false) =
            Report(outcomes, network, reason, address, tailscaleHint, relay)
        return when {
            network.kind == NetworkKind.NONE -> report(Reason.NO_NETWORK)
            first(Outcome.CERTIFICATE) != null -> report(Reason.NOT_THE_DESKTOP, first(Outcome.CERTIFICATE))
            relay?.result == RelayResult.DESKTOP_CERTIFICATE -> report(Reason.NOT_THE_DESKTOP)
            first(Outcome.REFUSED) != null -> report(Reason.REFUSED, first(Outcome.REFUSED))
            relay?.result == RelayResult.DESKTOP_OFFLINE -> report(Reason.DESKTOP_OFFLINE, relay.host)
            network.kind == NetworkKind.MOBILE && !network.vpn && noOther -> report(Reason.OFF_NETWORK)
            outcomes.isNotEmpty() && outcomes.all { it.second == Outcome.TIMED_OUT || it.second == Outcome.NO_ROUTE } ->
                report(Reason.NO_ANSWER, tailscaleHint = !network.vpn && AddressKind.TAILSCALE in kinds)
            else -> report(Reason.UNREACHABLE)
        }
    }

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = IPV4.matchEntire(host)?.groupValues?.drop(1)?.map { it.toInt() } ?: return null
        return if (parts.all { it in 0..255 }) parts[0] to parts[1] else null
    }
}
