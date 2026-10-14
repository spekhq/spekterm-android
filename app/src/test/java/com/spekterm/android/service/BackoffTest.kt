package com.spekterm.android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackoffTest {
    @Test
    fun growsFromOneSecondAndNeverExceedsTheCap() {
        val random = Random(42)
        repeat(200) {
            val backoff = Backoff { random.nextDouble() }
            var previousBase = 0L
            for (n in 0 until 20) {
                val base = minOf(Backoff.CAP_MS, Backoff.BASE_MS shl n)
                val delay = backoff.nextDelayMs()
                assertTrue("delay $delay at $n", delay >= base / 2 && delay <= base)
                assertTrue(delay <= Backoff.CAP_MS)
                assertTrue(base >= previousBase)
                previousBase = base
            }
        }
    }

    @Test
    fun theExtremesOfTheJitter() {
        assertEquals(500L, Backoff { 0.0 }.nextDelayMs())
        val top = Backoff { 1.0 }
        repeat(10) { top.nextDelayMs() }
        assertEquals(Backoff.CAP_MS, top.nextDelayMs())
    }

    @Test
    fun resetStartsOver() {
        val backoff = Backoff { 1.0 }
        repeat(5) { backoff.nextDelayMs() }
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun throttledWaitsAtLeastSixtySeconds() {
        val random = Random(7)
        repeat(500) {
            val delay = Backoff { random.nextDouble() }.nextDelayMs(throttled = true)
            assertTrue("delay $delay", delay >= 60_000)
        }
    }
}
