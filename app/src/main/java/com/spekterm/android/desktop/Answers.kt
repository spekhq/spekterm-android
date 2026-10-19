package com.spekterm.android.desktop

import com.spekterm.android.net.AnswerResult
import com.spekterm.android.net.Operations
import com.spekterm.android.net.Prompt
import com.spekterm.android.net.RemoteError

/** An answer to the prompt [promptId]: in flight while [outcome] is `null` (design D4). */
data class AnswerState(val promptId: String, val decision: String, val outcome: AnswerOutcome? = null) {
    val inFlight: Boolean get() = outcome == null
}

/** How the desktop answered a `conversation.answer`, as the screen treats it (design D4). */
sealed interface AnswerOutcome {
    /** Every key written, or the agent recorded the choice: nothing to say. */
    data object Done : AnswerOutcome

    data object Partial : AnswerOutcome

    data object Mismatch : AnswerOutcome

    /** `PROMPT_GONE`: nothing was sent. */
    data object Gone : AnswerOutcome

    /** Refused before any key was written. */
    data class Refused(val code: String) : AnswerOutcome

    /** It is unknown whether the answer was written. */
    data object Unknown : AnswerOutcome

    /** The connection log's name for it, fixed in code so a shrunk build logs the same (`play-release` D3). */
    val logName: String
        get() = when (this) {
            Done -> "Done"
            Partial -> "Partial"
            Mismatch -> "Mismatch"
            Gone -> "Gone"
            is Refused -> "Refused"
            Unknown -> "Unknown"
        }

    /** Kept until dismissed; the others go when the prompt changes. */
    val warning: Boolean get() = this == Partial || this == Mismatch || this == Unknown

    companion object {
        private val REFUSED = setOf(
            RemoteError.NOT_ANSWERABLE,
            RemoteError.BAD_PARAMS,
            RemoteError.UNKNOWN_OPERATION,
            RemoteError.NOT_WATCHING,
            RemoteError.NOT_FOUND,
        )

        /** [outcome] from an ok answer, or [errorCode] from an error answer; both null when the connection ended. */
        fun of(outcome: String?, errorCode: String?): AnswerOutcome = when {
            errorCode == RemoteError.PROMPT_GONE -> Gone
            errorCode in REFUSED -> Refused(errorCode!!)
            errorCode != null -> Unknown
            outcome == AnswerResult.SENT || outcome == AnswerResult.CONFIRMED -> Done
            outcome == AnswerResult.PARTIAL -> Partial
            outcome == AnswerResult.MISMATCH -> Mismatch
            else -> Unknown
        }
    }
}

/** What the phone offers for the open prompt (design D3). */
sealed interface Offer {
    data class ReadOnly(val reason: Reason) : Offer

    /** [decisions] in the order shown. */
    data class Answerable(val decisions: List<String>) : Offer

    sealed interface Reason {
        /** The desktop does not accept `conversation.answer`, or the phone cannot reach it now. */
        data object NoOperation : Reason

        data object NotConnected : Reason

        data object Paused : Reason

        data object NotWatching : Reason

        data class Open(val count: Int) : Reason

        /** The phone's own guard on questions. */
        data object TooLarge : Reason

        data object NotAnswerable : Reason

        data object Answering : Reason

        /** Answered from the phone; the prompt goes once the desktop says so. */
        data object Answered : Reason
    }
}

/** The pure rules of answering prompts (design D3). */
object Answers {
    fun offer(
        prompt: Prompt,
        operations: Set<String>,
        connected: Boolean,
        paused: Boolean,
        watch: Watch,
        answer: AnswerState?,
    ): Offer {
        val mine = answer?.takeIf { it.promptId == prompt.id }
        val reason = when {
            Operations.ANSWER !in operations -> Offer.Reason.NoOperation
            paused -> Offer.Reason.Paused
            !connected -> Offer.Reason.NotConnected
            watch != Watch.Watching -> Offer.Reason.NotWatching
            prompt.open > 1 -> Offer.Reason.Open(prompt.open)
            prompt is Prompt.Question && !prompt.fits -> Offer.Reason.TooLarge
            !prompt.answerable -> Offer.Reason.NotAnswerable
            mine?.inFlight == true -> Offer.Reason.Answering
            mine?.outcome == AnswerOutcome.Done -> Offer.Reason.Answered
            else -> null
        }
        return if (reason != null) Offer.ReadOnly(reason) else Offer.Answerable(decisions(prompt))
    }

    fun decisions(prompt: Prompt): List<String> = when (prompt) {
        is Prompt.Permission -> if (prompt.canAllow) listOf(Prompt.ALLOW, Prompt.DENY) else listOf(Prompt.DENY)
        is Prompt.Question -> listOf(Prompt.ANSWER, Prompt.DENY)
        is Prompt.Plan -> listOfNotNull(Prompt.APPROVE, Prompt.APPROVE_AUTO.takeIf { it in prompt.choices }, Prompt.DENY)
    }

    /**
     * The `selections` for [chosen] (question index → option indexes), or `null` unless every single-choice
     * question has exactly one valid option and every several-choice question at least one.
     */
    fun selections(prompt: Prompt.Question, chosen: Map<Int, Set<Int>>): List<List<Int>>? {
        if (!prompt.fits) return null
        return prompt.questions.mapIndexed { i, q ->
            val picked = chosen[i].orEmpty().filter { it in q.options.indices }.distinct().sorted()
            val valid = if (q.multiSelect) picked.isNotEmpty() else picked.size == 1
            if (!valid) return null
            picked
        }
    }
}
