package com.spekterm.android.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.net.AgentUsage
import com.spekterm.android.net.UsageWindow
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The usage line (`agent-usage`, "Show the usage on one line, and in full on request"). */
@RunWith(AndroidJUnit4::class)
class UsageLineTest {
    @get:Rule val compose = createComposeRule()

    private var locale: Locale = Locale.getDefault()

    @Before fun english() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restore() = Locale.setDefault(locale)

    private fun show(usage: AgentUsage) = compose.setContent {
        English {
            val now = rememberUsageNow(usage)
            UsageRow(usage, now) {}
        }
    }

    @Test
    fun aTypicalSessionOnOneLine() {
        show(AgentUsage("s1", "Opus 5.5", "high", 42.5, 200_000, 3.214, UsageWindow(31.0, System.currentTimeMillis() + 3_600_000)))
        compose.onNodeWithText("Opus 5.5 (high) · Context 43% of 200K · \$3.21").assertIsDisplayed()
    }

    @Test
    fun aLimitLeavesTheLineWhenItsResetPasses() {
        show(AgentUsage("s1", model = "Opus 5.5", fiveHour = UsageWindow(85.0, System.currentTimeMillis() + 3_000)))
        compose.onNodeWithText("Opus 5.5 · 5-hour limit 85%").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("5-hour limit", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Opus 5.5").assertIsDisplayed()
    }
}
