@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.OpenSpecGates
import com.spekterm.android.desktop.OpenSpecLoaded
import com.spekterm.android.desktop.OpenSpecTarget
import com.spekterm.android.desktop.OpenSpecText
import com.spekterm.android.net.HistoryEntry
import com.spekterm.android.net.OsArtifact
import com.spekterm.android.net.OsChange
import com.spekterm.android.net.OsChangeDetail
import com.spekterm.android.net.OsChanges
import com.spekterm.android.net.OsSpec
import com.spekterm.android.net.OsSpecSummary
import com.spekterm.android.net.Piece
import com.spekterm.android.net.TaskList
import com.spekterm.android.net.Worktree
import com.spekterm.android.store.Announcements
import java.text.DateFormat
import java.util.Date

/*
 * A folder's OpenSpec, read-only (`openspec-browse` design D6): the folders that have one, a folder's changes and
 * specs, a change's artifacts, a spec and its history. Every text shown is the repository's: untrusted (D7).
 */

/** A tab: accent when selected, quiet otherwise. */
@Composable
private fun OsTab(selected: Boolean, onClick: () -> Unit, text: @Composable () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = text,
        selectedContentColor = MaterialTheme.colorScheme.primary,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Takes the OpenSpec focus while this screen is visible, released by [owner] only (design D3). */
@Composable
private fun FocusWhileShown(owner: String, target: OpenSpecTarget) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, owner, target) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> Desktop.openspecFocus(owner, target)
                Lifecycle.Event.ON_STOP -> Desktop.openspecUnfocus(owner)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            Desktop.openspecUnfocus(owner)
        }
    }
}

/** What every reading screen needs: its entry, the connection, the gates. */
private class Reading(val loaded: OpenSpecLoaded?, val connected: Boolean, val paused: Boolean, val gates: OpenSpecGates) {
    val usable: Boolean get() = connected && !paused
    val pending: Boolean get() = loaded?.pending == true
}

@Composable
private fun reading(target: OpenSpecTarget, announcements: Announcements): Reading {
    val all by Desktop.openspec.collectAsStateWithLifecycle()
    val connected by Desktop.connected.collectAsStateWithLifecycle()
    val operations by Desktop.operations.collectAsStateWithLifecycle()
    return Reading(all[target], connected, announcements.paused, OpenSpecGates.of(operations))
}

@Composable
private fun RefreshButton(r: Reading) {
    IconButton(onClick = { Desktop.openspecRefresh() }, enabled = r.usable && !r.pending) { Icon(SpekIcons.Refresh, "Refresh") }
}

