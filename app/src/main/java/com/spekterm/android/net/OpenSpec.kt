package com.spekterm.android.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The OpenSpec payloads of the remote protocol (`docs/remote-protocol.md` section 5, "OpenSpec"; `openspec-browse`
 * design D1). Read-only, and the repository's text: untrusted (obligation 14).
 *
 * Decoded by hand, element by element: a malformed change, artifact, delta, history entry, section or task is
 * skipped, never the answer (obligation 7). Unknown keys are ignored, so an older desktop's extra fields (`relPath`,
 * `schema`, a tasks artifact's raw `content`, a spec's `relatedChanges`) read the same.
 */

/** Content the desktop may leave out of an answer that would exceed its bound, standing in as `omitted: { bytes }`. */
sealed interface Piece<out T> {
    data class Present<T>(val value: T) : Piece<T>

    /** [bytes]: the piece's size as serialized JSON. */
    data class Omitted(val bytes: Long) : Piece<Nothing>
}

/** The working tree a change or spec is read from; present only when the repository has more than one. */
data class Worktree(val branch: String?, val isMain: Boolean)

data class TaskStats(val total: Int, val completed: Int)

/** One change of `openspec.changes`. [timestamp] is decoded but not shown (design Non-Goals). */
data class OsChange(
    val slug: String,
    val status: String,
    val description: String,
    val createdDate: String?,
    val archivedDate: String?,
    val timestamp: String?,
    val taskStats: TaskStats?,
    val worktree: Worktree?,
) {
    val archived: Boolean get() = status == ARCHIVED

    companion object {
        const val ARCHIVED = "archived"
    }
}

data class OsChanges(val active: List<OsChange>, val archived: List<OsChange>)

data class OsChangeDetail(
    val slug: String,
    val status: String,
    val createdDate: String?,
    val archivedDate: String?,
    val worktree: Worktree?,
    val artifacts: List<OsArtifact>,
) {
    val archived: Boolean get() = status == OsChange.ARCHIVED
}

data class TaskList(val total: Int, val completed: Int, val sections: List<Section>) {
    data class Section(val title: String, val tasks: List<Task>)

    /** [text] is Markdown and may span several lines. */
    data class Task(val text: String, val completed: Boolean)
}

data class Delta(val topic: String, val content: Piece<String>)

/** An artifact of a change, by `kind`. An unknown kind, or a known one whose content is unreadable, is [Other]. */
sealed interface OsArtifact {
    val id: String
    val title: String

    data class Markdown(override val id: String, override val title: String, val content: Piece<String>) : OsArtifact

    /** YAML or JSON, shown as plain text; [title] is the file name. */
    data class Data(override val id: String, override val title: String, val content: Piece<String>) : OsArtifact

    data class Tasks(override val id: String, override val title: String, val tasks: Piece<TaskList>) : OsArtifact

    data class Specs(override val id: String, override val title: String, val deltas: List<Delta>) : OsArtifact

    data class Other(override val id: String, override val title: String) : OsArtifact
}

data class OsSpecSummary(val topic: String, val relatedChangeCount: Int)

data class HistoryEntry(val slug: String, val status: String, val description: String, val date: String?) {
    val archived: Boolean get() = status == OsChange.ARCHIVED
}

data class OsSpec(val topic: String, val content: Piece<String>, val history: List<HistoryEntry>, val origin: Worktree?)

object OpenSpec {
    /** `openspec.changes`; `null` when the answer is not an object. */
    fun changes(element: JsonElement?): OsChanges? {
        val o = element as? JsonObject ?: return null
        return OsChanges(each(o["active"], ::change), each(o["archived"], ::change))
    }

    fun change(element: JsonElement): OsChange? {
        val o = element as? JsonObject ?: return null
        val slug = o.string("slug") ?: return null
        return OsChange(
            slug = slug,
            status = o.string("status") ?: "",
            description = o.string("description") ?: "",
            createdDate = o.string("createdDate"),
            archivedDate = o.string("archivedDate"),
            timestamp = o.string("timestamp"),
            taskStats = (o["taskStats"] as? JsonObject)?.let { s ->
                val total = s.int("total") ?: return@let null
                TaskStats(total, s.int("completed") ?: 0)
            },
            worktree = worktree(o["worktree"]),
        )
    }

