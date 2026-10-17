package com.spekterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.spekterm.android.R
import com.spekterm.android.pairing.PairingLink
import com.spekterm.android.store.Pairing

/**
 * A pairing link handed over by another app, asked about before anything is sent (`device-pairing`, "Open a
 * pairing link from another app"; `scan-connect-zh` D4).
 */
object PairOffer {
    enum class Kind {
        /** Not paired: a first pairing. */
        NEW,
        /** The link's fingerprint is the pairing's: a new code for the same desktop. */
        SAME_DESKTOP,
        /** Another fingerprint: another desktop, whatever name the link gives. */
        OTHER_DESKTOP,
    }

    fun kind(link: PairingLink, current: Pairing?): Kind = when {
        current == null -> Kind.NEW
        PairingLink.normalizeFingerprint(current.fingerprint) == link.fingerprint -> Kind.SAME_DESKTOP
        else -> Kind.OTHER_DESKTOP
    }

    /** What the question says, after its title: the comparison with [current], then where the link came from. */
    fun lines(link: PairingLink, current: Pairing?): List<UiText> {
        val currentName = current?.desktopName?.ifEmpty { null } ?: "spekterm"
        return listOfNotNull(
            when (kind(link, current)) {
                Kind.NEW -> null
                Kind.SAME_DESKTOP -> UiText.Res(R.string.pair_offer_same, currentName)
                Kind.OTHER_DESKTOP -> if (link.name.isEmpty()) {
                    UiText.Res(R.string.pair_offer_different_unnamed, currentName)
                } else {
                    UiText.Res(R.string.pair_offer_different, currentName, link.name)
                }
            },
            UiText.Res(R.string.pair_offer_from_link),
        )
    }

    /** A different desktop is never titled by the name it gives: that name proves nothing (D4). */
    fun title(link: PairingLink, current: Pairing?): UiText = when {
        kind(link, current) == Kind.OTHER_DESKTOP -> UiText.Res(R.string.pair_offer_title_other)
        kind(link, current) == Kind.SAME_DESKTOP -> UiText.Res(R.string.pair_offer_title_same, current?.desktopName?.ifEmpty { null } ?: "spekterm")
        link.name.isEmpty() -> UiText.Res(R.string.pair_offer_title_unnamed)
        else -> UiText.Res(R.string.pair_offer_title, link.name)
    }
}

@Composable
private fun OfferBody(link: PairingLink, current: Pairing?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (line in PairOffer.lines(link, current)) Text(line.text(), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.pair_offer_port, link.port),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (address in link.addresses) Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** Inline, on the pairing screen. */
@Composable
fun PairOfferCard(link: PairingLink, current: Pairing?, onPair: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(PairOffer.title(link, current).text(), style = MaterialTheme.typography.titleMedium)
        OfferBody(link, current)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            FilledTonalButton(onClick = onPair) { Text(stringResource(R.string.common_pair)) }
        }
    }
}

/** Over whatever screen is showing while paired, so cancelling returns to it unchanged. */
@Composable
fun PairOfferDialog(raw: String, current: Pairing, onPair: () -> Unit, onCancel: () -> Unit) {
    when (val parsed = PairingLink.parse(raw)) {
        is PairingLink.Result.Refused -> AlertDialog(
            onDismissRequest = onCancel,
            text = { Text(refusalText(parsed.reason).text()) },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_ok)) } },
        )
        is PairingLink.Result.Ok -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(PairOffer.title(parsed.link, current).text()) },
            text = { OfferBody(parsed.link, current) },
            confirmButton = { TextButton(onClick = onPair) { Text(stringResource(R.string.common_pair)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

fun refusalText(reason: PairingLink.Refusal): UiText = UiText.Res(
    when (reason) {
        PairingLink.Refusal.UNSUPPORTED_VERSION -> R.string.pair_refused_version
        PairingLink.Refusal.BAD_ADDRESS -> R.string.pair_refused_address
        else -> R.string.pair_refused_invalid
    },
)
