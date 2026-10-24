@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

import com.spekterm.android.attention.AttentionNotifier
import androidx.compose.ui.res.pluralStringResource
import com.spekterm.android.service.Unreachability
import com.spekterm.android.service.ConnectionService
import com.spekterm.android.R
import androidx.compose.ui.res.stringResource
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
    onAbout: () -> Unit = {},
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
    var reporting by remember { mutableStateOf(false) }
    var allAddresses by remember { mutableStateOf(false) }

    // The app's settings: everything it lets the user set already lives here, the connection first (design D4, D10).
    SpekScaffold(topBar = { scroll -> TopBar(stringResource(R.string.status_title), scroll, onBack = onBack) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section(stringResource(R.string.status_section_connection))
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
                    status.lastClose?.let { Small(stringResource(R.string.status_last_disconnect, it)) }
                    Small(stringResource(R.string.status_connections, status.reconnects))

                    // Why the last round reached no address (`scan-connect-zh` D6), unless a stronger state says more.
                    val report = status.report?.takeIf {
                        !announcements.paused && !status.throttled && !announcements.identityChanged && announcements.unsupported == null
                    }
                    if (report != null) {
                        val advice = Reasons.advice(report, pairing.port).map { it.text() }
                        Notice((listOf(Reasons.said(report, pairing.port).text()) + advice).joinToString("\n"), Tone.Warning)
                    }

                    // The order they are tried; more than three collapse until asked (`desktop-connection`, D10) —
                    // except while a round is under way or its failure is shown: then each address shows its outcome.
                    val outcomes: List<Pair<String, Unreachability.Outcome?>>? = when {
                        status.attempts.isNotEmpty() -> pairing.addressOrder().filter { it in status.attempts }.map { it to status.attempts[it] }
                        report != null -> report.outcomes
                        else -> null
                    }
                    val addresses = pairing.addressOrder()
                    if (outcomes != null) {
                        Small(stringResource(R.string.status_addresses_order, pairing.port))
                        for ((address, outcome) in outcomes) {
                            // A long IPv6 address wraps; its outcome stays on one line beside it.
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(address, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                Text(
                                    Reasons.outcome(outcome).text(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    } else if (addresses.size <= 3 || allAddresses) {
                        Small(stringResource(R.string.status_addresses_order, pairing.port))
                        for (address in addresses) Mono(address)
                    } else {
                        Small(pluralStringResource(R.plurals.status_addresses_count, addresses.size, addresses.size, pairing.port))
                        TextButton(onClick = { allAddresses = true }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.status_show_all)) }
                    }

                    // States also announced by notifications, so they show without the notification permission.
                    if (announcements.identityChanged) {
                        Notice(stringResource(R.string.status_identity_changed), Tone.Error, stringResource(R.string.common_dismiss), {
                            onAnnouncements { it.copy(identityChanged = false) }
                        })
                    }
                    if (announcements.connectionStopped) {
                        Notice(stringResource(R.string.status_connection_stopped), Tone.Warning, stringResource(R.string.common_dismiss), {
                            onAnnouncements { it.copy(connectionStopped = false) }
                        })
                    }
                    announcements.unsupported?.let { hint ->
                        val who = when (hint) {
                            "UPDATE_APP" -> stringResource(R.string.status_update_app)
                            "UPDATE_DESKTOP" -> stringResource(R.string.status_update_desktop)
                            else -> stringResource(R.string.status_update_either)
                        }
                        Notice(stringResource(R.string.status_unsupported, who), Tone.Error, stringResource(R.string.common_dismiss), {
                            onAnnouncements { it.copy(unsupported = null) }
                        })
                    }

                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (announcements.paused || status.state == Status.State.STOPPED) {
                            FilledTonalButton(onClick = onResume) { Text(stringResource(R.string.common_resume)) }
                        } else {
                            FilledTonalButton(onClick = onPause) { Text(stringResource(R.string.status_pause)) }
                        }
                        if (status.canTryNow(announcements.paused)) {
                            TextButton(onClick = { ConnectionService.tryNow(context) }) { Text(stringResource(R.string.status_try_now)) }
                        }
                        TextButton(onClick = { exportLog(context) }) { Text(stringResource(R.string.status_export_log)) }
                    }
                }

                Section(stringResource(R.string.status_section_appearance))
                val theme by context.app.theme.collectAsStateWithLifecycle()
                AppearanceSection(theme) { context.app.chooseTheme(it) }

                Section(stringResource(R.string.status_section_notifications))
                SettingRow(
                    stringResource(R.string.status_attention_notifications),
                    if (notificationsOn) stringResource(R.string.status_on) else stringResource(R.string.status_off),
                    if (notificationsOn) null else stringResource(R.string.status_notification_settings) to {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    },
                )
                SettingRow(
                    stringResource(R.string.status_battery_optimization),
                    if (batteryExempt) stringResource(R.string.status_battery_off) else stringResource(R.string.status_battery_on),
                    stringResource(R.string.status_battery_settings) to { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                )

                Section(stringResource(R.string.status_section_recent))
                if (status.recent.isEmpty()) Small(stringResource(R.string.status_recent_none))
                for (event in status.recent) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(time.format(Date(event.receivedAt)), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(stringResource(AttentionNotifier.headline(event.kind)), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(event.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(recentOutcome(event.outcome), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }

                Section(stringResource(R.string.status_section_pairing))
                OutlinedButton(onClick = onPairAgain) { Text(stringResource(R.string.status_pair_new_code)) }
                TextButton(onClick = { unpairing = true }) { Text(stringResource(R.string.status_unpair), color = colors.error) }

                Section(stringResource(R.string.status_section_about))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAbout) { Text(stringResource(R.string.about_open)) }
                    // Report a problem lives here, not in every conversation's menu (`play-release` D11).
                    TextButton(onClick = { reporting = true }) { Text(stringResource(R.string.report_problem)) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (reporting) ReportProblemDialog { reporting = false }
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
        title = { Text(stringResource(R.string.status_unpair_title, desktop)) },
        text = { Text(stringResource(R.string.status_unpair_body)) },
        confirmButton = { TextButton(onClick = onUnpair) { Text(stringResource(R.string.status_unpair), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Light or dark as the phone is set, or fixed (`app-appearance`, D13); a tap applies at once. */
@Composable
fun AppearanceSection(choice: ThemeChoice, onChoose: (ThemeChoice) -> Unit) {
    val options = listOf(ThemeChoice.SYSTEM to stringResource(R.string.status_theme_system), ThemeChoice.LIGHT to stringResource(R.string.status_theme_light), ThemeChoice.DARK to stringResource(R.string.status_theme_dark))
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

@Composable
private fun stateTitle(status: Status, paused: Boolean): String = stringResource(
    when {
        paused -> R.string.status_paused
        status.state == Status.State.CONNECTED -> R.string.status_connected
        status.remoteOff -> R.string.status_remote_off
        status.state == Status.State.CONNECTING -> R.string.status_connecting
        status.state == Status.State.WAITING -> R.string.status_waiting
        else -> R.string.status_not_connected
    },
)

@Composable
private fun stateText(status: Status, paused: Boolean, time: DateFormat): String = when {
    paused -> stringResource(R.string.status_paused)
    status.state == Status.State.CONNECTED -> status.since?.let {
        stringResource(R.string.status_connected_via_since, status.address.orEmpty(), time.format(Date(it)))
    } ?: stringResource(R.string.status_connected_via, status.address.orEmpty())
    status.remoteOff -> stringResource(R.string.status_remote_off_long)
    status.state == Status.State.CONNECTING ->
        status.address?.let { stringResource(R.string.status_connecting_to, it) } ?: stringResource(R.string.status_connecting)
    status.state == Status.State.WAITING ->
        status.retryAt?.let { stringResource(R.string.status_waiting_at, time.format(Date(it))) } ?: stringResource(R.string.status_waiting)
    else -> stringResource(R.string.status_not_connected)
}

/** What happened to a recent event, in words; an outcome the app does not know is shown as recorded. */
@Composable
private fun recentOutcome(outcome: String): String = when (outcome) {
    "notified" -> stringResource(R.string.status_recent_notified)
    "suppressed" -> stringResource(R.string.status_recent_suppressed)
    "visible" -> stringResource(R.string.status_recent_visible)
    "summarized" -> stringResource(R.string.status_recent_summarized)
    "replaced" -> stringResource(R.string.status_recent_replaced)
    else -> outcome
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
    context.startActivity(Intent.createChooser(send, context.getString(R.string.status_export_chooser)))
}
