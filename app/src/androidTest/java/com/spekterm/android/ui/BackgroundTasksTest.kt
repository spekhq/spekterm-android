package com.spekterm.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.net.BackgroundTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The conversation screen's background work (`background-tasks`, "Show background work on the conversation screen"). */
@RunWith(AndroidJUnit4::class)
class BackgroundTasksTest {
    @get:Rule val compose = createComposeRule()

    private val url = "live updates for artifact https://claude.ai/artifact/abc"
    private val tasks = listOf(
        BackgroundTask("a", BackgroundTask.Kind.AGENT, "Probe agent"),
        BackgroundTask("s", BackgroundTask.Kind.SHELL, "npm run dev -- --port 5173"),
        BackgroundTask("m", BackgroundTask.Kind.MONITOR, null),
        BackgroundTask("u", BackgroundTask.Kind.MONITOR, url),
    )

    /** Laid out as the screen is: the item, the entries' weight, a prompt card, the composer, in a 500 dp column. */
    private fun show(tasks: List<BackgroundTask>, open: Boolean = false) {
        compose.setContent {
            var expanded by remember { mutableStateOf(open) }
            Column(Modifier.fillMaxWidth().height(500.dp)) {
                BackgroundTasks(tasks, expanded, { expanded = !expanded }, 125.dp)
                Box(Modifier.fillMaxWidth().weight(1f).testTag("entries"))
                Box(Modifier.fillMaxWidth().height(160.dp).testTag("card"))
                Box(Modifier.fillMaxWidth().height(56.dp).testTag("composer"))
            }
        }
    }

    @Test
    fun collapsedAtFirstThenListsEachTaskInOrder() {
        show(tasks)
        compose.onNodeWithText("4 running in the background").assertIsDisplayed()
        compose.onNodeWithText("Probe agent").assertDoesNotExist()
        compose.onNodeWithText("4 running in the background").performClick()
        val tops = listOf("Probe agent", "npm run dev -- --port 5173").map { compose.onNodeWithText(it).getBoundsInRoot().top }
        assertTrue(tops[0] < tops[1])
        compose.onNodeWithText("Subagent").assertIsDisplayed()
        compose.onNodeWithText("Shell").assertIsDisplayed()
        compose.onNodeWithText("As the agent last reported.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aTaskWithoutALabelShowsItsKindAlone() {
        show(tasks.filter { it.id == "m" }, open = true)
        compose.onNodeWithText("Monitor").assertIsDisplayed()
        compose.onNodeWithTag(BACKGROUND_LIST_TAG).assertIsDisplayed()
    }

    @Test
    fun aLabelWithAUrlIsPlainText() {
        show(tasks.filter { it.id == "u" }, open = true)
        val node = compose.onNodeWithText(url)
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
        node.performClick()
        // Nothing opened and the item stayed as it was.
        compose.onNodeWithText(url).assertIsDisplayed()
    }

    @Test
    fun manyTasksScrollAndLeaveTheCardAndTheComposerTheirPlace() {
        show(List(20) { BackgroundTask("t$it", BackgroundTask.Kind.SHELL, "task $it") }, open = true)
        val list = compose.onNodeWithTag(BACKGROUND_LIST_TAG).getBoundsInRoot()
        assertTrue("list ${list.height}", list.height <= 125.dp + 0.5.dp)
        assertEquals(160.dp.value, compose.onNodeWithTag("card").assertIsDisplayed().getBoundsInRoot().height.value, 0.5f)
        assertEquals(56.dp.value, compose.onNodeWithTag("composer").assertIsDisplayed().getBoundsInRoot().height.value, 0.5f)
        assertTrue(compose.onNodeWithTag("entries").getBoundsInRoot().height > 0.dp)
        compose.onNodeWithText("task 19").performScrollTo().assertIsDisplayed()
    }
}
