package com.spekterm.android.service

import com.spekterm.android.net.RaceResult
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

    enum class Reason { NO_NETWORK, NOT_THE_DESKTOP, REFUSED, OFF_NETWORK, NO_ANSWER, UNREACHABLE }

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
    )

    fun outcome(result: RemoteClient.OpenResult): Outcome = when (result) {
        is RemoteClient.OpenResult.PinMismatch -> Outcome.CERTIFICATE
        is RemoteClient.OpenResult.Unreachable -> outcome(result.cause)
        is RemoteClient.OpenResult.Open -> Outcome.FAILED
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

    /** The report of a round [race] in which nothing opened, over the addresses in the order tried. */
    fun report(order: List<String>, race: RaceResult<*>, network: Network): Report {
        val outcomes = order.map { it to (race.finished[it]?.let(::outcome) ?: Outcome.NOT_TRIED) }
        return report(outcomes, network)
    }

    /** The spec's rules, the first that applies. */
    fun report(outcomes: List<Pair<String, Outcome>>, network: Network): Report {
        val kinds = outcomes.map { kind(it.first) }
        val noOther = kinds.none { it == AddressKind.OTHER }
        fun first(outcome: Outcome) = outcomes.firstOrNull { it.second == outcome }?.first
        return when {
            network.kind == NetworkKind.NONE -> Report(outcomes, network, Reason.NO_NETWORK)
            first(Outcome.CERTIFICATE) != null -> Report(outcomes, network, Reason.NOT_THE_DESKTOP, first(Outcome.CERTIFICATE))
            first(Outcome.REFUSED) != null -> Report(outcomes, network, Reason.REFUSED, first(Outcome.REFUSED))
            network.kind == NetworkKind.MOBILE && !network.vpn && noOther -> Report(outcomes, network, Reason.OFF_NETWORK)
            outcomes.isNotEmpty() && outcomes.all { it.second == Outcome.TIMED_OUT || it.second == Outcome.NO_ROUTE } ->
                Report(outcomes, network, Reason.NO_ANSWER, tailscaleHint = !network.vpn && AddressKind.TAILSCALE in kinds)
            else -> Report(outcomes, network, Reason.UNREACHABLE)
        }
    }

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = IPV4.matchEntire(host)?.groupValues?.drop(1)?.map { it.toInt() } ?: return null
        return if (parts.all { it in 0..255 }) parts[0] to parts[1] else null
    }
}
