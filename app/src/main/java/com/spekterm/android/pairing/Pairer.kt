package com.spekterm.android.pairing

import com.spekterm.android.net.ConnectionClosedException
import com.spekterm.android.net.Frames
import com.spekterm.android.net.PairResult
import com.spekterm.android.net.RemoteClient
import com.spekterm.android.net.RemoteError
import com.spekterm.android.service.ConnectionPolicy
import com.spekterm.android.store.Pairing

/**
 * Pairs with the desktop of a [PairingLink] (`device-pairing`, "Pair with the desktop"): the link's addresses in
 * order, over the pinned connection, `pair` as the first frame. The code is sent at most once: whatever the
 * first pinned connection answers ends the attempt.
 */
object Pairer {
    sealed interface Result {
        data class Paired(val pairing: Pairing) : Result
        data class Failed(val failure: Failure, val tried: List<String>, val detail: String? = null) : Result
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

    suspend fun pair(link: PairingLink, deviceName: String, deviceModel: String): Result {
        val tried = mutableListOf<String>()
        var mismatch = false
        for (address in link.addresses) {
            tried += address
            when (val opened = RemoteClient.open(address, link.port, link.fingerprint)) {
                is RemoteClient.OpenResult.PinMismatch -> mismatch = true
                is RemoteClient.OpenResult.Unreachable -> Unit
                is RemoteClient.OpenResult.Open -> {
                    val connection = opened.connection
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
            }
        }
        return Result.Failed(if (mismatch) Failure.WRONG_CERTIFICATE else Failure.UNREACHABLE, tried)
    }

    private fun failure(error: RemoteError?, tried: List<String>): Result.Failed = when (error?.code) {
        RemoteError.PAIRING_FAILED -> Result.Failed(Failure.CODE_INVALID, tried)
        RemoteError.THROTTLED -> Result.Failed(Failure.THROTTLED, tried)
        RemoteError.UNSUPPORTED_VERSION -> Result.Failed(Failure.UNSUPPORTED, tried, Frames.supportedVersions(error).joinToString())
        else -> Result.Failed(Failure.OTHER, tried, error?.let { "${it.code}: ${it.message}" })
    }
}
