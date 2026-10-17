package com.spekterm.android.desktop

import com.spekterm.android.net.Entry

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

    /** A part of a [Run]: a call or the thinking between two calls. */
    sealed interface Part {
        val key: String
    }

    data class Call(override val key: String, val tool: Entry.Tool, val result: Entry.Result?, override val anchorsAfter: List<String> = emptyList()) : Shown, Part {
        val failed: Boolean get() = result?.error == true
    }

    data class Thinking(override val key: String, val entry: Entry.Thinking) : Part

    data class LoneResult(override val key: String, val result: Entry.Result, override val anchorsAfter: List<String> = emptyList()) : Shown

    /** Two or more calls in a row; its key is its first call's, so it stays as the run grows. */
    data class Run(override val key: String, val parts: List<Part>, override val anchorsAfter: List<String> = emptyList()) : Shown {
        val calls: List<Call> get() = parts.filterIsInstance<Call>()
        val failed: Int get() = calls.count { it.failed }
        val latest: Call get() = calls.last()
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
        val held = mutableListOf<Thinking>()

        fun emit(item: Shown) {
            out += item
        }

        fun close() {
            forming?.let { parts ->
                val first = parts.first() as Call
                emit(if (parts.size == 1) first else Run(first.key, parts.toList()))
            }
            forming = null
            held.forEach { emit(Plain(it.key, it.entry)) }
            held.clear()
        }

        entries.forEachIndexed { i, entry ->
            val key = keys[i]
            when {
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
                entry is Entry.Result && i in consumed -> Unit
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
    }
}
