package com.spekterm.android.desktop

import com.spekterm.android.log.ConnectionLog.Kind
import com.spekterm.android.net.AnswerResult
import com.spekterm.android.net.ConnectionClosedException
import com.spekterm.android.net.ConversationEnded
import com.spekterm.android.net.CreateResult
import com.spekterm.android.net.ConversationUpdate
import com.spekterm.android.net.ConversationWait
import com.spekterm.android.net.Frames
import com.spekterm.android.net.HandoffBrief
import com.spekterm.android.net.HandoffEntry
import com.spekterm.android.net.OpenSpec
import com.spekterm.android.net.Operations
import com.spekterm.android.net.OsChange
import com.spekterm.android.net.OsChangeDetail
import com.spekterm.android.net.OsChanges
import com.spekterm.android.net.OsSpec
import com.spekterm.android.net.OsSpecSummary
import com.spekterm.android.net.OsArtifact
import com.spekterm.android.net.Piece
import com.spekterm.android.net.PutResult
import com.spekterm.android.net.RemoteConnection
import com.spekterm.android.net.RemoteError
import com.spekterm.android.net.SendResult
import com.spekterm.android.net.ServerFrame
import com.spekterm.android.net.SessionList
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    private val mutableHandoffs = MutableStateFlow(HandoffsState())

    /** The desktop's handoff list (`session-relations` D3): kept across a detach, emptied by [clear]. */
    val handoffs: StateFlow<HandoffsState> = mutableHandoffs

    private val mutableConversation = MutableStateFlow<Conversation?>(null)

    /** The conversation of the session last focused, kept while unfocused until another is focused. */
    val conversation: StateFlow<Conversation?> = mutableConversation

    private val mutableConnected = MutableStateFlow(false)

    /** Whether a connection is attached (authenticated, attention watch answered). */
    val connected: StateFlow<Boolean> = mutableConnected

    private val mutableOperations = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The operations the desktop listed at the last authentication (design D1): kept across a detach, so controls
     * stay in place while unusable; replaced at the next authentication, emptied by [clear].
     */
    val operations: StateFlow<Set<String>> = mutableOperations

    /** Bumped at every attach: an upload that got no answer waits for the next connection (design D6). */
    private val attachments = MutableStateFlow(0L)

    private val mutableCreation = MutableStateFlow<Creation?>(null)

    /** A session being started from the phone, and its outcome until dismissed (`session-create`). */
    val creation: StateFlow<Creation?> = mutableCreation

    /** Whether the session list is on screen: a created session opens by itself only then (design D7). */
    @Volatile var listOnScreen: Boolean = false
        private set

    /** How long a created session may take to be listed running (design D6); shortened by tests. */
    @Volatile var createWaitMs: Long = 10_000L

    private val mutableOpenspec = MutableStateFlow<Map<OpenSpecTarget, OpenSpecLoaded>>(emptyMap())

    /**
     * What the OpenSpec screens show (`openspec-browse` design D4): the last read of each recent target, with
     * whether it is pending and whether its folder is not followed live. In memory only.
     */
    val openspec: StateFlow<Map<OpenSpecTarget, OpenSpecLoaded>> = mutableOpenspec

    /** How long after the last OpenSpec screen is left the folder's watch stops (design D3); shortened by tests. */
    @Volatile var openspecLingerMs: Long = 5_000L

    /** The session whose conversation is on screen: no notification is raised for it (design D8). */
    @Volatile var visibleSession: String? = null
        private set

    private class Attached(val connection: RemoteConnection, val scope: CoroutineScope)

    private data class Focus(val owner: String, val sessionId: String)

    private var attached: Attached? = null
    private var focus: Focus? = null
    private val clock = ListClock()
    private val seen = SeenTransitions()
    private var nextReplyId = 0L
    private var pendingCreate: CreateTracker.Pending? = null
    private var createTimer: Job? = null
    private var nextCreateId = 0L
    private var nextAttachmentId = 0L
    private var uploadJob: Job? = null

    private var tracker = OpenSpecTracker()
    /** In order of last focus, oldest first (design D4). */
    private val openspecCache = LinkedHashMap<OpenSpecTarget, OpenSpecLoaded>()
    private var openspecLinger: Job? = null
    /** Why the focused target is stale, for the log: the last input that made it so. */
    private var openspecCause = "focus"

    /** Bumped when everything is dropped ([clear]): a create of an older generation writes nothing. */
    private var generation = 0L

    // ---- Hooks for the service ----

    /**
     * Called with the sessions an accepted list shows newly ready and looked at (`session-relations` D8), once per
     * list, never with none; set by the service to remove their waiting notifications.
     */
    @Volatile var onSeen: ((List<String>) -> Unit)? = null

    suspend fun attach(connection: RemoteConnection, operations: Set<String> = emptySet()) = withContext(dispatcher) {
        attached?.scope?.cancel()
        val a = Attached(connection, CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + dispatcher))
        attached = a
        clock.reset()
        seen.reset()
        mutableHandoffs.value = mutableHandoffs.value.attached()
        mutableOperations.value = operations
        mutableConnected.value = true
        attachments.value++
        pumpUploads()
        openspecCause = "attach"
        openspecStep(tracker.attached(live = Operations.OPENSPEC_WATCH in operations))
        a.scope.launch {
            val answer = request(a, "sessions.list") ?: return@launch
            if (answer.ok) SessionList.decode(answer.result)?.let { applyList(a, answer.seq, it) }
            log(Kind.LIST, "error" to answer.error?.code)
            reconcile()
        }
        if (Operations.HANDOFFS_LIST in operations) {
            a.scope.launch {
                val answer = request(a, Operations.HANDOFFS_LIST) ?: return@launch
                val list = if (answer.ok) HandoffEntry.decodeList(answer.result) else null
                list?.let { applyHandoffs(a, answer.seq, it) }
                log(Kind.HANDOFFS, "error" to answer.error?.code, "count" to list?.size)
            }
        }
    }

    suspend fun onEvent(connection: RemoteConnection, event: ServerFrame.Event) = withContext(dispatcher) {
        val a = attached?.takeIf { it.connection === connection } ?: return@withContext
        when (event.kind) {
            "sessions.changed" -> SessionList.decode(event.data)?.let { applyList(a, event.seq, it) }
            "handoffs.changed" -> HandoffEntry.decodeList(event.data)?.let { applyHandoffs(a, event.seq, it) }
            "conversation.update" -> ConversationUpdate.decode(event.data)?.let { update ->
                change(update.sessionId) { ConversationReducer.apply(it, update, now()) }
            }
            "conversation.wait" -> ConversationWait.decode(event.data)?.let { wait ->
                change(wait.sessionId) { ConversationReducer.waited(it, wait.state, wait.pending, now(), wait.prompt) }
            }
            "conversation.ended" -> Frames.decodeOrNull<ConversationEnded>(event.data)?.let { ended ->
                val c = mutableConversation.value
                if (c != null && c.sessionId == ended.sessionId && c.watch.active) {
                    setHeld(ConversationReducer.ended(c, ended.reason))
                    log(Kind.CONVERSATION, "event" to "ended", "reason" to ended.reason)
                }
            }
            "openspec.changed" -> ((event.data as? JsonObject)?.get("folderId") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { folderId ->
                openspecCause = "event"
                openspecStep(tracker.changed(folderId))
            }
        }
    }

    suspend fun detach(connection: RemoteConnection) = withContext(dispatcher) {
        val a = attached?.takeIf { it.connection === connection } ?: return@withContext
        a.scope.cancel()
        attached = null
        mutableConnected.value = false
        mutableSessions.value = mutableSessions.value.copy(current = false)
        mutableHandoffs.value = mutableHandoffs.value.detached()
        track(CreateTracker.detached(trackerState(), a))
        mutableConversation.value?.let { mutableConversation.value = ConversationReducer.disconnected(it) }
        openspecStep(tracker.detached())
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
                setHeld(Conversation(sessionId, listedRunning = listedRunning(sessionId)))
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

    /** Renames [sessionId] (design D8); `""` hands naming back. `null` on success; the label follows the list. */
    suspend fun rename(sessionId: String, name: String): RemoteError? = withContext(dispatcher) {
        val a = attached ?: return@withContext RemoteError(NOT_CONNECTED)
        val answer = a.scope.async { request(a, "sessions.rename", Frames.sessionsRename(sessionId, name)) }.await()
        log(Kind.RENAME, "cleared" to name.isEmpty(), "error" to (answer?.error?.code ?: if (answer == null) NOT_CONNECTED else null))
        answer?.error ?: if (answer == null) RemoteError(NOT_CONNECTED) else null
    }

    /**
     * Keeps [sessionId], whose agent offered to close it (`close-offer`, D4). Nothing held changes: the offer goes when
     * the desktop's list drops the mark.
     */
    suspend fun keep(sessionId: String): RemoteError? = withContext(dispatcher) {
        val a = attached ?: return@withContext RemoteError(NOT_CONNECTED)
        val answer = a.scope.async { request(a, "sessions.keep", Frames.sessionsKeep(sessionId)) }.await()
        val error = answer?.error ?: if (answer == null) RemoteError(NOT_CONNECTED) else null
        log(Kind.KEEP, "error" to error?.code)
        error
    }

    /** The answer to [brief]: the note and report, or why there is none (`NOT_FOUND`, [NOT_CONNECTED], …). */
    data class BriefAnswer(val brief: HandoffBrief?, val error: RemoteError?)

    /** Reads the handoff note of [sessionId] (`session-relations` D4). Nothing is kept: the screen holds it. */
    suspend fun brief(sessionId: String): BriefAnswer = withContext(dispatcher) {
        val a = attached ?: return@withContext BriefAnswer(null, RemoteError(NOT_CONNECTED)).also { log(Kind.BRIEF, "error" to NOT_CONNECTED) }
        val answer = a.scope.async { request(a, Operations.HANDOFFS_BRIEF, Frames.handoffsBrief(sessionId)) }.await()
        val brief = if (answer?.ok == true) HandoffBrief.decode(answer.result) ?: HandoffBrief(null, null) else null
        val error = answer?.error ?: if (answer == null) RemoteError(NOT_CONNECTED) else null
        log(Kind.BRIEF, "error" to error?.code, "hasBody" to (brief?.body != null), "hasReport" to (brief?.report != null))
        BriefAnswer(brief, error)
    }

    /** Hibernates [sessionId] (design D9); [force] only after a busy refusal the user agreed to override. */
    suspend fun hibernate(sessionId: String, force: Boolean): RemoteError? =
        sessionAction(Kind.HIBERNATE, "sessions.hibernate", Frames.sessionsHibernate(sessionId, force), force)

    /** Closes [sessionId] (design D9), after the user confirmed. Its attachments go with it on success. */
    suspend fun close(sessionId: String, force: Boolean): RemoteError? {
        val error = sessionAction(Kind.CLOSE, "sessions.close", Frames.sessionsClose(sessionId, force), force)
        if (error == null) withContext(dispatcher) { change(sessionId, requireWatch = false) { ConversationReducer.gone(it) } }
        return error
    }

    private suspend fun sessionAction(kind: Kind, method: String, params: kotlinx.serialization.json.JsonObject, force: Boolean): RemoteError? = withContext(dispatcher) {
        val a = attached ?: return@withContext RemoteError(NOT_CONNECTED)
        val answer = a.scope.async { request(a, method, params) }.await()
        val error = answer?.error ?: if (answer == null) RemoteError(NOT_CONNECTED) else null
        log(kind, "force" to force, "error" to error?.code, "reason" to error?.detailString("reason"))
        error
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
        if (a == null || Replies.blocked(true, false, c.watch, c.wait, c.pending, inFlight, text, c.attachments) != null) return@withContext false
        val id = ++nextReplyId
        val carried = c.attachments
        val ids = carried.mapNotNull { it.ready?.attachmentId }
        mutableConversation.value = Replies.sent(c, id, text, carried)
        a.scope.launch {
            val answer = request(a, "conversation.send", Frames.conversationSend(sessionId, text, ids), SEND_TIMEOUT_MS)
            val result = answer?.result?.let { Frames.decodeOrNull<SendResult>(it) }
            val outcome = SendOutcome.of(result?.outcome, answer?.error?.code)
            log(Kind.SEND, "outcome" to outcome.javaClass.simpleName, "error" to answer?.error?.code, "length" to text.length, "attachments" to ids.size)
            change(sessionId, requireWatch = false) { Replies.answered(it, id, outcome, now(), result?.text) }
            // Files of attachments that were spent, or that did not come back to the composer, are not needed.
            dropFilesNotHeld(carried)
            if (outcome is SendOutcome.Refused) pumpUploads()
        }
        true
    }

    /**
     * Answers the held prompt [promptId] of the watched [sessionId] (design D4). Never retried. Returns false when
     * the gate refuses: another prompt is held, an answer to it is in flight, or the desktop does not answer.
     */
    suspend fun answer(sessionId: String, promptId: String, decision: String, selections: List<List<Int>>? = null): Boolean = withContext(dispatcher) {
        val a = attached ?: return@withContext false
        val c = mutableConversation.value?.takeIf { it.sessionId == sessionId } ?: return@withContext false
        val prompt = c.prompt?.takeIf { it.id == promptId } ?: return@withContext false
        if (c.watch != Watch.Watching || Operations.ANSWER !in mutableOperations.value) return@withContext false
        if (c.answer?.let { it.promptId == promptId && it.inFlight } == true) return@withContext false
        mutableConversation.value = c.copy(answer = AnswerState(promptId, decision))
        val kind = when (prompt) {
            is com.spekterm.android.net.Prompt.Permission -> "permission"
            is com.spekterm.android.net.Prompt.Question -> "question"
            is com.spekterm.android.net.Prompt.Plan -> "plan"
        }
        a.scope.launch {
            val answer = request(a, "conversation.answer", Frames.conversationAnswer(sessionId, promptId, decision, selections), ANSWER_TIMEOUT_MS)
            val outcome = AnswerOutcome.of(answer?.result?.let { Frames.decodeOrNull<AnswerResult>(it) }?.outcome, answer?.error?.code)
            log(Kind.ANSWER, "kind" to kind, "decision" to decision, "outcome" to outcome.javaClass.simpleName, "error" to answer?.error?.code, "open" to prompt.open)
            // Written back also when the screen was left meanwhile (design D4); a newer answer is not overwritten.
            change(sessionId, requireWatch = false) { held ->
                val mine = held.answer?.takeIf { it.promptId == promptId && it.inFlight } ?: return@change held
                held.copy(answer = mine.copy(outcome = outcome))
            }
        }
        true
    }

    /** The user dismissed the note about the last answer. */
    fun dismissAnswer(sessionId: String) {
        scope.launch { change(sessionId, requireWatch = false) { c -> if (c.answer?.inFlight == true) c else c.copy(answer = null) } }
    }

    // ---- Attachments (design D5, D6) ----

    /**
     * Makes room for picks named [names] in [sessionId]'s composer: as many as fit under the reply's limit, each
     * `Preparing`. Returns their ids in order; the rest are dropped, and the composer says so.
     */
    suspend fun addAttachments(sessionId: String, names: List<String>): List<Long> = withContext(dispatcher) {
        val c = mutableConversation.value?.takeIf { it.sessionId == sessionId && !it.closed } ?: return@withContext emptyList()
        val room = (Uploads.MAX_ATTACHMENTS - c.attachments.size).coerceAtLeast(0)
        val added = names.take(room).map { Attachment(++nextAttachmentId, it) }
        mutableConversation.value = c.copy(
            attachments = c.attachments + added,
            attachNote = if (names.size > room) AttachNote.TOO_MANY_PICKED else null,
        )
        added.map { it.localId }
    }

    /** [localId] was prepared into [file]. A preparation for an attachment no longer held deletes its output. */
    fun prepared(localId: Long, file: File, name: String, size: Long, converted: Boolean) {
        scope.launch {
            val held = heldAttachment(localId)
            if (held == null || held.state != AttachmentState.Preparing) {
                deleteFiles(listOf(file))
                return@launch
            }
            updateAttachment(localId) { it.copy(name = name, size = size, file = file, converted = converted, state = AttachmentState.Queued) }
            log(Kind.ATTACH, "event" to (if (converted) "converted" else "prepared"), "bytes" to size)
            pumpUploads()
        }
    }

    /** [localId] could not be attached: it leaves the composer, which says why. */
    fun prepareFailed(localId: Long, note: AttachNote) {
        scope.launch {
            val c = mutableConversation.value ?: return@launch
            if (c.attachments.none { it.localId == localId }) return@launch
            mutableConversation.value = c.copy(attachments = c.attachments.filter { it.localId != localId }, attachNote = note)
            log(Kind.ATTACH, "event" to "refused", "error" to note.name)
        }
    }

    /** Removes [localId] from the composer; its upload stops and its file is deleted. */
    fun removeAttachment(localId: Long) {
        scope.launch {
            val c = mutableConversation.value ?: return@launch
            val gone = c.attachments.firstOrNull { it.localId == localId } ?: return@launch
            mutableConversation.value = c.copy(attachments = c.attachments - gone)
            deleteFiles(listOfNotNull(gone.file))
            log(Kind.ATTACH, "event" to "removed")
        }
    }

    /** Uploads a failed attachment again, from the start. */
    fun retryAttachment(localId: Long) {
        scope.launch {
            if (heldAttachment(localId)?.state !is AttachmentState.Failed) return@launch
            updateAttachment(localId) { it.copy(state = if (it.file == null) it.state else AttachmentState.Queued, notFoundSends = 0) }
            pumpUploads()
        }
    }

    fun dismissAttachNote(sessionId: String) {
        scope.launch { change(sessionId, requireWatch = false) { it.copy(attachNote = null) } }
    }

    /**
     * Starts an agent session in [folderId] (`null`: the global item), named [name] on screen (design D6). Runs
     * in [Desktop]'s own scope, so leaving the screen does not abandon it; does nothing while not attached or
     * while another start is in progress. Never retried.
     */
    fun create(folderId: String?, name: String) {
        scope.launch {
            val a = attached ?: return@launch
            if (mutableCreation.value is Creation.Starting) return@launch
            val gen = generation
            val id = ++nextCreateId
            mutableCreation.value = Creation.Starting(name)
            try {
                val answer = request(a, "sessions.create", Frames.sessionsCreate(folderId), CREATE_TIMEOUT_MS)
                if (generation != gen) return@launch
                val outcome = CreateOutcome.of(
                    answered = answer != null,
                    sessionId = answer?.result?.let { Frames.decodeOrNull<CreateResult>(it) }?.sessionId,
                    code = answer?.error?.code,
                    message = answer?.error?.message,
                )
                log(Kind.CREATE, "global" to (folderId == null), "outcome" to outcome.logName(), "error" to answer?.error?.code)
                val list = mutableSessions.value.takeIf { it.current && attached === a }?.list
                track(CreateTracker.answered(trackerState(), id, name, outcome, a, attached, list, listOnScreen))
                if (pendingCreate?.id == id) {
                    createTimer = scope.launch {
                        delay(createWaitMs)
                        track(CreateTracker.timedOut(trackerState(), id))
                    }
                }
            } finally {
                // Every exit leaves an outcome, unless the answer is waiting for its list or all was cleared.
                if (generation == gen && pendingCreate == null && mutableCreation.value is Creation.Starting) {
                    mutableCreation.value = Creation.Failed(name, Creation.Reason.UNKNOWN)
                }
            }
        }
    }

    /** The session list is on screen ([shown]) or not; leaving it keeps a created session from opening by itself. */
    fun setListOnScreen(shown: Boolean) {
        listOnScreen = shown
        if (!shown) scope.launch { track(CreateTracker.leftList(trackerState())) }
    }

    /** The outcome of a start was shown and acted on or dismissed. Ignored while starting. */
    fun consumeCreation() {
        scope.launch { if (mutableCreation.value !is Creation.Starting) mutableCreation.value = null }
    }

    // ---- OpenSpec (`openspec-browse`) ----

    /** The screen [owner] (its back-stack entry id) shows [target] and is visible: watch its folder, then read it. */
    fun openspecFocus(owner: String, target: OpenSpecTarget) {
        scope.launch {
            openspecLinger?.cancel()
            openspecLinger = null
            // Most recently focused last; the oldest beyond the bound go, never the focused one.
            openspecCache.remove(target)?.let { openspecCache[target] = it }
            openspecCause = "focus"
            openspecStep(tracker.focus(owner, target))
            while (openspecCache.size > OPENSPEC_CACHE) openspecCache.remove(openspecCache.keys.first())
            publishOpenspec()
        }
    }

    /** The screen [owner] is no longer visible. The watch stops after the linger unless a screen takes the focus. */
    fun openspecUnfocus(owner: String) {
        scope.launch {
            if (tracker.focus?.owner != owner) return@launch
            openspecStep(tracker.unfocus(owner))
            openspecLinger?.cancel()
            openspecLinger = scope.launch {
                delay(openspecLingerMs)
                openspecStep(tracker.idle())
            }
        }
    }

    /** Reads the focused target again; on a live desktop, first watches a folder whose watch was refused. */
    fun openspecRefresh() {
        scope.launch {
            openspecCause = "refresh"
            openspecStep(tracker.refresh())
        }
    }

    /** Unpaired: nothing of the old desktop is kept. */
    fun clear() {
        scope.launch {
            attached?.scope?.cancel()
            attached = null
            focus = null
            visibleSession = null
            clock.reset()
            seen.reset()
            mutableConnected.value = false
            mutableOperations.value = emptySet()
            mutableSessions.value = SessionsState()
            mutableHandoffs.value = HandoffsState()
            setHeld(null)
            dropCreation()
            dropOpenspec()
        }
    }

    /** Clears every state, for instrumented tests; waits until done. */
    suspend fun resetForTest() = withContext(dispatcher) {
        attached?.scope?.cancel()
        attached = null
        focus = null
        visibleSession = null
        clock.reset()
        seen.reset()
        nextReplyId = 0
        mutableConnected.value = false
        mutableOperations.value = emptySet()
        mutableSessions.value = SessionsState()
        mutableHandoffs.value = HandoffsState()
        setHeld(null)
        dropCreation()
        dropOpenspec()
        listOnScreen = false
        createWaitMs = 10_000L
        openspecLingerMs = 5_000L
    }

    // ---- Internals, on the dispatcher ----

    private fun trackerState() = CreateTracker.State(pendingCreate, mutableCreation.value)

    /** Applies a tracker step; when a wait ends, its timer goes and the outcome is logged. */
    private fun track(next: CreateTracker.State) {
        val waited = pendingCreate
        pendingCreate = next.pending
        mutableCreation.value = next.creation
        if (waited != null && next.pending == null) {
            createTimer?.cancel()
            createTimer = null
            log(Kind.CREATE, "event" to (if (next.creation is Creation.Opened) "listed" else "unlisted"))
        }
    }

    private fun dropCreation() {
        generation++
        createTimer?.cancel()
        createTimer = null
        pendingCreate = null
        mutableCreation.value = null
    }

    private fun dropOpenspec() {
        openspecLinger?.cancel()
        openspecLinger = null
        tracker = OpenSpecTracker()
        openspecCache.clear()
        publishOpenspec()
    }

    /** Applies a tracker step and sends what it asks for. */
    private fun openspecStep(step: OpenSpecTracker.Step) {
        tracker = step.state
        step.watch?.let { openspecWatch(it.folderId) }
        step.read?.let { openspecRead(it, openspecCause) }
        publishOpenspec()
    }

    private fun publishOpenspec() {
        val shown = LinkedHashMap(openspecCache)
        tracker.focus?.target?.let { if (it !in shown) shown[it] = OpenSpecLoaded() }
        mutableOpenspec.value = shown.mapValues { (target, loaded) ->
            loaded.copy(pending = tracker.pending(target), notLive = tracker.notLive(target))
        }
    }

    private fun openspecWatch(folderId: String?) {
        val a = attached ?: return
        a.scope.launch {
            // No answer: the connection ended, and its detach resets the tracker.
            val answer = request(a, Operations.OPENSPEC_WATCH, Frames.openspecWatch(folderId), OPENSPEC_TIMEOUT_MS) ?: return@launch
            log(Kind.OPENSPEC, "op" to "watch", "stop" to (folderId == null), "error" to answer.error?.code)
            if (attached !== a) return@launch
            openspecStep(tracker.watchAnswered(answer.ok))
        }
    }

    private fun openspecRead(target: OpenSpecTarget, cause: String) {
        val a = attached ?: return
        a.scope.launch {
            val (method, params) = when (target) {
                is OpenSpecTarget.Changes -> Operations.OPENSPEC_CHANGES to Frames.openspecChanges(target.folderId)
                is OpenSpecTarget.Specs -> Operations.OPENSPEC_SPECS to Frames.openspecSpecs(target.folderId)
                is OpenSpecTarget.Change -> Operations.OPENSPEC_CHANGE to Frames.openspecChange(target.folderId, target.slug)
                is OpenSpecTarget.Spec -> Operations.OPENSPEC_SPEC to Frames.openspecSpec(target.folderId, target.topic)
            }
            val answer = request(a, method, params, OPENSPEC_TIMEOUT_MS) ?: return@launch
            if (attached !== a) return@launch
            val value: Any? = answer.result?.takeIf { answer.ok }?.let {
                when (target) {
                    is OpenSpecTarget.Changes -> OpenSpec.changes(it)
                    is OpenSpecTarget.Specs -> OpenSpec.specs(it)
                    is OpenSpecTarget.Change -> OpenSpec.changeDetail(it)
                    is OpenSpecTarget.Spec -> OpenSpec.spec(it)
                }
            }
            val error = answer.error ?: if (value == null) RemoteError(RemoteError.INTERNAL, "malformed answer") else null
            val (items, omitted) = openspecCounts(value)
            log(Kind.OPENSPEC, "op" to method.removePrefix("openspec."), "cause" to cause, "error" to error?.code, "items" to items, "omitted" to omitted)
            val before = openspecCache[target] ?: OpenSpecLoaded()
            openspecCache[target] = if (error == null) {
                OpenSpecLoaded(value = value, readAt = now())
            } else {
                before.copy(error = error)
            }
            if (target is OpenSpecTarget.Change && error?.code == RemoteError.NOT_FOUND && !OpenSpecText.dated(target.slug)) {
                openspecArchivedAs(a, target)
            }
            openspecStep(tracker.readAnswered())
        }
    }

    /** A vanished active change: where was it archived to? One read of the list, outside the tracker (design D5). */
    private fun openspecArchivedAs(a: Attached, target: OpenSpecTarget.Change) {
        a.scope.launch {
            val answer = request(a, Operations.OPENSPEC_CHANGES, Frames.openspecChanges(target.folderId), OPENSPEC_TIMEOUT_MS) ?: return@launch
            val changes = answer.result?.takeIf { answer.ok }?.let { OpenSpec.changes(it) }
            log(Kind.OPENSPEC, "op" to "changes", "cause" to "archived", "error" to (answer.error?.code ?: if (changes == null) RemoteError.INTERNAL else null), "items" to changes?.let { it.active.size + it.archived.size })
            if (attached !== a || changes == null) return@launch
            openspecCache[OpenSpecTarget.Changes(target.folderId)]?.let {
                openspecCache[OpenSpecTarget.Changes(target.folderId)] = it.copy(value = changes, readAt = now(), error = null)
            }
            openspecCache[target]?.let { held ->
                if (held.error?.code == RemoteError.NOT_FOUND) openspecCache[target] = held.copy(archivedAs = OpenSpecText.archivedAs(target.slug, changes.archived))
            }
            publishOpenspec()
        }
    }

    /** For the log: how many items an answer holds, and how many of its pieces were left out. */
    private fun openspecCounts(value: Any?): Pair<Int?, Int?> = when (value) {
        is OsChanges -> (value.active.size + value.archived.size) to null
        is List<*> -> value.size to null
        is OsChangeDetail -> value.artifacts.size to value.artifacts.sumOf { artifact ->
            when (artifact) {
                is OsArtifact.Markdown -> if (artifact.content is Piece.Omitted) 1 else 0
                is OsArtifact.Data -> if (artifact.content is Piece.Omitted) 1 else 0
                is OsArtifact.Tasks -> if (artifact.tasks is Piece.Omitted) 1 else 0
                is OsArtifact.Specs -> artifact.deltas.count { it.content is Piece.Omitted }
                is OsArtifact.Other -> 0
            }
        }
        is OsSpec -> value.history.size to (if (value.content is Piece.Omitted) 1 else 0)
        else -> null to null
    }

    private fun CreateOutcome.logName(): String = when (this) {
        is CreateOutcome.Created -> "created"
        is CreateOutcome.Refused -> reason.name.lowercase()
    }

    private fun applyHandoffs(a: Attached, seq: Long, list: List<HandoffEntry>) {
        if (attached !== a) return
        mutableHandoffs.value.applied(seq, list)?.let { mutableHandoffs.value = it }
    }

    private fun applyList(a: Attached, seq: Long, list: SessionList) {
        if (attached !== a || !clock.accept(seq)) return
        mutableSessions.value = SessionsState(list, current = true, receivedAt = now())
        seen.next(list).takeIf { it.isNotEmpty() }?.let { ids -> onSeen?.invoke(ids) }
        track(CreateTracker.listed(trackerState(), list, a, listOnScreen))
        mutableConversation.value?.let { held ->
            var next = ConversationReducer.listed(held, list)
            // A list with a window that no longer holds the session: closed, or its process ended (design D10).
            if (list.window && list.sessions.none { it.id == held.sessionId } && (!held.closed || held.attachments.isNotEmpty())) {
                next = ConversationReducer.gone(next)
            }
            setHeld(next)
        }
        reconcile()
    }

    /**
     * Replaces the held conversation. Attachments it no longer holds — another session's conversation, or a gone
     * session — lose their upload and their file.
     */
    private fun setHeld(next: Conversation?) {
        val before = mutableConversation.value
        mutableConversation.value = next
        if (before != null && (next == null || next.sessionId != before.sessionId)) {
            uploadJob?.cancel()
            uploadJob = null
        }
        dropFilesNotHeld(before?.attachments.orEmpty())
    }

    /** Deletes the files of [candidates] the held conversation no longer holds, in its composer or a reply. */
    private fun dropFilesNotHeld(candidates: List<Attachment> = emptyList()) {
        val c = mutableConversation.value
        val kept = c?.let { held -> (held.attachments + held.replies.filter { it.state == Reply.State.SENDING }.flatMap { it.attachments }).mapNotNull { it.file } }.orEmpty().toSet()
        deleteFiles(candidates.mapNotNull { it.file }.filter { it !in kept })
    }

    private fun deleteFiles(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch(Dispatchers.IO) { files.forEach { runCatching { it.delete() } } }
    }

    private fun heldAttachment(localId: Long): Attachment? = mutableConversation.value?.attachments?.firstOrNull { it.localId == localId }

    private fun updateAttachment(localId: Long, transform: (Attachment) -> Attachment) {
        val c = mutableConversation.value ?: return
        if (c.attachments.none { it.localId == localId }) return
        mutableConversation.value = c.copy(attachments = c.attachments.map { if (it.localId == localId) transform(it) else it })
    }

    /** Starts the upload worker unless it runs: one attachment at a time, in pick order (design D6). */
    private fun pumpUploads() {
        if (uploadJob?.isActive == true) return
        val c = mutableConversation.value ?: return
        if (c.attachments.none { it.state is AttachmentState.Queued || it.state is AttachmentState.Uploading }) return
        uploadJob = scope.launch { uploadLoop(c.sessionId) }
    }

    private suspend fun uploadLoop(sessionId: String) {
        while (true) {
            val c = mutableConversation.value?.takeIf { it.sessionId == sessionId } ?: return
            val next = c.attachments.firstOrNull { it.state is AttachmentState.Queued || it.state is AttachmentState.Uploading } ?: return
            val a = attached
            if (a == null || a.connection.closed.isCompleted) {
                // Wait for the next connection: the current state, not an attach that may already have happened.
                val gen = attachments.value
                attachments.first { it != gen }
                continue
            }
            val file = next.file ?: return
            val up = next.state as? AttachmentState.Uploading ?: AttachmentState.Uploading()
            if (next.state == AttachmentState.Queued) updateAttachment(next.localId) { it.copy(state = up) }
            val length = Uploads.chunkLength(next.size, up.received)
            val data = withContext(Dispatchers.IO) { runCatching { readChunk(file, up.received, length) }.getOrNull() }
            if (data == null) {
                if (heldAttachment(next.localId)?.state is AttachmentState.Uploading) updateAttachment(next.localId) { it.copy(state = AttachmentState.Failed(Uploads.FILE)) }
                log(Kind.ATTACH, "event" to "failed", "error" to Uploads.FILE)
                continue
            }
            val gen = attachments.value
            val answer = request(
                a,
                "attachments.put",
                Frames.attachmentsPut(sessionId, next.name, next.size, up.received, Base64.getEncoder().encodeToString(data), up.attachmentId),
                PUT_TIMEOUT_MS,
            )
            val reply = when {
                answer == null -> Uploads.Answer.None
                answer.ok -> answer.result?.let { Frames.decodeOrNull<PutResult>(it) }?.let { Uploads.Answer.Ok(it) }
                    ?: Uploads.Answer.Error(RemoteError(RemoteError.INTERNAL))
                else -> Uploads.Answer.Error(answer.error!!)
            }
            // Removed, retried or dropped meanwhile: this answer is not its own any more.
            if (heldAttachment(next.localId)?.state != up) continue
            val after = Uploads.next(up, next.size, length, reply, now())
            updateAttachment(next.localId) { it.copy(state = after) }
            when {
                after is AttachmentState.Ready -> log(Kind.ATTACH, "event" to "complete", "bytes" to next.size, "presumed" to after.presumed)
                after is AttachmentState.Failed -> log(Kind.ATTACH, "event" to "failed", "error" to after.code, "limit" to after.limit)
                after is AttachmentState.Uploading && reply is Uploads.Answer.Error -> log(Kind.ATTACH, "event" to "resumed", "error" to reply.error.code, "bytes" to after.received)
            }
            // No answer: the call ended this connection; wait for the next one.
            if (reply == Uploads.Answer.None && after is AttachmentState.Uploading) attachments.first { it != gen }
        }
    }

    private fun readChunk(file: File, offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val bytes = ByteArray(length)
        raf.seek(offset)
        raf.readFully(bytes)
        bytes
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
        if (!watchable || c.watch != Watch.Idle || c.notRunning || c.closed) return
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
        setHeld(transform(c))
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

    /** Above the desktop's own 15 s limit for its interface, so a slow create answers `TIMEOUT` first. */
    const val CREATE_TIMEOUT_MS = 20_000L

    /** Above the worst answer (37 keys 250 ms apart, 3 s of confirmation, a queued reply); never retried. */
    const val ANSWER_TIMEOUT_MS = 30_000L

    /**
     * An OpenSpec watch or read (`openspec-browse` design D3): a first watch scans the folder, git history included,
     * and an older desktop's answer is unbounded. No answer in time ends the connection.
     */
    const val OPENSPEC_TIMEOUT_MS = 60_000L

    /** The OpenSpec targets kept in memory (design D4). */
    const val OPENSPEC_CACHE = 8

    /** One 64 KiB chunk on a slow link (design D6). */
    const val PUT_TIMEOUT_MS = 30_000L
}

/**
 * The last read of one OpenSpec target (`openspec-browse` design D4). [value] is the decoded answer: [OsChanges],
 * a list of [OsSpecSummary], an [OsChangeDetail] or an [OsSpec]. [error] is the last read's, kept with the value it
 * failed to replace. [archivedAs] is where a vanished active change was archived to (design D5).
 */
data class OpenSpecLoaded(
    val value: Any? = null,
    val readAt: Long? = null,
    val error: RemoteError? = null,
    val pending: Boolean = false,
    val notLive: Boolean = false,
    val archivedAs: OsChange? = null,
)
