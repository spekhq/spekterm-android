package com.spekterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spekterm.android.desktop.SessionsView.RowState
import com.spekterm.android.service.Status
import com.spekterm.android.store.Announcements
import com.spekterm.android.ui.theme.spekColors

/**
 * A screen: its top bar and optional floating button, with the content kept clear of the system bars and the
 * keyboard. The background draws behind the bars (`ui-polish` D2).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpekScaffold(
    topBar: @Composable (TopAppBarScrollBehavior) -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { topBar(scroll) },
        floatingActionButton = floatingActionButton,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}

/** The top bar of a screen, with a back icon when there is a way back (D4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: String,
    scroll: TopAppBarScrollBehavior? = null,
    onBack: (() -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.invoke()
            }
        },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(SpekIcons.Back, "Back") } },
        actions = { actions() },
        scrollBehavior = scroll,
        colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** The overflow button of a top bar or a row. */
@Composable
fun MoreButton(onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(SpekIcons.More, "More options") }
}

/** How serious a notice is; every notice states it (D5). */
enum class Tone { Info, Warning, Error }

/** A notice across the screen, with up to two optional actions. */
@Composable
fun Notice(text: String, tone: Tone, action: String? = null, onAction: () -> Unit = {}, second: String? = null, onSecond: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        Tone.Info -> colors.surfaceContainerHigh to colors.onSurface
        Tone.Warning -> colors.primaryContainer to colors.onPrimaryContainer
        Tone.Error -> colors.errorContainer to colors.onErrorContainer
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(container, MaterialTheme.shapes.medium)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            when (tone) {
                Tone.Info -> SpekIcons.Info
                Tone.Warning -> SpekIcons.Warning
                Tone.Error -> SpekIcons.Error
            },
            null,
            Modifier.size(18.dp),
            tint = content,
        )
        Text(text, Modifier.weight(1f).padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = content)
        if (action != null) TextButton(onClick = onAction) { Text(action, color = content, fontWeight = FontWeight.SemiBold) }
        if (second != null) TextButton(onClick = onSecond) { Text(second, color = content) }
    }
}

/** A state's dot: filled, or hollow for dormant (D6). */
@Composable
fun StatusDot(color: Color, hollow: Boolean = false, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .then(if (hollow) Modifier.border(1.5.dp, color, CircleShape) else Modifier.background(color, CircleShape)),
    )
}

/**
 * A session's state at the end of its row or under a title: words, with emphasis by how much it needs the user
 * (D6, spec `app-appearance`). [RowState.NONE] on a running session is a dot only, labeled "Running".
 */
@Composable
fun StateLabel(state: RowState, modifier: Modifier = Modifier) {
    val spek = spekColors
    val colors = MaterialTheme.colorScheme
    when (state) {
        RowState.CHOICE -> Text(
            "Asks for a choice",
            modifier
                .background(colors.primaryContainer, CircleShape)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onPrimaryContainer,
        )
        RowState.READY -> DotText("Waiting for you", spek.attention, spek.attention, FontWeight.SemiBold, modifier)
        RowState.BUSY -> DotText("Working", spek.working, colors.onSurfaceVariant, FontWeight.Normal, modifier)
        RowState.DORMANT -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(spek.dormant, hollow = true)
            Text("Dormant", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        RowState.NONE -> StatusDot(spek.working, modifier = modifier.semantics { contentDescription = "Running" })
    }
}

@Composable
private fun DotText(text: String, dot: Color, color: Color, weight: FontWeight, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(dot)
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = weight, color = color)
    }
}

/** A centered message filling the screen, with an icon above it. */
@Composable
fun CenteredMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier, below: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        below()
    }
}

/**
 * Why the phone is not connected, from what the connection status knows (`session-list`, "Say when the list is
 * not current"). [attached] is whether the desktop layer has a connection; the service may already be
 * connected while the layer is still catching up.
 */
fun notConnectedReason(status: Status, announcements: Announcements): String = when {
    announcements.unsupported != null -> "The app and the desktop do not support the same protocol version."
    announcements.identityChanged -> "The desktop's identity changed: it presents a different certificate than when you paired."
    announcements.connectionStopped -> "The connection stopped. Resume it from the connection status."
    status.remoteOff -> "Remote access is off on the desktop, or spekterm quit."
    status.state == Status.State.CONNECTING -> "Connecting to the desktop…"
    status.state == Status.State.WAITING -> "Not connected — waiting to retry."
    status.state == Status.State.CONNECTED -> "Connected — catching up with the desktop…"
    else -> "Not connected to the desktop."
}
