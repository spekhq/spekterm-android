package com.spekterm.android.net

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What one attempt at an address came to: a value to keep, or why not (`PinMismatch` or `Unreachable`). */
sealed interface Attempt<out T> {
    data class Opened<T>(val value: T) : Attempt<T>

    data class Failed(val outcome: RemoteClient.OpenResult) : Attempt<Nothing>
}

/**
 * How a [race] ended: the winner, if any; the attempts that finished without opening, in the order they finished
 * (by address); the addresses never started; and those started but cancelled when another won.
 */
data class RaceResult<T>(
    val winner: Pair<String, T>?,
    val finished: Map<String, RemoteClient.OpenResult>,
    val notTried: List<String>,
    val cancelled: List<String>,
)

/**
 * A staggered race over [addresses] (`desktop-connection`, "Try the pairing's addresses"; `scan-connect-zh` D5).
 * Attempts start in order, each [stagger] ms after the one before, or at once when every attempt started so far
 * has finished without opening; earlier ones keep running. The first [Attempt.Opened] wins: the others are
 * cancelled, and any other opened value — one in flight, or one that lands as the race ends — goes to [drop]
 * exactly once and is never returned. [onStart] and [onOutcome] are called from this coroutine, one at a time;
 * cancelled attempts report no outcome.
 */
suspend fun <T> race(
    addresses: List<String>,
    open: suspend (String) -> Attempt<T>,
    drop: (T) -> Unit,
    stagger: Long = STAGGER_MS,
    onStart: (String) -> Unit = {},
    onOutcome: (String, RemoteClient.OpenResult) -> Unit = { _, _ -> },
): RaceResult<T> = coroutineScope {
    val events = Channel<Event<T>>(Channel.UNLIMITED) { (it as? Event.Done)?.drop(drop) }
    val finished = LinkedHashMap<String, RemoteClient.OpenResult>()
    val started = mutableListOf<String>()
    var running = 0
    var winner: Pair<String, T>? = null

    fun start() {
        val index = started.size
        val address = addresses[index]
        started += address
        running++
        onStart(address)
        launch {
            val event = Event.Done(address, open(address))
            // Sent even after this attempt was cancelled; a closed channel means the race is over, so drop it.
            if (!events.trySend(event).isSuccess) event.drop(drop)
        }
        if (started.size < addresses.size) {
            launch {
                delay(stagger)
                events.trySend(Event.Tick(index))
            }
        }
    }

    try {
        if (addresses.isNotEmpty()) start()
        while (winner == null && (running > 0 || started.size < addresses.size)) {
            when (val event = events.receive()) {
                // Only the latest start's tick starts the next; an earlier one was overtaken by a failure.
                is Event.Tick -> if (event.index == started.size - 1 && started.size < addresses.size) start()
                is Event.Done -> {
                    running--
                    when (val attempt = event.attempt) {
                        is Attempt.Opened -> if (event.take()) winner = event.address to attempt.value
                        is Attempt.Failed -> {
                            finished[event.address] = attempt.outcome
                            onOutcome(event.address, attempt.outcome)
                            if (running == 0 && started.size < addresses.size) start()
                        }
                    }
                }
            }
        }
    } finally {
        // Cancelling the channel drops every opened value still in it; the attempts still running are cancelled.
        events.cancel()
        coroutineContext.cancelChildren(CancellationException("race over"))
    }
    val won = winner?.first
    RaceResult(
        winner = winner,
        finished = finished,
        notTried = addresses.drop(started.size),
        cancelled = started.filter { it != won && it !in finished },
    )
}

const val STAGGER_MS = 250L

/** An open's result as a race attempt; an `Open` is the value, anything else why not. */
fun RemoteClient.OpenResult.attempt(): Attempt<RemoteConnection> = when (this) {
    is RemoteClient.OpenResult.Open -> Attempt.Opened(connection)
    else -> Attempt.Failed(this)
}

private sealed interface Event<out T> {
    data class Tick(val index: Int) : Event<Nothing>

    class Done<T>(val address: String, val attempt: Attempt<T>) : Event<T> {
        private val handled = AtomicBoolean(false)

        /** Claims the opened value for the winner; false if it was already dropped. */
        fun take(): Boolean = handled.compareAndSet(false, true)

        fun drop(drop: (T) -> Unit) {
            val opened = attempt as? Attempt.Opened ?: return
            if (handled.compareAndSet(false, true)) drop(opened.value)
        }
    }
}
