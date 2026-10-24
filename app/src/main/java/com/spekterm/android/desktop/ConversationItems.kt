package com.spekterm.android.desktop

import com.spekterm.android.net.Entry
import com.spekterm.android.net.Origin

/**
 * What the conversation screen shows for its entries: a tool call with its result, runs of tool calls summarized,
 * everything else as it is (`ui-polish` D7). Each item carries the anchors of the user's settled replies that go
 * right after it, so a reply stays where it was sent and never lands inside a run.
 */
object ConversationItems {
    sealed interface Shown {
        val key: String

        /** Anchor keys whose replies are shown right after this item, in entry order. */
        val anchorsAfter: List<String>
    }

    /** A part of a [Run]: a call, or the thinking or a background task's notice between two calls. */
    sealed interface Part {
        val key: String
    }

    data class Call(override val key: String, val tool: Entry.Tool, val result: Entry.Result?, override val anchorsAfter: List<String> = emptyList()) : Shown, Part {
        val failed: Boolean get() = result?.error == true
    }

    data class Thinking(override val key: String, val entry: Entry.Thinking) : Part

    /** A background task's notice that arrived between two calls (`tool-call-display` D8). */
    data class Notice(override val key: String, val entry: Entry.User) : Part

    data class LoneResult(override val key: String, val result: Entry.Result, override val anchorsAfter: List<String> = emptyList()) : Shown

    /** Two or more calls in a row; its key is its first call's, so it stays as the run grows. */
    data class Run(override val key: String, val parts: List<Part>, override val anchorsAfter: List<String> = emptyList()) : Shown {
        val calls: List<Call> get() = parts.filterIsInstance<Call>()
        val failed: Int get() = calls.count { it.failed }
        val latest: Call get() = calls.last()
    }

    /**
     * Something exchanged between parties, shown as a chat message (`tool-call-display` D11): a peer's message ([entry]
     * a user entry), or the agent's SendMessage or SendUserFile ([call] and its [result]). [to] is set for a message
     * the agent sent; [files] for files it sent.
     */
    data class Message(
        override val key: String,
        val from: Messages.Who,
        val to: Messages.Who?,
        val body: String,
        val markdown: Boolean,
        val at: Long,
        val call: Entry.Tool? = null,
        val result: Entry.Result? = null,
        val files: List<String> = emptyList(),
        override val anchorsAfter: List<String> = emptyList(),
    ) : Shown {
        val status: Messages.Status? get() = when {
            call?.name == SEND_MESSAGE -> Messages.status(result)
            result?.error == true -> Messages.Status.NotSent(result.text.takeIf { it.isNotBlank() })
            else -> null
        }
    }

    const val SEND_MESSAGE = "SendMessage"
    const val SEND_FILES = "SendUserFile"

    /** A call shown as a message: SendMessage, and SendUserFile with files, when the desktop sent their input. */
    private fun message(key: String, tool: Entry.Tool, result: Entry.Result?): Message? = when {
        tool.input.isEmpty -> null
        tool.name == SEND_MESSAGE -> Message(
            key, Messages.Who(Messages.Kind.SELF, null), Messages.recipient(tool.input) ?: Messages.Who(Messages.Kind.SESSION, null),
            tool.input.string("message") ?: "", markdown = false, at = tool.at, call = tool, result = result,
        )
        tool.name == SEND_FILES && !tool.input.strings("files").isNullOrEmpty() -> Message(
            key, Messages.Who(Messages.Kind.SELF, null), null, tool.input.string("caption") ?: "", markdown = false, at = tool.at,
            call = tool, result = result, files = tool.input.strings("files")!!,
        )
        else -> null
    }

    /** Any other entry: a user or injected message, the agent's text, thinking outside a run. */
    data class Plain(override val key: String, val entry: Entry, override val anchorsAfter: List<String> = emptyList()) : Shown

