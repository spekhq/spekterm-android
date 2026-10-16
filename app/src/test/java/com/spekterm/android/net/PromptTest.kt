package com.spekterm.android.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Prompt.decode` and `ConversationWait.decode` (design D2). */
class PromptTest {
    private fun prompt(json: String): Prompt? = Prompt.decode(Json.parseToJsonElement(json))

    private fun wait(json: String): ConversationWait? = ConversationWait.decode(Json.parseToJsonElement(json))

    @Test
    fun decodesAPermission() {
        assertEquals(
            Prompt.Permission("p1", "Bash", "npm test", canAllow = true, answerable = true, open = 1),
            prompt("""{"id":"p1","kind":"permission","tool":"Bash","arg":"npm test","canAllow":true,"answerable":true,"open":1,"later":1}"""),
        )
    }

    @Test
    fun decodesAQuestion() {
        val q = prompt(
            """{"id":"p2","kind":"question","answerable":true,"open":1,"questions":[
              {"question":"Which?","header":"Pick","multiSelect":false,"options":[{"label":"A","description":"first"},{"label":"B"}]},
              {"question":"And?","header":"More","multiSelect":true,"options":[{"label":"C","description":"c"},{"description":"no label"},{"label":"D","description":"d"}]}]}""",
        ) as Prompt.Question
        assertEquals(2, q.questions.size)
        assertEquals(Prompt.Question.Option("B", ""), q.questions[0].options[1])
        assertFalse(q.questions[0].multiSelect)
        assertTrue(q.questions[1].multiSelect)
        // An option without a label is skipped, element by element.
        assertEquals(listOf("C", "D"), q.questions[1].options.map { it.label })
        assertTrue(q.answerable)
    }

    @Test
    fun decodesAPlanAndDropsUnknownChoices() {
        assertEquals(
            Prompt.Plan("p3", "Do it", listOf("approve", "approve-auto", "deny"), answerable = true, open = 1),
            prompt("""{"id":"p3","kind":"plan","plan":"Do it","choices":["approve","approve-auto","later","deny"],"answerable":true,"open":1}"""),
        )
        assertEquals(Prompt.Plan("p4", null, listOf("approve", "deny"), answerable = false, open = 1), prompt("""{"id":"p4","kind":"plan","plan":null,"choices":["approve","deny"]}"""))
    }

    @Test
    fun malformedOrUnknownIsNull() {
        assertNull(prompt("""{"id":"p1","kind":"later","answerable":true}"""))
        assertNull(prompt("""{"kind":"permission","tool":"Bash"}"""))
        assertNull(prompt("""{"id":"","kind":"permission","tool":"Bash"}"""))
        assertNull(prompt("""{"id":5,"kind":"permission","tool":"Bash"}"""))
        assertNull(prompt("null"))
    }

    @Test
    fun missingBooleansAreFalseAndOpenDefaultsToOne() {
        val p = prompt("""{"id":"p1","kind":"permission","tool":"Read"}""") as Prompt.Permission
        assertFalse(p.answerable)
        assertFalse(p.canAllow)
        assertEquals(1, p.open)
        assertEquals(2, prompt("""{"id":"p1","kind":"permission","tool":"Read","open":2}""")!!.open)
    }

    @Test
    fun thePhonesGuardMarksLargeQuestionsNotAnswerable() {
        val option = """{"label":"x","description":""}"""
        fun question(options: Int) = """{"question":"q","header":"h","multiSelect":false,"options":[${List(options) { option }.joinToString(",")}]}"""
        val five = prompt("""{"id":"p","kind":"question","answerable":true,"questions":[${List(5) { question(2) }.joinToString(",")}]}""") as Prompt.Question
        assertEquals(5, five.questions.size)
        assertFalse(five.fits)
        assertFalse(five.answerable)
        val nine = prompt("""{"id":"p","kind":"question","answerable":true,"questions":[${question(9)}]}""") as Prompt.Question
        assertFalse(nine.answerable)
        val none = prompt("""{"id":"p","kind":"question","answerable":true,"questions":[]}""") as Prompt.Question
        assertFalse(none.answerable)
        val noOptions = prompt("""{"id":"p","kind":"question","answerable":true,"questions":[${question(0)}]}""") as Prompt.Question
        assertFalse(noOptions.answerable)
        val ok = prompt("""{"id":"p","kind":"question","answerable":true,"questions":[${List(4) { question(8) }.joinToString(",")}]}""") as Prompt.Question
        assertTrue(ok.answerable)
    }

    @Test
    fun aWaitEventWithAMalformedPromptKeepsItsState() {
        val w = wait("""{"sessionId":"s1","state":"awaiting-choice","pending":{"tool":"Bash","arg":"ls"},"prompt":{"kind":"permission"}}""")!!
        assertEquals("awaiting-choice", w.state)
        assertEquals(Pending("Bash", "ls"), w.pending)
        assertNull(w.prompt)
        assertNull(wait("""{"sessionId":"s1","state":"ready","pending":null}""")!!.prompt)
        assertNull(wait("""{"sessionId":"s1","state":"ready","pending":null,"prompt":null}""")!!.prompt)
        assertEquals("p1", wait("""{"sessionId":"s1","state":"awaiting-choice","pending":null,"prompt":{"id":"p1","kind":"permission","tool":"Bash"}}""")!!.prompt!!.id)
        assertNull(wait("""{"state":"ready"}"""))
    }
}
