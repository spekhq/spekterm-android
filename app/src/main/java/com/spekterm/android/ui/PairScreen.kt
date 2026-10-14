package com.spekterm.android.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        if (granted) scanning = true else message = "Scanning needs the camera. You can paste the pairing link instead."
    }

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pair with spekterm", style = MaterialTheme.typography.headlineSmall)
        if (pairAgain) {
            Text(
                "The desktop no longer accepts this phone (it was revoked, or remote access was reset). Pair again.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text("On the desktop: Settings → Remote access → Pair device. Then scan the code, or paste its link.")

        if (scanning) {
            QrScanner(Modifier.fillMaxWidth().aspectRatio(1f)) { pair(it) }
            OutlinedButton(onClick = { scanning = false }) { Text("Cancel") }
        } else {
            Button(
                enabled = busy == null,
                onClick = {
                    message = null
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        scanning = true
                    } else {
                        askCamera.launch(Manifest.permission.CAMERA)
                    }
                },
            ) { Text("Scan QR code") }
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Pairing link") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = busy == null, onClick = {
                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it }
            }) { Text("Paste") }
            Button(enabled = busy == null && text.isNotBlank(), onClick = { pair(text) }) { Text("Pair") }
        }

        busy?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(it)
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (onCancel != null) {
            Text("The current pairing stays until a new one succeeds.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = busy == null, onClick = onCancel) { Text("Back to status") }
        }
    }
}

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
