package com.spekterm.android.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spekterm.android.ui.theme.SpektermTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** About, Licenses, the pairing screen's About and Report a problem (`play-distribution`, `play-release` 2.4, 2.5). */
@RunWith(AndroidJUnit4::class)
class AboutTest {
    @get:Rule val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aboutShowsTheInstalledVersion() {
        val context = instrumentation.targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        compose.setContent { SpektermTheme(false) { AboutScreen(onLicenses = {}, onBack = {}) } }
        compose.onNodeWithText("spekterm").assertIsDisplayed()
        compose.onNodeWithText("Version ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})").assertIsDisplayed()
        compose.onNodeWithText("Privacy policy").assertIsDisplayed()
        compose.onNodeWithText("Source code").assertIsDisplayed()
        compose.onNodeWithText("Open-source licenses").assertIsDisplayed()
    }

    @Test
    fun licensesScrollToTheLastLicense() {
        compose.setContent { SpektermTheme(false) { LicensesScreen(onBack = {}) } }
        compose.onNodeWithText("This app").assertIsDisplayed()
        compose.onNodeWithTag("licenses").performScrollToNode(hasText("Mozilla Public License 2.0", substring = true))
        compose.onNodeWithTag("licenses").performScrollToNode(hasText("Notices"))
        compose.onNodeWithText("Notices").assertIsDisplayed()
    }

    @Test
    fun aboutFromThePairingScreenKeepsWhatWasTyped() {
        compose.setContent { SpektermTheme(false) { PairScreen(pairAgain = false, onPaired = {}) } }
        compose.onNodeWithText("Pairing link").performTextInput("spekterm://pair?partial")
        compose.onNodeWithText("About spekterm").performClick()
        compose.onNodeWithText("Open-source licenses").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("spekterm://pair?partial").assertIsDisplayed()
    }

    @Test
    fun aboutIsHiddenWhilePairing() {
        // 192.0.2.1 (TEST-NET-1) never answers, so pairing stays under way while the screen is checked.
        val fp = "AB".repeat(32)
        val link = "spekterm://pair?v=1&a=192.0.2.1&p=47615&fp=$fp&c=ABCDEFGHIJKLMNOPQRSTUVWXY2&n=desk"
        compose.setContent { SpektermTheme(false) { PairScreen(pairAgain = false, onPaired = {}, start = link) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Pairing with desk…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("About spekterm").assertDoesNotExist()
    }

    @Test
    fun pairingAgainHasNoAboutButton() {
        compose.setContent { SpektermTheme(false) { PairScreen(pairAgain = false, onPaired = {}, onCancel = {}) } }
        compose.onNodeWithText("Pair with spekterm").assertExists()
        compose.onNodeWithText("About spekterm").assertDoesNotExist()
    }

    @Test
    fun reportOpensTheNewIssuePageAndNothingElse() {
        // Catches the browser's start, so nothing opens, and keeps what it was asked to open.
        val started = mutableListOf<Intent>()
        // The no-argument monitor is the one whose onStartActivity sees every start; only a view is caught.
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                synchronized(started) { started += intent }
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.setContent { SpektermTheme(false) { ReportProblemDialog(onDismiss = {}) } }
            compose.onNodeWithText("Report a problem").assertIsDisplayed()
            compose.onNodeWithText("Nothing from your conversations is attached", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Report").performClick()
            compose.waitUntil(5_000) { synchronized(started) { started.isNotEmpty() } }
            assertEquals(listOf("https://github.com/spekhq/spekterm-android/issues/new"), synchronized(started) { started.map { it.dataString } })
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
