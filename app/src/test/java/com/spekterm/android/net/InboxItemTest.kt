package com.spekterm.android.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `inbox` task 1.1: the inbox list is decoded item by item, leniently (design D1). */
class InboxItemTest {
    private fun decode(text: String) = InboxItem.decodeList(Json.parseToJsonElement(text))

    private fun one(fields: String): InboxItem = decode("""{"items":[{"id":"i","adapter":"a"$fields}]}""")!!.single()

    @Test
    fun anAnswerThatIsNotAnItemsObjectIsNoList() {
        assertNull(decode("[]"))
        assertNull(decode("""{"other":[]}"""))
        assertNull(decode("""{"items":{}}"""))
        assertNull(decode("""{"items":"x"}"""))
        assertEquals(emptyList<InboxItem>(), decode("""{"items":[]}"""))
    }

    @Test
    fun malformedItemsAreSkippedAndTheRestKeptInOrder() {
        val items = decode(
            """{"items":[{"id":"1","adapter":"a"},{"adapter":"a"},{"id":"","adapter":"a"},{"id":3,"adapter":"a"},
               {"id":"4"},"x",null,{"id":"2","adapter":"b"}]}""",
        )!!
        assertEquals(listOf("a/1", "b/2"), items.map { it.key })
    }

    @Test
    fun aFullItemIsReadAsSent() {
        val item = one(
            ""","state":"pending","originKind":"slack","originId":"C1","originLabel":"#dev","title":"T","actor":"Ann",
               "body":"B","bodyLength":1,"receivedAt":10,"occurredAt":9,"sessionId":"s","folderId":"f",
               "unresolved":"NO_MATCH","scheduledAt":20,"extra":{"x":1}""",
        )
        assertEquals(
            InboxItem("i", "a", InboxItem.State.PENDING, "#dev", "T", "Ann", "B", 1, 10, 9, "s", "f", InboxItem.Unresolved.NO_MATCH, 20),
            item,
        )
    }

    @Test
    fun missingOrMistypedFieldsReadAsEmpty() {
        val item = one(""","title":5,"body":null,"bodyLength":"3","receivedAt":true,"sessionId":7,"folderId":{}""")
        assertEquals(InboxItem("i", "a", InboxItem.State.OTHER), item)
    }

