@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spekterm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.spekterm.android.R

/**
 * About (`play-distribution`, `play-release` D4): the app's version, its privacy policy and source, and its licenses.
 * Links open in the browser through [SafeUriHandler].
 */
@Composable
fun AboutScreen(onLicenses: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val links = remember(context) { SafeUriHandler(context) }
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName.orEmpty() to PackageInfoCompat.getLongVersionCode(info)
    }
    val privacy = stringResource(R.string.url_privacy_policy)
    val source = stringResource(R.string.url_source)
    SpekScaffold(topBar = { scroll -> TopBar(stringResource(R.string.about_title), scroll, onBack = onBack) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.about_version, version.first, version.second),
                    Modifier.testTag("about-version"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                AboutRow(stringResource(R.string.about_privacy), privacy) { links.openUri(privacy) }
                AboutRow(stringResource(R.string.about_source), source) { links.openUri(source) }
                AboutRow(stringResource(R.string.about_licenses), null, onLicenses)
            }
        }
    }
}

@Composable
private fun AboutRow(title: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * The licenses of the app and of every library it ships, from `res/raw/third_party_licenses.txt` (D4). Its section
 * markers become the app's own headings; the entries, license texts and notices are shown as their authors wrote them,
 * in English, whatever the app's language.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember(context) {
        val text = context.resources.openRawResource(R.raw.third_party_licenses).bufferedReader().use { it.readText() }
        LicenseText.blocks(text)
    }
    SpekScaffold(topBar = { scroll -> TopBar(stringResource(R.string.licenses_title), scroll, onBack = onBack) }) {
        LazyColumn(Modifier.fillMaxSize().testTag("licenses"), contentPadding = PaddingValues(16.dp)) {
            items(blocks) { block ->
                when (block) {
                    is LicenseText.Block.Heading -> Text(
                        stringResource(
                            when (block.section) {
                                LicenseText.Section.APP -> R.string.licenses_app
                                LicenseText.Section.LIBRARIES -> R.string.licenses_libraries
                                LicenseText.Section.LICENSES -> R.string.licenses_texts
                                LicenseText.Section.NOTICES -> R.string.licenses_notices
                            },
                        ),
                        Modifier.padding(top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is LicenseText.Block.Paragraph -> SelectionContainer {
                        Text(
                            block.text,
                            Modifier.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (block.preformatted) FontFamily.Monospace else null,
                        )
                    }
                }
            }
        }
    }
}

/** Report a problem (`play-release` D11): says where agent output comes from, then opens the new-issue page. */
@Composable
fun ReportProblemDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val url = stringResource(R.string.url_new_issue)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_title)) },
        text = { Text(stringResource(R.string.report_text)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                SafeUriHandler(context).openUri(url)
            }) { Text(stringResource(R.string.report_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
