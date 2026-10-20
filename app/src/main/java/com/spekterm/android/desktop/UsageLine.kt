package com.spekterm.android.desktop

import com.spekterm.android.net.AgentUsage
import com.spekterm.android.net.UsageWindow
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.floor

/**
 * The usage line of the conversation screen, as typed parts the screen puts in words (`agent-usage-and-skills` D3).
 * Pure: no text for people here.
 */
object UsageLine {
    /** A rate-limit window shows on the line from this rounded percent, until its reset. */
    const val LIMIT_WARNING = 80

    enum class Window { FIVE_HOUR, SEVEN_DAY }

    sealed interface Part {
        data class Model(val model: String?, val effort: String?) : Part

        /** [percent] rounded; [size] in tokens. At least one is present. */
        data class Context(val percent: Int?, val size: Long?) : Part

        data class Cost(val usd: Double) : Part

        data class Limit(val window: Window, val percent: Int) : Part
    }

    fun parts(usage: AgentUsage, now: Long): List<Part> = buildList {
        if (usage.model != null || usage.effort != null) add(Part.Model(usage.model, usage.effort))
        if (usage.contextPercent != null || usage.contextSize != null) add(Part.Context(usage.contextPercent?.let(::percent), usage.contextSize))
        usage.costUsd?.let { add(Part.Cost(it)) }
        for ((window, w) in windows(usage)) {
            val p = percent(w.percent)
            if (p >= LIMIT_WARNING && !past(w, now)) add(Part.Limit(window, p))
        }
    }

    /** The windows reported, five-hour first. */
    fun windows(usage: AgentUsage): List<Pair<Window, UsageWindow>> = listOfNotNull(
        usage.fiveHour?.let { Window.FIVE_HOUR to it },
        usage.sevenDay?.let { Window.SEVEN_DAY to it },
    )

    /** Its reset time has come: the percent no longer holds. A window without one never is. */
    fun past(window: UsageWindow, now: Long): Boolean = window.resetsAt != null && window.resetsAt <= now

    /** The earliest reset still ahead, when the line must be looked at again without a new report. */
    fun nextChange(usage: AgentUsage, now: Long): Long? = windows(usage).mapNotNull { it.second.resetsAt }.filter { it > now }.minOrNull()

    /** Rounded half up (42.5 → 43); `NumberFormat`'s half-even would give 42. */
    fun percent(p: Double): Int = floor(p + 0.5).toInt()

    /** Tokens as the agent's own UI names windows: 200K, 1M, 1.5M. */
    fun size(tokens: Long): String = when {
        tokens >= 1_000_000 -> "${oneDecimal(tokens / 1_000_000.0)}M"
        tokens >= 1_000 -> "${oneDecimal(tokens / 1_000.0)}K"
        else -> tokens.toString()
    }

    private fun oneDecimal(value: Double): String {
        val tenths = floor(value * 10 + 0.5).toLong()
        return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    }

    /** US dollars in [locale]'s form, always with cents: a locale whose own currency has none would drop them. */
    fun cost(usd: Double, locale: Locale): String = NumberFormat.getCurrencyInstance(locale).apply {
        currency = Currency.getInstance("USD")
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(usd)

    /** Which values are present, for the log: names only, never a value (design D7). */
    fun logFields(usage: AgentUsage): String = listOfNotNull(
        "model".takeIf { usage.model != null },
        "effort".takeIf { usage.effort != null },
        "context".takeIf { usage.contextPercent != null },
        "size".takeIf { usage.contextSize != null },
        "cost".takeIf { usage.costUsd != null },
        "fiveHour".takeIf { usage.fiveHour != null },
        "sevenDay".takeIf { usage.sevenDay != null },
    ).joinToString(",")
}
