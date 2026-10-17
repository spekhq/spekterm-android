package com.spekterm.android.pairing

import com.spekterm.android.net.Attempt
import com.spekterm.android.net.ConnectionClosedException
import com.spekterm.android.net.Frames
import com.spekterm.android.net.PairResult
import com.spekterm.android.net.RemoteClient
import com.spekterm.android.net.RemoteConnection
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.STAGGER_MS
import com.spekterm.android.net.attempt
import com.spekterm.android.net.race
import com.spekterm.android.service.ConnectionPolicy
import com.spekterm.android.service.Unreachability
import com.spekterm.android.store.Pairing

/**
 * Pairs with the desktop of a [PairingLink] (`device-pairing`, "Pair with the desktop"): the link's addresses
 * raced over the pinned connection (`scan-connect-zh` D5), `pair` as the first frame of the winner only. The code
 * is sent at most once: whatever the first pinned connection answers ends the attempt.
 */
object Pairer {
    sealed interface Result {
        data class Paired(val pairing: Pairing) : Result

        /** [report] says why no address connected, for [Failure.UNREACHABLE] and [Failure.WRONG_CERTIFICATE]. */
        data class Failed(
            val failure: Failure,
            val tried: List<String>,
            val detail: String? = null,
            val report: Unreachability.Report? = null,
        ) : Result
    }

    enum class Failure {
        /** `PAIRING_FAILED`: used, expired, or its view was closed. */
        CODE_INVALID,
        /** `THROTTLED` (an `id: null` frame) or close 4030: wait a minute. */
        THROTTLED,
        /** `UNSUPPORTED_VERSION`. */
        UNSUPPORTED,
        /** Some address presented a certificate that is not the one in the link. */
        WRONG_CERTIFICATE,
        /** No address accepted a connection. */
        UNREACHABLE,
        OTHER,
    }

    /** [network] is read once the race has failed; [onAttempt] is called as each address is tried. */
    suspend fun pair(
        link: PairingLink,
        deviceName: String,
        deviceModel: String,
        network: () -> Unreachability.Network,
        onAttempt: (String) -> Unit = {},
    ): Result = pairWith(
        link,
        open = { RemoteClient.open(it, link.port, link.fingerprint).attempt() },
        drop = RemoteConnection::cancel,
        exchange = { address, connection -> exchange(link, address, connection, deviceName, deviceModel) },
        network = network,
        onAttempt = onAttempt,
    )

    /** The race and its failure, apart from the connection's own type, so it is testable without sockets. */
    internal suspend fun <C> pairWith(
        link: PairingLink,
        open: suspend (String) -> Attempt<C>,
        drop: (C) -> Unit,
        exchange: suspend (String, C) -> Result,
        network: () -> Unreachability.Network,
        onAttempt: (String) -> Unit = {},
        stagger: Long = STAGGER_MS,
    ): Result {
        val race = race(link.addresses, open, drop, stagger, onStart = onAttempt)
        race.winner?.let { (address, connection) -> return exchange(address, connection) }
        val report = Unreachability.report(link.addresses, race, network())
        val mismatch = race.finished.values.any { it is RemoteClient.OpenResult.PinMismatch }
        return Result.Failed(if (mismatch) Failure.WRONG_CERTIFICATE else Failure.UNREACHABLE, link.addresses, report = report)
    }

    private suspend fun exchange(link: PairingLink, address: String, connection: RemoteConnection, deviceName: String, deviceModel: String): Result {
        val tried = link.addresses
        return try {
            val answer = connection.call("pair", Frames.pair(link.code, deviceName, deviceModel))
            val result = answer.result?.let { Frames.decodeOrNull<PairResult>(it) }
            when {
                answer.ok && result != null -> Result.Paired(
                    Pairing(
                        // The link's `n` is the desktop's host name; `server.name` is the application's.
                        desktopName = link.name.ifEmpty { result.server.name },
                        addresses = link.addresses,
                        port = link.port,
                        fingerprint = link.fingerprint,
                        deviceId = result.deviceId,
                        deviceSecret = result.deviceSecret,
                        lastAddress = address,
                        latestAddresses = Frames.addresses(answer.result) ?: emptyList(),
                    ),
                )
                else -> failure(answer.error, tried)
            }
        } catch (e: ConnectionClosedException) {
            if (e.info.code == ConnectionPolicy.THROTTLED || e.info.error?.code == RemoteError.THROTTLED) {
                Result.Failed(Failure.THROTTLED, tried)
            } else {
                Result.Failed(Failure.OTHER, tried, e.info.code?.let { "closed $it" } ?: e.info.failure?.message)
            }
        } finally {
            // The service takes over with `hello`; this connection only pairs (design D5).
            connection.close()
        }
    }

    private fun failure(error: RemoteError?, tried: List<String>): Result.Failed = when (error?.code) {
        RemoteError.PAIRING_FAILED -> Result.Failed(Failure.CODE_INVALID, tried)
        RemoteError.THROTTLED -> Result.Failed(Failure.THROTTLED, tried)
        RemoteError.UNSUPPORTED_VERSION -> Result.Failed(Failure.UNSUPPORTED, tried, Frames.supportedVersions(error).joinToString())
        else -> Result.Failed(Failure.OTHER, tried, error?.let { "${it.code}: ${it.message}" })
    }
}
