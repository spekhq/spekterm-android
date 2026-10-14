package com.spekterm.android.service

/**
 * Notices that the desktop's identity changed (`desktop-connection`, design D5): after a reset the desktop has
 * a new certificate, and a phone that was away never hears 4004 — it only meets a certificate that does not
 * match its pin.
 *
 * Raised after [ROUNDS] consecutive rounds in each of which at least one address presented a mismatching
 * certificate and none presented the pinned one. Unreachable addresses count neither way: the link lists
 * addresses (Tailscale, IPv6) that are often unreachable. A matching handshake clears it.
 */
class IdentityTracker {
    enum class Outcome { MATCH, MISMATCH, UNREACHABLE }

    private var round = mutableListOf<Outcome>()
    private var mismatchRounds = 0

    var changed = false
        private set

    fun record(outcome: Outcome) {
        round.add(outcome)
    }

    /** Ends a round of attempts; returns [changed]. */
    fun endRound(): Boolean {
        when {
            Outcome.MATCH in round -> {
                mismatchRounds = 0
                changed = false
            }
            Outcome.MISMATCH in round -> {
                mismatchRounds++
                if (mismatchRounds >= ROUNDS) changed = true
            }
        }
        round = mutableListOf()
        return changed
    }

    companion object {
        const val ROUNDS = 3
    }
}
