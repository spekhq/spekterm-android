package com.spekterm.android.desktop

import com.spekterm.android.net.FileChunk
import com.spekterm.android.net.RemoteError

/** The files the agent sent the user: what kind each is, and how it is shown (`tool-call-display` D6, D9). */
object SentFiles {
    /** By the name's extension: the kinds the app shows itself, and [OTHER], handed to another app. */
    enum class Kind { IMAGE, MARKDOWN, TEXT, OTHER }

    /** How a downloaded file is shown. */
    enum class View { IMAGE, MARKDOWN, TEXT, HAND_OFF }

    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp")
    private val MARKDOWN = setOf("md", "markdown")
    private val TEXT = setOf("txt", "log", "json", "yaml", "yml", "csv")

    /** Largest file the phone downloads to show, per kind; [OTHER] up to the desktop's own limit. */
    fun limit(kind: Kind): Long = when (kind) {
        Kind.MARKDOWN -> 256L * 1024
        Kind.TEXT -> 1024L * 1024
        Kind.IMAGE -> 16L * 1024 * 1024
        Kind.OTHER -> 32L * 1024 * 1024
    }

    /** The picture types the app decodes itself; any other result picture is not shown (`tool-result-content` D2). */
    private val DECODABLE = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    fun decodable(mediaType: String): Boolean = mediaType.substringBefore(';').trim().lowercase() in DECODABLE

    fun kind(name: String): Kind {
        val base = ToolView.fileName(name)
        val dot = base.lastIndexOf('.')
        if (dot <= 0) return Kind.OTHER
        val ext = base.substring(dot + 1).lowercase()
        return when (ext) {
            in IMAGE -> Kind.IMAGE
            in MARKDOWN -> Kind.MARKDOWN
            in TEXT -> Kind.TEXT
            else -> Kind.OTHER
        }
    }

    /**
     * The view for a file of [kind] answered with [mediaType]: SVG and HTML are never rendered in the app (protocol
     * obligation 18); a type the app knows decides; anything else (`application/octet-stream` included) goes by [kind].
     */
    fun view(mediaType: String, kind: Kind): View {
        val type = mediaType.substringBefore(';').trim().lowercase()
        return when {
            type == "image/svg+xml" || type == "text/html" -> View.HAND_OFF
            type.startsWith("image/") -> View.IMAGE
            type == "text/markdown" -> View.MARKDOWN
            type.startsWith("text/") || type == "application/json" -> View.TEXT
            else -> when (kind) {
                Kind.IMAGE -> View.IMAGE
                Kind.MARKDOWN -> View.MARKDOWN
                Kind.TEXT -> View.TEXT
                Kind.OTHER -> View.HAND_OFF
            }
        }
    }
}

/** Reading one sent file, or one picture in a tool result, in chunks (`tool-call-display` D6, `tool-result-content` D1). */
object SentFileRead {
    /** The phone's chunk for a sent file: the same as `attachments.put`'s, whose timeout it shares. */
    const val CHUNK = 64 * 1024

    /** The chunk for a picture in a tool result: the desktop parses the whole record per chunk, so few and large. */
    const val PICTURE_CHUNK = 512 * 1024

    /** Why a file could not be read. [NO_IMAGE]: a result picture no longer in the conversation. */
    enum class Reason { NOT_SENT, GONE, CHANGED, TOO_LARGE, NOT_CONNECTED, SESSION_GONE, NO_IMAGE, OTHER }

    sealed interface Outcome {
        data class Done(val name: String, val mediaType: String, val size: Long) : Outcome

        /** [size]: the file's size, or the desktop's limit, for [Reason.TOO_LARGE]; [code]: the desktop's, for [Reason.OTHER]. */
        data class Failed(val reason: Reason, val size: Long? = null, val code: String? = null) : Outcome
    }

    /** Where the bytes go: memory for the kinds the app shows, a cache file for one handed to another app. */
    interface Sink {
        fun write(bytes: ByteArray)

        /** Drops what was written, for a restart or a failure. */
        fun reset()
    }

    /** One answer: the chunk, or the desktop's error; the whole answer `null` when the connection ended first. */
    data class Answer(val chunk: FileChunk?, val error: RemoteError?)

