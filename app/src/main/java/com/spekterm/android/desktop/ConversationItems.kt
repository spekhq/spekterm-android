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

    /**
     * The agent CLI's answer to a built-in command, recorded as a user entry wrapped in `<local-command-stdout>`
     * (`session-model-effort` D8): [text] is what is between the markers, without terminal escape sequences.
     */
    data class CommandOutput(override val key: String, val text: String, val entry: Entry.User, override val anchorsAfter: List<String> = emptyList()) : Shown

    /**
     * A command the user ran in the agent CLI's shell mode, from the phone or the desktop, recorded as a user entry
     * wrapped in `<bash-input>` (`run-shell-commands` D5): [command] is what is between the markers, untouched.
     */
    data class ShellCommand(override val key: String, val command: String, val entry: Entry.User, override val anchorsAfter: List<String> = emptyList()) : Shown

    /**
     * The output of a shell-mode command (D5). [stderr] not empty means it failed; [persisted]: the CLI kept only a
     * preview, the full output being saved on the desktop.
     */
    data class ShellOutput(
        override val key: String,
        val stdout: String,
        val stderr: String,
        val persisted: Boolean,
        val entry: Entry.User,
        override val anchorsAfter: List<String> = emptyList(),
    ) : Shown {
        val failed: Boolean get() = stderr.isNotEmpty()
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
        // Anchors of entries not shown before anything was: carried by the next item.
        val carried = mutableListOf<String>()

        fun emit(item: Shown) {
            out += carried.fold(item, ::withAnchor)
            carried.clear()
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
                // The CLI's caveat before a built-in command: not shown, and it ends a run as the injected entry it is (D8).
                entry is Entry.User && isCommandCaveat(entry) -> close()
                entry is Entry.User && commandOutput(entry) != null -> {
                    close()
                    emit(CommandOutput(key, commandOutput(entry)!!, entry))
                }
                entry is Entry.User && shellCommand(entry) != null -> {
                    close()
                    emit(ShellCommand(key, shellCommand(entry)!!, entry))
                }
                entry is Entry.User && shellOutput(key, entry) != null -> {
                    close()
                    emit(shellOutput(key, entry)!!)
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
                if (out.isEmpty()) carried += key else out[out.lastIndex] = withAnchor(out.last(), key)
            }
        }
        close()
        return out
    }

    /** What full screen swipes through (`tool-result-content` D8). */
    sealed interface ViewerSource {
        data class Call(val tool: Entry.Tool, val result: Entry.Result) : ViewerSource

        data class Lone(val result: Entry.Result) : ViewerSource

        data class Sent(val message: Message) : ViewerSource
    }

    /**
     * In conversation order: each call or lone result whose result has pictures, and each message with files when the
     * desktop sends them. A result already given by its id is not given again.
     */
    fun viewerSources(items: List<Shown>, imagesReadable: Boolean, canReadFiles: Boolean): List<ViewerSource> {
        val out = mutableListOf<ViewerSource>()
        val given = HashSet<String>()
        fun result(tool: Entry.Tool?, result: Entry.Result?) {
            if (result == null || ToolView.pictures(result, imagesReadable).isEmpty() || !given.add("r:${result.id}")) return
            out += if (tool != null) ViewerSource.Call(tool, result) else ViewerSource.Lone(result)
        }
        for (item in items) {
            when (item) {
                is Call -> result(item.tool, item.result)
                is Run -> item.calls.forEach { result(it.tool, it.result) }
                is LoneResult -> result(null, item.result)
                is Message -> {
                    val id = item.call?.id
                    if (canReadFiles && id != null && item.files.isNotEmpty() && given.add("s:$id")) out += ViewerSource.Sent(item)
                }
                is Plain, is CommandOutput, is ShellCommand, is ShellOutput -> Unit
            }
        }
        return out
    }

    private val PICTURE_NOTE =
        Regex("""^\[Image: original \d+x\d+, displayed at \d+x\d+\. Multiply coordinates by [\d.]+ to map to original image\.]$""")

    fun isPictureNote(entry: Entry.User): Boolean = entry.meta && entry.origin == null && PICTURE_NOTE.matches(entry.text)

    /** One whole wrapper of [tag] around the text, holding no other marker: what is inside, or null (D8). */
    private fun wrapped(text: String, vararg tags: String): String? {
        val t = Replies.jsTrim(text)
        val tag = tags.firstOrNull { t.length >= 2 * it.length + 5 && t.startsWith("<$it>") && t.endsWith("</$it>") } ?: return null
        return t.substring(tag.length + 2, t.length - tag.length - 3).takeUnless { COMMAND_MARKER in it }
    }

    private const val COMMAND_MARKER = "local-command-"

    /** CSI sequences, then any other escape and the character after it. */
    private val ESCAPE = Regex("\u001b\\[[0-?]*[ -/]*[@-~]|\u001b[\\s\\S]?")

    /** The text of a built-in command's output, or null: one whole wrapper, no other marker inside, not injected (D8). */
    fun commandOutput(entry: Entry.User): String? {
        if (entry.meta || entry.origin != null) return null
        val inner = wrapped(entry.text, "local-command-stdout", "local-command-stderr") ?: return null
        return Replies.jsTrim(inner.replace(ESCAPE, ""))
    }

    /** A shell-mode command's text, or null: typed by the user (not injected, no origin), wholly `<bash-input>` (D5). */
    fun shellCommand(entry: Entry.User): String? =
        if (entry.meta || entry.origin != null) null else Replies.ranCommand(entry.text)

    private const val STDOUT_OPEN = "<bash-stdout>"
    private const val STDOUT_CLOSE = "</bash-stdout>"
    private const val STDERR_OPEN = "<bash-stderr>"
    private const val STDERR_CLOSE = "</bash-stderr>"
    private const val PERSISTED_OPEN = "<persisted-output>"
    private const val PERSISTED_CLOSE = "</persisted-output>"

    /**
     * A shell-mode command's output, or null (D5): `<bash-stdout>…</bash-stdout><bash-stderr>…</bash-stderr>`, cut at
     * the last `</bash-stdout>` as the CLI's own renderer does — the CLI escapes stderr, and stdout except its preview
     * of a too-large output, which may hold a raw `<` — or `<bash-stderr>…</bash-stderr>` alone.
     */
    fun shellOutput(key: String, entry: Entry.User): ShellOutput? {
        if (entry.meta || entry.origin != null) return null
        val t = Replies.jsTrim(entry.text)
        if (!t.endsWith(STDERR_CLOSE)) return null
        val stdout: String
        val stderr: String
        if (t.startsWith(STDOUT_OPEN)) {
            val close = t.lastIndexOf(STDOUT_CLOSE)
            if (close < STDOUT_OPEN.length) return null
            val rest = t.substring(close + STDOUT_CLOSE.length)
            if (!rest.startsWith(STDERR_OPEN) || rest.length < STDERR_OPEN.length + STDERR_CLOSE.length) return null
            stdout = t.substring(STDOUT_OPEN.length, close)
            stderr = rest.substring(STDERR_OPEN.length, rest.length - STDERR_CLOSE.length)
        } else if (t.startsWith(STDERR_OPEN) && t.length >= STDERR_OPEN.length + STDERR_CLOSE.length) {
            stdout = ""
            stderr = t.substring(STDERR_OPEN.length, t.length - STDERR_CLOSE.length)
        } else {
            return null
        }
        // Escaped, the error output never holds a marker; one that does is not the CLI's.
        if ('<' in stderr) return null
        val trimmed = Replies.jsTrim(stdout)
        val persisted = trimmed.startsWith(PERSISTED_OPEN) && trimmed.endsWith(PERSISTED_CLOSE) &&
            trimmed.length >= PERSISTED_OPEN.length + PERSISTED_CLOSE.length
        val out = if (persisted) trimmed.substring(PERSISTED_OPEN.length, trimmed.length - PERSISTED_CLOSE.length) else unescape(stdout)
        return ShellOutput(key, plain(out), plain(unescape(stderr)), persisted, entry)
    }

    /** What the CLI escapes in shell-mode output, back to the characters; `&amp;` last, so `&amp;lt;` stays `&lt;`. */
    private fun unescape(text: String): String =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun plain(text: String): String = Replies.jsTrim(text.replace(ESCAPE, ""))

    /** The CLI's fixed caveat it injects before a built-in command (D8). */
    fun isCommandCaveat(entry: Entry.User): Boolean =
        entry.meta && entry.origin == null && wrapped(entry.text, "local-command-caveat") != null

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
        is CommandOutput -> item.copy(anchorsAfter = item.anchorsAfter + anchor)
        is ShellCommand -> item.copy(anchorsAfter = item.anchorsAfter + anchor)
        is ShellOutput -> item.copy(anchorsAfter = item.anchorsAfter + anchor)
    }
}
