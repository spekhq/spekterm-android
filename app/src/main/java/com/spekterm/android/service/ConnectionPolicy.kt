package com.spekterm.android.service

import com.spekterm.android.net.Frames
import com.spekterm.android.net.RemoteError

/**
 * What to do after a connection ended (`desktop-connection`, "Honor the desktop's close codes").
 *
 * [closeCode] is the WebSocket close code the desktop sent, or `null` when the connection failed without one.
 * [error] is the code of the error that answered `hello` (or arrived with `id: null`) on that connection.
 */
object ConnectionPolicy {
    const val NO_AUTH_IN_TIME = 4001
    const val REFUSED = 4003
    const val REVOKED = 4004
    const val REMOTE_OFF = 4005
    const val TOO_MANY = 4029
    const val THROTTLED = 4030

    sealed interface Decision {
        /** Revoked, reset, or an unknown credential: forget the pairing and ask to pair again. */
        data object Unpair : Decision

        /** The versions do not overlap: stop, keep the pairing, and say which side needs updating. */
        data class Unsupported(val hint: UpdateHint) : Decision

        /** Try again after the backoff delay. */
        data class Retry(val throttled: Boolean = false, val remoteOff: Boolean = false) : Decision
    }

    enum class UpdateHint { UPDATE_APP, UPDATE_DESKTOP, UNKNOWN }

    fun decide(closeCode: Int?, error: RemoteError?): Decision = when {
        closeCode == REVOKED || error?.code == RemoteError.AUTH_FAILED -> Decision.Unpair
        error?.code == RemoteError.UNSUPPORTED_VERSION -> Decision.Unsupported(updateHint(Frames.supportedVersions(error)))
        closeCode == THROTTLED || error?.code == RemoteError.THROTTLED -> Decision.Retry(throttled = true)
        closeCode == REMOTE_OFF -> Decision.Retry(remoteOff = true)
        else -> Decision.Retry()
    }

    /** Compares the desktop's `detail.supported` with the app's version. */
    fun updateHint(supported: List<Int>, ours: Int = Frames.PROTOCOL): UpdateHint = when {
        supported.isEmpty() -> UpdateHint.UNKNOWN
        supported.all { it > ours } -> UpdateHint.UPDATE_APP
        supported.all { it < ours } -> UpdateHint.UPDATE_DESKTOP
        else -> UpdateHint.UNKNOWN
    }
}
