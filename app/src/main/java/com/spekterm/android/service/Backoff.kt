package com.spekterm.android.service

import kotlin.math.min

/**
 * Reconnection delays (`desktop-connection`, design D5): `min(60 s, 1 s · 2^n) · U(0.5, 1)`, so jitter never
 * pushes a delay past the cap; after throttling, at least 60 s.
 *
 * [reset] is called only once a connection has authenticated **and** its attention watch was answered, so a
 * connection refused right after `hello` keeps backing off.
 */
class Backoff(private val random: () -> Double = Math::random) {
    private var failures = 0

    fun nextDelayMs(throttled: Boolean = false): Long {
        if (throttled) {
            failures++
            return (THROTTLE_MS * (1.0 + 0.25 * random())).toLong()
        }
        val base = min(CAP_MS, BASE_MS shl min(failures, 16))
        failures++
        return (base * (0.5 + 0.5 * random())).toLong()
    }

    fun reset() {
        failures = 0
    }

    companion object {
        const val BASE_MS = 1_000L
        const val CAP_MS = 60_000L
        /** The desktop refuses a throttled address for 60 s (`docs/remote-protocol.md` section 4). */
        const val THROTTLE_MS = 60_000L
    }
}
