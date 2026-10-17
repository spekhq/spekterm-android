package com.spekterm.android.service

import com.spekterm.android.service.Unreachability.Network
import com.spekterm.android.service.Unreachability.NetworkKind
import com.spekterm.android.service.Unreachability.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lifecycle of a round's report (`scan-connect-zh` D6) and "Try now" (D7). */
class StatusTest {
    private val report = Unreachability.report(listOf("192.168.0.199" to Outcome.REFUSED), Network(NetworkKind.WIFI, vpn = false))

    @Test
    fun aFailedRoundAfter4005ClearsRemoteOff() {
        val status = Status(remoteOff = true).roundStarted().roundFailed(report)
        assertFalse(status.remoteOff)
        assertEquals(report, status.report)
    }

    @Test
    fun aPinnedOpenFollowedByThrottlingClearsTheReport() {
        val status = Status(report = report).roundStarted().pinnedOpen("192.168.0.199").waiting(retryAt = 60_000, throttled = true)
        assertNull(status.report)
        assertTrue(status.throttled)
        assertFalse(status.canTryNow(paused = false))
    }

    @Test
    fun pausingClearsTheReport() {
        assertNull(Status(report = report).paused().report)
    }

    @Test
    fun attemptsShowTryingThenTheirOutcome() {
        val status = Status().roundStarted().attemptStarted("a").attemptStarted("b").attemptFinished("a", Outcome.TIMED_OUT)
        assertEquals(mapOf("a" to Outcome.TIMED_OUT, "b" to null), status.attempts)
        assertTrue(status.roundStarted().attempts.isEmpty())
    }

    @Test
    fun tryNowOnlyWhileWaitingUnthrottledAndNotPaused() {
        val waiting = Status().waiting(retryAt = 1_000, throttled = false)
        assertTrue(waiting.canTryNow(paused = false))
        assertFalse(waiting.canTryNow(paused = true))
        assertFalse(Status(state = Status.State.CONNECTING).canTryNow(paused = false))
        assertFalse(waiting.roundStarted().canTryNow(paused = false))
    }
}
