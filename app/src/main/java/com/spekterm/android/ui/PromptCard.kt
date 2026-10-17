@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spekterm.android.desktop.AnswerOutcome
import com.spekterm.android.desktop.Answers
import com.spekterm.android.desktop.Conversation
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.Offer
import com.spekterm.android.net.Prompt
import com.spekterm.android.net.RemoteError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a prompt is shown before its answers can be tapped (design D3). */
private const val TAP_GUARD_MS = 600L

/**
 * The open prompt as the desktop states it, above the composer (`prompt-answer`; design D3). Plain text only —
 * the agent's own text, which may hold paths. Capped at about half the screen, scrolling, so the composer stays.
 */
@Composable
fun PromptCard(sessionId: String, prompt: Prompt, offer: Offer) {
    val answerable = offer as? Offer.Answerable
    // The answers arm only a moment after this prompt (and its answerability) was first shown.
    var armed by remember(prompt.id, prompt.answerable) { mutableStateOf(false) }
    LaunchedEffect(prompt.id, prompt.answerable) {
        delay(TAP_GUARD_MS)
        armed = true
    }
    val chosen = remember(prompt.id) { mutableStateMapOf<Int, Set<Int>>() }
    val scope = rememberCoroutineScope()
    // Disabled from the tap until the answer comes back: the offer is then read-only while it is in flight.
    fun answer(decision: String, selections: List<List<Int>>? = null) {
        scope.launch { Desktop.answer(sessionId, prompt.id, decision, selections) }
    }
    val usable = answerable != null && armed
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Set apart with the accent: it is what the agent waits for (D9).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                when (prompt) {
                    is Prompt.Permission -> SpekIcons.Warning
                    is Prompt.Question -> SpekIcons.Help
                    is Prompt.Plan -> SpekIcons.Plan
                },
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                when (prompt) {
                    is Prompt.Permission -> "The agent asks for permission"
                    is Prompt.Question -> "The agent asks"
                    is Prompt.Plan -> "The agent asks to approve its plan"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        when (prompt) {
            is Prompt.Permission -> SelectionContainer {
                Column {
                    Text(prompt.tool, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    prompt.arg?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
            }
            is Prompt.Question -> prompt.questions.forEachIndexed { i, q ->
                QuestionForm(q, chosen[i].orEmpty(), enabled = usable && prompt.fits) { picked -> chosen[i] = picked }
            }
            is Prompt.Plan -> prompt.plan?.let { PlanText(it) }
        }
        when (offer) {
            is Offer.ReadOnly -> readOnlyText(offer.reason, prompt)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is Offer.Answerable -> {
                if (prompt is Prompt.Permission && !prompt.canAllow) {
                    Text("To allow it, answer on the desktop.", style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (decision in offer.decisions) {
                        val selections = (prompt as? Prompt.Question)?.let { Answers.selections(it, chosen) }
                        val enabled = usable && (decision != Prompt.ANSWER || selections != null)
                        val onClick = { answer(decision, if (decision == Prompt.ANSWER) selections else null) }
                        val label: @Composable () -> Unit = { Text(decisionLabel(prompt, decision)) }
                        // Going ahead is filled, auto mode tonal, stopping outlined (D9).
                        when (decision) {
                            Prompt.ALLOW, Prompt.ANSWER, Prompt.APPROVE -> Button(onClick, enabled = enabled) { label() }
                            Prompt.APPROVE_AUTO -> FilledTonalButton(onClick, enabled = enabled) { label() }
                            else -> OutlinedButton(onClick, enabled = enabled) { label() }
                        }
                    }
                }
                Text("${denyLabel(prompt)} stops the agent; it then waits for your reply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun QuestionForm(q: Prompt.Question.Item, picked: Set<Int>, enabled: Boolean, onPick: (Set<Int>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SelectionContainer {
            Column {
                if (q.header.isNotBlank()) Text(q.header, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(q.question, style = MaterialTheme.typography.bodyMedium)
                Text(if (q.multiSelect) "Choose one or more" else "Choose one", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        q.options.forEachIndexed { j, option ->
            val on = j in picked
            val toggle = { onPick(if (q.multiSelect) (if (on) picked - j else picked + j) else setOf(j)) }
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = toggle), verticalAlignment = Alignment.CenterVertically) {
                if (q.multiSelect) Checkbox(on, { toggle() }, enabled = enabled) else RadioButton(on, toggle, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** The plan in a box of its own that scrolls; "Show all" opens it full-screen. */
@Composable
private fun PlanText(plan: String) {
    var all by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
        SelectionContainer { Text(plan, style = MaterialTheme.typography.bodySmall) }
    }
    TextButton(onClick = { all = true }) { Text("Show all") }
    if (all) {
        Dialog(onDismissRequest = { all = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                SpekScaffold(topBar = { scroll -> TopBar("Plan", scroll, onBack = { all = false }) }) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SelectionContainer { Text(plan, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

private fun decisionLabel(prompt: Prompt, decision: String): String = when (decision) {
    Prompt.ALLOW -> "Allow once"
    Prompt.ANSWER -> "Send answer"
    Prompt.APPROVE -> "Approve (ask before edits)"
    Prompt.APPROVE_AUTO -> "Approve in auto mode"
    else -> denyLabel(prompt)
}

private fun denyLabel(prompt: Prompt): String = when (prompt) {
    is Prompt.Permission -> "Deny"
    is Prompt.Question -> "Decline"
    is Prompt.Plan -> "Keep planning"
}

private fun readOnlyText(reason: Offer.Reason, prompt: Prompt): String? = when (reason) {
    // The composer already says why nothing can be sent now.
    Offer.Reason.NotConnected, Offer.Reason.Paused, Offer.Reason.NotWatching -> null
    Offer.Reason.NoOperation -> "Answer it on the desktop."
    is Offer.Reason.Open -> "${reason.count} requests are open. Answer them on the desktop."
    Offer.Reason.TooLarge -> "Too many questions or options to answer on the phone. Answer it on the desktop."
    Offer.Reason.NotAnswerable -> "This choice can no longer be answered from the phone. Answer it on the desktop."
    Offer.Reason.Answering -> "Answering…"
    Offer.Reason.Answered -> if (prompt is Prompt.Question || prompt is Prompt.Plan) "Answered. Waiting for the agent." else "Answered."
}

/** How the last answer went (design D4); warnings stay until dismissed. `null` when there is nothing to say. */
fun answerNote(c: Conversation): String? = when (val outcome = c.answer?.outcome) {
    null, AnswerOutcome.Done -> null
    AnswerOutcome.Partial -> "Only part of the answer was written. Finish it on the desktop."
    AnswerOutcome.Mismatch -> "The agent recorded a different answer than chosen. Check the desktop."
    AnswerOutcome.Gone -> "This choice could no longer be answered from the phone — it changed, or was typed into on the desktop. Nothing was sent."
    is AnswerOutcome.Refused -> if (outcome.code == RemoteError.NOT_FOUND) {
        "The session is not running. Nothing was sent."
    } else {
        "The desktop did not take that answer from the phone. Nothing was sent; answer it on the desktop."
    }
    AnswerOutcome.Unknown -> "Can't tell whether the answer was written. Check the desktop."
}

/** The tone of [answerNote] (D5): something that may need finishing is a warning; nothing sent is an error. */
fun answerTone(c: Conversation): Tone = when (c.answer?.outcome) {
    AnswerOutcome.Gone, is AnswerOutcome.Refused -> Tone.Error
    else -> Tone.Warning
}