    /**
     * [entries] with their [keys] (`ConversationReducer.keys`) as shown items. [anchors] are the anchor keys of the
     * settled replies: reaching one ends what is being formed, and the anchor goes after the last item shown.
     */
    fun group(entries: List<Entry>, keys: List<String>, anchors: Set<String>): List<Shown> {
        val results = pair(entries)
        val consumed = results.values.toSet()
        val out = mutableListOf<Shown>()
        var forming: MutableList<Part>? = null
        // Thinking and task notices after a call: joined to the run when another call follows, else shown on their own.
        val held = mutableListOf<Part>()

        fun emit(item: Shown) {
            out += item
        }

        fun close() {
            forming?.let { parts ->
                val first = parts.first() as Call
                emit(if (parts.size == 1) first else Run(first.key, parts.toList()))
            }
            forming = null
            held.forEach {
                when (it) {
                    is Thinking -> emit(Plain(it.key, it.entry))
                    is Notice -> emit(Plain(it.key, it.entry))
                    is Call -> Unit
                }
            }
            held.clear()
        }

        entries.forEachIndexed { i, entry ->
            val key = keys[i]
            when {
                entry is Entry.Tool && message(key, entry, results[i]?.let { entries[it] as Entry.Result }) != null -> {
                    close()
                    emit(message(key, entry, results[i]?.let { entries[it] as Entry.Result })!!)
                }
                entry is Entry.User && entry.origin is Origin.Peer -> {
                    close()
                    val origin = entry.origin as Origin.Peer
                    emit(Message(key, Messages.sender(origin), null, PeerText.strip(entry.text), markdown = true, at = entry.at))
                }
                entry is Entry.Tool -> {
                    val call = Call(key, entry, results[i]?.let { entries[it] as Entry.Result })
                    val parts = forming
                    if (parts == null) {
                        forming = mutableListOf(call)
                    } else {
                        parts += held
                        held.clear()
                        parts += call
                    }
                }
                entry is Entry.Thinking && forming != null -> held += Thinking(key, entry)
                entry is Entry.User && entry.origin == Origin.TaskNotice && forming != null -> held += Notice(key, entry)
                entry is Entry.Result && i in consumed -> Unit
                // The CLI's note to the model about a picture it scaled: not shown, and the run goes on (`tool-result-content` D5).
                entry is Entry.User && isPictureNote(entry) -> Unit
                entry is Entry.Result -> {
                    close()
                    emit(LoneResult(key, entry))
                }
                else -> {
                    close()
                    emit(Plain(key, entry))
                }
            }
            if (key in anchors) {
                close()
                out.lastOrNull()?.let { out[out.lastIndex] = withAnchor(it, key) }
            }
        }
        close()
        return out
    }

    private val PICTURE_NOTE =
        Regex("""^\[Image: original \d+x\d+, displayed at \d+x\d+\. Multiply coordinates by [\d.]+ to map to original image\.]$""")

    fun isPictureNote(entry: Entry.User): Boolean = entry.meta && entry.origin == null && PICTURE_NOTE.matches(entry.text)

    /** Call index → result index: each result with the earliest earlier call of its id still without one. */
    private fun pair(entries: List<Entry>): Map<Int, Int> {
        val open = HashMap<String, ArrayDeque<Int>>()
        val out = HashMap<Int, Int>()
        entries.forEachIndexed { i, entry ->
            when (entry) {
                is Entry.Tool -> if (entry.id.isNotEmpty()) open.getOrPut(entry.id) { ArrayDeque() }.addLast(i)
                is Entry.Result -> if (entry.id.isNotEmpty()) open[entry.id]?.removeFirstOrNull()?.let { out[it] = i }
                else -> Unit
            }
        }
        return out
    }

    private fun withAnchor(item: Shown, anchor: String): Shown = when (item) {
        is Call -> item.copy(anchorsAfter = item.anchorsAfter + anchor)
        is LoneResult -> item.copy(anchorsAfter = item.anchorsAfter + anchor)
        is Run -> item.copy(anchorsAfter = item.anchorsAfter + anchor)
        is Plain -> item.copy(anchorsAfter = item.anchorsAfter + anchor)
        is Message -> item.copy(anchorsAfter = item.anchorsAfter + anchor)
    }
}
