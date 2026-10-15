package com.spekterm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.SessionsView
import com.spekterm.android.desktop.SessionsView.RowState
import com.spekterm.android.net.Session
import com.spekterm.android.service.StatusBoard
import com.spekterm.android.store.Announcements
import java.text.DateFormat
import java.util.Date

/** The paired app's main screen: the desktop's sessions (`session-list`, design D8). */
@Composable
fun SessionsScreen(
    desktopName: String,
    announcements: Announcements,
    onOpen: (Session) -> Unit,
    onStatus: () -> Unit,
    onResume: () -> Unit,
) {
    val sessions by Desktop.sessions.collectAsStateWithLifecycle()
    val connected by Desktop.connected.collectAsStateWithLifecycle()
    val status by StatusBoard.status.collectAsStateWithLifecycle()
    val list = sessions.list
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)

    Column(Modifier.fillMaxSize()) {
        TopBar(desktopName) { TextButton(onClick = onStatus) { Text("Connection") } }
        when {
            announcements.paused -> Notice("The connection is paused.", "Resume", onResume)
            !connected || !sessions.current -> {
                val age = sessions.receivedAt?.let { " Showing the list from ${time.format(Date(it))}." } ?: " No session list received yet."
                Notice(notConnectedReason(status, announcements) + age, "Details", onStatus)
            }
            list?.window == false -> Notice("spekterm has no open window on the desktop, so it lists no sessions. Open spekterm there.")
        }
        if (list == null || (list.window && list.sessions.isEmpty())) {
            if (list != null) Text("No sessions on the desktop.", Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            for (group in SessionsView.groups(list)) {
                item(key = "group:${group.folderId}:${group.global}") {
                    Text(
                        when {
                            group.global -> "Global"
                            group.name != null -> group.name
                            else -> "Other"
                        },
                        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(group.rows, key = { "session:${it.session.id}" }) { row ->
                    SessionRow(row, onClick = { onOpen(row.session) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SessionRow(row: SessionsView.Row, onClick: () -> Unit) {
    val session = row.session
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(session.label.ifEmpty { session.id }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = listOfNotNull(
                if (session.isAgent) "Agent" else "Shell",
                session.lifecycle?.let { "handoff: $it" },
            ).joinToString(" · ")
            Text(second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        stateLabel(row.state)?.let { (text, color) ->
            Text(text, style = MaterialTheme.typography.labelMedium, color = color ?: MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun stateLabel(state: RowState): Pair<String, Color?>? = when (state) {
    RowState.DORMANT -> "Dormant" to null
    RowState.READY -> "Waiting for you" to MaterialTheme.colorScheme.primary
    RowState.BUSY -> "Working" to null
    RowState.CHOICE -> "Asks for a choice" to MaterialTheme.colorScheme.error
    RowState.NONE -> null
}
