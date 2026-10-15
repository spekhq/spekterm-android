package com.spekterm.android.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The session and conversation payloads of the remote protocol (`docs/remote-protocol.md` section 5).
 *
 * Lists are decoded element by element: one malformed or unknown element is skipped, never the whole list.
 */

@Serializable
data class Folder(
    val id: String,
    val name: String = "",
    val branch: String? = null,
    val pinned: Boolean = false,
    val hasOpenSpec: Boolean = false,
)

@Serializable
data class Lineage(val parentId: String, val title: String? = null)

@Serializable
data class Session(
    val id: String,
    /** `null` = the global item. */
    val folderId: String? = null,
    val target: String,
    val label: String = "",
    val running: Boolean = false,
    /** Agent sessions only: `ready`, `busy`, `awaiting-choice`, `unknown`. */
    val wait: String? = null,
    /** Handoff-created sessions only. */
    val lifecycle: String? = null,
    val lineage: Lineage? = null,
) {
    val isAgent: Boolean get() = target == CLAUDE

    companion object {
        const val CLAUDE = "claude"
        const val SHELL = "shell"
    }
}

/** The answer to `sessions.list` and the data of `sessions.changed`. */
data class SessionList(val window: Boolean, val folders: List<Folder>, val sessions: List<Session>) {
    companion object {
        fun decode(element: JsonElement?): SessionList? {
            val o = element as? JsonObject ?: return null
            val window = (o["window"] as? JsonPrimitive)?.booleanOrNull ?: return null
            return SessionList(
                window,
                each(o["folders"]) { Frames.decodeOrNull<Folder>(it) },
                each(o["sessions"]) { Frames.decodeOrNull<Session>(it) },
            )
        }
    }
}

/** One conversation entry. One transcript record yields several entries sharing its [uuid]. */
sealed interface Entry {
    val uuid: String
    val at: Long

    data class User(override val uuid: String, override val at: Long, val text: String, val meta: Boolean) : Entry

    data class Text(override val uuid: String, override val at: Long, val text: String) : Entry

    data class Thinking(override val uuid: String, override val at: Long, val text: String) : Entry

    /** A tool call: its [name] and its single identifying argument. */
    data class Tool(override val uuid: String, override val at: Long, val id: String, val name: String, val arg: String?) : Entry

    data class Result(override val uuid: String, override val at: Long, val id: String, val text: String, val error: Boolean) : Entry

    companion object {
        /** An entry, or `null` for an unknown kind or a malformed one. */
        fun decode(element: JsonElement): Entry? {
            val o = element as? JsonObject ?: return null
            val uuid = o.string("uuid") ?: ""
            val at = (o["at"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
            return when (o.string("kind")) {
                "user" -> User(uuid, at, o.string("text") ?: return null, o.bool("meta"))
                "text" -> Text(uuid, at, o.string("text") ?: return null)
                "thinking" -> Thinking(uuid, at, o.string("text") ?: return null)
                "tool" -> Tool(uuid, at, o.string("id") ?: "", o.string("name") ?: return null, o.string("arg"))
                "result" -> Result(uuid, at, o.string("id") ?: "", o.string("text") ?: "", o.bool("error"))
                else -> null
            }
        }
    }
}

/** `conversation.update`. */
data class ConversationUpdate(
    val sessionId: String,
    /** `attaching`, `ok` or `unavailable`. */
    val status: String,
    val events: List<Entry>,
    val truncated: Boolean,
    val reset: Boolean,
) {
    companion object {
        const val OK = "ok"
        const val UNAVAILABLE = "unavailable"

        fun decode(element: JsonElement?): ConversationUpdate? {
            val o = element as? JsonObject ?: return null
            return ConversationUpdate(
                sessionId = o.string("sessionId") ?: return null,
                status = o.string("status") ?: return null,
                events = each(o["events"], Entry::decode),
                truncated = o.bool("truncated"),
                reset = o.bool("reset"),
            )
        }
    }
}

/** What the agent asks while it waits on a choice. Plain text, as reported. */
@Serializable
data class Pending(val tool: String = "", val arg: String? = null)

/** `conversation.wait`: whether a reply can be sent. */
@Serializable
data class ConversationWait(val sessionId: String, val state: String, val pending: Pending? = null)

/** `conversation.ended`. */
@Serializable
data class ConversationEnded(val sessionId: String, val reason: String = "")

/** The answer to `conversation.send`. */
@Serializable
data class SendResult(val outcome: String) {
    companion object {
        const val SUBMITTED = "submitted"
        const val FILLED_NOT_SUBMITTED = "filled-not-submitted"
    }
}

/** The agent's wait states (`agent-input-bridge`); anything else is unknown. */
object WaitState {
    const val READY = "ready"
    const val BUSY = "busy"
    const val AWAITING_CHOICE = "awaiting-choice"
    const val UNKNOWN = "unknown"

    fun of(value: String?): String = when (value) {
        READY, BUSY, AWAITING_CHOICE -> value
        else -> UNKNOWN
    }
}

private inline fun <T> each(element: JsonElement?, decode: (JsonElement) -> T?): List<T> =
    (element as? JsonArray)?.mapNotNull { runCatching { decode(it) }.getOrNull() } ?: emptyList()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
