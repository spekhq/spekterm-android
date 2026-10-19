package com.spekterm.android.desktop

import com.spekterm.android.net.Operations
import com.spekterm.android.net.Prompt
import com.spekterm.android.net.RemoteError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `Answers` (design D3) and `AnswerOutcome` (design D4). */
class AnswersTest {
    private val ops = setOf(Operations.ANSWER)
    private fun permission(canAllow: Boolean = true, answerable: Boolean = true, open: Int = 1, id: String = "p1") =
        Prompt.Permission(id, "Bash", "ls", canAllow, answerable, open)
    private fun item(multi: Boolean, options: Int = 3) = Prompt.Question.Item("q", "h", multi, List(options) { Prompt.Question.Option("o$it", "") })
    private val question = Prompt.Question("p2", listOf(item(false), item(true)), desktopAnswerable = true, open = 1)
    private fun plan(choices: List<String>) = Prompt.Plan("p3", "plan", choices, answerable = true, open = 1)

    private fun offer(
        prompt: Prompt = permission(),
        operations: Set<String> = ops,
        connected: Boolean = true,
        paused: Boolean = false,
        watch: Watch = Watch.Watching,
        answer: AnswerState? = null,
    ) = Answers.offer(prompt, operations, connected, paused, watch, answer)

    @Test
    fun decisionsPerKind() {
        assertEquals(Offer.Answerable(listOf("allow", "deny")), offer(permission(canAllow = true)))
        assertEquals(Offer.Answerable(listOf("deny")), offer(permission(canAllow = false)))
        assertEquals(Offer.Answerable(listOf("answer", "deny")), offer(question))
        assertEquals(Offer.Answerable(listOf("approve", "approve-auto", "deny")), offer(plan(listOf("approve", "approve-auto", "deny"))))
        assertEquals(Offer.Answerable(listOf("approve", "deny")), offer(plan(listOf("approve", "deny"))))
    }

    @Test
    fun readOnlyForEachGate() {
        assertEquals(Offer.ReadOnly(Offer.Reason.NoOperation), offer(operations = emptySet()))
        assertEquals(Offer.ReadOnly(Offer.Reason.NotConnected), offer(connected = false))
        assertEquals(Offer.ReadOnly(Offer.Reason.Paused), offer(paused = true))
        assertEquals(Offer.ReadOnly(Offer.Reason.NotWatching), offer(watch = Watch.Requested))
        assertEquals(Offer.ReadOnly(Offer.Reason.Open(2)), offer(permission(answerable = false, open = 2)))
        assertEquals(Offer.ReadOnly(Offer.Reason.NotAnswerable), offer(permission(answerable = false, open = 1)))
        val big = Prompt.Question("p", List(5) { item(false) }, desktopAnswerable = true, open = 1)
        assertEquals(Offer.ReadOnly(Offer.Reason.TooLarge), offer(big))
        assertEquals(Offer.ReadOnly(Offer.Reason.Answering), offer(answer = AnswerState("p1", "allow")))
        assertEquals(Offer.ReadOnly(Offer.Reason.Answered), offer(answer = AnswerState("p1", "allow", AnswerOutcome.Done)))
    }

    @Test
    fun anAnswerToAnotherPromptDoesNotBlock() {
        assertEquals(Offer.Answerable(listOf("allow", "deny")), offer(permission(id = "p9"), answer = AnswerState("p1", "allow")))
        // A refused answer to this prompt can be tried again by the user.
        assertEquals(Offer.Answerable(listOf("allow", "deny")), offer(answer = AnswerState("p1", "allow", AnswerOutcome.Refused(RemoteError.NOT_ANSWERABLE))))
    }

    @Test
    fun selections() {
        assertNull(Answers.selections(question, mapOf(1 to setOf(0))))
        assertNull(Answers.selections(question, mapOf(0 to setOf(0, 1), 1 to setOf(0))))
        assertNull(Answers.selections(question, mapOf(0 to setOf(0), 1 to emptySet())))
        assertEquals(listOf(listOf(1), listOf(0, 2)), Answers.selections(question, mapOf(0 to setOf(1), 1 to setOf(2, 0))))
        assertNull(Answers.selections(question, mapOf(0 to setOf(7), 1 to setOf(0))))
        val big = Prompt.Question("p", List(5) { item(false) }, desktopAnswerable = true, open = 1)
        assertNull(Answers.selections(big, (0 until 5).associateWith { setOf(0) }))
    }

    @Test
    fun outcomes() {
        assertEquals(AnswerOutcome.Done, AnswerOutcome.of("sent", null))
        assertEquals(AnswerOutcome.Done, AnswerOutcome.of("confirmed", null))
        assertEquals(AnswerOutcome.Partial, AnswerOutcome.of("partial", null))
        assertEquals(AnswerOutcome.Mismatch, AnswerOutcome.of("mismatch", null))
        assertEquals(AnswerOutcome.Gone, AnswerOutcome.of(null, RemoteError.PROMPT_GONE))
        for (code in listOf(RemoteError.NOT_ANSWERABLE, RemoteError.BAD_PARAMS, RemoteError.UNKNOWN_OPERATION, RemoteError.NOT_WATCHING, RemoteError.NOT_FOUND)) {
            assertEquals(AnswerOutcome.Refused(code), AnswerOutcome.of(null, code))
        }
        assertEquals(AnswerOutcome.Unknown, AnswerOutcome.of(null, RemoteError.TIMEOUT))
        assertEquals(AnswerOutcome.Unknown, AnswerOutcome.of(null, null))
        assertEquals(AnswerOutcome.Unknown, AnswerOutcome.of("later", null))
    }

    /** The log names each outcome by a fixed name, the same in a shrunk build (`play-release` D3). */
    @Test
    fun outcomesHaveTheirLogNames() {
        assertEquals("Done", AnswerOutcome.Done.logName)
        assertEquals("Partial", AnswerOutcome.Partial.logName)
        assertEquals("Mismatch", AnswerOutcome.Mismatch.logName)
        assertEquals("Gone", AnswerOutcome.Gone.logName)
        assertEquals("Refused", AnswerOutcome.Refused(RemoteError.WAIT_STATE).logName)
        assertEquals("Unknown", AnswerOutcome.Unknown.logName)
    }
}
