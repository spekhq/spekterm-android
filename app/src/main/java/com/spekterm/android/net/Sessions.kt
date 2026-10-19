package com.spekterm.android.net

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
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

/** The session that handed this one off ([parentId], which may be gone) and its title at the time (`session-relations`). */
@Serializable
data class Lineage(val parentId: String, val title: String? = null)

/**
 * A lineage that is an object with a string `parentId`; anything else reads as none, so a malformed one never drops
 * the session that carries it (obligation 7; `session-relations` design D1). A `title` that is not a string is dropped.
 */
object LenientLineage : KSerializer<Lineage?> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Lineage? {
        val o = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject ?: return null
        return Lineage(o.string("parentId") ?: return null, o.string("title"))
    }

    override fun serialize(encoder: Encoder, value: Lineage?) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(value?.let { json.json.encodeToJsonElement(Lineage.serializer(), it) } ?: JsonNull)
    }
}

/** The inbox item a filled-in prompt came from (`inbox` design D3). */
@Serializable
data class ItemRef(val adapter: String, val id: String)

/**
 * A filled-in first prompt nobody has sent (`Session.prefill`, spekterm `remote-prefill-send`): [text] exactly as written
 * into the agent's input box, [intake] the item it came from, [edited] whether the box may hold more than [text].
 */
@Serializable
data class Prefill(val id: String, val text: String, val intake: ItemRef, val edited: Boolean)

/**
 * `prefill` read leniently (`inbox` design D3): anything but an object with a non-empty `id`, a string `text` and an
 * `intake` with string `adapter` and `id` is none; an `edited` that is not a boolean reads `true`, so no send is offered.
 */
object LenientPrefill : KSerializer<Prefill?> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Prefill? {
        val o = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject ?: return null
        val intake = o["intake"] as? JsonObject ?: return null
        return Prefill(
            id = o.string("id")?.takeIf { it.isNotEmpty() } ?: return null,
            text = o.string("text") ?: return null,
            intake = ItemRef(intake.string("adapter") ?: return null, intake.string("id") ?: return null),
            edited = (o["edited"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: true,
        )
    }

    override fun serialize(encoder: Encoder, value: Prefill?) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(value?.let { json.json.encodeToJsonElement(Prefill.serializer(), it) } ?: JsonNull)
    }
}

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
    /**
     * Present exactly while [wait] is `ready`: whether someone looked at it since its turn ended; `null` from a desktop
     * older than the field (`session-relations` D7).
     */
    @Serializable(with = OnlyBoolean::class)
    val seen: Boolean? = null,
    /** Handoff-created sessions only. */
    val lifecycle: String? = null,
    @Serializable(with = LenientLineage::class)
    val lineage: Lineage? = null,
    /** The agent asked to close its own session and the user has not answered (`close-offer`, design D1). */
    @Serializable(with = OnlyTrue::class)
    val closeOffered: Boolean = false,
    /** The work its agent runs in the background, as the agent last reported it (`background-tasks`, design D1). */
    @Serializable(with = LenientBackground::class)
    val background: List<BackgroundTask> = emptyList(),
    /** A filled-in first prompt from the inbox that nobody has sent (`inbox` design D3). */
    @Serializable(with = LenientPrefill::class)
    val prefill: Prefill? = null,
) {
    val isAgent: Boolean get() = target == CLAUDE

    companion object {
        const val CLAUDE = "claude"
        const val SHELL = "shell"
    }
}

/** One task of [Session.background]: [label] is the agent's own text, shown as plain text (obligation 15). */
data class BackgroundTask(val id: String, val kind: Kind, val label: String? = null) {
    enum class Kind(val wire: String) { AGENT("agent"), SHELL("shell"), MONITOR("monitor"), OTHER("other") }
}

/**
 * The background work as an array of tasks; anything else reads as none, so it never drops the session that carries it
 * (obligation 7; `background-tasks` D1). A task needs a non-empty string `id`; a `kind` the app does not know, or none,
 * is [BackgroundTask.Kind.OTHER]; a `label` that is not a string with something other than whitespace is none.
 */
