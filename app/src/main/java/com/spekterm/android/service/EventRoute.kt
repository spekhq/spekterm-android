package com.spekterm.android.service

/**
 * Where the service sends an event of the authenticated connection (`addresses-and-mid-turn-replies` D4). A kind
 * the app does not know is ignored, never an error (`docs/remote-protocol.md` section 9, obligation 7).
 */
enum class EventRoute {
    ATTENTION,
    ADDRESSES,
    DESKTOP,
    IGNORED;

    companion object {
        private val DESKTOP_KINDS = setOf("sessions.changed", "conversation.update", "conversation.wait", "conversation.ended", "openspec.changed", "handoffs.changed", "inbox.changed")

        fun of(kind: String): EventRoute = when (kind) {
            "attention" -> ATTENTION
            "addresses.changed" -> ADDRESSES
            in DESKTOP_KINDS -> DESKTOP
            else -> IGNORED
        }
    }
}
