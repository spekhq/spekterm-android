package com.spekterm.android.net

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

    /** `detail.supported` of an `UNSUPPORTED_VERSION` error: the desktop's versions. */
    fun supportedVersions(error: RemoteError): List<Int> =
        ((error.detail?.get("supported")) as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
}

sealed interface ServerFrame {
    /**
     * `{ t: 'res', id, ok, r | e }`. [id] is `null` when the desktop could not read one, or answered before
     * any request (a throttled address: `THROTTLED` at accept time).
     */
    data class Response(val id: Long?, val result: JsonElement?, val error: RemoteError?) : ServerFrame {
        val ok: Boolean get() = error == null
    }

    /** `{ t: 'evt', k, d }`. */
    data class Event(val kind: String, val data: JsonElement) : ServerFrame
}

/** `{ code, message, detail? }`. Branch on [code]; [message] is for display as a detail only. */
@Serializable
data class RemoteError(val code: String, val message: String = "", val detail: JsonObject? = null) {
    companion object {
        const val BAD_FRAME = "BAD_FRAME"
        const val UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
        const val AUTH_FAILED = "AUTH_FAILED"
        const val PAIRING_FAILED = "PAIRING_FAILED"
        const val THROTTLED = "THROTTLED"
        const val TOO_MANY_CONNECTIONS = "TOO_MANY_CONNECTIONS"
        const val NOT_FOUND = "NOT_FOUND"
    }
}

@Serializable
data class ServerInfo(val name: String = "", val version: String = "")

/** The answer to `hello`. */
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
