package com.spekterm.android.desktop

import com.spekterm.android.desktop.OpenSpecTracker.Watch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSpecTrackerTest {
    private val aChanges = OpenSpecTarget.Changes("A")
    private val aSpecs = OpenSpecTarget.Specs("A")
    private val aChange = OpenSpecTarget.Change("A", "x")
    private val bChanges = OpenSpecTarget.Changes("B")

    /** A connected tracker; [confirmed] as if its watch had been answered. */
    private fun live(confirmed: String? = null) = OpenSpecTracker(attached = true, live = true, wanted = confirmed, confirmed = confirmed)

    private fun OpenSpecTracker.Step.sends(watch: Watch?, read: OpenSpecTarget?): OpenSpecTracker {
        assertEquals("watch", watch, this.watch)
        assertEquals("read", read, this.read)
        return state
    }

    @Test
    fun aLiveFocusWatchesBeforeItReads() {
        var t = live().focus("o1", aChanges).sends(Watch("A"), null)
        assertTrue(t.pending(aChanges))
        t = t.watchAnswered(true).sends(null, aChanges)
        assertTrue(t.pending(aChanges))
        t = t.readAnswered().sends(null, null)
        assertFalse(t.pending(aChanges))
    }

    @Test
    fun aFocusInTheConfirmedFolderReadsAtOnce() {
        live("A").focus("o1", aChanges).sends(null, aChanges)
    }

    @Test
    fun aDesktopWithoutTheWatchIsReadAndNeverWatched() {
        var t = OpenSpecTracker(attached = true, live = false).focus("o1", aChanges).sends(null, aChanges)
        t = t.readAnswered().sends(null, null)
        t = t.refresh().sends(null, aChanges)
        t.readAnswered().state.unfocus("o1").state.idle().sends(null, null)
    }

    @Test
    fun switchingTabsWhileTheWatchIsInFlightSendsNoSecondWatch() {
        var t = live().focus("o1", aChanges).sends(Watch("A"), null)
        t = t.focus("o1", aSpecs).sends(null, null)
        t.watchAnswered(true).sends(null, aSpecs)
    }

    @Test
    fun movingToAnotherFolderWaitsForTheWatchInFlight() {
        var t = live().focus("o1", aChanges).sends(Watch("A"), null)
        t = t.focus("o2", bChanges).sends(null, null)
        t = t.watchAnswered(true).sends(Watch("B"), null)
        t.watchAnswered(true).sends(null, bChanges)
    }

    @Test
    fun idleWhileTheWatchIsInFlightStopsAfterItsAnswer() {
        var t = live().focus("o1", aChanges).sends(Watch("A"), null)
        t = t.unfocus("o1").sends(null, null)
        t = t.idle().sends(null, null)
        // The focus left: no read; the stop follows the answer that recorded A.
        t = t.watchAnswered(true).sends(Watch(null), null)
        t = t.watchAnswered(true).sends(null, null)
        assertNull(t.confirmed)
    }

    @Test
    fun aRefusedWatchKeepsTheConfirmedFolderReadsAndStops() {
        var t = live("A").focus("o1", bChanges).sends(Watch("B"), null)
        // The read of B and the stop go together: no events for a folder not shown.
        t = t.watchAnswered(false).sends(Watch(null), bChanges)
        assertEquals("A", t.confirmed)
        assertTrue(t.notLive(bChanges))
        t = t.watchAnswered(true).sends(null, null)
        assertNull(t.confirmed)
        // Not asked again on this connection.
        t = t.readAnswered().state.focus("o1", OpenSpecTarget.Specs("B")).sends(null, OpenSpecTarget.Specs("B"))
    }

    @Test
    fun eventsReadOnlyTheConfirmedFolderOnScreen() {
        var t = live("A").focus("o1", aChanges).sends(null, aChanges).readAnswered().state
        t = t.changed("A").sends(null, aChanges).readAnswered().state
        t.changed("B").sends(null, null)
        t.unfocus("o1").state.changed("A").sends(null, null)
    }

    @Test
    fun aBurstOfEventsDuringAReadCostsOneMoreRead() {
        var t = live("A").focus("o1", aChange).sends(null, aChange)
        t = t.changed("A").sends(null, null)
        t = t.changed("A").sends(null, null)
        t = t.changed("A").sends(null, null)
        t = t.readAnswered().sends(null, aChange)
        t.readAnswered().sends(null, null)
    }

    @Test
    fun theStaleFlagIsClearedWhenTheReadIsSentSoAnEventDuringItReadsAgain() {
        var t = live("A").focus("o1", aChange).sends(null, aChange)
        assertFalse(t.stale)
        t = t.changed("A").state
        assertTrue(t.stale)
        t.readAnswered().sends(null, aChange)
    }

    @Test
    fun aFocusMovedDuringAReadIsReadAfterIt() {
        var t = live("A").focus("o1", aChanges).sends(null, aChanges)
        t = t.focus("o2", aChange).sends(null, null)
        t.readAnswered().sends(null, aChange)
    }

    @Test
    fun anUnfocusByAnotherOwnerIsIgnoredAndAFocusWithinTheLingerKeepsTheWatch() {
        var t = live("A").focus("o1", aChanges).sends(null, aChanges).readAnswered().state
        t = t.unfocus("o2").sends(null, null)
        assertEquals("o1", t.focus?.owner)
        t = t.unfocus("o1").state.focus("o3", aChange).sends(null, aChange)
        t.idle().sends(null, null)
    }

    @Test
    fun anAttachWatchesAgainThenReads() {
        var t = live("A").focus("o1", aChange).sends(null, aChange)
        t = t.detached().sends(null, null)
        t = t.attached(live = true).sends(Watch("A"), null)
        t.watchAnswered(true).sends(null, aChange)
        OpenSpecTracker().attached(live = true).sends(null, null)
    }

    @Test
    fun nothingIsSentWhileDetached() {
        var t = live("A").focus("o1", aChanges).sends(null, aChanges).detached().state
        assertFalse(t.pending(aChanges))
        t = t.focus("o1", bChanges).sends(null, null)
        t = t.refresh().sends(null, null)
        t = t.unfocus("o1").state.idle().sends(null, null)
        t = t.focus("o1", bChanges).state
        t = t.attached(live = true).sends(Watch("B"), null)
        t.watchAnswered(true).sends(null, bChanges)
    }

    @Test
    fun backToAFolderWhileAnotherWatchIsInFlightReadsAfterItsOwnWatch() {
        var t = live("A").focus("o1", aChanges).sends(null, aChanges).readAnswered().state
        t = t.focus("o2", bChanges).sends(Watch("B"), null)
        // A is still confirmed, but B's answer will replace it on the desktop: no read yet.
        t = t.focus("o3", aChanges).sends(null, null)
        t = t.watchAnswered(true).sends(Watch("A"), null)
        t.watchAnswered(true).sends(null, aChanges)
    }

    @Test
    fun aRefusedFolderIsReadAtOnceWithoutAWatchAndRefreshTriesAgain() {
        var t = live("A").focus("o1", bChanges).state.watchAnswered(false).state.watchAnswered(true).state.readAnswered().state
        t = t.focus("o2", aChanges).sends(Watch("A"), null).watchAnswered(true).sends(null, aChanges).readAnswered().state
        // Back on B: read at once in the same step as the stop of A.
        t = t.focus("o3", bChanges).sends(Watch(null), bChanges)
        assertTrue(t.notLive(bChanges))
        t = t.watchAnswered(true).state.readAnswered().state
        // Refresh: watch B again before reading it.
        t = t.refresh().sends(Watch("B"), null)
        t = t.watchAnswered(true).sends(null, bChanges)
        assertFalse(t.notLive(bChanges))
    }

    @Test
    fun anInternalRefusalIsRetriedByRefreshAndForgottenByANewConnection() {
        var t = live().focus("o1", aChanges).state.watchAnswered(false).sends(null, aChanges)
        assertTrue(t.notLive(aChanges))
        t = t.readAnswered().state.detached().state.attached(live = true).sends(Watch("A"), null)
        assertFalse(t.notLive(aChanges))
        t = t.watchAnswered(false).sends(null, aChanges).readAnswered().state
        t.refresh().sends(Watch("A"), null)
    }

    @Test
    fun anEventBeforeItsWatchAnswerIsIgnoredAndTheAnswerReads() {
        var t = live().focus("o1", aChanges).sends(Watch("A"), null)
        t = t.changed("A").sends(null, null)
        t.watchAnswered(true).sends(null, aChanges)
    }

    @Test
    fun aWatchFailingAfterTheFocusMovedOnWatchesTheNextFolder() {
        var t = live().focus("o1", aChanges).sends(Watch("A"), null)
        t = t.focus("o2", bChanges).sends(null, null)
        t = t.watchAnswered(false).sends(Watch("B"), null)
        assertTrue("A" in t.refused)
        t.watchAnswered(true).sends(null, bChanges)
    }

    @Test
    fun anyAnswerToAStopConfirmsNothingWatched() {
        var t = live("A").focus("o1", aChanges).state.readAnswered().state.unfocus("o1").state.idle().sends(Watch(null), null)
        t = t.watchAnswered(false).sends(null, null)
        assertNull(t.confirmed)
    }

    @Test
    fun aRefreshInTheConfirmedFolderReadsAtOnce() {
        live("A").focus("o1", aChanges).state.readAnswered().state.refresh().sends(null, aChanges)
    }

    @Test
    fun anUnfocusWhileTheWatchIsInFlightReadsNothingWhenItAnswers() {
        var t = live().focus("o1", aChanges).sends(Watch("A"), null)
        t = t.unfocus("o1").state
        assertFalse(t.pending(aChanges))
        t.watchAnswered(true).sends(null, null)
    }

    @Test
    fun anUnfocusDuringAReadThenAnEventReadsNoMore() {
        var t = live("A").focus("o1", aChanges).sends(null, aChanges)
        t = t.unfocus("o1").state.changed("A").sends(null, null)
        t.readAnswered().sends(null, null)
    }
}
