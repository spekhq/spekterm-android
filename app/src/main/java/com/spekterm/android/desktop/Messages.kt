package com.spekterm.android.desktop

import com.spekterm.android.net.Entry
import com.spekterm.android.net.Origin
import com.spekterm.android.net.SessionList
import com.spekterm.android.net.ToolInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What is exchanged between parties, shown as chat messages (`tool-call-display` D11): a peer's message, a subagent's
 * report, the agent's SendMessage and the files it sent the user. Pure; the screen supplies the words.
 */
object Messages {
    /** Who sent or receives a message; [name] as the protocol gives it (a messaging name, a subagent type). */
    data class Who(val kind: Kind, val name: String?)

    enum class Kind {
        /** This session's own agent. */
        SELF,
        SESSION,
        SUBAGENT,

        /** A sender of another kind. */
        AGENT,
    }

    sealed interface Status {
        /** No result yet. */
        data object Sending : Status

        data object Sent : Status

        data class NotSent(val reason: String?) : Status
    }

    /** A body longer than [CUT_LINES] lines or [CUT_CHARS] characters shows its beginning first. */
    const val CUT_LINES = 12
    const val CUT_CHARS = 1_200
    const val SHOW_LINES = 10
    const val SHOW_CHARS = 1_000

    /** The avatars' colours, picked by a name's hash. */
    const val COLORS = 6

    private val AGENT_ID = Regex("^a[0-9a-f]{16}$")
    private val SUMMARY = Regex("<summary>([\\s\\S]*?)</summary>")

    /** The sender of a received message. */
    fun sender(origin: Origin.Peer): Who = when (origin.sender) {
        Origin.Sender.SESSION -> Who(Kind.SESSION, origin.name)
        Origin.Sender.SUBAGENT -> Who(Kind.SUBAGENT, origin.name)
        Origin.Sender.OTHER -> Who(Kind.AGENT, origin.name)
    }

    /** Whom a SendMessage went to: a named recipient by name; an address or an agent id by its kind only. */
    fun recipient(input: ToolInput): Who? {
        val to = input.text("to")
        if (to != null) {
            return when {
                to.startsWith("uds:") -> Who(Kind.SESSION, null)
                AGENT_ID.matches(to) -> Who(Kind.SUBAGENT, null)
                else -> Who(Kind.SESSION, to)
            }
        }
        return when (input.string("recipient_kind")) {
            "session" -> Who(Kind.SESSION, null)
            "agent" -> Who(Kind.SUBAGENT, null)
            else -> null
        }
    }

    /**
     * A name to show: a session in [list] by its label, this session by [self], any other by the name it was given;
     * `null` when there is none (the screen then words the kind).
     */
    fun nameOf(who: Who, list: SessionList?, self: String?): String? = when (who.kind) {
        Kind.SELF -> self?.takeIf { it.isNotBlank() }
        Kind.SESSION, Kind.AGENT -> who.name?.let { SessionNames.label(it, list) ?: it }
        Kind.SUBAGENT -> who.name
    }

    /** The SendMessage's outcome from its result (`{"success": bool, "message"}`), an error, or any other text. */
    fun status(result: Entry.Result?): Status {
        if (result == null) return Status.Sending
        val o = runCatching { Json.parseToJsonElement(result.text) as? JsonObject }.getOrNull()
        val success = (o?.get("success") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        return when {
            success == true -> Status.Sent
            success == false -> Status.NotSent((o["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() })
            result.error -> Status.NotSent(result.text.takeIf { it.isNotBlank() })
            else -> Status.Sent
        }
    }

    /** The beginning of a long [text], or `null` when it is short enough to show whole. */
    fun cut(text: String): String? {
        val lines = text.replace("\r\n", "\n").split('\n')
        if (lines.size <= CUT_LINES && text.length <= CUT_CHARS) return null
        val head = lines.take(SHOW_LINES).joinToString("\n")
        return if (head.length > SHOW_CHARS) head.take(SHOW_CHARS) else head
    }

    /** What a background task's notice says finished: its `<summary>`, or `null`. */
    fun summary(text: String): String? = SUMMARY.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** A stable avatar colour for [name]. */
    fun colorIndex(name: String): Int = Math.floorMod(name.hashCode(), COLORS)
}
