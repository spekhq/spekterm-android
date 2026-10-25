package com.spekterm.android.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spekterm.android.R
import com.spekterm.android.desktop.Conversation
import com.spekterm.android.desktop.Switch
import com.spekterm.android.desktop.Watch
import com.spekterm.android.net.AgentUsage
import com.spekterm.android.net.Operations
import com.spekterm.android.net.WaitState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The "Model and effort" sheet (`session-model-effort` D1–D5), in en-US. */
@RunWith(AndroidJUnit4::class)
class ModelEffortSheetTest {
    @get:Rule val compose = createComposeRule()

    private val english: Context = InstrumentationRegistry.getInstrumentation().targetContext.let { base ->
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.US) })
    }

    private fun text(id: Int) = english.getString(id)

    /** A chip: the details below the choices name the reported values too. */
    private fun chip(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction())

    private val reported = AgentUsage("s1", model = "Sonnet 5.5", effort = "xhigh", contextPercent = 43.0, contextSize = 200_000, costUsd = 3.21)

    private fun conversation(usage: AgentUsage = reported, wait: String = WaitState.READY) =
        Conversation("s1", watch = Watch.Watching, wait = wait, usage = usage)

    private fun show(
        c: Conversation = conversation(),
        operations: Set<String> = setOf(Operations.SET_MODEL),
        inboxPrompt: Boolean = false,
        chosen: MutableList<Switch> = mutableListOf(),
    ) = compose.setContent {
        English { ModelEffortSheet(c, operations, connected = true, paused = false, answerable = false, inboxPrompt = inboxPrompt, onChoose = { chosen += it }, onDismiss = {}) }
    }

    @Test
    fun theReportedOnesAreMarkedAndAMarkedChoiceStillSends() {
        val chosen = mutableListOf<Switch>()
        show(chosen = chosen)
        compose.onNodeWithText("Now: Sonnet 5.5 · xhigh").assertIsDisplayed()
        chip("xhigh").assertIsSelected()
        chip("low").assertIsNotSelected()
        chip("Sonnet").assertIsSelected()
        chip("Opus").assertIsNotSelected()
        chip("Sonnet").performClick()
        compose.waitForIdle()
        assertEquals(listOf<Switch>(Switch.Model("sonnet")), chosen)
    }

    @Test
    fun withoutTheOperationOnlyEffortIsOffered() {
        show(operations = emptySet())
        compose.onNodeWithText(text(R.string.model_effort_needs_desktop)).assertIsDisplayed()
        chip("Sonnet").assertDoesNotExist()
        chip("low").assertIsEnabled()
    }

    @Test
    fun aNameOutsideTheCharsetCannotBeSent() {
        show()
        compose.onNodeWithText("Other…").performClick()
        compose.onNodeWithText("Model name").performTextInput("opus 1m")
        compose.onNodeWithText(text(R.string.model_effort_other_charset)).assertIsDisplayed()
        compose.onNodeWithText("Switch").assertIsNotEnabled()
    }

    @Test
    fun aPendingChoiceDisablesTheChoicesAndSaysWhy() {
        show(conversation(wait = WaitState.AWAITING_CHOICE))
        chip("low").assertIsNotEnabled()
        chip("Sonnet").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.conversation_blocked_choice)).assertIsDisplayed()
    }

    @Test
    fun anInboxPromptSaysItWaits() {
        show(inboxPrompt = true)
        compose.onNodeWithText(text(R.string.model_effort_inbox_prompt)).assertIsDisplayed()
        chip("high").assertIsNotEnabled()
    }

    @Test
    fun theDetailsAndTheNotesAreThere() {
        show()
        compose.onNodeWithText(text(R.string.model_effort_note_default)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.model_effort_note_reread)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Context").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.usage_footnote)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withNoSessionOperationTheMenuStillOpensWithTheItem() {
        compose.setContent {
            English { SessionMenu(expanded = true, onDismiss = {}, actions = emptyList(), enabled = true, extra = listOf("Model and effort" to {})) {} }
        }
        compose.onNodeWithText("Model and effort").assertIsDisplayed()
    }
}
