package com.spekterm.android.desktop

import com.spekterm.android.log.ConnectionLog.Kind
import com.spekterm.android.net.ConnectionClosedException
import com.spekterm.android.net.ConversationEnded
import com.spekterm.android.net.ConversationUpdate
import com.spekterm.android.net.ConversationWait
import com.spekterm.android.net.Frames
import com.spekterm.android.net.RemoteConnection
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.SendResult
import com.spekterm.android.net.ServerFrame
import com.spekterm.android.net.SessionList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop as the screens see it (`session-conversation` design D1): the session list, the conversation of
 * the focused session, and requests over the service's single connection. The service feeds it
 * ([attach], [onEvent], [detach]); the screens observe its flows and call [focus], [wake], [send].
 *
 * Every transition runs on one single-threaded dispatcher, so a stamp comparison and the update that follows are
 * atomic. Everything started for a connection runs in that connection's scope, cancelled when it detaches, and
 * frames of any other connection are discarded. Conversation content is held in memory only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object Desktop {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Where codes and outcomes are logged (never text). Set by the app. */
    @Volatile var log: (Kind, Array<out Pair<String, Any?>>) -> Unit = { _, _ -> }

    @Volatile var now: () -> Long = System::currentTimeMillis

    private val mutableSessions = MutableStateFlow(SessionsState())
    val sessions: StateFlow<SessionsState> = mutableSessions

    private val mutableConversation = MutableStateFlow<Conversation?>(null)

    /** The conversation of the session last focused, kept while unfocused until another is focused. */
    val conversation: StateFlow<Conversation?> = mutableConversation

    private val mutableConnected = MutableStateFlow(false)

    /** Whether a connection is attached (authenticated, attention watch answered). */
    val connected: StateFlow<Boolean> = mutableConnected

    /** The session whose conversation is on screen: no notification is raised for it (design D8). */
    @Volatile var visibleSession: String? = null
        private set

    private class Attached(val connection: RemoteConnection, val scope: CoroutineScope)

    private data class Focus(val owner: String, val sessionId: String)

    private var attached: Attached? = null
    private var focus: Focus? = null
    private val clock = ListClock()
    private var nextReplyId = 0L

    // ---- Hooks for the service ----

    suspend fun attach(connection: RemoteConnection) = withContext(dispatcher) {
        attached?.scope?.cancel()
        val a = Attached(connection, CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + dispatcher))
        attached = a
        clock.reset()
        mutableConnected.value = true
        a.scope.launch {
            val answer = request(a, "sessions.list") ?: return@launch
            if (answer.ok) SessionList.decode(answer.result)?.let { applyList(a, answer.seq, it) }
            log(Kind.LIST, "error" to answer.error?.code)
            reconcile()
        }
    }

    suspend fun onEvent(connection: RemoteConnection, event: ServerFrame.Event) = withContext(dispatcher) {
        val a = attached?.takeIf { it.connection === connection } ?: return@withContext
        when (event.kind) {
            "sessions.changed" -> SessionList.decode(event.data)?.let { applyList(a, event.seq, it) }
            "conversation.update" -> ConversationUpdate.decode(event.data)?.let { update ->
                change(update.sessionId) { ConversationReducer.apply(it, update, now()) }
            }
            "conversation.wait" -> Frames.decodeOrNull<ConversationWait>(event.data)?.let { wait ->
                change(wait.sessionId) { ConversationReducer.waited(it, wait.state, wait.pending) }
            }
            "conversation.ended" -> Frames.decodeOrNull<ConversationEnded>(event.data)?.let { ended ->
                val c = mutableConversation.value
                if (c != null && c.sessionId == ended.sessionId && c.watch.active) {
                    mutableConversation.value = ConversationReducer.ended(c)
                    log(Kind.CONVERSATION, "event" to "ended", "reason" to ended.reason)
                }
            }
        }
    }

    suspend fun detach(connection: RemoteConnection) = withContext(dispatcher) {
        val a = attached?.takeIf { it.connection === connection } ?: return@withContext
        a.scope.cancel()
        attached = null
        mutableConnected.value = false
        mutableSessions.value = mutableSessions.value.copy(current = false)
        mutableConversation.value?.let { mutableConversation.value = ConversationReducer.disconnected(it) }
    }

    // ---- Calls for the screens ----

    /** The screen [owner] (its back-stack entry id) shows [sessionId] and is visible. */
    fun focus(owner: String, sessionId: String) {
        visibleSession = sessionId
        scope.launch {
            focus = Focus(owner, sessionId)
            visibleSession = sessionId
            val c = mutableConversation.value
            if (c == null || c.sessionId != sessionId) {
                // Another session: drop what was held for the previous one, after unwatching it.
                if (c != null && c.watch.active) unwatch(c)
                mutableConversation.value = Conversation(sessionId, listedRunning = listedRunning(sessionId))
            }
            reconcile()
        }
    }

    /** The screen [owner] is no longer visible. Ignored unless [owner] holds the focus. */
    fun unfocus(owner: String) {
        scope.launch {
            if (focus?.owner != owner) return@launch
            focus = null
            visibleSession = null
            reconcile()
        }
    }

    /** Keeps the draft of [sessionId] across leaving its screen. */
    fun setDraft(sessionId: String, text: String) {
        scope.launch { change(sessionId, requireWatch = false) { it.copy(draft = text) } }
    }

    /** Wakes a dormant session. `null` on success, else the desktop's error, or [NOT_CONNECTED]. */
    suspend fun wake(sessionId: String): RemoteError? = withContext(dispatcher) {
        val a = attached ?: return@withContext RemoteError(NOT_CONNECTED)
        val answer = a.scope.async { request(a, "sessions.wake", Frames.sessionsWake(sessionId)) }.await()
            ?: return@withContext RemoteError(NOT_CONNECTED)
        log(Kind.WAKE, "error" to answer.error?.code)
        if (answer.ok && attached === a) {
            change(sessionId, requireWatch = false) { ConversationReducer.cleared(it) }
            reconcile()
        }
        answer.error
    }

    /**
     * Sends [text] to the watched [sessionId]. The reply shows at once; its outcome arrives in [conversation].
     * Returns false when the gate refuses (the screen shows why).
     */
    suspend fun send(sessionId: String, text: String): Boolean = withContext(dispatcher) {
        val a = attached
        val c = mutableConversation.value?.takeIf { it.sessionId == sessionId } ?: return@withContext false
        val inFlight = c.replies.any { it.state == Reply.State.SENDING }
        if (a == null || Replies.blocked(true, false, c.watch, c.wait, c.pending, inFlight, text) != null) return@withContext false
        val id = ++nextReplyId
        mutableConversation.value = Replies.sent(c, id, text)
        a.scope.launch {
            val answer = request(a, "conversation.send", Frames.conversationSend(sessionId, text), SEND_TIMEOUT_MS)
            val outcome = SendOutcome.of(answer?.result?.let { Frames.decodeOrNull<SendResult>(it) }?.outcome, answer?.error?.code)
            log(Kind.SEND, "outcome" to outcome.javaClass.simpleName, "error" to answer?.error?.code, "length" to text.length)
            change(sessionId, requireWatch = false) { Replies.answered(it, id, outcome, now()) }
        }
        true
    }

    /** Unpaired: nothing of the old desktop is kept. */
    fun clear() {
        scope.launch {
            attached?.scope?.cancel()
            attached = null
            focus = null
            visibleSession = null
            clock.reset()
            mutableConnected.value = false
            mutableSessions.value = SessionsState()
            mutableConversation.value = null
        }
    }

    /** Clears every state, for instrumented tests; waits until done. */
    suspend fun resetForTest() = withContext(dispatcher) {
        attached?.scope?.cancel()
        attached = null
        focus = null
        visibleSession = null
        clock.reset()
        nextReplyId = 0
        mutableConnected.value = false
        mutableSessions.value = SessionsState()
        mutableConversation.value = null
    }

    // ---- Internals, on the dispatcher ----

    private fun applyList(a: Attached, seq: Long, list: SessionList) {
        if (attached !== a || !clock.accept(seq)) return
        mutableSessions.value = SessionsState(list, current = true, receivedAt = now())
        mutableConversation.value?.let { mutableConversation.value = ConversationReducer.listed(it, list) }
        reconcile()
    }

    /**
     * Brings the watch in line with the focus and the list (design D3): watch the focused, running agent session
     * when nothing is requested or watching; unwatch when unfocused, or when the list shows it not running.
     */
    private fun reconcile() {
        val a = attached ?: return
        val c = mutableConversation.value ?: return
        val f = focus
        val list = mutableSessions.value.takeIf { it.current }?.list
        val session = list?.sessions?.firstOrNull { it.id == c.sessionId }
        if (f == null || f.sessionId != c.sessionId) {
            if (c.watch.active) unwatch(c)
            return
        }
        if (c.watch.active) {
            if (list != null && list.window && session?.running != true) unwatch(c)
            return
        }
        val watchable = list != null && list.window && session != null && session.running && session.isAgent
        if (!watchable || c.watch != Watch.Idle || c.notRunning) return
        val sessionId = c.sessionId
        val after = c.afterUuid
        mutableConversation.value = ConversationReducer.requested(c)
        a.scope.launch {
            val answer = request(a, "conversation.watch", Frames.conversationWatch(sessionId, after)) ?: return@launch
            log(Kind.CONVERSATION, "event" to "watch", "resume" to (after != null), "error" to answer.error?.code)
            val now = mutableConversation.value
            if (attached !== a || now == null || now.sessionId != sessionId || now.watch != Watch.Requested) return@launch
            mutableConversation.value = ConversationReducer.answered(now, answer.error?.code)
        }
    }

    private fun unwatch(c: Conversation) {
        if (mutableConversation.value?.sessionId == c.sessionId) mutableConversation.value = ConversationReducer.unwatched(mutableConversation.value!!)
        val a = attached ?: return
        a.scope.launch {
            val answer = request(a, "conversation.unwatch")
            log(Kind.CONVERSATION, "event" to "unwatch", "error" to answer?.error?.code)
        }
    }

    /**
     * Applies [transform] to the held conversation if it is [sessionId]'s. Desktop events apply only while a
     * watch is requested or running, so updates of a follow already left are dropped.
     */
    private inline fun change(sessionId: String, requireWatch: Boolean = true, transform: (Conversation) -> Conversation) {
        val c = mutableConversation.value ?: return
        if (c.sessionId != sessionId || (requireWatch && !c.watch.active)) return
        mutableConversation.value = transform(c)
    }

    private fun listedRunning(sessionId: String): Boolean? {
        val state = mutableSessions.value
        if (!state.current) return null
        return state.list?.takeIf { it.window }?.let { list -> list.sessions.firstOrNull { it.id == sessionId }?.running ?: false }
    }

    /** A request on [a]; `null` when the connection ended first (its detach then updates the state). */
    private suspend fun request(a: Attached, method: String, params: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()), timeoutMs: Long = 20_000) =
        try {
            a.connection.call(method, params, timeoutMs)
        } catch (_: ConnectionClosedException) {
            null
        }

    private fun log(kind: Kind, vararg fields: Pair<String, Any?>) = log.invoke(kind, fields)

    const val NOT_CONNECTED = "NOT_CONNECTED"

    /** Replies to one session queue on the desktop, and a call with no answer ends the connection. */
    const val SEND_TIMEOUT_MS = 30_000L
}
