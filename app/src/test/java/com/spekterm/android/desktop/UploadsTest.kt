package com.spekterm.android.desktop

import com.spekterm.android.net.PutResult
import com.spekterm.android.net.RemoteError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Uploads.next` (design D6). */
class UploadsTest {
    private val k = Uploads.CHUNK.toLong()
    private val size = 3 * k
    private fun ok(id: String, received: Long, complete: Boolean = false) = Uploads.Answer.Ok(PutResult(id, received, complete))
    private fun err(code: String, vararg detail: Pair<String, Long>) =
        Uploads.Answer.Error(RemoteError(code, "", JsonObject(detail.associate { it.first to JsonPrimitive(it.second) })))
    private fun next(up: AttachmentState.Uploading, answer: Uploads.Answer, length: Int = Uploads.CHUNK) = Uploads.next(up, size, length, answer, 100)

    @Test
    fun chunkLengths() {
        assertEquals(1, Uploads.chunkLength(1, 0))
        assertEquals(Uploads.CHUNK, Uploads.chunkLength(k, 0))
        assertEquals(Uploads.CHUNK, Uploads.chunkLength(k + 1, 0))
        assertEquals(1, Uploads.chunkLength(k + 1, k))
    }

    @Test
    fun continuesAndCompletes() {
        assertEquals(AttachmentState.Uploading("a1", k), next(AttachmentState.Uploading(), ok("a1", k)))
        assertEquals(AttachmentState.Ready("a1", 100), next(AttachmentState.Uploading("a1", 2 * k), ok("a1", size, complete = true)))
    }

    @Test
    fun resumesFromWhereTheDesktopSaysUpToThreeTimesWithoutProgress() {
        val up = AttachmentState.Uploading("a1", k)
        assertEquals(AttachmentState.Uploading("a1", 2 * k), next(up, err(RemoteError.BAD_PARAMS, "received" to 2 * k)))
        var s: AttachmentState = up
        repeat(2) { s = next(s as AttachmentState.Uploading, err(RemoteError.BAD_PARAMS, "received" to k)) }
        assertTrue(s is AttachmentState.Uploading)
        assertEquals(AttachmentState.Failed(RemoteError.BAD_PARAMS), next(s as AttachmentState.Uploading, err(RemoteError.BAD_PARAMS, "received" to k)))
        assertEquals(AttachmentState.Failed(RemoteError.BAD_PARAMS), next(up, err(RemoteError.BAD_PARAMS)))
    }

    @Test
    fun notFoundAfterAnUnansweredLastChunkIsPresumedReady() {
        val last = AttachmentState.Uploading("a1", 2 * k)
        val unanswered = next(last, Uploads.Answer.None) as AttachmentState.Uploading
        assertTrue(unanswered.lastUnanswered)
        assertEquals(AttachmentState.Ready("a1", 100, presumed = true), next(unanswered, err(RemoteError.NOT_FOUND)))
    }

    @Test
    fun notFoundOtherwiseRestartsOnceThenFails() {
        val restarted = next(AttachmentState.Uploading("a1", k), err(RemoteError.NOT_FOUND))
        assertEquals(AttachmentState.Uploading(restarted = true), restarted)
        val again = next(AttachmentState.Uploading("a2", k, restarted = true), err(RemoteError.NOT_FOUND))
        assertEquals(AttachmentState.Failed(Uploads.GONE), again)
        assertEquals(AttachmentState.Failed(RemoteError.NOT_FOUND), next(AttachmentState.Uploading(), err(RemoteError.NOT_FOUND)))
    }

    @Test
    fun refusalsCarryTheirLimit() {
        assertEquals(AttachmentState.Failed(RemoteError.TOO_MANY, 10), next(AttachmentState.Uploading(), err(RemoteError.TOO_MANY, "limit" to 10)))
        assertEquals(AttachmentState.Failed(RemoteError.TOO_LARGE, 10485760), next(AttachmentState.Uploading(), err(RemoteError.TOO_LARGE, "limit" to 10485760)))
        assertEquals(AttachmentState.Failed(RemoteError.UNSUPPORTED_TYPE), next(AttachmentState.Uploading("a", 2 * k), err(RemoteError.UNSUPPORTED_TYPE)))
        assertEquals(AttachmentState.Failed(RemoteError.NO_CONVERSATION), next(AttachmentState.Uploading(), err(RemoteError.NO_CONVERSATION)))
    }

    @Test
    fun threeUnansweredAtOneOffsetFail() {
        var s = next(AttachmentState.Uploading("a1", k), Uploads.Answer.None) as AttachmentState.Uploading
        assertFalse(s.lastUnanswered)
        s = next(s, Uploads.Answer.None) as AttachmentState.Uploading
        assertEquals(AttachmentState.Failed(Uploads.NO_ANSWER), next(s, Uploads.Answer.None))
        // Progress resets the count.
        val progressed = next(s, ok("a1", 2 * k)) as AttachmentState.Uploading
        assertEquals(0, progressed.unanswered)
    }

    @Test
    fun doubtfulAttachments() {
        assertTrue(Uploads.doubtful(Attachment(1, "a", state = AttachmentState.Ready("x", 0, presumed = true)), 1))
        assertTrue(Uploads.doubtful(Attachment(1, "a", state = AttachmentState.Ready("x", 0)), Uploads.DOUBT_AFTER_MS + 1))
        assertFalse(Uploads.doubtful(Attachment(1, "a", state = AttachmentState.Ready("x", 0)), 1000))
    }
}
