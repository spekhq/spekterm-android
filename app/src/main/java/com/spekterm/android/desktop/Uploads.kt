package com.spekterm.android.desktop

import com.spekterm.android.net.PutResult
import com.spekterm.android.net.RemoteError
import java.io.File

/**
 * A photo or file attached to the reply being written (design D6). [file] is the app's own copy, set once
 * prepared; [notFoundSends] counts the sends refused `NOT_FOUND` it was part of (design D7).
 */
data class Attachment(
    val localId: Long,
    val name: String,
    val size: Long = 0,
    val file: File? = null,
    val converted: Boolean = false,
    val state: AttachmentState = AttachmentState.Preparing,
    val notFoundSends: Int = 0,
) {
    val ready: AttachmentState.Ready? get() = state as? AttachmentState.Ready
}

sealed interface AttachmentState {
    data object Preparing : AttachmentState

    data object Queued : AttachmentState

    /**
     * [received] is where the next chunk starts. [unanswered] counts chunks in a row at this offset that got no
     * answer; [resumes] the `BAD_PARAMS` resumes in a row without progress; [restarted] whether it already started
     * again after the desktop lost it; [lastUnanswered] whether the last attempt was the final chunk and got no
     * answer.
     */
    data class Uploading(
        val attachmentId: String? = null,
        val received: Long = 0,
        val unanswered: Int = 0,
        val resumes: Int = 0,
        val restarted: Boolean = false,
        val lastUnanswered: Boolean = false,
    ) : AttachmentState

    /** [presumed]: taken as complete after an unanswered last chunk; [completedAt] when the phone learned it. */
    data class Ready(val attachmentId: String, val completedAt: Long, val presumed: Boolean = false) : AttachmentState

    /** [code] is the desktop's, or one of [Uploads]' own; [limit] the limit the desktop names. */
    data class Failed(val code: String, val limit: Long? = null) : AttachmentState
}

/** The pure rules of uploading in chunks (design D6). */
object Uploads {
    /** 64 KiB decoded: about 14 s at 50 kbit/s, within the 30 s call timeout and between the desktop's pings. */
    const val CHUNK = 64 * 1024

    const val MAX_FILE = 10L * 1024 * 1024

    /** The desktop's unsent limit per session, and so the phone's per reply. */
    const val MAX_ATTACHMENTS = 10

    const val MAX_UNANSWERED = 3
    const val MAX_RESUMES = 3

    /** The desktop removes unsent attachments after 24 h; one completed longer ago than this is doubted. */
    const val DOUBT_AFTER_MS = 23L * 60 * 60 * 1000

    // The phone's own failure codes.
    const val FILE = "FILE"
    const val GONE = "GONE"
    const val NO_ANSWER = "NO_ANSWER"

    sealed interface Answer {
        data class Ok(val result: PutResult) : Answer

        data class Error(val error: RemoteError) : Answer

        /** The connection ended, or the call timed out (which ends it). */
        data object None : Answer
    }

    /** The length of the chunk starting at [received] of a file of [size] bytes. */
    fun chunkLength(size: Long, received: Long): Int = minOf(CHUNK.toLong(), (size - received).coerceAtLeast(0)).toInt()

    /** The state after the desktop answered [answer] to the chunk of [length] bytes sent from [up]. */
    fun next(up: AttachmentState.Uploading, size: Long, length: Int, answer: Answer, now: Long): AttachmentState = when (answer) {
        is Answer.Ok -> {
            val r = answer.result
            when {
                r.complete -> AttachmentState.Ready(r.attachmentId, now)
                r.received > up.received -> AttachmentState.Uploading(r.attachmentId, r.received, restarted = up.restarted)
                // No progress: count it like a resume, so a desktop that never moves does not loop forever.
                up.resumes + 1 >= MAX_RESUMES -> AttachmentState.Failed(RemoteError.BAD_PARAMS)
                else -> up.copy(attachmentId = r.attachmentId, received = r.received, resumes = up.resumes + 1, unanswered = 0, lastUnanswered = false)
            }
        }
        is Answer.Error -> error(up, answer.error, now)
        Answer.None -> when {
            up.unanswered + 1 >= MAX_UNANSWERED -> AttachmentState.Failed(NO_ANSWER)
            else -> up.copy(unanswered = up.unanswered + 1, lastUnanswered = up.received + length >= size)
        }
    }

    private fun error(up: AttachmentState.Uploading, e: RemoteError, now: Long): AttachmentState = when (e.code) {
        RemoteError.BAD_PARAMS -> {
            val at = e.detailLong("received")
            when {
                at == null || at < 0 || up.attachmentId == null -> AttachmentState.Failed(RemoteError.BAD_PARAMS)
                at > up.received -> up.copy(received = at, resumes = 0, unanswered = 0, lastUnanswered = false)
                up.resumes + 1 >= MAX_RESUMES -> AttachmentState.Failed(RemoteError.BAD_PARAMS)
                else -> up.copy(received = at, resumes = up.resumes + 1, unanswered = 0, lastUnanswered = false)
            }
        }
        RemoteError.NOT_FOUND -> when {
            up.attachmentId == null -> AttachmentState.Failed(RemoteError.NOT_FOUND)
            // The desktop most likely completed it and forgot the id when the answer to the last chunk was lost.
            up.lastUnanswered -> AttachmentState.Ready(up.attachmentId, now, presumed = true)
            !up.restarted -> AttachmentState.Uploading(restarted = true)
            else -> AttachmentState.Failed(GONE)
        }
        else -> AttachmentState.Failed(e.code, e.detailLong("limit"))
    }

    /** Whether [a] may no longer be on the desktop: presumed complete, or completed close to the 24 h expiry. */
    fun doubtful(a: Attachment, now: Long): Boolean {
        val ready = a.ready ?: return false
        return ready.presumed || now - ready.completedAt > DOUBT_AFTER_MS
    }
}

/** What the composer says about attaching (design D5, D7). */
enum class AttachNote {
    /** More picked than the reply has room for; the rest were dropped. */
    TOO_MANY_PICKED,

    /** Not a photo, PDF or text file. */
    KIND,

    /** Larger than the desktop's 10 MB limit. */
    SIZE,

    EMPTY,

    /** The picked file could not be read or converted. */
    UNREADABLE,

    /** A send was refused because the session or an attachment was not found. */
    SEND_NOT_FOUND,
}
