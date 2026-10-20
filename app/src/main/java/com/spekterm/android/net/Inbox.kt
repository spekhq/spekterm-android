package com.spekterm.android.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One item of the desktop's inbox (`inbox.list` / `inbox.changed`, `docs/remote-protocol.md` section 5; `inbox` design D1).
 * [body], [title], [actor] and [originLabel] are third-party text: shown as plain text, never logged.
 */
data class InboxItem(
    val id: String,
    val adapter: String,
    val state: State,
    val originLabel: String = "",
    val title: String = "",
    val actor: String = "",
    val body: String = "",
    val bodyLength: Long = 0,
    val receivedAt: Long = 0,
    /** When it happened, never later than its arrival; 0 when its content expired. */
    val occurredAt: Long = 0,
    val sessionId: String? = null,
    /** Pending only: the folder the desktop suggests. */
    val folderId: String? = null,
    val unresolved: Unresolved? = null,
    /** Present only while a handoff waits for its time (pending, not opened); a past value is due. */
    val scheduledAt: Long? = null,
    /** A repeating handoff's schedule (`recurring-handoff` design D1); its role, never [scheduledAt], says what the item is. */
    val recurrence: Recurrence? = null,
) {
    enum class State { PENDING, ACCEPTED, OTHER }

    /**
     * [repeat] is the cron expression as sent (the desktop's time zone), empty when none came; [until] the end and [next]
     * a proposal's coming times as of when the list was built, both epoch ms.
     */
    data class Recurrence(val role: Role, val repeat: String = "", val until: Long? = null, val next: List<Long> = emptyList()) {
        enum class Role { PROPOSAL, OCCURRENCE, OTHER }
    }

    enum class Unresolved { NO_MATCH, FOLDER_GONE, OTHER }

    /** The item's identity across lists: its adapter and its id. */
    val key: String get() = keyOf(adapter, id)

    companion object {
        fun keyOf(adapter: String, id: String): String = "$adapter/$id"

        /** The items of an `inbox.list` answer or `inbox.changed` event; `null` when it is not `{ items: [...] }`. */
        fun decodeList(element: JsonElement?): List<InboxItem>? {
            val items = (element as? JsonObject)?.get("items") as? JsonArray ?: return null
            return each(items) { e ->
                val o = e as? JsonObject ?: return@each null
                val id = o.string("id")?.takeIf { it.isNotEmpty() } ?: return@each null
                val adapter = o.string("adapter")?.takeIf { it.isNotEmpty() } ?: return@each null
                InboxItem(
                    id = id,
                    adapter = adapter,
                    state = when (o.string("state")) {
                        "pending" -> State.PENDING
                        "accepted" -> State.ACCEPTED
                        else -> State.OTHER
                    },
                    originLabel = o.string("originLabel").orEmpty(),
                    title = o.string("title").orEmpty(),
                    actor = o.string("actor").orEmpty(),
                    body = o.string("body").orEmpty(),
                    bodyLength = o.long("bodyLength") ?: 0,
                    receivedAt = o.long("receivedAt") ?: 0,
                    occurredAt = o.long("occurredAt") ?: 0,
                    sessionId = o.string("sessionId"),
                    folderId = o.string("folderId"),
                    unresolved = when (o["unresolved"]) {
                        null, JsonNull -> null
                        else -> when (o.string("unresolved")) {
                            "NO_MATCH" -> Unresolved.NO_MATCH
                            "FOLDER_GONE" -> Unresolved.FOLDER_GONE
                            else -> Unresolved.OTHER
                        }
                    },
                    scheduledAt = o.long("scheduledAt"),
                    recurrence = recurrenceOf(o["recurrence"]),
                )
            }
        }

        /** `null` unless an object with a string `role`; its other fields are each read or left out on their own. */
        private fun recurrenceOf(element: JsonElement?): Recurrence? {
            val o = element as? JsonObject ?: return null
            val role = o.string("role") ?: return null
            return Recurrence(
                role = when (role) {
                    "proposal" -> Recurrence.Role.PROPOSAL
                    "occurrence" -> Recurrence.Role.OCCURRENCE
                    else -> Recurrence.Role.OTHER
                },
                repeat = o.string("repeat").orEmpty(),
                until = o.long("until"),
                next = (o["next"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.let { p -> p.longOrNull ?: p.doubleOrNull?.toLong() } }.orEmpty(),
            )
        }
    }
}

/** The answer to `inbox.accept`: the session the item opened in. */
@Serializable
data class AcceptResult(val sessionId: String)

/** The answer to `sessions.submitPrefill` (`inbox` design D8). */
@Serializable
data class SubmitResult(val outcome: String) {
    companion object {
        const val CONFIRMED = "confirmed"
        const val SENT = "sent"
    }
}
