@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

import com.spekterm.android.service.Networks
import com.spekterm.android.log.ConnectionLog
import com.spekterm.android.app
import kotlinx.coroutines.delay
import com.spekterm.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.spekterm.android.pairing.Pairer
import com.spekterm.android.pairing.PairingLink
import com.spekterm.android.store.Pairing
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pairing (`device-pairing`): scanning, pasting, and a link handed over by another app. [offered] is such a link,
 * asked about inline until [onOfferDone]; [start] is one the user already confirmed over another screen, paired at
 * once ([onStarted] consumes it). [current] is the pairing in place while pairing again. [onBusy] says when a
 * pairing is under way, so the activity drops a link that arrives meanwhile (`scan-connect-zh` D4).
 */
@Composable
fun PairScreen(
    pairAgain: Boolean,
    onPaired: (Pairing) -> Unit,
    onCancel: (() -> Unit)? = null,
    current: Pairing? = null,
    offered: String? = null,
    onOfferDone: () -> Unit = {},
    start: String? = null,
    onStarted: () -> Unit = {},
    onBusy: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<UiText?>(null) }
    var message by remember { mutableStateOf<Message?>(null) }
    /** When the scanner last read a QR code that is not a pairing link; the hint clears 3 s after (D3). */
    var otherCodeAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(otherCodeAt) {
        val at = otherCodeAt ?: return@LaunchedEffect
        delay(OTHER_CODE_HINT_MS - (System.currentTimeMillis() - at))
        if (otherCodeAt == at) otherCodeAt = null
    }
    LaunchedEffect(busy != null) { onBusy(busy != null) }

    fun pair(raw: String) {
        scanning = false
        when (val parsed = PairingLink.parse(raw)) {
            is PairingLink.Result.Refused -> message = Message(listOf(refusalText(parsed.reason)), Tone.Error)
            is PairingLink.Result.Ok -> {
                val link = parsed.link
                busy = if (link.name.isEmpty()) UiText.Res(R.string.pair_busy_unnamed) else UiText.Res(R.string.pair_busy, link.name)
                message = null
                scope.launch {
                    val result = try {
                        Pairer.pair(
                            link,
                            deviceName(context),
                            Build.MODEL,
                            network = { Networks.current(context) },
                            onAttempt = { context.app.log.write(ConnectionLog.Kind.ATTEMPT, "address" to it, "source" to "pair") },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Pairer.Result.Failed(Pairer.Failure.OTHER, link.addresses, e.javaClass.simpleName)
                    }
                    busy = null
                    when (result) {
                        is Pairer.Result.Paired -> onPaired(result.pairing)
                        is Pairer.Result.Failed -> message = Message(failureText(result, link.port), Tone.Error)
                    }
                }
            }
        }
    }

    // A link confirmed over another screen pairs at once; one offered here stops scanning and is asked about.
    LaunchedEffect(start) {
        val link = start ?: return@LaunchedEffect
        onStarted()
        pair(link)
    }
    val offer = offered?.let { PairingLink.parse(it) }
    LaunchedEffect(offered) {
        if (offered == null) return@LaunchedEffect
        scanning = false
        if (offer is PairingLink.Result.Refused) {
            message = Message(listOf(refusalText(offer.reason)), Tone.Error)
            onOfferDone()
        }
    }

    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else message = Message(listOf(UiText.Res(R.string.pair_camera_denied)), Tone.Warning)
    }

    // Back while re-pairing returns to settings, as the top bar's back does; the pairing stays.
    BackHandler(enabled = onCancel != null) { onCancel?.invoke() }
    SpekScaffold(topBar = { scroll -> if (onCancel != null) TopBar(stringResource(R.string.pair_again_title), scroll, onBack = onCancel) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (onCancel == null) Spacer(Modifier.height(24.dp))
                Box(
                    Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(SpekIcons.QrCode, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineSmall)
                if (pairAgain) Notice(stringResource(R.string.pair_revoked), Tone.Warning)
                if (offer is PairingLink.Result.Ok && busy == null) {
                    PairOfferCard(
                        offer.link,
                        current,
                        onPair = {
                            offered?.let { raw ->
                                onOfferDone()
                                pair(raw)
                            }
                        },
                        onCancel = onOfferDone,
                    )
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Step(1, stringResource(R.string.pair_step_1))
                    Step(2, stringResource(R.string.pair_step_2))
                }

                if (scanning) {
                    QrScanner(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)) { decoded ->
                        // A pairing link stops scanning, even one `parse` then refuses; anything else is skipped (D3).
                        if (PairingLink.isPairingLink(decoded)) {
                            pair(decoded)
                            true
                        } else {
                            otherCodeAt = System.currentTimeMillis()
                            false
                        }
                    }
                    if (otherCodeAt != null) Notice(stringResource(R.string.scan_not_a_pairing_code), Tone.Info)
                    OutlinedButton(onClick = { scanning = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
                } else {
                    Button(
                        enabled = busy == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        onClick = {
                            message = null
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                scanning = true
                            } else {
                                askCamera.launch(Manifest.permission.CAMERA)
                            }
                        },
                    ) {
                        Icon(SpekIcons.QrCode, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pair_scan))
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text(stringResource(R.string.pair_or), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.weight(1f))
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.pair_link_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    trailingIcon = {
                        IconButton(enabled = busy == null, onClick = {
                            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                            clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it }
                        }) { Icon(SpekIcons.Paste, stringResource(R.string.pair_paste)) }
                    },
                )
                FilledTonalButton(
                    enabled = busy == null && text.isNotBlank(),
                    onClick = { pair(text) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.common_pair)) }

                busy?.let {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(it.text(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                message?.let { m -> Notice(m.lines.map { it.text() }.joinToString("\n"), m.tone) }
                if (onCancel != null) {
                    Text(stringResource(R.string.pair_current_stays), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = busy == null, onClick = onCancel) { Text(stringResource(R.string.pair_back_to_settings)) }
                }
            }
        }
    }
}

/** A notice's lines and its tone (D5): the camera refused is a warning (pasting still works), failures errors. */
private data class Message(val lines: List<UiText>, val tone: Tone)

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "$n",
            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).size(24.dp).wrapContentHeight(),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val OTHER_CODE_HINT_MS = 3_000L

private fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

/** The failure's sentences; an unreachable or wrong-certificate pairing says why, with each address's outcome (D6). */
private fun failureText(result: Pairer.Result.Failed, port: Int): List<UiText> {
    val report = result.report
    return when (result.failure) {
        Pairer.Failure.CODE_INVALID -> listOf(UiText.Res(R.string.pair_failed_code))
        Pairer.Failure.THROTTLED -> listOf(UiText.Res(R.string.pair_failed_throttled))
        Pairer.Failure.UNSUPPORTED -> listOf(
            result.detail?.let { UiText.Res(R.string.pair_failed_unsupported_versions, it) } ?: UiText.Res(R.string.pair_failed_unsupported),
        )
        Pairer.Failure.WRONG_CERTIFICATE, Pairer.Failure.UNREACHABLE -> if (report == null) {
            listOf(UiText.Res(R.string.pair_failed_unreachable))
        } else {
            listOf(UiText.Res(R.string.pair_failed_unreachable), Reasons.said(report, port)) + Reasons.advice(report, port) +
                report.outcomes.map { (address, outcome) -> UiText.Res(R.string.common_address_outcome, address, Reasons.outcome(outcome)) }
        }
        Pairer.Failure.OTHER -> listOf(result.detail?.let { UiText.Res(R.string.pair_failed_other_detail, it) } ?: UiText.Res(R.string.pair_failed_other))
    }
}
