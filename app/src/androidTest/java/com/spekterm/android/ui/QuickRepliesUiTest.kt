package com.spekterm.android.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.desktop.Conversation
import com.spekterm.android.desktop.Desktop
import com.spekterm.android.desktop.Skills
import com.spekterm.android.desktop.Watch
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.Skill
import com.spekterm.android.net.WaitState
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

/** The chips above the reply field and their editor in Settings (`quick-replies` D3, D4). */
@RunWith(AndroidJUnit4::class)
class QuickRepliesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val close = "Can this session be closed now?"
    private val risk = "Is there any risk, or anything I need to decide?"
    private val remaining = "Is there anything left to do in this session?"
    private val sentences = listOf(close, risk)

    @Before fun reset() = runBlocking {
        Desktop.resetForTest()
        Desktop.focus("o1", "s1")
        withTimeout(5_000) { Desktop.conversation.first { it?.sessionId == "s1" } }
        Unit
    }

    private val pickers = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) = Unit
        }
    }

    private val tapped = mutableListOf<String>()

    private fun show(
        c: Conversation,
        list: List<String> = sentences,
        room: Boolean = true,
        answerable: Boolean = false,
        inboxPrompt: Boolean = false,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickers) {
                English {
                    Composer(
                        "s1", c, connected = true, paused = false, answerable = answerable, canAttach = false,
                        inboxPrompt = inboxPrompt, quickReplies = list, roomForQuick = room, onQuick = { tapped += it },
                    )
                }
            }
        }
    }

    private fun watched(wait: String = WaitState.READY, skills: Skills? = null) =
        Conversation("s1", watch = Watch.Watching, wait = wait, skills = skills)

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aTapSendsItsSentence() {
        show(watched())
        compose.onNodeWithText(close).assertIsEnabled()
        compose.onNodeWithText(risk).performClick()
        assertEquals(listOf(risk), tapped)
    }

    @Test
    fun hiddenForAnEmptyListAndBelowTheThreshold() {
        var list by mutableStateOf(emptyList<String>())
        var room by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickers) {
                English { Composer("s1", watched(), true, false, false, false, quickReplies = list, roomForQuick = room, onQuick = {}) }
            }
        }
        assertFalse(shown(close))
        list = sentences
        compose.waitForIdle()
        assertTrue(shown(close))
        room = false
        compose.waitForIdle()
        assertFalse(shown(close))
    }

    @Test
    fun hiddenInCommandMode() {
        show(watched().copy(commandMode = true))
        assertFalse(shown(close))
    }

    @Test
    fun hiddenWhileTheSkillsShow() {
        show(watched(skills = Skills.Known(listOf(Skill("simplify", null)))))
        assertTrue(shown(close))
        compose.onNode(hasSetTextAction()).performTextInput("/")
        assertTrue(shown("/simplify"))
        assertFalse(shown(close))
    }

    @Test
    fun disabledWithThePromptCard() {
        show(watched(WaitState.AWAITING_CHOICE), answerable = true)
        compose.onNodeWithText(close).assertIsNotEnabled()
        compose.onNodeWithText(close).performClick()
        assertTrue(tapped.isEmpty())
        assertTrue(shown("Answer the choice above."))
    }

    @Test
    fun disabledWhileTheStateIsUnknownAndSaysWhy() {
        show(watched(WaitState.UNKNOWN))
        compose.onNodeWithText(close).assertIsNotEnabled()
        assertTrue(shown("spekterm can't tell whether the agent can take a message"))
    }

    @Test
    fun aRefusedQuickReplyIsSaidAlsoBesideTheInboxNote() {
        show(watched().copy(quickRefused = RemoteError.PREFILL_PENDING, draft = "half a reply"), inboxPrompt = true)
        assertTrue(shown("Quick reply not sent: a first prompt waits in the session."))
        compose.onNodeWithText(close).assertIsNotEnabled()
    }

    // ---- The editor (D4) ----

    private var stored by mutableStateOf<List<String>?>(null)

    private fun editor() {
        compose.setContent { English { QuickRepliesSection(stored, { stored = it }, { stored = null }) } }
    }

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    @Test
    fun addsASentenceAfterTheDefaults() {
        editor()
        assertTrue(shown(remaining))
        compose.onNodeWithText("Add").performClick()
        inDialog("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("  Go ahead if there is no risk ")
        inDialog("Save").performClick()
        assertEquals(listOf(close, risk, remaining, "Go ahead if there is no risk"), stored)
    }

    @Test
    fun refusesADuplicate() {
        editor()
        compose.onNodeWithText("Add").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(risk)
        inDialog("Save").assertIsNotEnabled()
        assertTrue(shown("Already in the list."))
        assertEquals(null, stored)
    }

    @Test
    fun reordersAndRemoves() {
        editor()
        compose.onNodeWithContentDescription("Move “$risk” up").performClick()
        assertEquals(listOf(risk, close, remaining), stored)
        compose.onNodeWithContentDescription("Move “$risk” down").performClick()
        assertEquals(listOf(close, risk, remaining), stored)
        compose.onNodeWithContentDescription("Remove “$close”").performClick()
        assertEquals(listOf(risk, remaining), stored)
    }

    @Test
    fun restoresTheDefaultsAfterConfirming() {
        stored = emptyList()
        editor()
        assertTrue(shown("No quick replies"))
        compose.onNodeWithText("Restore defaults").performClick()
        inDialog("Cancel").performClick()
        assertEquals(emptyList<String>(), stored)
        compose.onNodeWithText("Restore defaults").performClick()
        inDialog("Restore").performClick()
        assertEquals(null, stored)
        assertTrue(shown(close))
    }

    @Test
    fun addIsNotOfferedAtTwelve() {
        stored = List(12) { "q$it" }
        editor()
        assertFalse(compose.onAllNodesWithText("Add").fetchSemanticsNodes().isNotEmpty())
        assertTrue(shown("At most 12 quick replies."))
    }
}
