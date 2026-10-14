package com.spekterm.android.ui

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spekterm.android.app
import com.spekterm.android.service.Notifications
import com.spekterm.android.service.Status
import com.spekterm.android.service.StatusBoard
import com.spekterm.android.store.Announcements
import com.spekterm.android.store.Pairing
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun StatusScreen(
    pairing: Pairing,
    announcements: Announcements,
    onAnnouncements: ((Announcements) -> Announcements) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onUnpair: () -> Unit,
    onPairAgain: () -> Unit,
) {
    val context = LocalContext.current
    val status by StatusBoard.status.collectAsStateWithLifecycle()
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val notificationsOn = Notifications.canPost(context)
    val batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(pairing.desktopName.ifEmpty { "spekterm" }, style = MaterialTheme.typography.headlineSmall)
        Text(stateText(status, announcements.paused, time))
        status.lastClose?.let { Text("Last disconnect: $it", style = MaterialTheme.typography.bodySmall) }
        Text("Connections this session: ${status.reconnects}", style = MaterialTheme.typography.bodySmall)
        Text("Addresses: ${pairing.addressOrder().joinToString()} · port ${pairing.port}", style = MaterialTheme.typography.bodySmall)

        // States also announced by notifications, so they show without the notification permission.
        if (announcements.identityChanged) {
            Banner("The desktop presents a different certificate than when you paired. If you reset remote access, pair again.") {
                onAnnouncements { it.copy(identityChanged = false) }
            }
        }
        if (announcements.connectionStopped) {
            Banner("Android stopped the connection in the background. Resume below.") {
                onAnnouncements { it.copy(connectionStopped = false) }
            }
        }
        announcements.unsupported?.let { hint ->
            val who = when (hint) {
                "UPDATE_APP" -> "Update this app."
                "UPDATE_DESKTOP" -> "Update spekterm on the desktop."
                else -> "Update the app or the desktop."
            }
            Banner("The app and the desktop speak different protocol versions. $who") {
                onAnnouncements { it.copy(unsupported = null) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (announcements.paused || status.state == Status.State.STOPPED) {
                Button(onClick = onResume) { Text("Resume") }
            } else {
                Button(onClick = onPause) { Text("Pause") }
            }
            OutlinedButton(onClick = { exportLog(context) }) { Text("Export log") }
        }

        HorizontalDivider()
        Text(if (notificationsOn) "Attention notifications: on" else "Attention notifications are off.")
        if (!notificationsOn) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }) { Text("Notification settings") }
        }
        Text(if (batteryExempt) "Battery optimization: off for this app" else "Battery optimization: on (Android may stop the connection)")
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
            Text("Battery settings")
        }

        HorizontalDivider()
        Text("Recent events", style = MaterialTheme.typography.titleMedium)
        if (status.recent.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall)
        for (event in status.recent) {
            Text("${time.format(Date(event.receivedAt))}  ${event.kind}  ${event.title}  (${event.outcome})", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        OutlinedButton(onClick = onPairAgain) { Text("Pair with a new code") }
        TextButton(onClick = onUnpair) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Banner(text: String, onDismiss: () -> Unit) {
    Column {
        Text(text, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

private fun stateText(status: Status, paused: Boolean, time: DateFormat): String = when {
    paused -> "Paused"
    status.state == Status.State.CONNECTED ->
        "Connected via ${status.address}" + (status.since?.let { " since ${time.format(Date(it))}" } ?: "")
    status.state == Status.State.CONNECTING -> "Connecting to ${status.address}…"
    status.state == Status.State.REMOTE_OFF -> "Remote access is off, or the desktop quit"
    status.state == Status.State.WAITING -> "Waiting to retry" + (status.retryAt?.let { " at ${time.format(Date(it))}" } ?: "")
    else -> "Not connected"
}

private fun exportLog(context: android.content.Context) {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val file = File(dir, "spekterm-connection-log.txt")
    file.writeText(context.app.log.export())
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Export connection log"))
}
