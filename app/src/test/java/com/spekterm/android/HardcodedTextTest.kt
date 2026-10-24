package com.spekterm.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No text for people is left in the code (`app-language`, `scan-connect-zh` D9): every string literal in
 * `src/main/java` — templates included, calls on several lines included — that reads as text fails, unless its
 * file or the literal itself is allowed below, with the reason. Comments are skipped.
 */
class HardcodedTextTest {
    private val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /** Files whose literals are never shown: protocol, log and storage formats. */
    private val allowedFiles = mapOf(
        "log/ConnectionLog.kt" to "the connection log stays English (`app-language`)",
        "net/Frames.kt" to "protocol frames and codes",
        "net/OpenSpec.kt" to "protocol fields and artifact file names",
        "net/Sessions.kt" to "protocol fields",
        "net/Pinning.kt" to "TLS diagnostics for the log",
        "net/RemoteClient.kt" to "exception messages for the log",
        "store/Stores.kt" to "storage formats and redacted toString",
        "pairing/PairingLink.kt" to "the link's format and redacted toString",
        "ui/QrScanner.kt" to "a debug log line",
        "ui/Icons.kt" to "vector names, never shown",
        "desktop/ToolView.kt" to "the agent CLI's tool names and result markers, matched, never shown",
        "desktop/PeerText.kt" to "the agent CLI's wrapper lines, matched and removed, never shown",
    )

    /** Literals that are not text for people wherever they appear. */
    private val allowedLiterals = setOf(
        "spekterm", // the product name
        "text/plain", "image/jpeg", "application/pdf",
        "malformed answer", "no stream", "race over", // exception and cancellation messages, for the log
        "Connection refused", // matched in exception messages (Unreachability)
        "close", "closed", // a close code beside it, as diagnostics ("Last disconnect: close 4005")
        "0000global", // a list key, "\u0000global"
        // The connection log's outcome names (`SendOutcome.logName`, `AnswerOutcome.logName`, `play-release` D3).
        "Submitted", "NotSubmitted", "Refused", "Unknown", "Done", "Partial", "Mismatch", "Gone",
    )

    private val sentence = Regex("[A-Za-z]{2,}[ \\u00a0]+[A-Za-z]{2,}")
    private val word = Regex("^[A-Z][a-z]+$")
    private val leadingWord = Regex("^[A-Z][a-z]+\\s")
    /** A word beside a number or a dropped template: "1 change", "${n} changes". */
    private val countedWord = Regex("(^|[\\s\\d])[a-z]{3,}(\\s|$)")

    /** A sentence, a capitalized word, or text ending as a sentence does — but not a key such as `reply:`. */
    private fun readsAsText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t in allowedLiterals) return false
        return sentence.containsMatchIn(t) || (' ' in text && countedWord.containsMatchIn(t)) || word.matches(t) || leadingWord.containsMatchIn(text.trimStart()) || (t.length > 1 && t.any { it.isLetter() } && t.last() in ".?…:" && (' ' in t || t.first().isUpperCase()))
    }

    @Test
    fun noTextForPeopleInTheCode() {
        val found = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val path = file.relativeTo(root).path.substringAfter("com/spekterm/android/")
            if (path in allowedFiles) return@forEach
            for ((line, text) in KotlinSource.literals(file.readText())) {
                if (readsAsText(text)) found += "$path:$line: \"$text\""
            }
        }
        assertTrue("${found.size} literals read as text:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
