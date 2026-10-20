package com.spekterm.android.service

import org.junit.Assert.assertEquals
import org.junit.Test

class EventRouteTest {
    @Test
    fun eachKnownKindHasItsRoute() {
        assertEquals(EventRoute.ATTENTION, EventRoute.of("attention"))
        assertEquals(EventRoute.ADDRESSES, EventRoute.of("addresses.changed"))
        for (kind in listOf("sessions.changed", "conversation.update", "conversation.wait", "conversation.ended", "conversation.status", "conversation.skills", "openspec.changed", "handoffs.changed", "inbox.changed")) {
            assertEquals(kind, EventRoute.DESKTOP, EventRoute.of(kind))
        }
    }

    @Test
    fun anUnknownKindIsIgnored() {
        for (kind in listOf("terminal.data", "something.new", "")) {
            assertEquals(kind, EventRoute.IGNORED, EventRoute.of(kind))
        }
    }
}
