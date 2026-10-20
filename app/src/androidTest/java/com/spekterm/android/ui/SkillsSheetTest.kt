package com.spekterm.android.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
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

/** The skills sheet and choosing a skill (`skill-commands`, "Choose a skill to start a reply"). */
@RunWith(AndroidJUnit4::class)
class SkillsSheetTest {
    @get:Rule val compose = createComposeRule()

    private val described = "See https://example.com/x and [a](https://x) for more"

    @Before fun reset() = runBlocking {
        Desktop.resetForTest()
        Desktop.focus("o1", "s1")
        withTimeout(5_000) { Desktop.conversation.first { it?.sessionId == "s1" } }
        Unit
    }

    @Test
    fun choosingASkillStartsTheReplyAndKeepsTheDraft() {
        val c = Conversation("s1", watch = Watch.Watching, draft = "fix it", skills = Skills.Known(listOf(Skill("simplify", "Review the changed code"), Skill("android-log"))))
        compose.setContent { English { Composer("s1", c, connected = true, paused = false, answerable = false, canAttach = false) } }
        compose.onNodeWithContentDescription("Skills").performClick()
        // Sorted by name.
        val rows = compose.onAllNodesWithText("/", substring = true).fetchSemanticsNodes().map { n -> n.config[SemanticsProperties.Text].joinToString("") { it.text } }
        assertEquals(listOf("/android-log", "/simplify"), rows.filter { it.startsWith("/") && " " !in it })
        compose.onNodeWithText("/simplify").performClick()
        val field = compose.onNode(hasSetTextAction())
        field.assertTextEquals("/simplify fix it")
        assertEquals(TextRange(10), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        runBlocking { withTimeout(5_000) { Desktop.conversation.first { it?.draft == "/simplify fix it" } } }
    }

    @Test
    fun aDescriptionIsPlainText() {
        compose.setContent { English { SkillsSheet(Skills.Known(listOf(Skill("docs", described))), {}, {}) } }
        val node = compose.onNodeWithText(described).fetchSemanticsNode()
        val annotated = node.config[SemanticsProperties.Text]
        assertFalse(annotated.any { it.getLinkAnnotations(0, it.length).isNotEmpty() || it.getStringAnnotations(0, it.length).isNotEmpty() })
    }

    @Test
    fun aListNotKnownIsNotShownAsEmpty() {
        compose.setContent { English { SkillsSheet(Skills.Unknown, {}, {}) } }
        compose.onNodeWithText("first message", substring = true).assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("No skills", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Search skills").fetchSemanticsNodes().isEmpty())
    }
}
