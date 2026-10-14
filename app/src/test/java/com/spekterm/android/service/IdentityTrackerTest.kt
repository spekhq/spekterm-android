package com.spekterm.android.service

import com.spekterm.android.service.IdentityTracker.Outcome.MATCH
import com.spekterm.android.service.IdentityTracker.Outcome.MISMATCH
import com.spekterm.android.service.IdentityTracker.Outcome.UNREACHABLE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTrackerTest {
    private fun IdentityTracker.round(vararg outcomes: IdentityTracker.Outcome): Boolean {
        outcomes.forEach { record(it) }
        return endRound()
    }

    @Test
    fun oneMismatchAndTwoUnreachableCountsAsARound() {
        // At home without Tailscale after a desktop reset: the LAN address mismatches, the others are unreachable.
        val tracker = IdentityTracker()
        assertFalse(tracker.round(MISMATCH, UNREACHABLE, UNREACHABLE))
        assertFalse(tracker.round(MISMATCH, UNREACHABLE, UNREACHABLE))
        assertTrue(tracker.round(MISMATCH, UNREACHABLE, UNREACHABLE))
    }

    @Test
    fun unreachableRoundsCountNeitherWay() {
        val tracker = IdentityTracker()
        tracker.round(MISMATCH)
        tracker.round(MISMATCH)
        assertFalse(tracker.round(UNREACHABLE, UNREACHABLE))
        assertTrue(tracker.round(MISMATCH))
    }

    @Test
    fun aMatchClearsIt() {
        val tracker = IdentityTracker()
        repeat(3) { tracker.round(MISMATCH) }
        assertTrue(tracker.changed)
        assertFalse(tracker.round(MISMATCH, MATCH))
        assertFalse(tracker.changed)
        // and the count starts over
        assertFalse(tracker.round(MISMATCH))
        assertFalse(tracker.round(MISMATCH))
    }

    @Test
    fun aMatchInterruptsTheStreak() {
        val tracker = IdentityTracker()
        tracker.round(MISMATCH)
        tracker.round(MISMATCH)
        tracker.round(MATCH)
        assertFalse(tracker.round(MISMATCH))
    }
}
