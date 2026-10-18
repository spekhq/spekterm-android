package com.spekterm.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spekterm.android.R
import com.spekterm.android.desktop.SessionsView
import com.spekterm.android.net.BackgroundTask

/**
 * The work a session's agent runs in the background, at the top of its conversation (`background-tasks` D4): one line
 * that says how much, and, expanded, each task in the desktop's order within at most [maxHeight], scrolling past it, so
 * the cards and the composer below always keep their place. Labels are plain text (D5).
 */
@Composable
internal fun BackgroundTasks(tasks: List<BackgroundTask>, expanded: Boolean, onToggle: () -> Unit, maxHeight: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().testTag(BACKGROUND_TAG)) {
        Row(
            Modifier.fillMaxWidth().toggles(expanded, onToggle).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) SpekIcons.ExpandMore else SpekIcons.ChevronRight,
                null,
                Modifier.padding(end = 4.dp).size(18.dp),
                tint = colors.onSurfaceVariant,
            )
            Icon(SpekIcons.Shell, null, Modifier.padding(end = 8.dp).size(16.dp), tint = colors.onSurfaceVariant)
            Text(
                pluralStringResource(R.plurals.background_running, tasks.size, tasks.size),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 34.dp, end = 12.dp, bottom = 8.dp)
                    .testTag(BACKGROUND_LIST_TAG),
            ) {
                for (task in tasks) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(stringResource(SessionsView.backgroundKind(task.kind)), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        // As written, as plain text: no link, no markdown (obligation 15).
                        task.label?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Text(stringResource(R.string.background_note), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
    }
}

internal const val BACKGROUND_TAG = "background-tasks"
internal const val BACKGROUND_LIST_TAG = "background-tasks-list"
