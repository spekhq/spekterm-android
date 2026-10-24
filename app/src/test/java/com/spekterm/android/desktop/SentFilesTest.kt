package com.spekterm.android.desktop

import com.spekterm.android.desktop.SentFileRead.Answer
import com.spekterm.android.desktop.SentFileRead.Outcome
import com.spekterm.android.desktop.SentFileRead.Reason
import com.spekterm.android.net.FileChunk
import com.spekterm.android.net.RemoteError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `SentFileRead.read` and its sinks (`tool-call-display` D6, D9). */
class SentFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A desktop serving [content] in chunks of the requested length; [version] may change between requests. */
    private class Fake(val content: ByteArray, val mediaType: String = "text/plain", var version: () -> String = { "v1" }) {
        val requests = mutableListOf<Pair<Long, String?>>()

        fun answer(offset: Long, named: String?): Answer {
            requests += offset to named
            val current = version()
            if (named != null && named != current) return Answer(null, RemoteError(RemoteError.FILE_CHANGED))
            val end = minOf(content.size.toLong(), offset + SentFileRead.CHUNK).toInt()
            val bytes = content.copyOfRange(offset.toInt(), end)
            return Answer(FileChunk("f", content.size.toLong(), mediaType, current, offset, bytes, end == content.size), null)
        }
    }

    private fun error(code: String, limit: Long? = null) =
        Answer(null, RemoteError(code, detail = limit?.let { buildJsonObject { put("limit", JsonPrimitive(it)) } }))

    @Test
    fun aFileArrivesWholeAcrossChunks() = runBlocking {
        val content = ByteArray(1_228_800) { (it % 251).toByte() }
        val fake = Fake(content)
        val sink = MemorySink()
        var last = 0L
        val outcome = SentFileRead.read(SentFiles.limit(SentFiles.Kind.TEXT) * 2, sink, { done, _ -> last = done }) { o, v -> fake.answer(o, v) }
        assertEquals(Outcome.Done("f", "text/plain", content.size.toLong()), outcome)
        assertArrayEquals(content, sink.bytes())
        assertEquals(19, fake.requests.size)
        assertEquals(null, fake.requests[0].second)
        assertTrue(fake.requests.drop(1).all { it.second == "v1" })
        assertEquals(content.size.toLong(), last)
    }

    @Test
    fun tooLargeForItsKindStopsAfterTheFirstChunk() = runBlocking {
        for ((kind, size) in listOf(SentFiles.Kind.MARKDOWN to 300 * 1024, SentFiles.Kind.TEXT to 2 * 1024 * 1024)) {
            val fake = Fake(ByteArray(size))
            val outcome = SentFileRead.read(SentFiles.limit(kind), MemorySink()) { o, v -> fake.answer(o, v) }
            assertEquals(Outcome.Failed(Reason.TOO_LARGE, size.toLong()), outcome)
            assertEquals(1, fake.requests.size)
        }
    }

    @Test
    fun aChangedFileRestartsOnce() = runBlocking {
        var n = 0
        val once = Fake(ByteArray(200_000)) { if (n++ < 2) "v1" else "v2" }
        val sink = MemorySink()
        assertTrue(SentFileRead.read(1L shl 20, sink) { o, v -> once.answer(o, v) } is Outcome.Done)
        assertEquals(200_000, sink.bytes().size)
        var m = 0
        val always = Fake(ByteArray(200_000)) { "v${m++}" }
        assertEquals(Outcome.Failed(Reason.CHANGED, code = RemoteError.FILE_CHANGED), SentFileRead.read(1L shl 20, MemorySink()) { o, v -> always.answer(o, v) })
    }

    @Test
    fun errorsMapToTheirReasons() = runBlocking {
        suspend fun read(answer: Answer?) = SentFileRead.read(1L shl 20, MemorySink()) { _, _ -> answer }
        assertEquals(Outcome.Failed(Reason.NOT_SENT, code = RemoteError.FILE_NOT_SENT), read(error(RemoteError.FILE_NOT_SENT)))
        assertEquals(Outcome.Failed(Reason.GONE, code = RemoteError.FILE_GONE), read(error(RemoteError.FILE_GONE)))
        assertEquals(Outcome.Failed(Reason.TOO_LARGE, 33_554_432, RemoteError.TOO_LARGE), read(error(RemoteError.TOO_LARGE, 33_554_432)))
        assertEquals(Outcome.Failed(Reason.SESSION_GONE, code = RemoteError.NOT_FOUND), read(error(RemoteError.NOT_FOUND)))
        assertEquals(Outcome.Failed(Reason.NOT_CONNECTED), read(null))
        for (code in listOf(RemoteError.NO_CONVERSATION, RemoteError.BAD_PARAMS, "LATER")) assertEquals(Outcome.Failed(Reason.OTHER, code = code), read(error(code)))
        assertEquals(Reason.OTHER, (read(Answer(null, null)) as Outcome.Failed).reason)
    }

    @Test
    fun aDisconnectMidwayDropsWhatWasRead() = runBlocking {
        val fake = Fake(ByteArray(200_000))
        val sink = MemorySink()
        var calls = 0
        val outcome = SentFileRead.read(1L shl 20, sink) { o, v -> if (calls++ < 2) fake.answer(o, v) else null }
        assertEquals(Outcome.Failed(Reason.NOT_CONNECTED), outcome)
        assertEquals(0, sink.bytes().size)
    }

    @Test
    fun cancellingStopsFurtherRequests() = runBlocking {
        val fake = Fake(ByteArray(300_000))
        var calls = 0
        val result = runCatching {
            SentFileRead.read(1L shl 20, MemorySink()) { o, v -> if (++calls == 2) throw CancellationException("left") else fake.answer(o, v) }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(2, calls)
    }

    @Test
    fun aHandedOffFileIsWrittenWholeAndACancelLeavesNothing() = runBlocking {
        val root = tmp.newFolder("sent")
        val content = ByteArray(1_228_800) { it.toByte() }
        val fake = Fake(content, "video/mp4")
        val sink = CacheSink(root, "../../reel.mp4")
        assertTrue(SentFileRead.read(SentFiles.limit(SentFiles.Kind.OTHER), sink) { o, v -> fake.answer(o, v) } is Outcome.Done)
        sink.close()
        assertEquals("reel.mp4", sink.file.name)
        assertEquals(root, sink.file.parentFile!!.parentFile)
        assertArrayEquals(content, sink.file.readBytes())
        val cancelled = CacheSink(root, "x.zip")
        cancelled.write(byteArrayOf(1, 2))
        cancelled.reset()
        assertFalse(cancelled.file.exists())
        assertFalse(cancelled.file.parentFile!!.exists())
        CacheSink.clear(root)
        assertFalse(root.exists())
    }

    @Test
    fun safeNames() {
        assertEquals("reel.mp4", CacheSink.safeName("../../reel.mp4"))
        assertEquals("a.zip", CacheSink.safeName("C:\\x\\a.zip"))
        assertEquals("env", CacheSink.safeName("/x/.env"))
        assertEquals("file", CacheSink.safeName("/x/"))
    }
}
