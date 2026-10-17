@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spekterm.android.app
import com.spekterm.android.service.Notifications
import com.spekterm.android.service.Status
import com.spekterm.android.service.StatusBoard
import com.spekterm.android.store.Announcements
import com.spekterm.android.store.Pairing
import com.spekterm.android.store.ThemeChoice
import com.spekterm.android.ui.theme.spekColors
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
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val status by StatusBoard.status.collectAsStateWithLifecycle()
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val notificationsOn = Notifications.canPost(context)
    val batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    val colors = MaterialTheme.colorScheme
    val desktop = pairing.desktopName.ifEmpty { "spekterm" }
    var unpairing by remember { mutableStateOf(false) }
    var allAddresses by remember { mutableStateOf(false) }

    // The app's settings: everything it lets the user set already lives here, the connection first (design D4, D10).
    SpekScaffold(topBar = { scroll -> TopBar("Settings", scroll, onBack = onBack) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("Connection")
                Column(
                    Modifier.fillMaxWidth().background(colors.surfaceContainer, MaterialTheme.shapes.large).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusDot(stateColor(status, announcements), size = 10.dp)
                        Text(stateTitle(status, announcements.paused), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(desktop, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    // The full state, with the address in use and since when; the title above is its short form.
                    stateText(status, announcements.paused, time).takeIf { it != stateTitle(status, announcements.paused) }?.let { Small(it) }
                    status.lastClose?.let { Small("Last disconnect: $it") }
                    Small("Connections this session: ${status.reconnects}")

                    // The order they are tried; more than three collapse until asked (`desktop-connection`, D10).
                    val addresses = pairing.addressOrder()
                    if (addresses.size <= 3 || allAddresses) {
                        Small("Addresses, in the order they are tried · port ${pairing.port}")
                        for (address in addresses) Mono(address)
                    } else {
                        Small("${addresses.size} addresses · port ${pairing.port}")
                        TextButton(onClick = { allAddresses = true }, contentPadding = PaddingValues(0.dp)) { Text("Show all") }
                    }

                    // States also announced by notifications, so they show without the notification permission.
                    if (announcements.identityChanged) {
                        Notice("The desktop presents a different certificate than when you paired. If you reset remote access, pair again.", Tone.Error, "Dismiss", {
                            onAnnouncements { it.copy(identityChanged = false) }
                        })
                    }
                    if (announcements.connectionStopped) {
                        Notice("Android stopped the connection in the background. Resume below.", Tone.Warning, "Dismiss", {
                            onAnnouncements { it.copy(connectionStopped = false) }
                        })
                    }
                    announcements.unsupported?.let { hint ->
                        val who = when (hint) {
                            "UPDATE_APP" -> "Update this app."
                            "UPDATE_DESKTOP" -> "Update spekterm on the desktop."
                            else -> "Update the app or the desktop."
                        }
                        Notice("The app and the desktop speak different protocol versions. $who", Tone.Error, "Dismiss", {
                            onAnnouncements { it.copy(unsupported = null) }
                        })
                    }

                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (announcements.paused || status.state == Status.State.STOPPED) {
                            FilledTonalButton(onClick = onResume) { Text("Resume") }
                        } else {
                            FilledTonalButton(onClick = onPause) { Text("Pause") }
                        }
                        TextButton(onClick = { exportLog(context) }) { Text("Export log") }
                    }
                }

                Section("Appearance")
                val theme by context.app.theme.collectAsStateWithLifecycle()
                AppearanceSection(theme) { context.app.chooseTheme(it) }

                Section("Notifications")
                SettingRow(
                    "Attention notifications",
                    if (notificationsOn) "On" else "Off",
                    if (notificationsOn) null else "Notification settings" to {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    },
                )
                SettingRow(
                    "Battery optimization",
                    if (batteryExempt) "Off for this app" else "On — Android may stop the connection",
                    "Battery settings" to { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                )

                Section("Recent events")
                if (status.recent.isEmpty()) Small("None yet.")
                for (event in status.recent) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(time.format(Date(event.receivedAt)), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(event.kind, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(event.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(event.outcome, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }

                Section("Pairing")
                OutlinedButton(onClick = onPairAgain) { Text("Pair with a new code") }
                TextButton(onClick = { unpairing = true }) { Text("Unpair", color = colors.error) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (unpairing) {
        UnpairDialog(desktop, onUnpair = {
            unpairing = false
            onUnpair()
        }, onDismiss = { unpairing = false })
    }
}

/** Unpairing asks first: the phone has to be paired again from the desktop afterwards (`device-pairing`, D10). */
@Composable
fun UnpairDialog(desktop: String, onUnpair: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unpair from $desktop?") },
        text = { Text("The phone stops connecting and forgets this desktop. To use it again, pair from the desktop's Remote access settings.") },
        confirmButton = { TextButton(onClick = onUnpair) { Text("Unpair", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Light or dark as the phone is set, or fixed (`app-appearance`, D13); a tap applies at once. */
@Composable
fun AppearanceSection(choice: ThemeChoice, onChoose: (ThemeChoice) -> Unit) {
    val options = listOf(ThemeChoice.SYSTEM to "System", ThemeChoice.LIGHT to "Light", ThemeChoice.DARK to "Dark")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = choice == value,
                onClick = { if (choice != value) onChoose(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(top = 16.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Mono(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

/** A setting: its name, its current value, and where to change it. */
@Composable
private fun SettingRow(title: String, value: String, action: Pair<String, () -> Unit>?) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large).padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
    }
}

/** The connection's dot (D10): green connected, attention on the way, faint paused, danger when it can't work. */
@Composable
private fun stateColor(status: Status, announcements: Announcements): Color {
    val spek = spekColors
    return when {
        announcements.identityChanged || announcements.unsupported != null -> spek.danger
        announcements.paused || status.state == Status.State.STOPPED -> spek.dormant
        status.state == Status.State.CONNECTED -> spek.working
        else -> spek.attention
    }
}

private fun stateTitle(status: Status, paused: Boolean): String = when {
    paused -> "Paused"
    status.state == Status.State.CONNECTED -> "Connected"
    status.remoteOff -> "Remote access is off"
    status.state == Status.State.CONNECTING -> "Connecting…"
    status.state == Status.State.WAITING -> "Waiting to retry"
    else -> "Not connected"
}

private fun stateText(status: Status, paused: Boolean, time: DateFormat): String = when {
    paused -> "Paused"
    status.state == Status.State.CONNECTED ->
        "Connected via ${status.address}" + (status.since?.let { " since ${time.format(Date(it))}" } ?: "")
    status.remoteOff -> "Remote access is off, or the desktop quit"
    status.state == Status.State.CONNECTING -> "Connecting to ${status.address}…"
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
