package com.spekterm.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the status screen and the ongoing notification show. In memory; announcements persist separately. */
data class Status(
    val state: State = State.STOPPED,
    val address: String? = null,
    val since: Long? = null,
    val reconnects: Int = 0,
    val lastClose: String? = null,
    val retryAt: Long? = null,
    /** The desktop said remote access is off (or it quit): kept until a connection authenticates again. */
    val remoteOff: Boolean = false,
    val recent: List<RecentEvent> = emptyList(),
    /** The addresses started in the round under way, in order; `null` while an attempt is still trying (D5). */
    val attempts: Map<String, Unreachability.Outcome?> = emptyMap(),
    /** The last round in which no address connected, until one connects or the user pauses (D6). */
    val report: Unreachability.Report? = null,
    /** Waiting out the 60 s after throttling: no "Try now", and no reason shown (D6, D7). */
    val throttled: Boolean = false,
) {
    enum class State { STOPPED, CONNECTING, CONNECTED, WAITING }

    data class RecentEvent(val kind: String, val title: String, val receivedAt: Long, val outcome: String)

    // The transitions of `scan-connect-zh` D6's lifecycle, pure so they are tested on the JVM.

    fun roundStarted(): Status = copy(state = State.CONNECTING, address = null, since = null, attempts = emptyMap(), throttled = false)

    fun attemptStarted(address: String): Status = copy(attempts = attempts + (address to null))

    fun attemptFinished(address: String, outcome: Unreachability.Outcome): Status = copy(attempts = attempts + (address to outcome))

    /** No address connected: the round's own evidence replaces "remote access is off" (4005). */
    fun roundFailed(report: Unreachability.Report): Status = copy(report = report, remoteOff = false, attempts = emptyMap())

    /** A pinned handshake completed: whatever follows on that connection, the last failure no longer applies. */
    fun pinnedOpen(address: String): Status = copy(state = State.CONNECTING, address = address, report = null, attempts = emptyMap())

    fun waiting(retryAt: Long, throttled: Boolean): Status = copy(state = State.WAITING, retryAt = retryAt, since = null, throttled = throttled)

    fun paused(): Status = copy(report = null, attempts = emptyMap(), throttled = false)

    /** "Try now" is offered while waiting, unless throttled or paused (`desktop-connection`, D7). */
    fun canTryNow(paused: Boolean): Boolean = state == State.WAITING && !throttled && !paused
}

object StatusBoard {
    private val mutable = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutable

    fun update(change: (Status) -> Status) = mutable.update(change)

    fun recordEvent(event: Status.RecentEvent) = update { it.copy(recent = (listOf(event) + it.recent).take(10)) }
}
