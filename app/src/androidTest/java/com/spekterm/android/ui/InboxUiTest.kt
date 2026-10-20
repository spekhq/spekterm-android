package com.spekterm.android.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spekterm.android.desktop.InboxView
import com.spekterm.android.net.InboxItem
import com.spekterm.android.net.ItemRef
import com.spekterm.android.net.Folder
import com.spekterm.android.net.Prefill
import com.spekterm.android.net.SessionList
import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The inbox's entry, the opened card and the conversation note (`inbox` tasks 2.3, 2.5), driven with parameters. */
@RunWith(AndroidJUnit4::class)
class InboxUiTest {
    @get:Rule val compose = createComposeRule()

    private val item = InboxItem("m1", "slack", InboxItem.State.ACCEPTED, originLabel = "#dev", body = "Please look at the deploy", bodyLength = 25, receivedAt = 1, sessionId = "s1")
    private val prefill = Prefill("p1", "Read the context file /tmp/ctx.md", ItemRef("slack", "m1"), edited = false)

    // ---- 2.3 the entry ----

    @Test
    fun theEntryShowsTheWaitingCount() {
        compose.setContent { InboxButton(2, shown = true) {} }
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Inbox, 2 waiting").assertIsDisplayed()
    }

    @Test
    fun noNumberWhenNothingWaitsAndNothingWhenNotShown() {
        compose.setContent { InboxButton(0, shown = true) {} }
        compose.onNodeWithContentDescription("Inbox").assertIsDisplayed()
        compose.onAllNodesWithText("0").assertCountEquals(0)
    }

    @Test
    fun noEntryWithoutAnInbox() {
        compose.setContent { InboxButton(3, shown = false) {} }
        compose.onAllNodesWithText("3").assertCountEquals(0)
    }

    // ---- 2.5 the opened card ----

    private fun card(prefill: Prefill?, running: Boolean = true, canSend: Boolean = true) {
        compose.setContent { OpenedCard(item, "spekterm", prefill, running, canSend, null, enabled = true, onOpen = {}, onSend = {}) }
    }

    @Test
    fun aListedPromptIsShownWithTheBodyAndTheSend() {
        card(prefill)
        compose.onNodeWithText("Please look at the deploy").assertIsDisplayed()
        compose.onNodeWithText("Read the context file /tmp/ctx.md").assertIsDisplayed()
        compose.onNodeWithText("Send the first prompt").assertIsDisplayed()
    }

    @Test
    fun anEditedPromptHasNoSend() {
        card(prefill.copy(edited = true))
        compose.onNodeWithText("Something else was typed there too — finish it at the desktop.").assertIsDisplayed()
        compose.onAllNodesWithText("Send the first prompt").assertCountEquals(0)
    }

    @Test
    fun withoutAPromptTheCardSaysWhereItIs() {
        card(null, canSend = true)
        compose.onNodeWithText("The desktop is filling in the first prompt.").assertIsDisplayed()
    }

    @Test
    fun anOlderDesktopSendsOnTheDesktop() {
        card(null, canSend = false)
        compose.onNodeWithText("Its first prompt waits on the desktop — send it there.").assertIsDisplayed()
    }

    @Test
    fun aDormantSessionSaysNothingAboutAPrompt() {
        card(null, running = false)
        compose.onAllNodesWithText("The desktop is filling in the first prompt.").assertCountEquals(0)
        compose.onAllNodesWithText("Its first prompt waits on the desktop — send it there.").assertCountEquals(0)
    }

    // ---- 2.5 the conversation note ----

