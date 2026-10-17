package com.spekterm.android.net

import com.spekterm.android.pairing.PairingLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The wire frames of spekterm's remote protocol, version 1 (`docs/remote-protocol.md` section 3).
 *
 * Unknown keys are ignored everywhere, so fields the desktop adds later do not break the app.
 */
object Frames {
    const val PROTOCOL = 1

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** `{ t: 'req', id, m, p }`. */
    fun request(id: Long, method: String, params: JsonObject = JsonObject(emptyMap())): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("t", "req")
                put("id", id)
                put("m", method)
                put("p", params)
            },
        )

    /** A server frame, or `null` when the text is not one (malformed JSON, another `t`). */
    fun decode(text: String): ServerFrame? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        return when ((root["t"] as? JsonPrimitive)?.content) {
            "res" -> {
                val ok = (root["ok"] as? JsonPrimitive)?.content == "true"
                val id = (root["id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let { it.longOrNull ?: return null }
                if (ok) {
                    ServerFrame.Response(id, root["r"] ?: JsonNull, null)
                } else {
                    val error = (root["e"] as? JsonObject)?.let { decodeOrNull<RemoteError>(it) }
                        ?: RemoteError("INTERNAL", "malformed error frame")
                    ServerFrame.Response(id, null, error)
                }
            }
            "evt" -> {
                val kind = (root["k"] as? JsonPrimitive)?.content ?: return null
                ServerFrame.Event(kind, root["d"] ?: JsonNull)
            }
            else -> null
        }
    }

    inline fun <reified T> decodeOrNull(element: JsonElement): T? = try {
        json.decodeFromJsonElement<T>(element)
    } catch (_: Exception) {
        null
    }

    // ---- Operation payloads ----

    fun hello(deviceId: String, deviceSecret: String): JsonObject = buildJsonObject {
        put("protocol", JsonArray(listOf(JsonPrimitive(PROTOCOL))))
        put("deviceId", deviceId)
        put("deviceSecret", deviceSecret)
    }

    fun pair(code: String, deviceName: String, deviceModel: String): JsonObject = buildJsonObject {
        put("protocol", JsonArray(listOf(JsonPrimitive(PROTOCOL))))
        put("code", code)
        put("deviceName", deviceName)
        put("deviceModel", deviceModel)
    }

    fun attentionWatch(after: Position?): JsonObject = buildJsonObject {
        if (after != null) put("after", json.encodeToJsonElement(after))
    }

    fun sessionsWake(sessionId: String): JsonObject = buildJsonObject { put("sessionId", sessionId) }

    /** An agent session; the global item is an explicit `null` — the desktop refuses an absent `folderId`. */
    fun sessionsCreate(folderId: String?): JsonObject = buildJsonObject {
        put("folderId", folderId?.let(::JsonPrimitive) ?: JsonNull)
        put("target", Session.CLAUDE)
    }

    /** `afterUuid` is omitted when nothing is held: the desktop then sends the last 400 entries, replacing. */
    fun conversationWatch(sessionId: String, afterUuid: String?): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        if (afterUuid != null) put("afterUuid", afterUuid)
    }

    /** `attachments` only when there are some: an older desktop knows no such key. */
    fun conversationSend(sessionId: String, text: String, attachments: List<String> = emptyList()): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("text", text)
        if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map(::JsonPrimitive)))
    }

    /** An empty [name] hands naming back to the session's own title. */
    fun sessionsRename(sessionId: String, name: String): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("name", name)
    }

    /** `force` only when true: a first request never forces (obligation 12). */
    fun sessionsHibernate(sessionId: String, force: Boolean): JsonObject = sessionAction(sessionId, force)

    fun sessionsClose(sessionId: String, force: Boolean): JsonObject = sessionAction(sessionId, force)

    /** Keeps a session whose agent offered to close it (`close-offer`). */
    fun sessionsKeep(sessionId: String): JsonObject = buildJsonObject { put("sessionId", sessionId) }

    fun handoffsBrief(sessionId: String): JsonObject = buildJsonObject { put("sessionId", sessionId) }

    private fun sessionAction(sessionId: String, force: Boolean): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        if (force) put("force", true)
    }

    /** `selections` only with the `answer` decision: one list of option indexes per question. */
    fun conversationAnswer(sessionId: String, promptId: String, decision: String, selections: List<List<Int>>? = null): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("promptId", promptId)
        put("decision", decision)
        if (selections != null) put("selections", JsonArray(selections.map { q -> JsonArray(q.map(::JsonPrimitive)) }))
    }

    /** One chunk; `attachmentId` from the second chunk on. [data] is base64. */
    fun attachmentsPut(sessionId: String, name: String, size: Long, offset: Long, data: String, attachmentId: String?): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("name", name)
        put("size", size)
        put("offset", offset)
        put("data", data)
        if (attachmentId != null) put("attachmentId", attachmentId)
    }

    fun openspecChanges(folderId: String): JsonObject = buildJsonObject { put("folderId", folderId) }

    fun openspecChange(folderId: String, slug: String): JsonObject = buildJsonObject {
        put("folderId", folderId)
        put("slug", slug)
    }

    fun openspecSpecs(folderId: String): JsonObject = buildJsonObject { put("folderId", folderId) }

    fun openspecSpec(folderId: String, topic: String): JsonObject = buildJsonObject {
        put("folderId", folderId)
        put("topic", topic)
    }

    /** One folder per connection; `null` stops watching (`openspec-browse` design D3). */
    fun openspecWatch(folderId: String?): JsonObject = buildJsonObject {
        put("folderId", folderId?.let(::JsonPrimitive) ?: JsonNull)
    }

    /**
     * `operations` of a `hello` or `pair` answer (design D1): what the desktop accepts besides authentication.
     * Absent or not an array (a desktop before 29a5d0a) is none; non-string elements are skipped.
     */
    fun operations(element: JsonElement?): Set<String> {
        val array = (element as? JsonObject)?.get("operations") as? JsonArray ?: return emptySet()
        return array.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
    }

    /**
     * `addresses` of a `hello` or `pair` answer or an `addresses.changed` event (`docs/remote-protocol.md` sections
     * 4 and 6): `null` when absent or not an array (a desktop older than 0.2.5), else its usable string elements.
     * Read by hand so one odd element does not discard the answer it came in.
     */
    fun addresses(element: JsonElement?): List<String>? {
        val array = (element as? JsonObject)?.get("addresses") as? JsonArray ?: return null
        return addressList(array.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content })
    }

    /** Addresses normalized as the pairing link's are, unusable ones left out, each once. */
    fun addressList(addresses: List<String>): List<String> = addresses.mapNotNull { PairingLink.normalize(it) }.distinct()

    /** `detail.supported` of an `UNSUPPORTED_VERSION` error: the desktop's versions. */
    fun supportedVersions(error: RemoteError): List<Int> =
        ((error.detail?.get("supported")) as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
}

