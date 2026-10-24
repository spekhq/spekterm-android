package com.spekterm.android.desktop

import com.spekterm.android.net.Entry
import com.spekterm.android.net.ResultImage
import com.spekterm.android.net.SessionList
import com.spekterm.android.net.ToolInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What a tool call shows, per tool (`tool-call-display` D2): its [title], one-line summary ([recipient] and [line]),
 * and the [parts] shown when expanded; [pictures] the result held, shown without expanding (`tool-result-content` D3).
 * Pure, so every tool's view is tested on the JVM; the screen supplies the words.
 */
data class ToolView(
    val title: String,
    val recipient: Recipient?,
    val line: String?,
    val parts: List<Part>,
    val pictures: List<ResultImage> = emptyList(),
) {
    /** Who a SendMessage went to. */
    sealed interface Recipient {
        /** A session by its label, or a name no listed session has, as given. */
        data class Named(val name: String) : Recipient

        data object AnotherSession : Recipient

        data object Subagent : Recipient
    }

    enum class Label { COMMAND, OUTPUT, ERROR, MESSAGE, PROMPT, CONTENT, PLAN, ANSWER, ARGUMENT }

    enum class Role { INPUT, OUTPUT, ERROR }

    enum class Note { BACKGROUND, SENT, NOT_SENT, IDLE_ONLY, REPLACE_ALL, DELAY_MINUTES, PICTURES_NEED_DESKTOP }

    sealed interface Part {
        /**
         * A labeled text; [cut] is the original length when the desktop shortened it. [numbered]: a Read's result,
         * shown by [ReadLines] when it has the CLI's line numbers (`tool-result-content` D4).
         */
        data class Block(val label: Label, val text: String, val role: Role, val cut: Long? = null, val numbered: Boolean = false) : Part

        /** A file's path, shown muted. */
        data class Path(val path: String) : Part

        data class Diff(val diff: EditDiff.Diff, val cut: Boolean) : Part

        /** The files a SendUserFile call sent, by position; [cutItems] is the original count when some were dropped. */
        data class Files(val files: List<String>, val cutItems: Long?) : Part

        data class Questions(val questions: List<Question>) : Part

        /** A fixed sentence the screen words; [value] fills it (a count of minutes, a failed send's reason). */
        data class Notice(val note: Note, val value: String? = null) : Part
    }

    data class Question(val text: String, val options: List<String>)

    companion object {
        private const val LAUNCHED = "Async agent launched"
        private const val MONITOR_STARTED = "Monitor started"
        private val AGENT_ID = Regex("^a[0-9a-f]{16}$")

        /** `mcp__server__tool` → `server · tool`; any other name as it is. */
        fun title(name: String): String {
            if (!name.startsWith("mcp__")) return name
            val rest = name.removePrefix("mcp__")
            val i = rest.indexOf("__")
            return if (i <= 0 || i + 2 >= rest.length) name else rest.substring(0, i) + " · " + rest.substring(i + 2)
        }

        /** The last path segment. */
        fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

        private fun firstLine(s: String?): String? = s?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

        /** [imagesReadable]: the desktop offers `images.read`; without it no picture is shown (`tool-result-content` D3). */
        fun of(tool: Entry.Tool, result: Entry.Result?, list: SessionList? = null, imagesReadable: Boolean = false): ToolView {
            val pictures = pictures(result, imagesReadable)
            val title = title(tool.name)
            val input = tool.input
            if (input.isEmpty) return fallback(title, tool, result).copy(pictures = pictures)
            val cut = tool.cut
            fun block(label: Label, key: String, role: Role = Role.INPUT) =
                input.string(key)?.takeIf { it.isNotEmpty() }?.let { Part.Block(label, it, role, cut[key]) }
            fun output(hidden: (String) -> Boolean = { false }): List<Part> = when {
                result == null -> emptyList()
                result.error -> listOf(Part.Block(Label.ERROR, result.text, Role.ERROR))
                result.text.isBlank() || hidden(result.text) -> emptyList()
                else -> listOf(Part.Block(Label.OUTPUT, result.text, Role.OUTPUT))
            }
            val failedOnly = output { true }
            val background = if (input.bool("run_in_background") == true) listOf(Part.Notice(Note.BACKGROUND)) else emptyList()
            val path = input.text("file_path")
            val view = when (tool.name) {
                "Bash" -> ToolView(title, null, input.text("description") ?: input.text("command"), listOfNotNull(block(Label.COMMAND, "command")) + background + output())
                "SendMessage" -> {
                    val message = input.string("message")
                    val parts = mutableListOf<Part>()
                    if (message.isNullOrBlank()) parts += Part.Notice(Note.IDLE_ONLY) else parts += Part.Block(Label.MESSAGE, message, Role.INPUT, cut["message"])
                    parts += sendResult(result)
                    ToolView(title, recipient(input, list), input.text("summary") ?: firstLine(message), parts)
                }
                "Edit" -> ToolView(
                    title, null, path?.let(::fileName),
                    listOfNotNull(path?.let(Part::Path), Part.Notice(Note.REPLACE_ALL).takeIf { input.bool("replace_all") == true }) +
                        Part.Diff(EditDiff.of(input.string("old_string") ?: "", input.string("new_string") ?: ""), "old_string" in cut || "new_string" in cut) +
                        failedOnly,
                )
                "Write" -> ToolView(title, null, path?.let(::fileName), listOfNotNull(path?.let(Part::Path), block(Label.CONTENT, "content")) + failedOnly)
                "Read" -> {
                    val content = output().map { if (it is Part.Block && it.label == Label.OUTPUT) it.copy(numbered = true) else it }
                    // An older desktop sends a picture's result as no text at all (design D6).
                    val older = !imagesReadable && path != null && SentFiles.kind(path) == SentFiles.Kind.IMAGE &&
                        result != null && !result.error && result.text.isBlank()
                    ToolView(
                        title, null, path?.let { fileName(it) + lines(input) },
                        listOfNotNull(path?.let(Part::Path), Part.Notice(Note.PICTURES_NEED_DESKTOP).takeIf { older }) + content,
                    )
                }
                "Grep", "Glob" -> ToolView(title, null, input.text("pattern"), listOfNotNull(input.text("path")?.let(Part::Path)) + output())
                "Agent", "Task" -> ToolView(
                    title, null,
                    listOfNotNull(input.text("subagent_type"), input.text("description")).joinToString(" · ").ifEmpty { null },
                    listOfNotNull(block(Label.PROMPT, "prompt")) + background + output { it.trimStart().startsWith(LAUNCHED) },
                )
                "Skill" -> ToolView(title, null, input.text("skill")?.let { s -> listOfNotNull("/$s", input.text("args")).joinToString(" ") }, output())
                "SendUserFile" -> {
                    val files = input.strings("files").orEmpty()
                    ToolView(
                        title, null, input.text("caption") ?: files.joinToString(", ") { fileName(it) }.ifEmpty { null },
                        listOf(Part.Files(files, cut["files"])) + failedOnly,
                    )
                }
                "AskUserQuestion" -> {
                    val questions = input.objects("questions").orEmpty().mapNotNull { q ->
                        q.text("question")?.let { Question(it, q.objects("options").orEmpty().mapNotNull { o -> o.text("label") }) }
                    }
                    val answer = when {
                        result == null || result.text.isBlank() -> emptyList()
                        result.error -> listOf(Part.Block(Label.ERROR, result.text, Role.ERROR))
                        else -> listOf(Part.Block(Label.ANSWER, result.text, Role.OUTPUT))
                    }
                    ToolView(title, null, questions.firstOrNull()?.text, listOfNotNull(Part.Questions(questions).takeIf { questions.isNotEmpty() }) + answer)
                }
                "ToolSearch", "WebSearch" -> ToolView(title, null, input.text("query"), output())
                "WebFetch" -> ToolView(title, null, input.text("url"), listOfNotNull(block(Label.PROMPT, "prompt")) + output())
                "Monitor" -> ToolView(
                    title, null, input.text("description") ?: input.text("command"),
                    listOfNotNull(block(Label.COMMAND, "command")) + output { it.trimStart().startsWith(MONITOR_STARTED) },
                )
                "TaskStop" -> ToolView(title, null, input.text("task_id"), output())
                "ScheduleWakeup" -> ToolView(
                    title, null, input.text("reason"),
                    listOfNotNull(input.int("delaySeconds")?.let { Part.Notice(Note.DELAY_MINUTES, ((it + 59) / 60).toString()) }) + failedOnly,
                )
                "CronCreate" -> ToolView(title, null, input.text("cron"), listOfNotNull(block(Label.PROMPT, "prompt")) + output())
                "Artifact" -> ToolView(
                    title, null,
                    listOfNotNull(input.text("action"), input.text("file_path")?.let(::fileName) ?: input.text("url")).joinToString(" · ").ifEmpty { null },
                    output(),
                )
                "ExitPlanMode" -> ToolView(title, null, firstLine(input.string("plan")), listOfNotNull(block(Label.PLAN, "plan")) + output())
                else -> return fallback(title, tool, result).copy(pictures = pictures)
            }
            // A field missing from the input falls back to the identifying argument for the line.
            return (if (view.line == null && view.recipient == null) view.copy(line = tool.arg) else view).copy(pictures = pictures)
        }

        /** The file a Read's picture is, which names it (`tool-result-content` D2); none for another tool. */
        fun pictureFile(tool: Entry.Tool): String? = tool.input.text("file_path")?.takeIf { tool.name == "Read" }?.let(::fileName)

        /** A result's pictures, when they can be read and the result is not an error. */
        fun pictures(result: Entry.Result?, imagesReadable: Boolean): List<ResultImage> =
            if (imagesReadable && result != null && !result.error) result.images else emptyList()

        /** No input: the name, the identifying argument and the result, as before this change. */
        private fun fallback(title: String, tool: Entry.Tool, result: Entry.Result?): ToolView {
            val parts = mutableListOf<Part>()
            tool.arg?.let { parts += Part.Block(Label.ARGUMENT, it, Role.INPUT) }
            if (result != null) {
                if (result.error) parts += Part.Block(Label.ERROR, result.text, Role.ERROR)
                else if (result.text.isNotBlank()) parts += Part.Block(Label.OUTPUT, result.text, Role.OUTPUT)
            }
            return ToolView(title, null, tool.arg, parts)
        }

        private fun lines(input: ToolInput): String {
            val offset = input.int("offset")
            val limit = input.int("limit")
            return when {
                offset != null && limit != null -> ":$offset–${offset + limit - 1}"
                offset != null -> ":$offset–"
                limit != null -> ":1–$limit"
                else -> ""
            }
        }

        private fun recipient(input: ToolInput, list: SessionList?): Recipient? {
            val to = input.text("to")
            if (to != null) {
                return when {
                    to.startsWith("uds:") -> Recipient.AnotherSession
                    AGENT_ID.matches(to) -> Recipient.Subagent
                    else -> Recipient.Named(SessionNames.label(to, list) ?: to)
                }
            }
            return when (input.string("recipient_kind")) {
                "session" -> Recipient.AnotherSession
                "agent" -> Recipient.Subagent
                else -> null
            }
        }

        /** SendMessage's result: `{"success": bool, "message"}` read as sent or not; any other text as it is. */
        private fun sendResult(result: Entry.Result?): List<Part> {
            if (result == null) return emptyList()
            val o = runCatching { Json.parseToJsonElement(result.text) as? JsonObject }.getOrNull()
            val success = (o?.get("success") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (success != null) {
                val message = (o["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                return listOf(if (success) Part.Notice(Note.SENT) else Part.Notice(Note.NOT_SENT, message))
            }
            return when {
                result.error -> listOf(Part.Block(Label.ERROR, result.text, Role.ERROR))
                result.text.isBlank() -> emptyList()
                else -> listOf(Part.Block(Label.OUTPUT, result.text, Role.OUTPUT))
            }
        }
    }
}
