package com.spekterm.android.net

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `scan-connect-zh` D5, on virtual time: values are plain strings, `drop` records what it was given. */
class AddressRaceTest {
    private val unreachable = Attempt.Failed(RemoteClient.OpenResult.Unreachable(IOException("timeout")))

    private class Log {
        val events = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        var busy = false

        fun start(address: String) = step("start $address")
        fun outcome(address: String) = step("outcome $address")
        private fun step(what: String) {
            check(!busy) { "callbacks overlap" }
            busy = true
            events += what
            busy = false
        }
    }

    @Test
    fun aHangingFirstAddressLetsTheSecondWinAfterTheStagger() = runTest {
        val log = Log()
        var cancelledFirst = false
        val result = race(
            listOf("a", "b"),
            open = { address ->
                if (address == "a") {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelledFirst = true
                    }
                } else {
                    Attempt.Opened("conn-b")
                }
            },
            drop = { log.dropped += it },
            onStart = { log.start(it) },
            onOutcome = { address, _ -> log.outcome(address) },
        )
        assertEquals("b" to "conn-b", result.winner)
        assertEquals(250L, currentTime)
        assertTrue(cancelledFirst)
        assertEquals(listOf("start a", "start b"), log.events)
        assertEquals(listOf("a"), result.cancelled)
        assertTrue(log.dropped.isEmpty())
    }

    @Test
    fun aFirstAddressFailingAtOnceStartsTheNextAtOnce() = runTest {
        val startedAt = mutableMapOf<String, Long>()
        val result = race(
            listOf("a", "b"),
            open = { address ->
                startedAt[address] = currentTime
                if (address == "a") unreachable else Attempt.Opened("conn-b")
            },
            drop = {},
        )
        assertEquals(0L, startedAt["b"])
        assertEquals("b", result.winner?.first)
        assertEquals(setOf("a"), result.finished.keys)
    }

    @Test
    fun twoOpeningAtOnceGiveOneWinnerAndDropTheOther() = runTest {
        val log = Log()
        val result = race(
            listOf("a", "b"),
            open = { address ->
                // Both open at 300 ms: "a" was started at 0, "b" at 250.
                delay(if (address == "a") 300 else 50)
                Attempt.Opened("conn-$address")
            },
            drop = { log.dropped += it },
        )
        val winner = result.winner!!.second
        val other = if (winner == "conn-a") "conn-b" else "conn-a"
        assertEquals(listOf(other), log.dropped)
    }

    @Test
    fun allFailingKeepsEveryOutcomeInOrder() = runTest {
        val log = Log()
        val result = race(
            listOf("a", "b", "c"),
            open = { address ->
                delay(if (address == "a") 1_000 else 10)
                unreachable
            },
            drop = {},
            onStart = { log.start(it) },
            onOutcome = { address, _ -> log.outcome(address) },
        )
        assertNull(result.winner)
        assertEquals(listOf("b", "c", "a"), result.finished.keys.toList())
        assertEquals(listOf("start a", "start b", "outcome b", "start c", "outcome c", "outcome a"), log.events)
        assertTrue(result.notTried.isEmpty())
        assertTrue(result.cancelled.isEmpty())
    }

    @Test
    fun aWinnerBeforeTheThirdStartsLeavesItNotTried() = runTest {
        val log = Log()
        val result = race(
            listOf("a", "b", "c"),
            open = { address -> if (address == "a") Attempt.Opened("conn-a") else error("must not start") },
            drop = {},
            onStart = { log.start(it) },
        )
        assertEquals("a", result.winner?.first)
        assertEquals(listOf("b", "c"), result.notTried)
        assertEquals(listOf("start a"), log.events)
    }

    @Test
    fun cancellingTheCallerCancelsEveryAttemptAndDropsWhatOpens() = runTest {
        val log = Log()
        val opened = CompletableDeferred<Unit>()
        var cancelled = 0
        val job = async {
            race<String>(
                listOf("a", "b"),
                open = { address ->
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled++
                        if (address == "b") opened.complete(Unit)
                    }
                },
                drop = { log.dropped += it },
            )
        }
        advanceTimeBy(300)
        job.cancel()
        advanceUntilIdle()
        assertEquals(2, cancelled)
        assertTrue(opened.isCompleted)
    }

    @Test
    fun anOpenLandingAfterTheRaceIsDropped() = runTest {
        val log = Log()
        val result = race(
            listOf("a", "b"),
            open = { address ->
                if (address == "a") {
                    // Opens at 260, after "b" started at 250 and before "b" opens at 270.
                    delay(260)
                    Attempt.Opened("conn-a")
                } else {
                    // Ignores cancellation, as an open that has just completed would.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { delay(20) }
                    Attempt.Opened("conn-b")
                }
            },
            drop = { log.dropped += it },
        )
        runCurrent()
        assertEquals("a", result.winner?.first)
        assertEquals(listOf("conn-b"), log.dropped)
    }
}
