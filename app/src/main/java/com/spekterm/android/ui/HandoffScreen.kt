package com.spekterm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.HandoffView
import com.spekterm.android.desktop.HandoffView.Shows
import com.spekterm.android.desktop.Relations
import com.spekterm.android.net.HandoffBrief
import com.spekterm.android.net.Operations
import com.spekterm.android.net.SessionList
import com.spekterm.android.service.StatusBoard
import com.spekterm.android.store.Announcements
import java.text.DateFormat
import java.util.Date

/** The note as the screen holds it (D4): fetched on every visit, never cached in `Desktop`. */
private sealed interface Note {
    data object Loading : Note

    data class Loaded(val brief: HandoffBrief) : Note

    data object Missing : Note

    data object NeedsConnection : Note

    data class Failed(val code: String) : Note
}

/**
 * A session's handoff: where it came from, its note and latest report, and what was handed off from it
 * (`session-relations` design D4). Everything but the note is read from the lists `Desktop` holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoffScreen(
    sessionId: String,
    announcements: Announcements,
    onBack: () -> Unit,
    onStatus: () -> Unit,
    onResume: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val sessions by Desktop.sessions.collectAsStateWithLifecycle()
    val connected by Desktop.connected.collectAsStateWithLifecycle()
    val operations by Desktop.operations.collectAsStateWithLifecycle()
    val handoffs by Desktop.handoffs.collectAsStateWithLifecycle()
    val status by StatusBoard.status.collectAsStateWithLifecycle()

    // The last list that had a window: a desktop without one neither empties the screen nor reads as closed.
    var kept by remember { mutableStateOf<SessionList?>(null) }
    sessions.list?.takeIf { it.window }?.let { if (it !== kept) kept = it }
    val list = HandoffView.relationsList(sessions, kept)
    val existing = remember(list) { list?.let { Relations.existing(it) }.orEmpty() }
    val session = list?.sessions?.firstOrNull { it.id == sessionId }
    val shows = HandoffView.of(connected, announcements.paused, sessions, sessionId)

    val entry = handoffs.entries[sessionId]
    val canRead = session?.lineage != null && Operations.HANDOFFS_BRIEF in operations
    var note by remember(sessionId) { mutableStateOf<Note>(Note.Loading) }
    var attempt by remember(sessionId) { mutableIntStateOf(0) }
    // Asked on entering, on a new report, on "Try again" (D4).
    LaunchedEffect(sessionId, canRead, entry?.reportedAt, attempt) {
        if (!canRead) return@LaunchedEffect
        if (note !is Note.Loaded) note = Note.Loading
        val answer = Desktop.brief(sessionId)
        note = when {
            answer.brief != null -> Note.Loaded(answer.brief)
            answer.error?.code == "NOT_FOUND" -> Note.Missing
            answer.error?.code == Desktop.NOT_CONNECTED -> if (note is Note.Loaded) note else Note.NeedsConnection
            else -> Note.Failed(answer.error?.code ?: "INTERNAL")
        }
    }
    // And again when the connection comes back without it (D4).
    LaunchedEffect(connected) {
        if (connected && note == Note.NeedsConnection) attempt++
    }

    SpekScaffold(
        topBar = { scroll ->
            TopBar(
                title = session?.let { Relations.label(it) } ?: "Handoff",
                scroll = scroll,
                onBack = onBack,
                subtitle = { Text("Handoff", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            when (shows) {
                Shows.PAUSED -> Notice("The connection is paused. What is shown may be out of date.", Tone.Warning, "Resume", onResume)
                Shows.NOT_CONNECTED -> Notice(notConnectedReason(status, announcements) + " What is shown may be out of date.", Tone.Warning, "Details", onStatus)
                Shows.NO_WINDOW -> Notice("spekterm has no open window on the desktop. What is shown may be out of date.", Tone.Warning)
                else -> Unit
            }
            if (shows == Shows.CLOSED || session == null || list == null) {
                CenteredMessage(SpekIcons.Info, "This session is no longer on the desktop.")
                return@Column
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                if (session.lineage != null) {
                    Section("From") {
                        val source = Relations.source(session, list, existing)
                        val text = Relations.sourceText(session, list, existing)!!
                        if (source != null) {
                            Text(
                                text,
                                Modifier.fillMaxWidth().clickable { onOpen(source.id) }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Body(text)
                        }
                    }
                    if (Operations.HANDOFFS_BRIEF in operations) {
                        Section("Handoff note") { NoteBody(note) { attempt++ } }
                    }
                    Section("Latest report") {
                        val report = HandoffView.latestReport((note as? Note.Loaded)?.brief?.report, entry)
                        if (report == null) {
                            Body("No report yet.", faint = true)
                        } else {
                            report.reportedAt?.let { Body(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)), faint = true) }
                            SelectionContainer { Body(report.summary) }
                        }
                    }
                }
                val children = Relations.children(session, list, existing)
                if (children.isNotEmpty()) {
                    Section("Handed off from here") {
                        for (child in children) {
                            Column(
                                Modifier.fillMaxWidth().clickable { onOpen(child.id) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(Relations.label(child), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(Relations.groupName(child, list), Relations.stateText(child.lifecycle)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                handoffs.entries[child.id]?.summary?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    content()
    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Plain text, line breaks kept: no Markdown, no links, nothing loaded (D4). */
@Composable
private fun Body(text: String, faint: Boolean = false) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (faint) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun NoteBody(note: Note, onRetry: () -> Unit) {
    when (note) {
        Note.Loading -> Body("Loading…", faint = true)
        is Note.Loaded -> note.brief.body?.let { SelectionContainer { Body(it) } } ?: Body(NO_NOTE, faint = true)
        Note.Missing -> Body(NO_NOTE, faint = true)
        Note.NeedsConnection -> Body("Reading the note needs the connection to the desktop.", faint = true)
        is Note.Failed -> {
            Body("The desktop could not give the note (${note.code}).", faint = true)
            TextButton(onClick = onRetry, Modifier.padding(horizontal = 8.dp)) { Text("Try again") }
        }
    }
}

private const val NO_NOTE = "The desktop kept no note for this handoff."