private fun timeOf(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

/** The progress bar and the notices fixed between the bar and the content (design D6). */
@Composable
private fun StatusLine(r: Reading, target: OpenSpecTarget) {
    Box(Modifier.fillMaxWidth().height(3.dp)) {
        if (r.pending && r.usable) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    val readAt = r.loaded?.readAt?.let(::timeOf)
    when {
        !r.usable -> Notice(readAt?.let { "Not connected. Showing what was read at $it." } ?: "Not connected.", Tone.Warning)
        !r.gates.live && readAt != null -> Notice("This desktop doesn't update OpenSpec by itself. Read at $readAt.", Tone.Info)
        r.loaded?.notLive == true -> Notice("Not updating by itself right now. Refresh to try again." + (readAt?.let { " Read at $it." } ?: ""), Tone.Info)
    }
    // A vanished change that was archived says so instead (design D5).
    r.loaded?.error?.takeIf { r.usable && r.loaded.archivedAs == null }?.let { Notice(OpenSpecText.error(target, it), Tone.Error) }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BranchChip(worktree: Worktree?) {
    if (worktree == null || worktree.isMain) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
        Text(
            worktree.branch ?: "detached",
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Omitted(bytes: Long) {
    Notice("Left out by the desktop: too large to send with the rest (${formatBytes(bytes)}).", Tone.Info)
}

@Composable
private fun Chevron(show: Boolean) {
    if (show) Icon(SpekIcons.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- Folders ----

/** The folders of the desktop that have OpenSpec, in its order; also those with no session (design D8). */
@Composable
fun OpenSpecFoldersScreen(announcements: Announcements, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val sessions by Desktop.sessions.collectAsStateWithLifecycle()
    val connected by Desktop.connected.collectAsStateWithLifecycle()
    val operations by Desktop.operations.collectAsStateWithLifecycle()
    val folders = OpenSpecGates.of(operations).folders(sessions.list?.folders.orEmpty())
    SpekScaffold(topBar = { scroll -> TopBar("OpenSpec", scroll, onBack) }) {
        Column(Modifier.fillMaxSize()) {
            if (!connected || announcements.paused) Notice("Not connected.", Tone.Warning)
            LazyColumn(Modifier.fillMaxSize()) {
                if (sessions.list != null && folders.isEmpty()) item { Centered("No folder on the desktop has OpenSpec.") }
                items(folders, key = { it.id }) { folder ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(folder.id) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(SpekIcons.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(folder.name.ifEmpty { folder.id }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            folder.branch?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Chevron(true)
                    }
                }
            }
        }
    }
}

// ---- Folder ----

@Composable
fun OpenSpecFolderScreen(
    folderId: String,
    owner: String,
    announcements: Announcements,
    onBack: () -> Unit,
    onOpenChange: (String) -> Unit,
    onOpenSpec: (String) -> Unit,
) {
    val sessions by Desktop.sessions.collectAsStateWithLifecycle()
    val folder = sessions.list?.folders?.firstOrNull { it.id == folderId }
    var tab by rememberSaveable { mutableStateOf(0) }
    val operations by Desktop.operations.collectAsStateWithLifecycle()
    val specsShown = OpenSpecGates.of(operations).specs
    val target = if (tab == 1 && specsShown) OpenSpecTarget.Specs(folderId) else OpenSpecTarget.Changes(folderId)
    FocusWhileShown(owner, target)
    val r = reading(target, announcements)
    SpekScaffold(topBar = { scroll ->
        TopBar(
            folder?.name?.ifEmpty { null } ?: "OpenSpec",
            scroll,
            onBack,
            subtitle = folder?.branch?.let { { Text(it, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            actions = { RefreshButton(r) },
        )
    }) {
        Column(Modifier.fillMaxSize()) {
            if (specsShown) {
                PrimaryTabRow(selectedTabIndex = if (target is OpenSpecTarget.Specs) 1 else 0) {
                    OsTab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Changes") })
                    OsTab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Specs") })
                }
            }
            StatusLine(r, target)
            when (target) {
                is OpenSpecTarget.Specs -> SpecList(r.loaded?.value as? List<*>, r.gates.openSpec, onOpenSpec)
                else -> ChangeList(r.loaded?.value as? OsChanges, r.gates.openChange, onOpenChange)
            }
        }
    }
}

@Composable
private fun ChangeList(changes: OsChanges?, openable: Boolean, onOpen: (String) -> Unit) {
    // Archived rows are folded while there are active ones, until the user toggles them.
    var archivedShown by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (changes == null) return@LazyColumn
        if (changes.active.isEmpty() && changes.archived.isEmpty()) {
            item { Centered("No changes in this folder.") }
            return@LazyColumn
        }
        if (changes.active.isNotEmpty()) {
            item(key = "h:active") { SectionHeading("Active") }
            items(changes.active, key = { "a:" + it.slug }) { ChangeRow(it, openable, onOpen) }
        }
        if (changes.archived.isNotEmpty()) {
            val shown = archivedShown ?: changes.active.isEmpty()
            item(key = "h:archived") {
                Row(
                    Modifier.fillMaxWidth().clickable { archivedShown = !shown }.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (shown) SpekIcons.ExpandMore else SpekIcons.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("Archived · ${changes.archived.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            if (shown) items(changes.archived, key = { "r:" + it.slug }) { ChangeRow(it, openable, onOpen) }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ChangeRow(change: OsChange, openable: Boolean, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (openable) Modifier.clickable { onOpen(change.slug) } else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(change.description.ifEmpty { OpenSpecText.readableSlug(change.slug) }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                change.taskStats?.takeIf { it.total > 0 }?.let { stats ->
                    Text("${stats.completed}/${stats.total} tasks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { stats.completed.toFloat() / stats.total }, modifier = Modifier.width(48.dp), drawStopIndicator = {})
                }
                val date = if (change.archived) change.archivedDate?.let { "Archived $it" } else change.createdDate?.let { "Started $it" }
                date?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                BranchChip(change.worktree)
            }
        }
        Chevron(openable)
    }
}

@Composable
private fun SpecList(specs: List<*>?, openable: Boolean, onOpen: (String) -> Unit) {
    val list = specs?.filterIsInstance<OsSpecSummary>()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (list == null) return@LazyColumn
        if (list.isEmpty()) item { Centered("No specs in this folder.") }
        items(list, key = { it.topic }) { spec ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (openable) Modifier.clickable { onOpen(spec.topic) } else Modifier)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(spec.topic, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (spec.relatedChangeCount == 1) "1 change" else "${spec.relatedChangeCount} changes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Chevron(openable)
            }
        }
    }
}

// ---- Change ----

@Composable
fun OpenSpecChangeScreen(
    folderId: String,
    slug: String,
    owner: String,
    announcements: Announcements,
    onBack: () -> Unit,
    onOpenArchived: (String) -> Unit,
) {
    val target = OpenSpecTarget.Change(folderId, slug)
    FocusWhileShown(owner, target)
    val r = reading(target, announcements)
    val detail = r.loaded?.value as? OsChangeDetail
    SpekScaffold(topBar = { scroll ->
        TopBar(
            OpenSpecText.readableSlug(slug),
            scroll,
            onBack,
            subtitle = detail?.let { d ->
                {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (d.archived) "Archived" + (d.archivedDate?.let { " $it" } ?: "") else "Active",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        BranchChip(d.worktree)
                    }
                }
            },
            actions = { RefreshButton(r) },
        )
    }) {
        Column(Modifier.fillMaxSize()) {
            var selected by rememberSaveable { mutableStateOf<String?>(null) }
            val artifacts = detail?.artifacts.orEmpty()
            val index = artifacts.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: 0
            if (artifacts.isNotEmpty()) {
                PrimaryScrollableTabRow(selectedTabIndex = index, edgePadding = 8.dp) {
                    artifacts.forEachIndexed { i, a ->
                        OsTab(selected = i == index, onClick = { selected = a.id }, text = { Text(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
            StatusLine(r, target)
            r.loaded?.archivedAs?.let { archived ->
                Notice("This change was archived.", Tone.Info, action = "Open archived change", onAction = { onOpenArchived(archived.slug) })
            }
            when {
                detail == null -> Unit
                artifacts.isEmpty() -> Centered("This change has no artifacts.")
                else -> Artifact(artifacts[index])
            }
        }
    }
}

@Composable
private fun Artifact(artifact: OsArtifact) {
    val padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    when (artifact) {
        is OsArtifact.Markdown -> when (val c = artifact.content) {
            is Piece.Present -> RepositoryMarkdown(c.value, lazy = true, modifier = Modifier.fillMaxSize(), contentPadding = padding)
            is Piece.Omitted -> Omitted(c.bytes)
        }
        is OsArtifact.Data -> when (val c = artifact.content) {
            is Piece.Present -> DataText(c.value)
            is Piece.Omitted -> Omitted(c.bytes)
        }
        is OsArtifact.Tasks -> when (val t = artifact.tasks) {
            is Piece.Present -> Tasks(t.value)
            is Piece.Omitted -> Omitted(t.bytes)
        }
        is OsArtifact.Specs -> Deltas(artifact)
        is OsArtifact.Other -> Centered("This kind of artifact can't be shown on the phone.")
    }
}

/** A data file as its plain text, one lazy item per line, never parsed (design D6). */
@Composable
private fun DataText(text: String) {
    val lines = remember(text) { text.split('\n') }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        itemsIndexed(lines) { _, line ->
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun Tasks(tasks: TaskList) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "progress") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${tasks.completed} of ${tasks.total} done", style = MaterialTheme.typography.titleSmall)
                if (tasks.total > 0) LinearProgressIndicator(progress = { tasks.completed.toFloat() / tasks.total }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            }
        }
        tasks.sections.forEachIndexed { s, section ->
            if (section.title.isNotEmpty()) {
                item(key = "s$s") {
                    Text(
                        section.title,
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            section.tasks.forEachIndexed { t, task ->
                item(key = "s$s-t$t") { TaskRow(task) }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskList.Task) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // An icon, not a checkbox control: nothing here invites a tap.
        Icon(
            if (task.completed) SpekIcons.CheckBox else SpekIcons.CheckBoxOutline,
            if (task.completed) "Done" else "Not done",
            Modifier.padding(top = 2.dp).size(20.dp),
            tint = if (task.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        RepositoryMarkdown(task.text, lazy = false, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Deltas(specs: OsArtifact.Specs) {
    // Collapsed when there are more than three, so a large change does not lay out every delta at once.
    val expanded = remember(specs.id) { mutableStateMapOf<String, Boolean>() }
    val open = { topic: String -> expanded[topic] ?: (specs.deltas.size <= 3) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (specs.deltas.isEmpty()) item { Centered("No delta specs.") }
        specs.deltas.forEach { delta ->
            val isOpen = open(delta.topic)
            item(key = "h:" + delta.topic) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded[delta.topic] = !isOpen }.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (isOpen) SpekIcons.ExpandMore else SpekIcons.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(delta.topic, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (isOpen) {
                item(key = "b:" + delta.topic) {
                    when (val c = delta.content) {
                        is Piece.Present -> RepositoryMarkdown(c.value, lazy = false, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                        is Piece.Omitted -> Omitted(c.bytes)
                    }
                }
            }
        }
    }
}

// ---- Spec ----

@Composable
fun OpenSpecSpecScreen(
    folderId: String,
    topic: String,
    owner: String,
    announcements: Announcements,
    onBack: () -> Unit,
    onOpenChange: (String) -> Unit,
) {
    val target = OpenSpecTarget.Spec(folderId, topic)
    FocusWhileShown(owner, target)
    val r = reading(target, announcements)
    val spec = r.loaded?.value as? OsSpec
    var tab by rememberSaveable { mutableStateOf(0) }
    SpekScaffold(topBar = { scroll ->
        TopBar(
            topic,
            scroll,
            onBack,
            subtitle = spec?.origin?.let { o ->
                { Text("Main working tree · ${o.branch ?: "detached"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            },
            actions = { RefreshButton(r) },
        )
    }) {
        Column(Modifier.fillMaxSize()) {
            if (spec != null) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    OsTab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Spec") })
                    OsTab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History · ${spec.history.size}") })
                }
            }
            StatusLine(r, target)
            when {
                spec == null -> Unit
                tab == 0 -> when (val c = spec.content) {
                    is Piece.Present -> RepositoryMarkdown(c.value, lazy = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp))
                    is Piece.Omitted -> Omitted(c.bytes)
                }
                else -> History(spec.history, r.gates.openChange, onOpenChange)
            }
        }
    }
}

@Composable
private fun History(history: List<HistoryEntry>, openable: Boolean, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (history.isEmpty()) item { Centered("No change touched this spec.") }
        items(history, key = { it.slug }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (openable) Modifier.clickable { onOpen(entry.slug) } else Modifier)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entry.description.ifEmpty { OpenSpecText.readableSlug(entry.slug) }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(entry.date, if (entry.archived) "Archived" else "Active").joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Chevron(openable)
            }
        }
    }
}