    @Test
    fun theNoteInItsFourForms() {
        var opened: String? = null
        var anyway = false
        var linked by mutableStateOf<InboxView.Linked>(InboxView.Linked.Listed(prefill, item, shown = true))
        compose.setContent { InboxNote(linked, false, { opened = it }) { anyway = true } }
        compose.onNodeWithText("Review and send").performClick()
        assertEquals("slack/m1", opened)

        linked = InboxView.Linked.Listed(prefill.copy(edited = true), item, shown = true)
        compose.onNodeWithText("The first prompt from the inbox is not sent yet — finish it at the desktop.").assertIsDisplayed()
        compose.onNodeWithText("Open in inbox").assertIsDisplayed()

        linked = InboxView.Linked.Waiting(item, canSend = true)
        compose.onNodeWithText("The desktop is filling in the first prompt from the inbox.").assertIsDisplayed()
        compose.onAllNodesWithText("Reply anyway").assertCountEquals(0)

        linked = InboxView.Linked.Waiting(item, canSend = false)
        compose.onNodeWithText("Reply anyway").performClick()
        assertEquals(true, anyway)
    }

    // ---- 2.5 returning to the inbox ----

    @Serializable private data object Home

    @Serializable private data object Talk

    @Test
    fun openingTheInboxFromAConversationReturnsToTheOneBehindItWithTheItem() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Home) {
                composable<Home> { Text("home") }
                composable<InboxRoute> { entry ->
                    val focus by entry.savedStateHandle.getStateFlow("focus", entry.toRoute<InboxRoute>().focus).collectAsStateWithLifecycle()
                    Text("inbox:${focus ?: "-"}")
                }
                composable<Talk> { Text("talk") }
            }
        }
        compose.runOnIdle {
            nav.navigate(InboxRoute())
            nav.navigate(Talk)
        }
        compose.runOnIdle { nav.openInbox("slack/m1") }
        compose.onNodeWithText("inbox:slack/m1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, nav.currentBackStack.value.count { it.destination.route?.contains("InboxRoute") == true })
        }
    }

    // ---- recurring-handoff 2.2: proposals and occurrences ----

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private val gates = InboxView.Gates(accept = true, ignore = true, send = true)
    private val folders = SessionList(true, listOf(Folder("f1", "spekterm")), emptyList())
    private val pending = InboxItem("r1", "handoff", InboxItem.State.PENDING, originLabel = "spekterm", body = "Weekly report", bodyLength = 13, receivedAt = 1, folderId = "f1")

    private fun at(time: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(time))

    private fun proposal(repeat: String = "0 21 * * 3", until: Long? = null, next: List<Long>) {
        val item = pending.copy(recurrence = InboxItem.Recurrence(InboxItem.Recurrence.Role.PROPOSAL, repeat, until, next))
        compose.setContent { ProposalCard(item, folders, null, gates, {}, {}, now = now) }
    }

    private fun noAccept() {
        compose.onAllNodesWithText("Accept").assertCountEquals(0)
        compose.onAllNodesWithText("Run now").assertCountEquals(0)
    }

    @Test
    fun aProposalShowsItsScheduleFutureTimesBodyAndOnlyDecline() {
        proposal(next = listOf(now - 3_600_000, now + day, now + 8 * day))
        compose.onNodeWithText("Repeats 0 21 * * 3 (desktop time) · until stopped").assertIsDisplayed()
        compose.onNodeWithText("Next: ${at(now + day)} · ${at(now + 8 * day)}").assertIsDisplayed()
        compose.onAllNodesWithText(at(now - 3_600_000), substring = true).assertCountEquals(0)
        compose.onNodeWithText("Weekly report").assertIsDisplayed()
        compose.onNodeWithText("Approve it on the desktop.").assertIsDisplayed()
        compose.onNodeWithText("Decline").assertIsDisplayed()
        noAccept()
    }

    @Test
    fun aProposalWithAnEndShowsIt() {
        proposal(until = now + 14 * day, next = listOf(now + day))
        compose.onNodeWithText("Repeats 0 21 * * 3 (desktop time) · until ${at(now + 14 * day)}").assertIsDisplayed()
    }

    @Test
    fun staleTimesSendToTheDesktop() {
        proposal(next = listOf(now - day))
        compose.onNodeWithText("The next times are shown on the desktop. Approve it there.").assertIsDisplayed()
        compose.onAllNodesWithText("Next:", substring = true).assertCountEquals(0)
    }

    @Test
    fun aProposalPastItsEndCannotBeApproved() {
        proposal(until = now - day, next = listOf(now + day))
        compose.onNodeWithText("Its end time has passed, so it can no longer be approved.").assertIsDisplayed()
        compose.onAllNodesWithText("Next:", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Decline").assertIsDisplayed()
    }

    @Test
    fun noTimeBeforeTheEndCannotBeApprovedEither() {
        proposal(until = now + day, next = emptyList())
        compose.onNodeWithText("Its end time has passed, so it can no longer be approved.").assertIsDisplayed()
        compose.onAllNodesWithText("Next:", substring = true).assertCountEquals(0)
    }

    @Test
    fun aProposalWithoutItsExpressionStillOffersNoAccept() {
        proposal(repeat = "", next = listOf(now + day))
        compose.onAllNodesWithText("Repeats", substring = true).assertCountEquals(0)
        compose.onNodeWithText("until stopped").assertIsDisplayed()
        noAccept()
    }

    private fun scheduled(item: InboxItem) {
        compose.setContent { ScheduledCard(item, folders, null, gates, {}, {}, {}) }
    }

    @Test
    fun aScheduledOccurrenceShowsItsScheduleAndSkips() {
        scheduled(pending.copy(scheduledAt = now + day, recurrence = InboxItem.Recurrence(InboxItem.Recurrence.Role.OCCURRENCE, "0 21 * * 3")))
        compose.onNodeWithText("Repeats 0 21 * * 3 (desktop time)").assertIsDisplayed()
        compose.onNodeWithText("Skip this time").assertIsDisplayed()
        compose.onAllNodesWithText("Cancel").assertCountEquals(0)
    }

    @Test
    fun aOneOffScheduledItemStillCancels() {
        scheduled(pending.copy(scheduledAt = now + day))
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onAllNodesWithText("Skip this time").assertCountEquals(0)
        compose.onAllNodesWithText("Repeats", substring = true).assertCountEquals(0)
    }

    private fun waiting(item: InboxItem) {
        compose.setContent { WaitingCard(item, folders, InboxView.Preselection("f1"), null, gates, {}, {}, {}, {}) }
    }

    @Test
    fun aFailedOccurrenceIsAnOrdinaryItemMarkedAsRepeating() {
        waiting(pending.copy(recurrence = InboxItem.Recurrence(InboxItem.Recurrence.Role.OCCURRENCE, "0 21 * * 3")))
        compose.onNodeWithText("Part of a repeating handoff · Repeats 0 21 * * 3 (desktop time)").assertIsDisplayed()
        compose.onNodeWithText("Accept").assertIsDisplayed()
        compose.onNodeWithText("Skip this time").assertIsDisplayed()
        compose.onAllNodesWithText("Dismiss").assertCountEquals(0)
    }

    @Test
    fun anOccurrenceWithoutItsExpressionShowsNoEmptySchedule() {
        val occurrence = InboxItem.Recurrence(InboxItem.Recurrence.Role.OCCURRENCE, "")
        waiting(pending.copy(recurrence = occurrence))
        compose.onNodeWithText("Part of a repeating handoff").assertIsDisplayed()
        compose.onAllNodesWithText("Repeats", substring = true).assertCountEquals(0)
    }

    @Test
    fun aScheduledOccurrenceWithoutItsExpressionShowsNoEmptySchedule() {
        scheduled(pending.copy(scheduledAt = now + day, recurrence = InboxItem.Recurrence(InboxItem.Recurrence.Role.OCCURRENCE, "")))
        compose.onAllNodesWithText("Repeats", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Skip this time").assertIsDisplayed()
    }
}
