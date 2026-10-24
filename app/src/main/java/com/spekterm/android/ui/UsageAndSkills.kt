@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.spekterm.android.R
import com.spekterm.android.desktop.SkillFilter
import com.spekterm.android.desktop.Skills
import com.spekterm.android.desktop.UsageLine
import com.spekterm.android.desktop.UsageLine.Part
import com.spekterm.android.net.AgentUsage
import com.spekterm.android.net.Skill
import com.spekterm.android.net.UsageWindow
import com.spekterm.android.ui.theme.spekColors
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The time the usage is read at: moved on when a reported reset comes, since the desktop sends nothing when no value
 * changes, and on resume (`agent-usage-and-skills` D3).
 */
@Composable
internal fun rememberUsageNow(usage: AgentUsage): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(usage) { now = System.currentTimeMillis() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) now = System.currentTimeMillis() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val next = UsageLine.nextChange(usage, now)
    LaunchedEffect(next) {
        if (next != null) {
            // Until the wall clock has passed it: a delay is not bound to the wall clock.
            while (System.currentTimeMillis() <= next) delay(next - System.currentTimeMillis() + 50)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** One line under the top bar: what the agent last reported, a rate limit only near full; a tap shows it all (D3). */
@Composable
internal fun UsageRow(usage: AgentUsage, now: Long, onOpen: () -> Unit) {
    val parts = UsageLine.parts(usage, now)
    if (parts.isEmpty()) return
    val locale = LocalConfiguration.current.locales[0]
    val words = parts.map { partText(it, locale) }
    val warning = spekColors.attention
    val text = buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            if (i > 0) append(" · ")
            if (part is Part.Limit) withStyle(SpanStyle(color = warning, fontWeight = FontWeight.Medium)) { append(words[i]) } else append(words[i])
        }
    }
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.usage_details), role = Role.Button, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun partText(part: Part, locale: Locale): String = when (part) {
    is Part.Model -> when {
        part.model != null && part.effort != null -> stringResource(R.string.usage_model_effort, part.model, part.effort)
        else -> part.model ?: part.effort ?: ""
    }
    is Part.Context -> when {
        part.percent != null && part.size != null -> stringResource(R.string.usage_context_of, part.percent, UsageLine.size(part.size))
        part.percent != null -> stringResource(R.string.usage_context_percent, part.percent)
        else -> stringResource(R.string.usage_context_size, UsageLine.size(part.size ?: 0))
    }
    is Part.Cost -> UsageLine.cost(part.usd, locale)
    is Part.Limit -> stringResource(if (part.window == UsageLine.Window.FIVE_HOUR) R.string.usage_limit_five_hour else R.string.usage_limit_seven_day, part.percent)
}

/** Every value reported, the rate limits with their reset times (D3). */
@Composable
internal fun UsageDialog(usage: AgentUsage, now: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val rows = buildList {
        usage.model?.let { add(R.string.usage_row_model to it) }
        usage.effort?.let { add(R.string.usage_row_effort to it) }
        val size = usage.contextSize?.let { NumberFormat.getIntegerInstance(locale).format(it) }
        val percent = usage.contextPercent?.let(UsageLine::percent)
        when {
            percent != null && size != null -> add(R.string.usage_row_context to stringResource(R.string.usage_detail_context_of, percent, size))
            percent != null -> add(R.string.usage_row_context to stringResource(R.string.usage_detail_percent, percent))
            size != null -> add(R.string.usage_row_context to stringResource(R.string.usage_detail_context_size, size))
        }
        usage.costUsd?.let { add(R.string.usage_row_cost to UsageLine.cost(it, locale)) }
        for ((window, w) in UsageLine.windows(usage)) {
            val label = if (window == UsageLine.Window.FIVE_HOUR) R.string.usage_row_five_hour else R.string.usage_row_seven_day
            add(label to limitText(context, w, now))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
        title = { Text(stringResource(R.string.usage_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((label, value) in rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(label), Modifier.weight(0.4f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.usage_footnote), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun limitText(context: Context, w: UsageWindow, now: Long): String {
    val percent = UsageLine.percent(w.percent)
    val at = w.resetsAt ?: return stringResource(R.string.usage_detail_percent, percent)
    val time = resetTime(context, at)
    return if (UsageLine.past(w, now)) stringResource(R.string.usage_detail_reset_past, time) else stringResource(R.string.usage_detail_limit_resets, percent, time)
}

/** The time alone when today, with the weekday and date otherwise, in the phone's zone. */
private fun resetTime(context: Context, at: Long): String {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == LocalDate.now(zone)
    val flags = DateUtils.FORMAT_SHOW_TIME or if (today) 0 else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL
    return DateUtils.formatDateTime(context, at, flags)
}

/**
 * The session's skills above the reply field while a command name is typed (`tool-call-display` D10), filtered by
 * [query]. Names and descriptions are plain text (obligation 15). At most [maxHeight], so the field stays in view.
 */
@Composable
internal fun SlashPanel(skills: Skills, query: String, maxHeight: Dp, onChoose: (Skill) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shown = (skills as? Skills.Known)?.let { SkillFilter.ranked(it.list, query) }.orEmpty()
    val header = when {
        skills == Skills.Unknown -> stringResource(R.string.skills_unknown)
        (skills as Skills.Known).list.isEmpty() -> stringResource(R.string.skills_none)
        shown.isEmpty() -> stringResource(R.string.skills_no_match)
        else -> pluralStringResource(R.plurals.skills_count, shown.size, shown.size)
    }
    val insert = stringResource(R.string.skills_insert)
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).background(colors.surfaceContainerHigh, MaterialTheme.shapes.medium)) {
        // Announced when the panel appears and as the filter changes what it holds.
        Text(
            header,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(shown, key = { it.name }) { skill ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = insert, role = Role.Button) { onChoose(skill) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("/" + skill.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    skill.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // Under the rows, never scrolled away: what the list does not hold.
        Text(
            stringResource(R.string.skills_builtin_note),
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
