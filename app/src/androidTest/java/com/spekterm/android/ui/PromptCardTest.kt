package com.spekterm.android.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spekterm.android.R
import com.spekterm.android.desktop.Answers
import com.spekterm.android.desktop.Offer
import com.spekterm.android.desktop.Watch
import com.spekterm.android.net.Operations
import com.spekterm.android.net.Prompt
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The prompt card's answers despite typing on the desktop (`answer-despite-typed` D3–D5, D7), in en-US. */
@RunWith(AndroidJUnit4::class)
class PromptCardTest {
    @get:Rule val compose = createComposeRule()

    private val english: Context = InstrumentationRegistry.getInstrumentation().targetContext.let { base ->
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.US) })
    }

    private fun text(id: Int) = english.getString(id)

    private data class Sent(val promptId: String, val decision: String, val selections: List<List<Int>>?, val despiteTyped: Boolean)

    private val sent = mutableListOf<Sent>()

    private fun question(id: String = "q1", blockedBy: String? = Prompt.BLOCKED_TYPED) = Prompt.Question(
        id,
        listOf(Prompt.Question.Item("Which way?", "Way", false, listOf(Prompt.Question.Option("North", ""), Prompt.Question.Option("South", "")))),
        desktopAnswerable = blockedBy == null,
        open = 1,
        blockedBy = blockedBy,
    )

    private fun permission(blockedBy: String? = Prompt.BLOCKED_TYPED) =
        Prompt.Permission("p1", "Bash", "npm test", canAllow = true, answerable = blockedBy == null, open = 1, blockedBy = blockedBy)

    private fun plan(blockedBy: String? = Prompt.BLOCKED_TYPED) =
        Prompt.Plan("pl1", "Do it", listOf(Prompt.APPROVE, Prompt.APPROVE_AUTO, Prompt.DENY), answerable = blockedBy == null, open = 1, blockedBy = blockedBy)

    private fun offer(p: Prompt): Offer = Answers.offer(p, setOf(Operations.ANSWER), connected = true, paused = false, watch = Watch.Watching, answer = null)

    private var shown by mutableStateOf<Prompt>(question())

    private var started = false

    /** Shows [p]; the content is set once, later prompts replace the shown one as the desktop's would. */
    private fun show(p: Prompt) {
        shown = p
        if (!started) {
            started = true
            compose.setContent {
                English { PromptCard("s1", shown, offer(shown)) { id, decision, selections, despite -> sent += Sent(id, decision, selections, despite) } }
            }
        }
        compose.waitForIdle()
        // Past the tap guard (600 ms), which the test clock does not run through on its own.
        compose.mainClock.advanceTimeBy(700)
    }

    private fun button(label: Int) = compose.onNode(hasText(text(label)) and hasClickAction())

    @Test
    fun aQuestionTouchedOnTheDesktopAsksBeforeSending() {
        show(question())
        compose.onNodeWithText(text(R.string.prompt_typed_warning)).assertIsDisplayed()
        button(R.string.prompt_decline).assertIsDisplayed()
        compose.onNodeWithText("South").performClick()
        button(R.string.prompt_send_answer).performClick()
        compose.onNodeWithText(text(R.string.prompt_typed_confirm_title)).assertIsDisplayed()
        assertTrue("nothing sent before confirming", sent.isEmpty())

        compose.onNodeWithText(text(R.string.common_cancel)).performClick()
        compose.onNodeWithText(text(R.string.prompt_typed_confirm_title)).assertDoesNotExist()
        assertTrue("cancel sends nothing", sent.isEmpty())

        // Sent again without picking: the choice made before cancelling stayed.
        button(R.string.prompt_send_answer).performClick()
        compose.onNodeWithText(text(R.string.prompt_typed_send_anyway)).performClick()
        assertEquals(listOf(Sent("q1", Prompt.ANSWER, listOf(listOf(1)), true)), sent)
    }

    @Test
    fun allowOnceAndKeepPlanningAskToo() {
        show(permission())
        compose.onNodeWithText(text(R.string.prompt_typed_warning)).assertIsDisplayed()
        button(R.string.prompt_allow_once).performClick()
        compose.onNodeWithText(text(R.string.prompt_typed_send_anyway)).performClick()
        show(plan())
        button(R.string.prompt_keep_planning).performClick()
        compose.onNodeWithText(text(R.string.prompt_typed_send_anyway)).performClick()
        assertEquals(listOf(Sent("p1", Prompt.ALLOW, null, true), Sent("pl1", Prompt.DENY, null, true)), sent)
    }

    @Test
    fun aPlainAnswerIsSentWithoutAsking() {
        show(permission(blockedBy = null))
        compose.onNodeWithText(text(R.string.prompt_typed_warning)).assertDoesNotExist()
        button(R.string.prompt_allow_once).performClick()
        assertEquals(listOf(Sent("p1", Prompt.ALLOW, null, false)), sent)
    }

    @Test
    fun aReplacedPromptDropsTheConfirmation() {
        show(question())
        compose.onNodeWithText("North").performClick()
        button(R.string.prompt_send_answer).performClick()
        compose.onNodeWithText(text(R.string.prompt_typed_confirm_title)).assertIsDisplayed()
        shown = question(id = "q2")
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.prompt_typed_confirm_title)).assertDoesNotExist()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aPromptNoLongerTypedDropsTheConfirmation() {
        show(permission())
        button(R.string.prompt_allow_once).performClick()
        compose.onNodeWithText(text(R.string.prompt_typed_confirm_title)).assertIsDisplayed()
        shown = permission(blockedBy = null)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.prompt_typed_confirm_title)).assertDoesNotExist()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun turningAnswerableDespiteTypingRearmsTheTapGuard() {
        show(permission(blockedBy = Prompt.BLOCKED_STIRRED))
        compose.mainClock.autoAdvance = false
        shown = permission()
        compose.mainClock.advanceTimeByFrame()
        button(R.string.prompt_allow_once).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.prompt_typed_confirm_title)).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(700)
        button(R.string.prompt_allow_once).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(text(R.string.prompt_typed_confirm_title)).assertIsDisplayed()
    }

    @Test
    fun otherReasonsAreReadOnlyAndSayWhy() {
        show(permission(blockedBy = Prompt.BLOCKED_STIRRED))
        compose.onNodeWithText(text(R.string.prompt_not_answerable_stirred)).assertIsDisplayed()
        button(R.string.prompt_allow_once).assertDoesNotExist()
        button(R.string.prompt_deny).assertDoesNotExist()
        show(permission(blockedBy = Prompt.BLOCKED_DECISIVE))
        compose.onNodeWithText(text(R.string.prompt_not_answerable_decisive)).assertIsDisplayed()
        button(R.string.prompt_allow_once).assertDoesNotExist()
        show(permission(blockedBy = Prompt.BLOCKED_ANSWERED))
        compose.onNodeWithText(text(R.string.prompt_not_answerable_answered)).assertIsDisplayed()
        show(permission(blockedBy = null).copy(answerable = false))
        compose.onNodeWithText(text(R.string.prompt_not_answerable)).assertIsDisplayed()
    }
}
