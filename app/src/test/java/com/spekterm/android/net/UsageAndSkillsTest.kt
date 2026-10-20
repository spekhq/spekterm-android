package com.spekterm.android.net

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `AgentUsage.decode` and `SkillList.decode` (`agent-usage-and-skills` D1). */
class UsageAndSkillsTest {
    private fun usage(json: String): AgentUsage? = AgentUsage.decode(Json.parseToJsonElement(json))

    private fun skills(json: String): SkillList? = SkillList.decode(Json.parseToJsonElement(json))

    @Test
    fun aFullStatusDecodesEveryValue() {
        assertEquals(
            AgentUsage("s1", "Opus 5.5", "high", 42.5, 200_000, 3.214, UsageWindow(31.0, 1_792_471_200_000), UsageWindow(12.0)),
            usage(
                """{"sessionId":"s1","model":"Opus 5.5","effort":"high","context":{"percent":42.5,"size":200000},"costUsd":3.214,
                  "rateLimits":{"fiveHour":{"percent":31,"resetsAt":1792471200000},"sevenDay":{"percent":12}},"later":true}""",
            ),
        )
    }

    @Test
    fun theSessionIdAloneIsAUsageWithNoValue() {
        val u = usage("""{"sessionId":"s1"}""")
        assertEquals(AgentUsage("s1"), u)
        assertFalse(u!!.reported)
        assertNull(usage("""{"model":"Opus 5.5"}"""))
    }

    @Test
    fun aMalformedValueIsNoneAndLeavesTheOthers() {
        val u = usage(
            """{"sessionId":"s1","model":"M","context":{"percent":"42","size":-1},"costUsd":"3",
              "rateLimits":{"fiveHour":{"resetsAt":5},"sevenDay":{"percent":1e400}}}""",
        )!!
        assertEquals(AgentUsage("s1", model = "M"), u)
        assertTrue(u.reported)
        assertNull(usage("""{"sessionId":"s1","costUsd":-0.5}""")!!.costUsd)
        assertNull(usage("""{"sessionId":"s1","costUsd":1e400}""")!!.costUsd)
        assertEquals(AgentUsage("s1", costUsd = 1.0), usage("""{"sessionId":"s1","context":"x","rateLimits":[1],"costUsd":1}"""))
        assertNull(usage("""{"sessionId":"s1","model":"  "}""")!!.model)
    }

    @Test
    fun aPercentOutOfRangeReadsAsItsBound() {
        val u = usage("""{"sessionId":"s1","context":{"percent":120},"rateLimits":{"fiveHour":{"percent":-3}}}""")!!
        assertEquals(100.0, u.contextPercent!!, 0.0)
        assertEquals(0.0, u.fiveHour!!.percent, 0.0)
    }

    @Test
    fun aResetTimeThatIsNotPositiveIsNone() {
        val u = usage("""{"sessionId":"s1","rateLimits":{"fiveHour":{"percent":5,"resetsAt":0},"sevenDay":{"percent":5}}}""")!!
        assertNull(u.fiveHour!!.resetsAt)
        assertNull(u.sevenDay!!.resetsAt)
    }

    @Test
    fun noListKnownIsNeverAnEmptyList() {
        for (json in listOf("""{"sessionId":"s1","skills":null}""", """{"sessionId":"s1"}""", """{"sessionId":"s1","skills":"x"}""")) {
            val list = skills(json)
            assertNotNull(json, list)
            assertNull(json, list!!.skills)
            assertNull(list.logCount)
        }
        assertNull(skills("""{"skills":[]}"""))
    }

    @Test
    fun aMalformedSkillIsLeftOut() {
        val list = skills(
            """{"sessionId":"s1","skills":[
              {"name":"simplify","description":"Review the changed code"},
              {"description":"no name"},{"name":"  "},{"name":"two words"},{"name":"wide　space"},3,
              {"name":"opsx:apply"},{"name":"bare","description":null},{"name":"empty","description":""},
              {"name":"simplify","description":"a second one"}]}""",
        )!!
        assertEquals(
            listOf(Skill("simplify", "Review the changed code"), Skill("opsx:apply"), Skill("bare"), Skill("empty")),
            list.skills,
        )
        assertEquals(4, list.logCount)
    }
}
