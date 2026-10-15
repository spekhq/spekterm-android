package com.spekterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spekterm.android.service.Status
import com.spekterm.android.store.Announcements

/** A title row with an optional back button and actions; no icon library is needed. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("‹ Back") }
        Column(Modifier.weight(1f).padding(horizontal = if (onBack == null) 12.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        actions()
    }
}

/** A notice across the screen, with up to two optional actions. */
@Composable
fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}, second: String? = null, onSecond: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
        if (second != null) TextButton(onClick = onSecond) { Text(second) }
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