object LenientBackground : KSerializer<List<BackgroundTask>> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): List<BackgroundTask> {
        val array = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonArray ?: return emptyList()
        return each(array) { e ->
            val o = e as? JsonObject ?: return@each null
            val id = o.string("id")?.takeIf { it.isNotEmpty() } ?: return@each null
            val kind = o.string("kind").let { k -> BackgroundTask.Kind.entries.firstOrNull { it.wire == k } } ?: BackgroundTask.Kind.OTHER
            BackgroundTask(id, kind, o.string("label")?.takeIf { it.isNotBlank() })
        }
    }

    override fun serialize(encoder: Encoder, value: List<BackgroundTask>) {
        (encoder as JsonEncoder).encodeJsonElement(
            JsonArray(value.map { JsonObject(mapOf("id" to JsonPrimitive(it.id), "kind" to JsonPrimitive(it.kind.wire), "label" to (it.label?.let(::JsonPrimitive) ?: JsonNull))) }),
        )
    }
}

/**
 * A flag that is `true` only for the JSON boolean `true`; any other value (a string `"true"` included) is `false`, so
 * an unexpected value never drops the session that carries it (obligation 7; design D1).
 */
object OnlyTrue : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("OnlyTrue", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val e = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeBoolean()
        return e is JsonPrimitive && !e.isString && e.booleanOrNull == true
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

/**
 * A flag that is `true` or `false` only for the JSON booleans; any other value is `null`, so it never drops the
 * session that carries it (obligation 7; `session-relations` D7).
 */
object OnlyBoolean : KSerializer<Boolean?> {
    override val descriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Boolean? {
        val e = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeBoolean()
        return if (e is JsonPrimitive && !e.isString) e.booleanOrNull else null
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        (encoder as JsonEncoder).encodeJsonElement(value?.let { JsonPrimitive(it) } ?: JsonNull)
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

/** One entry of `handoffs.list` / `handoffs.changed`: a handoff-created session and its latest report. */
data class HandoffEntry(val sessionId: String, val state: String? = null, val summary: String? = null, val reportedAt: Long? = null) {
    companion object {
        /** The whole list, element by element; `null` when the answer is not an array. */
        fun decodeList(element: JsonElement?): List<HandoffEntry>? {
            if (element !is JsonArray) return null
            return each(element) { e ->
                val o = e as? JsonObject ?: return@each null
                HandoffEntry(o.string("sessionId") ?: return@each null, o.string("state"), o.string("summary"), o.long("reportedAt"))
            }
        }
    }
}

/** A completion report: its [summary] and when it was made. */
data class HandoffReport(val summary: String, val reportedAt: Long?)

/** The answer to `handoffs.brief`: the handoff's note and its latest report, each when the desktop has one. */
data class HandoffBrief(val body: String?, val report: HandoffReport?) {
    companion object {
        fun decode(element: JsonElement?): HandoffBrief? {
            val o = element as? JsonObject ?: return null
            val report = (o["report"] as? JsonObject)?.let { r -> r.string("summary")?.let { HandoffReport(it, r.long("reportedAt")) } }
            return HandoffBrief(o.string("body"), report)
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

/**
 * `conversation.wait`: whether a reply can be sent, and the open prompt. Decoded by hand, so a malformed or unknown
 * [prompt] never drops the event that carries it (design D2).
 */
data class ConversationWait(val sessionId: String, val state: String, val pending: Pending?, val prompt: Prompt?) {
    companion object {
        fun decode(element: JsonElement?): ConversationWait? {
            val o = element as? JsonObject ?: return null
            return ConversationWait(
                sessionId = o.string("sessionId") ?: return null,
                state = o.string("state") ?: return null,
                pending = (o["pending"] as? JsonObject)?.let { Frames.decodeOrNull<Pending>(it) },
                prompt = o["prompt"]?.let { runCatching { Prompt.decode(it) }.getOrNull() },
            )
        }
    }
}

/**
 * The agent's first open request, as the desktop states it (`docs/remote-protocol.md`, `conversation.watch`).
 * [answerable] is the desktop's word, false when missing; [open] is how many requests are open.
 */
sealed interface Prompt {
    val id: String
    val answerable: Boolean
    val open: Int

    /** [canAllow]: the tool's prompt puts a one-time "Yes" first. */
    data class Permission(
        override val id: String,
        val tool: String,
        val arg: String?,
        val canAllow: Boolean,
        override val answerable: Boolean,
        override val open: Int,
    ) : Prompt

    /** The agent's question tool. [answerable] is also false when the phone's own guard ([fits]) fails. */
    data class Question(
        override val id: String,
        val questions: List<Item>,
        val desktopAnswerable: Boolean,
        override val open: Int,
    ) : Prompt {
        data class Item(val question: String, val header: String, val multiSelect: Boolean, val options: List<Option>)

        data class Option(val label: String, val description: String)

        /** One to four questions of one to eight options: the phone never renders an unbounded form. */
        val fits: Boolean get() = questions.size in 1..4 && questions.all { it.options.size in 1..8 }

        override val answerable: Boolean get() = desktopAnswerable && fits
    }

    /** [choices] holds only the known decisions the desktop offers for it. */
    data class Plan(
        override val id: String,
        val plan: String?,
        val choices: List<String>,
        override val answerable: Boolean,
        override val open: Int,
    ) : Prompt

    companion object {
        const val ALLOW = "allow"
        const val DENY = "deny"
        const val ANSWER = "answer"
        const val APPROVE = "approve"
        const val APPROVE_AUTO = "approve-auto"

        private val PLAN_CHOICES = setOf(APPROVE, APPROVE_AUTO, DENY)

        /** A prompt, or `null` for none, an unknown kind or a malformed one (no id). */
        fun decode(element: JsonElement): Prompt? {
            val o = element as? JsonObject ?: return null
            val id = o.string("id")?.takeIf { it.isNotEmpty() } ?: return null
            val answerable = o.bool("answerable")
            val open = (o["open"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.toInt() ?: 1
            return when (o.string("kind")) {
                "permission" -> Permission(id, o.string("tool") ?: "", o.string("arg"), o.bool("canAllow"), answerable, open)
                "question" -> Question(
                    id,
                    each(o["questions"]) { q ->
                        val item = q as? JsonObject ?: return@each null
                        Question.Item(
                            question = item.string("question") ?: "",
                            header = item.string("header") ?: "",
                            multiSelect = item.bool("multiSelect"),
                            options = each(item["options"]) { opt ->
                                val option = opt as? JsonObject ?: return@each null
                                Question.Option(option.string("label") ?: return@each null, option.string("description") ?: "")
                            },
                        )
                    },
                    answerable,
                    open,
                )
                "plan" -> Plan(
                    id,
                    o.string("plan"),
                    each(o["choices"]) { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf { c -> c in PLAN_CHOICES } },
                    answerable,
                    open,
                )
                else -> null
            }
        }
    }
}

/** `conversation.ended`. Branch on [reason]; an unknown one is taken as the process not running (design D10). */
@Serializable
data class ConversationEnded(val sessionId: String, val reason: String = "") {
    companion object {
        const val WINDOW = "window"
        const val EXITED = "exited"
        const val HIBERNATED = "hibernated"
        const val CLOSED = "closed"
    }
}

/** The answer to `sessions.create` and `sessions.wake`. */
@Serializable
data class CreateResult(val sessionId: String)

/** The answer to `conversation.send`. [text] is exactly what the desktop wrote; absent before 29a5d0a. */
@Serializable
data class SendResult(val outcome: String, val text: String? = null) {
    companion object {
        const val SUBMITTED = "submitted"
        const val FILLED_NOT_SUBMITTED = "filled-not-submitted"
    }
}

/** The answer to `conversation.answer`. */
@Serializable
data class AnswerResult(val outcome: String) {
    companion object {
        const val SENT = "sent"
        const val PARTIAL = "partial"
        const val CONFIRMED = "confirmed"
        const val MISMATCH = "mismatch"
    }
}

/** The answer to one `attachments.put` chunk. */
@Serializable
data class PutResult(val attachmentId: String, val received: Long, val complete: Boolean = false)

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

internal inline fun <T> each(element: JsonElement?, decode: (JsonElement) -> T?): List<T> =
    (element as? JsonArray)?.mapNotNull { runCatching { decode(it) }.getOrNull() } ?: emptyList()

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
