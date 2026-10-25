package com.spekterm.android.desktop

import com.spekterm.android.net.AgentUsage
import com.spekterm.android.net.Entry
import com.spekterm.android.net.Operations

/** A change of the watched session's effort or model, as the agent's CLI takes it (`session-model-effort` D3). */
sealed interface Switch {
    data class Effort(val level: String) : Switch

    /** [name] is held trimmed: that is what is sent, and the desktop does not trim. */
    class Model(name: String) : Switch {
        val name: String = Replies.jsTrim(name)

        override fun equals(other: Any?): Boolean = other is Model && other.name == name

        override fun hashCode(): Int = name.hashCode()

        override fun toString(): String = "Model($name)"
    }

    /** The connection log's name for it: never the level or the name (D9). */
    val logName: String get() = if (this is Effort) "effort" else "model"
}

/**
 * A switch sent and not yet confirmed or dismissed (D6). [id] is new for each switch: an answer is stored only on the
 * switch it was sent for. [replyId] is set for a typed `/model` or `/effort` reply. [before] is the reported model (for
 * a model) or effort (for an effort) at the send, or null; [held] the entry uuids held then, as [Reply.held].
 */
data class PendingSwitch(
    val id: Long,
    val switch: Switch,
    val before: String?,
    val sentAt: Long,
    val held: Set<String>,
    val replyId: Long? = null,
    val state: State = State.Sending,
    /** "Continue anyway": the hold is lifted for this switch. */
    val anyway: Boolean = false,
) {
    sealed interface State {
        data object Sending : State

        data object Submitted : State

        /** No answer, or an error not read as a refusal: the desktop may have written it. */
        data object Lost : State

        data class Refused(val code: String) : State

        data object NotSubmitted : State
    }

    /** Still waiting to be confirmed by a report or its line. */
    val open: Boolean get() = state == State.Sending || state == State.Submitted || state == State.Lost

    /** Holds replies, answers and switches of the session (D6): a model switch from the send until confirmed. */
    val holds: Boolean get() = switch is Switch.Model && open && !anyway

    /** From 10 s after the send the screen says to look at the desktop. */
    fun late(now: Long): Boolean = now - sentAt >= ModelEffort.LATE_MS
}

/** The pure rules of switching model and effort (`session-model-effort` D3, D5, D6). */
object ModelEffort {
    val EFFORTS = listOf("low", "medium", "high", "xhigh", "max", "auto")

    val MODELS = listOf("default", "opus", "fable", "sonnet", "haiku")

    const val AUTO = "auto"

    const val DEFAULT = "default"

    const val LATE_MS = 10_000L

    const val MAX_NAME = 100

    private val NAME = Regex("""[A-Za-z0-9._:/@\[\]-]+""")

    /** "Opus 5.5": a family and a version, nothing after. */
    private val FAMILY_VERSION = Regex("""^(\S+) [0-9][0-9.]*$""")

    fun command(s: Switch): String = when (s) {
        is Switch.Effort -> "/effort ${s.level}"
        is Switch.Model -> "/model ${s.name}"
    }

    /** The desktop's charset for a model name (its D4), on the trimmed text. */
    fun validName(s: String): Boolean {
        val t = Replies.jsTrim(s)
        return t.length in 1..MAX_NAME && !t.startsWith("-") && NAME.matches(t)
    }

    /** The reported effort when it is one of the levels, ignoring case. */
    fun currentEffort(usage: AgentUsage?): String? = usage?.effort?.lowercase()?.takeIf { it in EFFORTS }

    /** The alias whose name begins a reported "<Family> <version>"; never [DEFAULT]. A hint only (D3). */
    fun currentModel(usage: AgentUsage?): String? {
        val family = usage?.model?.let { FAMILY_VERSION.find(it) }?.groupValues?.get(1)?.lowercase() ?: return null
        return family.takeIf { it in MODELS && it != DEFAULT }
    }

    /** The sheet is offered while the watch is active and the desktop sent a usage event for it (D5). */
    fun available(c: Conversation): Boolean = c.watch.active && c.usage != null

    fun modelsOffered(operations: Set<String>): Boolean = Operations.SET_MODEL in operations

    /**
     * The switch a typed reply makes (D6): `/model` or `/effort` with one argument, on one line, with no attachments.
     * Read from the line the desktop writes, case-sensitive as the CLI's command names are written.
     */
    fun typed(text: String, attachments: Int): Switch? {
        if (attachments > 0) return null
        val line = Replies.jsTrim(Replies.normalized(text))
        if ('\n' in line) return null
        val space = line.indexOf(' ')
        if (space < 0) return null
        val arg = line.substring(space + 1)
        if (arg.isEmpty() || arg.any(Replies::jsSpace)) return null
        return when (line.substring(0, space)) {
            "/model" -> Switch.Model(arg)
            "/effort" -> Switch.Effort(arg)
            else -> null
        }
    }

    /** `/model` or `/effort` alone, which opens a picker the phone cannot close (D7). */
    fun bare(text: String): Boolean = Replies.jsTrim(Replies.normalized(text)).let { it == "/model" || it == "/effort" }

    /**
     * Whether [c] confirms [p] (D6). A missing usage, a missing value or an unknown [PendingSwitch.before] is no
     * information, never a change.
     */
    fun confirmed(p: PendingSwitch, c: Conversation): Boolean {
        if (!p.open) return false
        val line = command(p.switch)
        if (c.entries.any { it is Entry.User && !it.meta && it.uuid !in p.held && Replies.jsTrim(it.text) == line }) return true
        return when (val s = p.switch) {
            is Switch.Effort -> {
                // No confirmation exists for an effort, so the marked one is taken at once once written.
                val level = s.level.lowercase()
                if (p.state == PendingSwitch.State.Submitted && level != AUTO && level in EFFORTS && p.before?.lowercase() == level) return true
                val now = c.usage?.effort ?: return false
                if (level == AUTO) p.before != null && !now.equals(p.before, ignoreCase = true) else now.equals(s.level, ignoreCase = true)
            }
            is Switch.Model -> {
                val now = c.usage?.model ?: return false
                val before = p.before ?: return false
                if (now == before) return false
                val alias = s.name.lowercase()
                if (alias in MODELS && alias != DEFAULT) now.substringBefore(' ').equals(alias, ignoreCase = true) else true
            }
        }
    }
}
