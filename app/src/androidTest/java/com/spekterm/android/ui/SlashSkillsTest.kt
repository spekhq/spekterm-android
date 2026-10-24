package com.spekterm.android.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.desktop.Conversation
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.Skills
import com.spekterm.android.desktop.Watch
import com.spekterm.android.net.Skill
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Skills offered from a typed slash (`skill-commands`, "Choose a skill to start a reply"; `tool-call-display` D10). */
@RunWith(AndroidJUnit4::class)
class SlashSkillsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val described = "See https://example.com/x and [a](https://x) for more"
    private val skills = Skills.Known(
        listOf(Skill("simplify", "then apply the fixes"), Skill("opsx:apply", null), Skill("opsx:archive", null), Skill("docs", described), Skill("code-review", "Review the open diff")),
    )

    @Before fun reset() = runBlocking {
        Desktop.resetForTest()
        Desktop.focus("o1", "s1")
        withTimeout(5_000) { Desktop.conversation.first { it?.sessionId == "s1" } }
        Unit
    }

    // The composer's pickers need a registry; nothing is launched here.
    private val pickers = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) = Unit
        }
    }

    private fun show(c: Conversation, connected: Boolean = true, panelMax: androidx.compose.ui.unit.Dp = 240.dp, wrap: @Composable (@Composable () -> Unit) -> Unit = { it() }) {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickers) {
                English { wrap { Composer("s1", c, connected = connected, paused = false, answerable = false, canAttach = false, panelMax = panelMax) } }
            }
        }
    }

    private fun watched(skills: Skills?, draft: String = "") = Conversation("s1", watch = Watch.Watching, draft = draft, skills = skills)

    private val field get() = compose.onNode(hasSetTextAction())

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aSlashOffersTheSkillsAndChoosingFillsTheField() {
        show(watched(skills))
        field.performTextInput("/")
        assertTrue(shown("/code-review"))
        assertTrue(shown("5 skills"))
        assertTrue(shown("Built-in commands"))
        field.performTextInput("opsx")
        assertTrue(shown("/opsx:apply"))
        assertFalse(shown("/simplify"))
        compose.onNodeWithText("/opsx:apply").performClick()
        field.assertTextEquals("/opsx:apply ")
        assertEquals(TextRange(12), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.assertIsFocused()
        assertFalse(shown("/opsx:archive"))
        runBlocking { withTimeout(5_000) { Desktop.conversation.first { it?.draft == "/opsx:apply " } } }
    }

    @Test
    fun aSlashBeforeTextListsAllAndKeepsTheText() {
        show(watched(skills, draft = "fix the login bug"))
        field.performClick()
        field.performTextInputSelection(TextRange(0))
        field.performTextInput("/")
        assertTrue(shown("5 skills"))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("/simplify"))
        compose.onNodeWithText("/simplify").performClick()
        field.assertTextEquals("/simplify fix the login bug")
    }

    @Test
    fun theListGoesAwayWhenTheWordEnds() {
        show(watched(skills))
        field.performTextInput("/review")
        assertTrue(shown("No skill matches") || shown("/code-review"))
        field.performTextInput(" ")
        assertFalse(shown("Built-in commands"))
        field.performTextReplacement("/tmp/x")
        assertFalse(shown("Built-in commands"))
        field.performTextReplacement("/op")
        assertTrue(shown("Built-in commands"))
        field.performTextInputSelection(TextRange(0))
        assertFalse(shown("Built-in commands"))
    }

    @Test
    fun backClosesTheListAndTypingBringsItBack() {
        show(watched(skills))
        field.performTextInput("/op")
        assertTrue(shown("/opsx:apply"))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(shown("/opsx:apply"))
        assertFalse(compose.activity.isFinishing)
        field.performTextInput("s")
        assertTrue(shown("/opsx:apply"))
    }

    @Test
    fun anEmptyList() {
        show(watched(Skills.Known(emptyList())))
        field.performTextInput("/")
        assertTrue(shown("No skills in this session"))
    }

    @Test
    fun noMatchAndPlainDescriptions() {
        show(watched(skills))
        field.performTextInput("/zzz")
        assertTrue(shown("No skill matches"))
        field.performTextReplacement("/docs")
        val node = compose.onNodeWithText(described).fetchSemanticsNode()
        val annotated = node.config[SemanticsProperties.Text]
        assertFalse(annotated.any { it.getLinkAnnotations(0, it.length).isNotEmpty() || it.getStringAnnotations(0, it.length).isNotEmpty() })
    }

    @Test
    fun notKnownYet() {
        show(watched(Skills.Unknown))
        field.performTextInput("/")
        compose.onNodeWithText("first message", substring = true).assertIsDisplayed()
        assertFalse(shown("No skills"))
    }

    @Test
    fun rowsAreButtonsWithALabel() {
        show(watched(skills))
        field.performTextInput("/simp")
        val row = compose.onAllNodesWithText("/simplify").fetchSemanticsNodes().first { SemanticsActions.OnClick in it.config || it.parent?.config?.contains(SemanticsActions.OnClick) == true }
        val clickable = if (SemanticsActions.OnClick in row.config) row else row.parent!!
        assertEquals(Role.Button, clickable.config[SemanticsProperties.Role])
        assertEquals("Insert", clickable.config[SemanticsActions.OnClick].label)
    }

    @Test
    fun aSmallWindowKeepsTheField() {
        // As the screen gives it: a quarter of the height left, at most 240 dp.
        show(watched(skills), panelMax = minOf(240.dp, 300.dp / 4)) { content -> Box(Modifier.fillMaxWidth().height(300.dp)) { Column { content() } } }
        field.performTextInput("/")
        field.assertIsDisplayed()
    }

    @Test
    fun theHintSaysSlash() {
        show(watched(skills))
        assertTrue(shown("Reply to the agent · / for skills"))
    }

    @Test
    fun withoutASkillEventNoListAndThePlainHint() {
        show(watched(null))
        assertTrue(shown("Reply to the agent"))
        assertFalse(shown("/ for skills"))
        field.performTextInput("/")
        assertFalse(shown("Built-in commands"))
    }

    @Test
    fun disconnectedNoList() {
        show(watched(skills), connected = false)
        field.performTextInput("/")
        assertFalse(shown("Built-in commands"))
    }
}
