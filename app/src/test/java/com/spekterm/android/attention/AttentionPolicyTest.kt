package com.spekterm.android.attention

import com.spekterm.android.attention.AttentionPolicy.Action
import com.spekterm.android.net.Attention
import com.spekterm.android.net.IntakeRef
import com.spekterm.android.net.Position
import com.spekterm.android.net.WatchAnswer
import org.junit.Assert.assertEquals
import org.junit.Test

class AttentionPolicyTest {
    private var seq = 0L
    private fun event(kind: String = Attention.WAITING, session: String? = "s1", suppressed: Boolean = false, intake: IntakeRef? = null) =
        Attention(Position("e", ++seq), kind, at = seq, sessionId = session, intake = intake, title = "t$seq", suppressed = suppressed)

    @Test
    fun aLiveEventNotifiesAndAlerts() {
        val e = event()
        assertEquals(listOf(Action.Notify("session:s1", e, alert = true), Action.Store(e.pos)), AttentionPolicy.onLive(e))
    }

    @Test
    fun aSuppressedLiveEventCancelsAndStillStores() {
        val e = event(suppressed = true)
        assertEquals(listOf(Action.Cancel("session:s1"), Action.Store(e.pos)), AttentionPolicy.onLive(e))
    }

    @Test
    fun aCompleteAnswerNotifiesEachKeyOnceAndAlertsOnce() {
        val a = event(session = "s1")
        val b = event(session = "s2")
        val actions = AttentionPolicy.onAnswer(WatchAnswer(listOf(a, b), complete = true))
        assertEquals(
            listOf(Action.Notify("session:s1", a, true), Action.Notify("session:s2", b, false), Action.Store(b.pos)),
            actions,
        )
    }

    @Test
    fun aReplayWithTwoEventsForOneSessionShowsTheLatest() {
        val choice = event(Attention.CHOICE)
        val waiting = event(Attention.WAITING)
        assertEquals(
            listOf(Action.Notify("session:s1", waiting, true), Action.Store(waiting.pos)),
            AttentionPolicy.onAnswer(WatchAnswer(listOf(choice, waiting), complete = true)),
        )
    }

    @Test
    fun aReplayWhoseLatestIsSuppressedCancels() {
        val a = event()
        val b = event(suppressed = true)
        assertEquals(
            listOf(Action.Cancel("session:s1"), Action.Store(b.pos)),
            AttentionPolicy.onAnswer(WatchAnswer(listOf(a, b), complete = true)),
        )
    }

    @Test
    fun anIncompleteAnswerIsSummarizedNotNotified() {
        val events = List(37) { event(session = "s$it") }
        assertEquals(
            listOf(Action.Summarize(37), Action.Store(events.last().pos)),
            AttentionPolicy.onAnswer(WatchAnswer(events, complete = false)),
        )
    }

    @Test
    fun theSummaryCountsOnlyUnsuppressedEvents() {
        val a = event(session = "s1")
        val b = event(session = "s2", suppressed = true)
        assertEquals(
            listOf(Action.Cancel("session:s2"), Action.Summarize(1), Action.Store(b.pos)),
            AttentionPolicy.onAnswer(WatchAnswer(listOf(a, b), complete = false)),
        )
    }

    @Test
    fun anIncompleteAnswerOfOnlySuppressedEventsStoresWithoutASummary() {
        val a = event(suppressed = true)
        assertEquals(listOf(Action.Cancel("session:s1"), Action.Store(a.pos)), AttentionPolicy.onAnswer(WatchAnswer(listOf(a), complete = false)))
    }

    @Test
    fun anEmptyAnswerDoesNothing() {
        assertEquals(emptyList<Action>(), AttentionPolicy.onAnswer(WatchAnswer(emptyList(), complete = false)))
        assertEquals(emptyList<Action>(), AttentionPolicy.onAnswer(WatchAnswer(emptyList(), complete = true)))
    }

    @Test
    fun keys() {
        assertEquals("session:s9", AttentionPolicy.key(event(session = "s9")))
        assertEquals("intake:file:x", AttentionPolicy.key(event(Attention.INTAKE, session = null, intake = IntakeRef("file", "x"))))
        val bare = event(session = null)
        assertEquals("pos:e:${bare.pos.seq}", AttentionPolicy.key(bare))
    }
}
