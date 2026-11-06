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
            race<String, String>(
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

    // `phone-relay` D3: the relay as the last candidate, 1 s after the last direct start.

    private val relay = Route.Relay("team.example")

    private fun routes(vararg addresses: String): List<Route> = addresses.map { Route.Direct(it) } + relay

    @Test
    fun aDirectAddressAnsweringWithinTheSecondMeansTheRelayNeverStarts() = runTest {
        val started = mutableListOf<Route>()
        val result = race(
            routes("a", "b"),
            open = { route ->
                if (route == Route.Direct("b")) {
                    delay(300)
                    Attempt.Opened("direct")
                } else {
                    awaitCancellation()
                }
            },
            drop = {},
            onStart = { started += it },
            delayBefore = ::delayBefore,
        )
        assertEquals(Route.Direct("b") to "direct", result.winner)
        assertEquals(550L, currentTime)
        assertEquals(listOf(Route.Direct("a"), Route.Direct("b")), started)
        assertEquals(listOf<Route>(relay), result.notTried)
    }

    @Test
    fun theRelayStartsAtOnceWhenEveryDirectAddressHasFailed() = runTest {
        val startedAt = mutableMapOf<Route, Long>()
        val result = race(
            routes("a", "b"),
            open = { route ->
                if (route == relay) {
                    Attempt.Opened("relayed")
                } else {
                    delay(50)
                    unreachable
                }
            },
            drop = {},
            onStart = { startedAt[it] = currentTime },
            delayBefore = ::delayBefore,
        )
        assertEquals(relay to "relayed", result.winner)
        // b started at 50 (a failed first), failed at 100: the relay at once, not at 1050.
        assertEquals(100L, startedAt[relay])
    }

    @Test
    fun withHangingDirectAddressesTheRelayStartsOneSecondAfterTheLast() = runTest {
        val startedAt = mutableMapOf<Route, Long>()
        val result = race(
            routes("a", "b", "c"),
            open = { route -> if (route == relay) Attempt.Opened("relayed") else awaitCancellation() },
            drop = {},
            onStart = { startedAt[it] = currentTime },
            delayBefore = ::delayBefore,
        )
        assertEquals(500L, startedAt[Route.Direct("c")])
        assertEquals(1500L, startedAt[relay])
        assertEquals(relay to "relayed", result.winner)
        assertEquals(listOf(Route.Direct("a"), Route.Direct("b"), Route.Direct("c")), result.cancelled)
    }

    @Test
    fun theRelayOpeningFirstWinsAndALaterDirectOneIsDropped() = runTest {
        val dropped = mutableListOf<String>()
        val late = CompletableDeferred<Unit>()
        val result = race(
            routes("a"),
            open = { route ->
                if (route == relay) {
                    Attempt.Opened("relayed")
                } else {
                    try {
                        delay(5_000)
                        Attempt.Opened("direct")
                    } finally {
                        late.complete(Unit)
                    }
                }
            },
            drop = { dropped += it },
            delayBefore = ::delayBefore,
        )
        assertEquals(relay to "relayed", result.winner)
        assertEquals(1000L, currentTime)
        assertTrue(late.isCompleted)
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun theRelayAloneStartsAtOnce() = runTest {
        val result = race(listOf<Route>(relay), open = { Attempt.Opened("relayed") }, drop = {}, delayBefore = ::delayBefore)
        assertEquals(relay to "relayed", result.winner)
        assertEquals(0L, currentTime)
    }

    @Test
    fun aRelayOnTheHostOfADirectAddressKeepsItsOwnOutcome() = runTest {
        val sameHost = Route.Relay("a")
        val result = race(
            listOf(Route.Direct("a"), sameHost),
            open = { route -> if (route == sameHost) Attempt.Failed(RemoteClient.OpenResult.Relayed(RelayOutcome.TimedOut)) else unreachable },
            drop = {},
            delayBefore = ::delayBefore,
        )
        assertNull(result.winner)
        assertEquals(unreachable.outcome, result.finished[Route.Direct("a")])
        assertEquals(RemoteClient.OpenResult.Relayed(RelayOutcome.TimedOut), result.finished[sameHost])
    }
}