    /** `openspec.change`; `null` when the answer is not an object with a slug. */
    fun changeDetail(element: JsonElement?): OsChangeDetail? {
        val o = element as? JsonObject ?: return null
        return OsChangeDetail(
            slug = o.string("slug") ?: return null,
            status = o.string("status") ?: "",
            createdDate = o.string("createdDate"),
            archivedDate = o.string("archivedDate"),
            worktree = worktree(o["worktree"]),
            artifacts = each(o["artifacts"], ::artifact),
        )
    }

    /** One artifact; `null` (skipped) without a string `id`. */
    fun artifact(element: JsonElement): OsArtifact? {
        val o = element as? JsonObject ?: return null
        val id = o.string("id") ?: return null
        val title = o.string("title") ?: id
        return when (o.string("kind")) {
            "markdown" -> piece(o, "content", ::text)?.let { OsArtifact.Markdown(id, title, it) }
            "data" -> piece(o, "content", ::text)?.let { OsArtifact.Data(id, title, it) }
            "tasks" -> piece(o, "tasks", ::taskList)?.let { OsArtifact.Tasks(id, title, it) }
            "specs" -> (o["specs"] as? JsonArray)?.let { OsArtifact.Specs(id, title, each(it, ::delta)) }
            else -> null
        } ?: OsArtifact.Other(id, title)
    }

    /** `openspec.specs`; `null` when the answer is not an array. */
    fun specs(element: JsonElement?): List<OsSpecSummary>? {
        if (element !is JsonArray) return null
        return each(element) { e ->
            val o = e as? JsonObject ?: return@each null
            OsSpecSummary(o.string("topic") ?: return@each null, o.int("relatedChangeCount") ?: 0)
        }
    }

    /** `openspec.spec`; `null` when the answer is not an object with a topic. */
    fun spec(element: JsonElement?): OsSpec? {
        val o = element as? JsonObject ?: return null
        return OsSpec(
            topic = o.string("topic") ?: return null,
            content = piece(o, "content", ::text) ?: Piece.Present(""),
            history = each(o["history"]) { e ->
                val h = e as? JsonObject ?: return@each null
                HistoryEntry(h.string("slug") ?: return@each null, h.string("status") ?: "", h.string("description") ?: "", h.string("date"))
            },
            origin = worktree(o["origin"]),
        )
    }

    private fun worktree(element: JsonElement?): Worktree? {
        val o = element as? JsonObject ?: return null
        return Worktree(o.string("branch"), o.bool("isMain"))
    }

    private fun delta(element: JsonElement): Delta? {
        val o = element as? JsonObject ?: return null
        val topic = o.string("topic") ?: return null
        return piece(o, "content", ::text)?.let { Delta(topic, it) }
    }

    private fun taskList(element: JsonElement): TaskList? {
        val o = element as? JsonObject ?: return null
        val sections = each(o["sections"]) { e ->
            val s = e as? JsonObject ?: return@each null
            TaskList.Section(
                s.string("title") ?: "",
                each(s["tasks"]) { t ->
                    val task = t as? JsonObject ?: return@each null
                    TaskList.Task(task.string("text") ?: return@each null, task.bool("completed"))
                },
            )
        }
        val all = sections.flatMap { it.tasks }
        return TaskList(o.int("total") ?: all.size, o.int("completed") ?: all.count { it.completed }, sections)
    }

    private fun text(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** [key] read by [read], else the holder's `omitted` stand-in, else `null` (neither is usable). */
    private fun <T> piece(o: JsonObject, key: String, read: (JsonElement) -> T?): Piece<T>? {
        o[key]?.let(read)?.let { return Piece.Present(it) }
        val bytes = (o["omitted"] as? JsonObject)?.long("bytes") ?: return null
        return Piece.Omitted(bytes)
    }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()
}
