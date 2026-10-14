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
    val recent: List<RecentEvent> = emptyList(),
) {
    enum class State { STOPPED, CONNECTING, CONNECTED, WAITING, REMOTE_OFF }

    data class RecentEvent(val kind: String, val title: String, val receivedAt: Long, val outcome: String)
}

object StatusBoard {
    private val mutable = MutableStateFlow(Status())
    val status: StateFlow<Status> = mutable

    fun update(change: (Status) -> Status) = mutable.update(change)

    fun recordEvent(event: Status.RecentEvent) = update { it.copy(recent = (listOf(event) + it.recent).take(10)) }
}