sealed interface ServerFrame {
    /** Arrival order within one connection, stamped on the reader thread; 0 before stamping (design D2). */
    val seq: Long

    /**
     * `{ t: 'res', id, ok, r | e }`. [id] is `null` when the desktop could not read one, or answered before
     * any request (a throttled address: `THROTTLED` at accept time).
     */
    data class Response(val id: Long?, val result: JsonElement?, val error: RemoteError?, override val seq: Long = 0) : ServerFrame {
        val ok: Boolean get() = error == null
    }

    /** `{ t: 'evt', k, d }`. */
    data class Event(val kind: String, val data: JsonElement, override val seq: Long = 0) : ServerFrame
}

/** `{ code, message, detail? }`. Branch on [code]; [message] is for display as a detail only. */
@Serializable
data class RemoteError(val code: String, val message: String = "", val detail: JsonObject? = null) {
    /** A numeric field of [detail] (`received`, `limit`, `open`); `null` when absent or not a number. */
    fun detailLong(key: String): Long? = (detail?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    /** A string field of [detail] (`reason`); `null` when absent or not a string. */
    fun detailString(key: String): String? = (detail?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val BAD_FRAME = "BAD_FRAME"
        const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
        const val AUTH_FAILED = "AUTH_FAILED"
        const val PAIRING_FAILED = "PAIRING_FAILED"
        const val THROTTLED = "THROTTLED"
        const val TOO_MANY_CONNECTIONS = "TOO_MANY_CONNECTIONS"
        const val NOT_FOUND = "NOT_FOUND"
        const val BAD_PARAMS = "BAD_PARAMS"
        const val UNKNOWN_OPERATION = "UNKNOWN_OPERATION"
        const val NO_WINDOW = "NO_WINDOW"
        const val NO_CONVERSATION = "NO_CONVERSATION"
        const val NOT_WATCHING = "NOT_WATCHING"
        const val WAIT_STATE = "WAIT_STATE"
        const val CREATE_FAILED = "CREATE_FAILED"
        const val TIMEOUT = "TIMEOUT"
        const val INTERNAL = "INTERNAL"
        const val PROMPT_GONE = "PROMPT_GONE"
        const val NOT_ANSWERABLE = "NOT_ANSWERABLE"
        const val TOO_LARGE = "TOO_LARGE"
        const val TOO_MANY = "TOO_MANY"
        const val UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE"
        const val SESSION_BUSY = "SESSION_BUSY"
    }
}

/** The operations a control depends on (`desktop-connection`, "Offer a control only when the desktop accepts its operation"). */
object Operations {
    const val RENAME = "sessions.rename"
    const val HIBERNATE = "sessions.hibernate"
    const val CLOSE = "sessions.close"
    const val WAKE = "sessions.wake"
    const val KEEP = "sessions.keep"
    const val ATTACH = "attachments.put"
    const val ANSWER = "conversation.answer"
    const val OPENSPEC_CHANGES = "openspec.changes"
    const val OPENSPEC_CHANGE = "openspec.change"
    const val OPENSPEC_SPECS = "openspec.specs"
    const val OPENSPEC_SPEC = "openspec.spec"
    const val OPENSPEC_WATCH = "openspec.watch"
    const val HANDOFFS_LIST = "handoffs.list"
    const val HANDOFFS_BRIEF = "handoffs.brief"
}

@Serializable
data class ServerInfo(val name: String = "", val version: String = "")

/** The answer to `hello`. Its `addresses` are read with [Frames.addresses]. */
@Serializable
data class HelloResult(val protocol: Int, val server: ServerInfo = ServerInfo())

/** The answer to `pair`. The credential is given once; the desktop keeps only its hash. */
@Serializable
data class PairResult(val protocol: Int, val server: ServerInfo = ServerInfo(), val deviceId: String, val deviceSecret: String) {
    override fun toString(): String = "PairResult(protocol=$protocol, server=$server, deviceId=$deviceId, deviceSecret=<redacted>)"
}

/** A position in the desktop's attention log: [epoch] names the span, [seq] the event within it. */
@Serializable
data class Position(val epoch: String, val seq: Long)

@Serializable
data class IntakeRef(val adapter: String, val id: String)

/** One attention event (`docs/remote-protocol.md` section 7). Text fields are already reduced by the desktop. */
@Serializable
data class Attention(
    val pos: Position,
    val kind: String,
    val at: Long,
    val sessionId: String? = null,
    val intake: IntakeRef? = null,
    val folderName: String? = null,
    val title: String = "",
    val summary: String? = null,
    val tool: String? = null,
    val suppressed: Boolean = false,
) {
    companion object {
        const val WAITING = "waiting"
        const val CHOICE = "choice"
        const val HANDOFF_DONE = "handoff-done"
        const val INTAKE = "intake"
    }
}

/** The answer to `attention.watch`. */
@Serializable
data class WatchAnswer(val events: List<Attention>, val complete: Boolean)