    @Test
    fun statesAndReasonsAreMappedAndUnknownOnesAreOther() {
        assertEquals(InboxItem.State.PENDING, one(""","state":"pending"""").state)
        assertEquals(InboxItem.State.ACCEPTED, one(""","state":"accepted"""").state)
        assertEquals(InboxItem.State.OTHER, one(""","state":"dismissed"""").state)
        assertEquals(InboxItem.State.OTHER, one("").state)
        assertEquals(InboxItem.Unresolved.NO_MATCH, one(""","unresolved":"NO_MATCH"""").unresolved)
        assertEquals(InboxItem.Unresolved.FOLDER_GONE, one(""","unresolved":"FOLDER_GONE"""").unresolved)
        assertEquals(InboxItem.Unresolved.OTHER, one(""","unresolved":"X"""").unresolved)
        assertNull(one("").unresolved)
        assertNull(one(""","unresolved":null""").unresolved)
    }

    @Test
    fun scheduledAtIsKeptOnlyAsANumber() {
        assertEquals(1_700_000_000_000L, one(""","scheduledAt":1700000000000""").scheduledAt)
        assertNull(one(""","scheduledAt":"1700000000000"""").scheduledAt)
        assertNull(one("").scheduledAt)
    }

    @Test
    fun theFramesCarryExactlyTheirParams() {
        assertEquals("""{"adapter":"a","id":"i","folderId":"f"}""", Frames.inboxAccept("a", "i", "f").toString())
        assertEquals("""{"adapter":"a","id":"i"}""", Frames.inboxIgnore("a", "i").toString())
    }

    private fun prefillOf(field: String): Prefill? =
        SessionList.decode(Json.parseToJsonElement("""{"window":true,"folders":[],"sessions":[{"id":"s","target":"claude","running":true$field}]}"""))!!.sessions.single().prefill

    @Test
    fun aSessionsPrefillIsReadLeniently() {
        val intake = ""","intake":{"adapter":"slack","id":"m1"}"""
        assertEquals(Prefill("p1", "Read x", ItemRef("slack", "m1"), false), prefillOf(""","prefill":{"id":"p1","text":"Read x"$intake,"edited":false}"""))
        assertEquals(Prefill("p1", "", ItemRef("slack", "m1"), true), prefillOf(""","prefill":{"id":"p1","text":""$intake}"""))
        assertEquals(Prefill("p1", "t", ItemRef("slack", "m1"), true), prefillOf(""","prefill":{"id":"p1","text":"t"$intake,"edited":"no"}"""))
        for (bad in listOf(
            "",
            ""","prefill":null""",
            ""","prefill":"x"""",
            ""","prefill":{"text":"t"$intake}""",
            ""","prefill":{"id":"","text":"t"$intake}""",
            ""","prefill":{"id":"p","text":5$intake}""",
            ""","prefill":{"id":"p","text":"t","intake":{"adapter":"slack"}}""",
            ""","prefill":{"id":"p","text":"t"}""",
        )) {
            assertNull(bad, prefillOf(bad))
        }
    }

    @Test
    fun theSubmitFrameCarriesExactlyItsParams() {
        assertEquals("""{"sessionId":"s","prefillId":"p1"}""", Frames.sessionsSubmitPrefill("s", "p1").toString())
    }

    // ---- recurring-handoff 1.1: `recurrence` (design D1) ----

    private val proposal = InboxItem.Recurrence.Role.PROPOSAL

    @Test
    fun aProposalIsReadWithItsScheduleEndAndTimes() {
        val item = one(""","state":"pending","recurrence":{"role":"proposal","repeat":"0 21 * * 3","until":500,"next":[100,200,300]}""")
        assertEquals(InboxItem.Recurrence(proposal, "0 21 * * 3", 500, listOf(100, 200, 300)), item.recurrence)
    }

    @Test
    fun anOccurrenceWithoutEndOrTimes() {
        val item = one(""","scheduledAt":20,"recurrence":{"role":"occurrence","repeat":"0 9 * * *"}""")
        assertEquals(InboxItem.Recurrence(InboxItem.Recurrence.Role.OCCURRENCE, "0 9 * * *"), item.recurrence)
        assertEquals(20L, item.scheduledAt)
    }

    @Test
    fun anUnknownRoleIsOtherAndAProposalNeedsNoExpression() {
        assertEquals(InboxItem.Recurrence.Role.OTHER, one(""","recurrence":{"role":"weekly","repeat":"x"}""").recurrence?.role)
        assertEquals(InboxItem.Recurrence(proposal), one(""","recurrence":{"role":"proposal"}""").recurrence)
        assertEquals(InboxItem.Recurrence(proposal), one(""","recurrence":{"role":"proposal","repeat":5}""").recurrence)
    }

    @Test
    fun aMalformedRecurrenceIsIgnoredAndTheItemKept() {
        for (value in listOf("\"weekly\"", "[]", "{}", """{"repeat":"0 21 * * 3"}""", """{"role":1,"repeat":"0 21 * * 3"}""", "null")) {
            val item = one(""","state":"pending","recurrence":$value""")
            assertEquals(value, "a/i", item.key)
            assertNull(value, item.recurrence)
        }
    }

    @Test
    fun mistypedEndAndTimesAreLeftOut() {
        assertNull(one(""","recurrence":{"role":"proposal","until":"soon"}""").recurrence!!.until)
        assertEquals(listOf(1L, 2L), one(""","recurrence":{"role":"proposal","next":[1,"x",null,true,2]}""").recurrence!!.next)
        assertEquals(emptyList<Long>(), one(""","recurrence":{"role":"proposal","next":"1"}""").recurrence!!.next)
    }

    @Test
    fun anItemWithoutTheFieldHasNoRecurrence() {
        assertNull(one(""","state":"pending","scheduledAt":20""").recurrence)
    }
}
