package com.spekterm.android.desktop

import com.spekterm.android.desktop.UsageLine.Part
import com.spekterm.android.desktop.UsageLine.Window
import com.spekterm.android.net.AgentUsage
import com.spekterm.android.net.UsageWindow
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `UsageLine` (`agent-usage-and-skills` D3, D7). */
class UsageLineTest {
    private val now = 1_000_000L
    private val typical = AgentUsage("s1", "Opus 5.5", "high", 42.5, 200_000, 3.214, UsageWindow(31.0, now + 60_000))

    @Test
    fun aTypicalSessionLeavesALowLimitOut() {
        assertEquals(listOf(Part.Model("Opus 5.5", "high"), Part.Context(43, 200_000), Part.Cost(3.214)), UsageLine.parts(typical, now))
    }

    @Test
    fun aLimitNearFullComesLastUntilItsReset() {
        val near = typical.copy(fiveHour = UsageWindow(85.0, now + 60_000))
        assertEquals(Part.Limit(Window.FIVE_HOUR, 85), UsageLine.parts(near, now).last())
        assertEquals(3, UsageLine.parts(near, now + 60_000).size)
        assertEquals(Part.Limit(Window.FIVE_HOUR, 80), UsageLine.parts(typical.copy(fiveHour = UsageWindow(79.5)), now).last())
        assertEquals(3, UsageLine.parts(typical.copy(fiveHour = UsageWindow(79.4)), now).size)
        assertEquals(3, UsageLine.parts(typical.copy(sevenDay = UsageWindow(90.0, now - 1)), now).size)
        // No reset time: never past.
        assertEquals(Part.Limit(Window.SEVEN_DAY, 85), UsageLine.parts(typical.copy(sevenDay = UsageWindow(85.0)), now).last())
        val both = typical.copy(fiveHour = UsageWindow(90.0), sevenDay = UsageWindow(95.0))
        assertEquals(listOf(Part.Limit(Window.FIVE_HOUR, 90), Part.Limit(Window.SEVEN_DAY, 95)), UsageLine.parts(both, now).takeLast(2))
    }

    @Test
    fun eachPartOnlyWhenReported() {
        assertEquals(listOf(Part.Model(null, "high")), UsageLine.parts(AgentUsage("s1", effort = "high"), now))
        assertEquals(listOf(Part.Context(null, 1_000_000)), UsageLine.parts(AgentUsage("s1", contextSize = 1_000_000), now))
        assertTrue(UsageLine.parts(AgentUsage("s1"), now).isEmpty())
    }

    @Test
    fun formatsPercentSizeAndCost() {
        assertEquals(43, UsageLine.percent(42.5))
        assertEquals(0, UsageLine.percent(0.4))
        assertEquals(100, UsageLine.percent(100.0))
        assertEquals("200K", UsageLine.size(200_000))
        assertEquals("128K", UsageLine.size(128_000))
        assertEquals("1M", UsageLine.size(1_000_000))
        assertEquals("1.5M", UsageLine.size(1_500_000))
        assertEquals("$3.21", UsageLine.cost(3.214, Locale.US))
        assertEquals("US$3.21", UsageLine.cost(3.214, Locale.TAIWAN))
        assertTrue(UsageLine.cost(3.214, Locale.JAPAN), UsageLine.cost(3.214, Locale.JAPAN).endsWith("3.21"))
    }

    @Test
    fun theNextChangeIsTheEarliestResetAhead() {
        val u = typical.copy(fiveHour = UsageWindow(85.0, now + 60_000), sevenDay = UsageWindow(10.0, now + 30_000))
        assertEquals(now + 30_000, UsageLine.nextChange(u, now))
        assertEquals(now + 60_000, UsageLine.nextChange(u, now + 30_000))
        assertNull(UsageLine.nextChange(u, now + 60_000))
        assertNull(UsageLine.nextChange(AgentUsage("s1"), now))
    }

    @Test
    fun theLogNamesValuesAndHoldsNone() {
        val fields = UsageLine.logFields(typical)
        assertEquals("model,effort,context,size,cost,fiveHour", fields)
        assertTrue(fields.none { it.isDigit() } && "Opus" !in fields)
        assertEquals("", UsageLine.logFields(AgentUsage("s1")))
    }
}
