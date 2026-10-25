package com.spekterm.android.desktop

import com.spekterm.android.net.AgentUsage
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Operations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ModelEffort` (`session-model-effort` D3, D5, D6). */
class ModelEffortTest {
    private fun usage(model: String? = null, effort: String? = null) = AgentUsage("s1", model = model, effort = effort)
    private fun watched(usage: AgentUsage? = null, entries: List<Entry> = emptyList()) =
        Conversation("s1", entries = entries, watch = Watch.Watching, usage = usage)
    private fun pending(
        s: Switch,
        before: String?,
        state: PendingSwitch.State = PendingSwitch.State.Submitted,
        held: Set<String> = emptySet(),
    ) = PendingSwitch(1, s, before, sentAt = 0, held = held, state = state)

    @Test
    fun theCommandLine() {
        assertEquals("/effort low", ModelEffort.command(Switch.Effort("low")))
        assertEquals("/model opus[1m]", ModelEffort.command(Switch.Model("opus[1m]")))
        assertEquals("/model sonnet", ModelEffort.command(Switch.Model(" sonnet ")))
        assertEquals("sonnet", Switch.Model(" sonnet ").name)
        assertEquals(Switch.Model("sonnet"), Switch.Model(" sonnet "))
    }

    @Test
    fun theDesktopsCharset() {
        for (ok in listOf("opus", "opus[1m]", "claude-opus-4-8", "claude-opus-5@20260101", "arn:aws:bedrock:us-east-1:1:x/y", " sonnet ", "a".repeat(100))) {
            assertTrue(ok, ModelEffort.validName(ok))
        }
        for (bad in listOf("", "  ", "opus 1m", "opus　x", "-x", "opus+1", "a'b", "模型", "a".repeat(101))) {
            assertFalse(bad, ModelEffort.validName(bad))
        }
    }

    @Test
    fun theCurrentEffort() {
        assertEquals("high", ModelEffort.currentEffort(usage(effort = "High")))
        assertNull(ModelEffort.currentEffort(usage(effort = "turbo")))
        assertNull(ModelEffort.currentEffort(usage()))
        assertNull(ModelEffort.currentEffort(null))
    }

    @Test
    fun theCurrentModelIsAHint() {
        assertEquals("opus", ModelEffort.currentModel(usage(model = "Opus 5.5")))
        assertEquals("sonnet", ModelEffort.currentModel(usage(model = "Sonnet 5")))
        assertNull(ModelEffort.currentModel(usage(model = "Opus 5.5 (1M context)")))
        assertNull(ModelEffort.currentModel(usage(model = "Claude Custom")))
        assertNull(ModelEffort.currentModel(usage(model = "Default 1")))
        assertNull(ModelEffort.currentModel(null))
    }

    @Test
    fun offeredWithAUsageEventOfAnActiveWatch() {
        assertFalse(ModelEffort.available(watched()))
        assertFalse(ModelEffort.available(Conversation("s1", usage = AgentUsage("s1"))))
        assertTrue(ModelEffort.available(watched(AgentUsage("s1"))))
        assertTrue(ModelEffort.available(Conversation("s1", watch = Watch.Requested, usage = AgentUsage("s1"))))
        assertTrue(ModelEffort.modelsOffered(setOf(Operations.SET_MODEL)))
        assertFalse(ModelEffort.modelsOffered(setOf(Operations.ANSWER)))
    }

    @Test
    fun aTypedCommandWithOneArgument() {
        assertEquals(Switch.Model("sonnet"), ModelEffort.typed("  /model   sonnet ", 0))
        assertEquals(Switch.Effort("low"), ModelEffort.typed("/effort low", 0))
        assertNull(ModelEffort.typed("/model sonnet", 1))
        assertNull(ModelEffort.typed("/model", 0))
        assertNull(ModelEffort.typed("/model a b", 0))
        assertNull(ModelEffort.typed("/model sonnet\nnow", 0))
        assertNull(ModelEffort.typed("/models x", 0))
        assertNull(ModelEffort.typed("/Model sonnet", 0))
        assertNull(ModelEffort.typed("model sonnet", 0))
    }

    @Test
    fun anEffortIsConfirmedByTheReportedLevel() {
        val low = pending(Switch.Effort("low"), before = "high")
        assertTrue(ModelEffort.confirmed(low, watched(usage(effort = "low"))))
        assertTrue(ModelEffort.confirmed(low, watched(usage(effort = "LOW"))))
        assertFalse(ModelEffort.confirmed(low, watched(usage(effort = "medium"))))
        assertFalse(ModelEffort.confirmed(low, watched(null)))
        assertFalse(ModelEffort.confirmed(low, watched(usage(model = "Opus 5.5"))))
    }

    @Test
    fun autoIsConfirmedByAChangeFromAKnownLevel() {
        assertTrue(ModelEffort.confirmed(pending(Switch.Effort("auto"), before = "high"), watched(usage(effort = "medium"))))
        assertFalse(ModelEffort.confirmed(pending(Switch.Effort("auto"), before = "medium"), watched(usage(effort = "medium"))))
        assertFalse(ModelEffort.confirmed(pending(Switch.Effort("auto"), before = null), watched(usage(effort = "medium"))))
    }

    @Test
    fun theMarkedEffortAtOnceOnceWritten() {
        assertTrue(ModelEffort.confirmed(pending(Switch.Effort("high"), before = "High"), watched(null)))
        assertFalse(ModelEffort.confirmed(pending(Switch.Effort("high"), before = "High", state = PendingSwitch.State.Sending), watched(null)))
        assertFalse(ModelEffort.confirmed(pending(Switch.Effort("high"), before = "low"), watched(null)))
    }

    @Test
    fun anAliasIsConfirmedByAChangeWithinItsFamily() {
        val opus = pending(Switch.Model("opus"), before = "Opus 4.8")
        assertTrue(ModelEffort.confirmed(opus, watched(usage(model = "Opus 5.5"))))
        assertFalse(ModelEffort.confirmed(opus, watched(usage(model = "Opus 4.8"))))
        assertFalse(ModelEffort.confirmed(pending(Switch.Model("opus"), before = "Opus 4.8"), watched(usage(model = "Sonnet 5.5"))))
        assertFalse(ModelEffort.confirmed(pending(Switch.Model("haiku"), before = "Opus 5.5"), watched(usage(model = "Sonnet 5.5"))))
    }

    @Test
    fun defaultAndATypedNameByAnyChange() {
        assertTrue(ModelEffort.confirmed(pending(Switch.Model("default"), before = "Opus 5.5"), watched(usage(model = "Sonnet 5.5"))))
        assertTrue(ModelEffort.confirmed(pending(Switch.Model("claude-opus-4-8"), before = "Opus 5.5"), watched(usage(model = "Opus 4.8"))))
        assertFalse(ModelEffort.confirmed(pending(Switch.Model("default"), before = "Opus 5.5"), watched(usage(model = "Opus 5.5"))))
    }

    @Test
    fun aModelIsNeverConfirmedAtOnce() {
        // Marked: the report reads its family, and the desktop answered that it wrote it.
        assertFalse(ModelEffort.confirmed(pending(Switch.Model("opus"), before = "Opus 4.8"), watched(usage(model = "Opus 4.8"))))
        assertFalse(ModelEffort.confirmed(pending(Switch.Model("sonnet"), before = null), watched(usage(model = "Sonnet 5.5"))))
        assertFalse(ModelEffort.confirmed(pending(Switch.Model("sonnet"), before = "Opus 5.5"), watched(null)))
        assertFalse(ModelEffort.confirmed(pending(Switch.Model("sonnet"), before = "Opus 5.5"), watched(usage(effort = "high"))))
    }

    @Test
    fun eitherByItsLineArrivingAfterTheSend() {
        val line = Entry.User("u2", 0, "/model sonnet", meta = false)
        val sonnet = pending(Switch.Model("sonnet"), before = null, held = setOf("u1"))
        assertTrue(ModelEffort.confirmed(sonnet, watched(entries = listOf(line))))
        assertFalse(ModelEffort.confirmed(sonnet.copy(held = setOf("u2")), watched(entries = listOf(line))))
        assertFalse(ModelEffort.confirmed(sonnet, watched(entries = listOf(line.copy(meta = true)))))
        val low = pending(Switch.Effort("low"), before = null)
        assertTrue(ModelEffort.confirmed(low, watched(entries = listOf(Entry.User("u3", 0, "/effort low", meta = false)))))
    }

    @Test
    fun aSettledSwitchIsNotConfirmed() {
        val refused = pending(Switch.Effort("low"), before = "high", state = PendingSwitch.State.Refused("WAIT_STATE"))
        assertFalse(ModelEffort.confirmed(refused, watched(usage(effort = "low"))))
        assertFalse(ModelEffort.confirmed(refused.copy(state = PendingSwitch.State.NotSubmitted), watched(usage(effort = "low"))))
    }

    @Test
    fun onlyAnOpenModelSwitchHolds() {
        val model = pending(Switch.Model("sonnet"), before = null, state = PendingSwitch.State.Sending)
        assertTrue(model.holds)
        assertTrue(model.copy(state = PendingSwitch.State.Lost).holds)
        assertFalse(model.copy(anyway = true).holds)
        assertFalse(model.copy(state = PendingSwitch.State.Refused("WAIT_STATE")).holds)
        assertFalse(model.copy(state = PendingSwitch.State.NotSubmitted).holds)
        assertFalse(pending(Switch.Effort("low"), before = null, state = PendingSwitch.State.Sending).holds)
        assertFalse(model.late(9_999))
        assertTrue(model.late(10_000))
    }
}
