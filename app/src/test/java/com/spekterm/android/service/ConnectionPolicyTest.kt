package com.spekterm.android.service

import com.spekterm.android.net.RemoteError
import com.spekterm.android.service.ConnectionPolicy.Decision
import com.spekterm.android.service.ConnectionPolicy.UpdateHint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every row of the `desktop-connection` close-code requirement. */
class ConnectionPolicyTest {
    private fun error(code: String, supported: List<Int>? = null) = RemoteError(
        code,
        "",
        supported?.let { JsonObject(mapOf("supported" to JsonArray(it.map { v -> JsonPrimitive(v) }))) },
    )

    @Test
    fun revokedUnpairs() {
        assertEquals(Decision.Unpair, ConnectionPolicy.decide(4004, null))
    }

    @Test
    fun authFailedUnpairs() {
        // `hello` answered AUTH_FAILED, then closed with 4003 a second later.
        assertEquals(Decision.Unpair, ConnectionPolicy.decide(4003, error(RemoteError.AUTH_FAILED)))
    }

    @Test
    fun unsupportedVersionStopsWithAHint() {
        assertEquals(Decision.Unsupported(UpdateHint.UPDATE_APP), ConnectionPolicy.decide(4003, error(RemoteError.UNSUPPORTED_VERSION, listOf(2, 3))))
        assertEquals(UpdateHint.UPDATE_DESKTOP, ConnectionPolicy.updateHint(listOf(0), ours = 1))
        assertEquals(UpdateHint.UNKNOWN, ConnectionPolicy.updateHint(emptyList()))
    }

    @Test
    fun remoteOffOrQuitRetries() {
        assertEquals(Decision.Retry(remoteOff = true), ConnectionPolicy.decide(4005, null))
    }

    @Test
    fun throttledWaits() {
        assertEquals(Decision.Retry(throttled = true), ConnectionPolicy.decide(4030, null))
        assertEquals(Decision.Retry(throttled = true), ConnectionPolicy.decide(4030, error(RemoteError.THROTTLED)))
        assertEquals(Decision.Retry(throttled = true), ConnectionPolicy.decide(null, error(RemoteError.THROTTLED)))
    }

    @Test
    fun everythingElseRetriesWithBackoff() {
        for (code in listOf(4029, 4001, 4003, 1000, 1006, null)) {
            assertEquals("code $code", Decision.Retry(), ConnectionPolicy.decide(code, null))
        }
        assertEquals(Decision.Retry(), ConnectionPolicy.decide(4003, error(RemoteError.BAD_FRAME)))
    }
}