    /**
     * Reads from offset 0 until the chunk that completes the file. After the first answer a size over [limit] stops
     * the read. `FILE_CHANGED` starts again from 0 once. [request] is given the offset, the length to ask ([chunk]) and
     * the version to name.
     */
    suspend fun read(
        limit: Long,
        sink: Sink,
        progress: (Long, Long) -> Unit = { _, _ -> },
        chunk: Int = CHUNK,
        request: suspend (offset: Long, length: Int, version: String?) -> Answer?,
    ): Outcome {
        var restarts = 0
        while (true) {
            sink.reset()
            var offset = 0L
            var version: String? = null
            var size = -1L
            while (true) {
                val answer = request(offset, chunk, version) ?: return failed(sink, Reason.NOT_CONNECTED)
                val error = answer.error
                if (error != null) {
                    if (error.code == RemoteError.FILE_CHANGED && restarts++ == 0) break
                    return failed(sink, reasonOf(error.code), if (error.code == RemoteError.TOO_LARGE) error.detailLong("limit") else null, error.code)
                }
                val chunk = answer.chunk ?: return failed(sink, Reason.OTHER, code = MALFORMED)
                if (size < 0) {
                    size = chunk.size
                    version = chunk.version
                    if (size > limit) return failed(sink, Reason.TOO_LARGE, size)
                }
                if (chunk.offset != offset || chunk.size != size || offset + chunk.bytes.size > size) return failed(sink, Reason.OTHER, code = MALFORMED)
                sink.write(chunk.bytes)
                offset += chunk.bytes.size
                progress(offset, size)
                if (chunk.complete || offset == size) {
                    if (offset != size) return failed(sink, Reason.OTHER, code = MALFORMED)
                    return Outcome.Done(chunk.name, chunk.mediaType, size)
                }
                if (chunk.bytes.isEmpty()) return failed(sink, Reason.OTHER, code = MALFORMED)
            }
        }
    }

    const val MALFORMED = "MALFORMED"

    private fun failed(sink: Sink, reason: Reason, size: Long? = null, code: String? = null): Outcome {
        sink.reset()
        return Outcome.Failed(reason, size, code)
    }

    fun reasonOf(code: String): Reason = when (code) {
        RemoteError.FILE_NOT_SENT -> Reason.NOT_SENT
        RemoteError.FILE_GONE -> Reason.GONE
        RemoteError.FILE_CHANGED -> Reason.CHANGED
        RemoteError.TOO_LARGE -> Reason.TOO_LARGE
        RemoteError.NOT_FOUND -> Reason.SESSION_GONE
        RemoteError.NO_IMAGE -> Reason.NO_IMAGE
        else -> Reason.OTHER
    }
}

/** The bytes in memory, for a file the app shows itself. */
class MemorySink : SentFileRead.Sink {
    private var out = java.io.ByteArrayOutputStream()

    override fun write(bytes: ByteArray) = out.write(bytes)

    override fun reset() {
        out = java.io.ByteArrayOutputStream()
    }

    fun bytes(): ByteArray = out.toByteArray()
}

/**
 * The bytes in a file under [root] (the app's `cacheDir/sent`), for a file handed to another app (design D9): one new
 * directory per read, the file named by the base name of [name] only. [reset] deletes it.
 */
class CacheSink(private val root: java.io.File, name: String) : SentFileRead.Sink {
    val file: java.io.File = java.io.File(java.io.File(root, java.util.UUID.randomUUID().toString()), safeName(name))
    private var out: java.io.OutputStream? = null

    override fun write(bytes: ByteArray) {
        val o = out ?: run {
            file.parentFile!!.mkdirs()
            java.io.FileOutputStream(file).also { out = it }
        }
        o.write(bytes)
    }

    /** Ends writing; the file stays for the other app. */
    fun close() {
        out?.close()
        out = null
    }

    override fun reset() {
        close()
        file.parentFile?.deleteRecursively()
    }

    companion object {
        /** The last segment, without separators or a leading dot; `file` when nothing is left. */
        fun safeName(name: String): String =
            name.replace('\\', '/').substringAfterLast('/').trim().trimStart('.').filter { it >= ' ' }.ifEmpty { "file" }

        /** Everything handed off so far: on leaving a conversation, at app start, on unpairing. */
        fun clear(root: java.io.File) {
            root.deleteRecursively()
        }
    }
}
