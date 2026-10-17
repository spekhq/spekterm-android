@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

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

@Composable
fun PairScreen(pairAgain: Boolean, onPaired: (Pairing) -> Unit, onCancel: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun pair(raw: String) {
        scanning = false
        when (val parsed = PairingLink.parse(raw)) {
            is PairingLink.Result.Refused -> message = refusalText(parsed.reason)
            is PairingLink.Result.Ok -> {
                val link = parsed.link
                busy = "Pairing with ${link.name.ifEmpty { "the desktop" }}…"
                message = null
                scope.launch {
                    val result = try {
                        Pairer.pair(link, deviceName(context), Build.MODEL)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Pairer.Result.Failed(Pairer.Failure.OTHER, link.addresses, e.javaClass.simpleName)
                    }
                    busy = null
                    when (result) {
                        is Pairer.Result.Paired -> onPaired(result.pairing)
                        is Pairer.Result.Failed -> message = failureText(result)
                    }
                }
            }
        }
    }

    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else message = CAMERA_DENIED
    }

    // Back while re-pairing returns to settings, as the top bar's back does; the pairing stays.
    BackHandler(enabled = onCancel != null) { onCancel?.invoke() }
    SpekScaffold(topBar = { scroll -> if (onCancel != null) TopBar("Pair again", scroll, onBack = onCancel) }) {
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
                Text("Pair with spekterm", style = MaterialTheme.typography.headlineSmall)
                if (pairAgain) {
                    Notice("The desktop no longer accepts this phone (it was revoked, or remote access was reset). Pair again.", Tone.Warning)
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Step(1, "On the desktop, open Settings → Remote access → Pair device.")
                    Step(2, "Scan the code, or paste its link.")
                }

                if (scanning) {
                    QrScanner(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)) { pair(it) }
                    OutlinedButton(onClick = { scanning = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
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
                        Text("Scan QR code")
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("or", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.weight(1f))
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Pairing link") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    trailingIcon = {
                        IconButton(enabled = busy == null, onClick = {
                            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                            clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it }
                        }) { Icon(SpekIcons.Paste, "Paste link") }
                    },
                )
                FilledTonalButton(
                    enabled = busy == null && text.isNotBlank(),
                    onClick = { pair(text) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Pair") }

                busy?.let {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                message?.let { Notice(it, messageTone(it)) }
                if (onCancel != null) {
                    Text("The current pairing stays until a new one succeeds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = busy == null, onClick = onCancel) { Text("Back to settings") }
                }
            }
        }
    }
}

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

/** D5: the camera refused is a warning (pasting still works); a refused link or a failed pairing is an error. */
private fun messageTone(message: String): Tone = if (message == CAMERA_DENIED) Tone.Warning else Tone.Error

private const val CAMERA_DENIED = "Scanning needs the camera. You can paste the pairing link instead."

private fun deviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

private fun refusalText(reason: PairingLink.Refusal): String = when (reason) {
    PairingLink.Refusal.UNSUPPORTED_VERSION -> "This link needs a newer version of the app."
    PairingLink.Refusal.BAD_ADDRESS -> "The link holds an address the phone cannot connect to."
    else -> "This is not a valid pairing link."
}

private fun failureText(result: Pairer.Result.Failed): String = when (result.failure) {
    Pairer.Failure.CODE_INVALID -> "The code is no longer valid (used, expired, or its view was closed). Show a new code on the desktop."
    Pairer.Failure.THROTTLED -> "Too many failed attempts. Wait a minute and try again."
    Pairer.Failure.UNSUPPORTED -> "The app and the desktop speak different protocol versions" +
        (result.detail?.let { " (desktop: $it)" } ?: "") + ". Update the older one."
    Pairer.Failure.WRONG_CERTIFICATE -> "A server answered at ${result.tried.joinToString()}, but it is not the desktop in the link."
    Pairer.Failure.UNREACHABLE -> "Could not reach the desktop. Tried: ${result.tried.joinToString()}."
    Pairer.Failure.OTHER -> "Pairing failed" + (result.detail?.let { ": $it" } ?: ".")
}
