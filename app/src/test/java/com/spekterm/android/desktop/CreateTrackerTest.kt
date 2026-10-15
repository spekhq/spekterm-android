package com.spekterm.android.desktop

import com.spekterm.android.desktop.Creation.Reason
import com.spekterm.android.net.Session
import com.spekterm.android.net.SessionList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreateOutcomeTest {
    @Test
    fun mapsEveryAnswer() {
        assertEquals(CreateOutcome.Created("n1"), CreateOutcome.of(true, "n1", null, null))
        assertEquals(CreateOutcome.Refused(Reason.CREATE_FAILED), CreateOutcome.of(true, null, null, null))
        assertEquals(CreateOutcome.Refused(Reason.NO_WINDOW), CreateOutcome.of(true, null, "NO_WINDOW", "the desktop has no window open"))
        assertEquals(CreateOutcome.Refused(Reason.CREATE_FAILED, "folder gone"), CreateOutcome.of(true, null, "CREATE_FAILED", "folder gone"))
        assertEquals(CreateOutcome.Refused(Reason.CREATE_FAILED), CreateOutcome.of(true, null, "CREATE_FAILED", " "))
        assertEquals(CreateOutcome.Refused(Reason.UNKNOWN), CreateOutcome.of(true, null, "TIMEOUT", "the desktop did not answer in time"))
        assertEquals(CreateOutcome.Refused(Reason.UNKNOWN), CreateOutcome.of(false, null, null, null))
        assertEquals(CreateOutcome.Refused(Reason.CREATE_FAILED), CreateOutcome.of(true, null, "BAD_PARAMS", "x"))
    }
}

class CreateTrackerTest {
    private val conn = Any()
    private val other = Any()
    private val starting = CreateTracker.State(null, Creation.Starting("alpha"))

    private fun listed(id: String, running: Boolean, window: Boolean = true) =
        SessionList(window, emptyList(), if (window) listOf(Session(id, "f1", "claude", running = running)) else emptyList())

    private fun answered(list: SessionList? = null, attached: Any? = conn, onScreen: Boolean = true, id: Long = 1) =
        CreateTracker.answered(starting, id, "alpha", CreateOutcome.Created("n1"), conn, attached, list, onScreen)

    @Test
    fun anAnswerThenAListHoldingItRunningOpensIt() {
        val waiting = answered()
        assertEquals(Creation.Starting("alpha"), waiting.creation)
        val done = CreateTracker.listed(waiting, listed("n1", running = true), conn, listOnScreen = true)
        assertEquals(Creation.Opened("n1", "alpha", autoOpen = true), done.creation)
        assertNull(done.pending)
    }

    @Test
    fun aListAlreadyHoldingItRunningOpensItAtOnce() {
        val done = answered(list = listed("n1", running = true), onScreen = false)
        assertEquals(Creation.Opened("n1", "alpha", autoOpen = false), done.creation)
        assertNull(done.pending)
    }

    @Test
    fun aNotYetRunningListingKeepsWaiting() {
        val waiting = answered(list = listed("n1", running = false))
        val still = CreateTracker.listed(waiting, listed("n1", running = false), conn, true)
        assertEquals(Creation.Starting("alpha"), still.creation)
        assertEquals("n1", still.pending?.sessionId)
        assertEquals(Creation.Opened("n1", "alpha", true), CreateTracker.listed(still, listed("n1", running = true), conn, true).creation)
    }

    @Test
    fun aListOfAnotherConnectionOrWithoutTheSessionIsIgnored() {
        val waiting = answered()
        assertEquals(waiting, CreateTracker.listed(waiting, listed("n1", running = true), other, true))
        assertEquals(waiting, CreateTracker.listed(waiting, listed("x", running = true), conn, true))
        assertEquals(waiting, CreateTracker.listed(waiting, listed("n1", running = true, window = false), conn, true))
    }

    @Test
    fun aDetachOrATimeoutIsUnlisted() {
        val waiting = answered()
        assertEquals(CreateTracker.State(null, Creation.Unlisted("alpha")), CreateTracker.detached(waiting, conn))
        assertEquals(waiting, CreateTracker.detached(waiting, other))
        assertEquals(CreateTracker.State(null, Creation.Unlisted("alpha")), CreateTracker.timedOut(waiting, 1))
    }

    @Test
    fun anAnswerAfterItsConnectionDetachedIsUnlistedAtOnce() {
        val done = answered(attached = null)
        assertEquals(CreateTracker.State(null, Creation.Unlisted("alpha")), done)
        assertEquals(CreateTracker.State(null, Creation.Unlisted("alpha")), answered(attached = other))
    }

    @Test
    fun theFirstEventWinsAndLaterOnesAreIgnored() {
        val opened = CreateTracker.listed(answered(), listed("n1", running = true), conn, true)
        assertEquals(opened, CreateTracker.detached(opened, conn))
        assertEquals(opened, CreateTracker.timedOut(opened, 1))
        val unlisted = CreateTracker.timedOut(answered(), 1)
        assertEquals(unlisted, CreateTracker.listed(unlisted, listed("n1", running = true), conn, true))
    }

    @Test
    fun anEarlierCreatesTimerDoesNotEndALaterWait() {
        val later = answered(id = 2)
        assertEquals(later, CreateTracker.timedOut(later, 1))
        assertEquals(Creation.Unlisted("alpha"), CreateTracker.timedOut(later, 2).creation)
    }

    @Test
    fun aRefusalFailsWithItsReason() {
        val failed = CreateTracker.answered(starting, 1, "alpha", CreateOutcome.Refused(Reason.CREATE_FAILED, "gone"), conn, conn, null, true)
        assertEquals(CreateTracker.State(null, Creation.Failed("alpha", Reason.CREATE_FAILED, "gone")), failed)
    }

    @Test
    fun leavingTheListKeepsAnUnconsumedOpenedFromNavigating() {
        val opened = CreateTracker.State(null, Creation.Opened("n1", "alpha", autoOpen = true))
        assertEquals(Creation.Opened("n1", "alpha", autoOpen = false), CreateTracker.leftList(opened).creation)
        assertEquals(starting, CreateTracker.leftList(starting))
    }
}
