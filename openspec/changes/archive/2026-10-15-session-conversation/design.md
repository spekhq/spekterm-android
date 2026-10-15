## Context

Built on `connection-spike` (commits `2747a55` and its review fixes `11d8a2e`; archived before this change, see
Migration Plan): `ConnectionService` is a `specialUse` foreground service that owns the phone's only
authenticated connection (spike D5). Its supervisor runs one `session()` per connection: `hello`, then
`attention.watch`, then a loop over `connection.events` that handles `attention` events and ignores every other
kind. `RemoteConnection.call` is safe to use from any coroutine (a concurrent map of pending requests; OkHttp's
`WebSocket.send` is thread-safe); **since `11d8a2e`, a call that gets no answer within its timeout ends the
connection** and throws `ConnectionClosedException` (failure `RequestTimeoutException`). The UI chooses in
`MainActivity` between pairing (unpaired) and status (paired); `MainActivity` reloads whenever `App.changes`
bumps (the service changed the pairing or the announcements) and handles `ACTION_RESUME` from the "connection
stopped" notification. `StatusBoard` is a process-wide `StateFlow` (`state` CONNECTING / CONNECTED / WAITING /
STOPPED, plus a `remoteOff` flag kept until a connection authenticates). Positions are bound to a pairing
(`PositionStore.load/save(deviceId, …)`); `AttentionNotifier` takes the device id and an `alive()` guard.
Attention notifications open `MainActivity` with no further target. The activity is `singleTop` and handles
configuration changes itself (`android:configChanges` includes orientation and screen size), so rotation does
not recreate it.

Facts about the desktop that shape the design (spekterm `docs/remote-protocol.md` sections 5–6, and source):

- **A watch's first events precede its answer.** `conversation.watch` first stops this connection's previous
  follow, then starts the new one synchronously; the follow emits `conversation.wait` and, normally, the first
  `conversation.update` during that call (`conversation-follow.ts` `startFollow`, `transcript-follow-service.ts`),
  and the `res` frame is written after. Events the previous follow had already sent are on the wire before all
  of these.
- **Resume.** The first update after a watch is the last 400 entries with `reset: true`. With `afterUuid`, it is
  only the entries after the **last** entry carrying that uuid, with `reset: false`; if that uuid is not among
  the 400, the update stays `reset: true` (`remote-conversation.ts` `resumeFrom`). One transcript record yields
  several entries **sharing one uuid**, always delivered in the same update (a record is one line, and lines
  are delivered whole). Later updates pass through: `reset: true` again whenever the transcript is rewritten or
  replaced (`/clear` in the agent starts a new one).
- **`truncated` is only meaningful on a replacing update.** Appending updates always carry `truncated: false`
  (`transcript-follow-service.ts` `#publish('ok', events, false, false)`), and a resumed first update keeps the
  original 400's flag although it carries only the tail. (The desktop's own view copies `truncated` from every
  update and so loses the notice — not copied here.)
- **Status values.** The desktop sends `ok` and `unavailable`; `attaching` exists in the type but is not sent to
  a remote watcher in practice. "Catching up" on the phone is therefore its own state: watched, no update yet.
- **Watching counts as displayed** for the desktop's hibernation (`hibernation.ts` `setRemoteWatched`): a
  watched session is not put to sleep for idleness. It does **not** suppress attention events: `suppressed`
  means only "displayed in the desktop's focused window" (`index.ts` `displayedInFocus`). So the phone gets
  unsuppressed `waiting` events for the very conversation it shows.
- **`sessions.changed`** goes to every authenticated connection whenever the list's JSON changes (debounced
  150 ms) — every wait-state change of any agent, pty start and exit, the window going away
  (`remote-service.ts`). The spike already receives these (and drops them); this change adds no traffic of its
  own while no conversation is watched.
- **A session stopping is not an `ended` event.** `conversation.ended` has only `reason: "window"`. When a
  watched session's pty exits (hibernated, or killed by a window reload — `ipc/terminal.ts` on `did-navigate`),
  the follow is not ended; the list then says `running: false`.
- **No window.** `windowGone` ends every watch with `conversation.ended { reason: "window" }` and sends a list
  with `window: false` and **no sessions**; when the window is back, `sessions.changed` carries `window: true`.
- **Reply.** The desktop cleans the text (removes C0 controls except newline, and DEL), refuses an empty result
  (`BAD_PARAMS`), and every refusal (`NOT_WATCHING`, `BAD_PARAMS`, `WAIT_STATE`, `NOT_FOUND` for a session with
  no pty) happens **before** anything is written. It then writes the text, waits 500 ms, re-reads the wait
  state and withholds Enter if it left `ready`/`busy` for any state (`filled-not-submitted`); an exception while
  writing Enter surfaces as `INTERNAL` after the text was written. Replies to one session are serialized across
  connections.
- **Frame size.** The desktop's WebSocket server refuses messages over 1 MiB (`maxPayload`) by closing the
  connection.
- **Timeouts.** Desktop operations carried out by its interface (`sessions.wake`) answer `TIMEOUT` after 15 s,
  below the phone's 20 s call timeout, so a slow wake does not end the connection.
- **Desktop UI precedents** (`ConversationView.tsx`): user messages and agent text as Markdown, thinking and
  tool results collapsed, a tool as its name plus one identifying argument (the protocol carries only that
  one argument); an unconfirmed message is cleared by a user entry whose trimmed text equals the sent text; the
  wait state starts as unknown.

## Goals / Non-Goals

**Goals:**

- A session list as the paired app's home, a conversation view of one agent session, and free-text replies,
  matching the desktop's own rules for when a reply may be sent and how its delivery is shown.
- Survive the connection's life: reconnects, the user pausing, the desktop's window going away, the session
  going dormant — always saying which of these is happening rather than showing stale content as current.
- Keep the spike's guarantees: one connection, owned by the service; nothing secret on disk.

**Non-Goals:**

- Terminal view, answering permission prompts, inbox, handoffs, OpenSpec, creating sessions.
- Keeping conversation content across the app's process ending (no disk cache), or several conversations at
  once.
- Battery work beyond not watching a conversation that is not on screen.
- Tablet or landscape-specific layouts; `FLAG_SECURE`.

## Decisions

### D1. A process-wide `Desktop` layer between the service and the screens

A new package `desktop` holds an `object Desktop` (process-wide, like `StatusBoard`) with:

- **State as `StateFlow`s**: `sessions` (`SessionsState`: the last list, whether it is from the current
  connection, when it was received), `conversation` (`ConversationState` of the session of interest, D4), and
  `link` (derived from `StatusBoard`, the persisted announcements and `paused`, so the screens can state the
  reason when not connected).
- **Hooks for the service**: `attach(connection)` once a connection has authenticated and its attention
  watch is answered; `onEvent(connection, event)` for every non-attention event, in arrival order;
  `detach(connection)` when that connection ends. Every hook names its connection, because the service keeps
  draining a closed connection's channel after the next one may already be attached.
- **Calls for the screens**: `focus(owner, sessionId)` / `unfocus(owner)` (D3), `wake(sessionId)`,
  `send(sessionId, text)`, `setDraft(sessionId, text)`. Each answers a typed result, including "not
  connected" — the screens never touch `RemoteConnection`.

**Why a singleton fed by the service rather than a bound service or a ViewModel holding a connection**: the
service already owns the only connection (spike D5), and the UI only needs to observe state and issue a few
calls. A bound service adds an IPC-shaped API with no second process to justify it; a ViewModel cannot own a
connection without breaking the one-connection rule. Pure state transitions (applying updates, matching
unconfirmed messages, gating) live in plain functions under `desktop/`, tested on the JVM; `Desktop` only wires
them to the connection and the flows. A `resetForTest()` clears it between instrumented tests.

**Concurrency.** Every state transition of `Desktop` — events from the service loop, answers to its own calls,
focus changes from the UI, the attach work — runs on **one single-threaded dispatcher**
(`Dispatchers.Default.limitedParallelism(1)`), so a stamp comparison and the update that follows it are atomic.
`attach` opens a **per-connection scope**, cancelled by `detach`; everything `Desktop` starts for a connection
(list, watch, wake, send) runs in that scope, and any answer or event that arrives for a connection other than
the currently attached one is discarded.

The service's loop becomes: `attention` → notifier (unchanged), anything else → `Desktop.onEvent(connection, event)`.

### D2. Ordering: an arrival stamp on every frame

A list answer and list events race: on the wire `[sessions.changed A][res L][sessions.changed B]` must end at B,
but the answer completes a deferred on OkHttp's reader thread while events pass through the service's channel
loop, so A can be applied after L. `RemoteConnection` stamps every decoded frame (answers and events) with an
arrival number taken on the reader thread, increasing within the connection. `Desktop` applies a session list
(answer or event) only if its stamp is greater than the last applied one; **the last applied stamp is reset on
every `attach`**, and stamps are only compared within the attached connection (D1). Small and additive to
`RemoteConnection`/`ServerFrame` (a `seq` field).

### D3. Focus: watch only what is on screen

The conversation screen calls `Desktop.focus(owner, id)` when its lifecycle reaches STARTED and
`unfocus(owner)` when it drops below, where `owner` is the screen instance's `NavBackStackEntry.id`.
navigation-compose gives each destination its own lifecycle; screen off, another app in front, or navigating
away all stop it. `Desktop` keeps at most one focus, **owned by a token**: `unfocus` acts only when its owner
still holds the focus, so a leaving screen whose STOPPED arrives after the arriving screen's STARTED (a
transition, or two `Conversation(X)` entries after a notification tap) does not clear the new focus.

The *watch state* of the focused session is one of `idle`, `requested` (watch sent, not answered),
`watching`, `refused(code)`. Reconciliation runs on every focus change, attach, `sessions.changed` and
`conversation.ended`, and acts only when the target differs from the state:

- focused, connected, the list (with `window: true`) shows the session running and an agent, state `idle` →
  send `conversation.watch` and set `requested`; **`requested` counts as watched**, so a `sessions.changed`
  arriving before the answer never sends a second watch;
- the list shows the focused session `running: false` while `requested`/`watching` → send
  `conversation.unwatch`, state `idle`, the screen shows dormant (D7); when it runs again, the first rule
  watches it;
- unfocused while `requested`/`watching` → `conversation.unwatch`, state `idle`;
- focus moves to another session → watch it (the desktop ends the previous watch itself);
- `refused(code)` → no new attempt until the next focus change, attach, or the list showing the session's
  `running` turn from false to true (no retry loop).

Unwatching promptly matters beyond traffic: a watched session counts as displayed on the desktop and is never
hibernated for idleness.

The held conversation is kept in memory when unfocused, for one session only (the last one focused), so
returning to it resumes rather than reloads. It is dropped, with its draft and unconfirmed replies, when another
session is focused, on unpair, and when the process ends.

### D4. Watch, resume and apply

`ConversationState` for the session of interest: `entries`, `phase` (`notWatching(reason)`, `catchingUp` —
watched, no update yet, `ok`, `unavailable`), `truncated`, `wait` (`ready|busy|awaiting-choice|unknown`,
initially `unknown`), `pending` (`tool`, `arg` or null), the watch state (D3), and the reply state (D6).

- **Request**: `conversation.watch { sessionId, afterUuid }` where `afterUuid` is the uuid of the last held
  entry, omitted when nothing is held. Before sending, the state becomes `requested`, `phase = catchingUp` and
  `wait = unknown`; events whose `sessionId` is the focused one are applied even before the answer (Context).
  Events for any other session are dropped.
- **Answer**: ok → `watching`. `NOT_FOUND` → the session is not running: watch state `idle`, shown as dormant
  (D7) until the list shows it running, when the first reconciliation rule watches it. `NO_CONVERSATION` →
  shown as a shell (should not happen; shells are never watched). Any other error → `refused(code)`, shown.
- **Apply** (pure, `ConversationReducer`):
  - `reset: true` replaces `entries` with the update's, and takes its `truncated`;
  - `reset: false` appends, **dropping every entry whose (non-empty) uuid is already held** — a record's
    entries arrive in one update, so a held uuid means the record was delivered before; this absorbs the
    previous follow's in-flight update overlapping a resume (Context: a quick unfocus/focus, or any
    reconnect race), and keeps list keys unique; `truncated` is left as it was;
  - `phase` becomes `ok` or `unavailable` from the update's `status` (`attaching`, if ever sent, keeps
    `catchingUp`).
  - List keys: `uuid` plus the entry's index among held entries sharing that uuid; unique by the dedup rule.
- **Cap**: at most 2,000 entries held; on overflow the oldest are dropped and `truncated` is set.
- **Disconnect**: `detach` sets `phase = notWatching(disconnected)`, watch state `idle`, `wait = unknown`, keeps
  `entries`; after the next `attach`, reconciliation re-watches with `afterUuid`, so the phone receives the
  missed entries, or a replacing update when the desktop no longer has that uuid among its 400.
- **`conversation.ended`**: `phase = notWatching(window)`, watch state `idle`. The next list with `window: true`
  clears the window reason: the session is then shown by the list — running (reconciliation re-watches it) or,
  as after a window reload that killed every pty, dormant with Wake (D7).

### D5. Rendering a conversation

One `LazyColumn`, newest at the bottom, keyed as in D4. Per kind, following the desktop:

- **user**: a bubble, Markdown; a `meta` entry (agent-injected, not typed) is labeled and collapsed.
- **text**: Markdown.
- **thinking**: collapsed under "Thinking", plain text when expanded.
- **tool**: the tool name and its identifying argument, plain text, one line ellipsized; tapping wraps the
  argument in full (the protocol carries only that one argument).
- **result**: collapsed "Result" / "Failed"; expanded, plain monospace text in a bounded, scrollable box.

**Markdown**: `com.mikepenz:multiplatform-markdown-renderer-m3` 0.46.0 (Compose, Material 3, built for the same
Kotlin 2.4.21; its POM brings no image or network library). Checked in the core library's 0.46.0 sources:

- **Images**: the default `imageTransformer` is `NoOpImageTransformerImpl`, which loads nothing; the app never
  configures another and never adds a Coil module. (The `-m3` wrapper's own defaults were not inspected; the
  app passes the no-op transformer explicitly, and the D9 test asserts no request.)
- **Links**: opened through `LocalUriHandler`; the conversation provides its own handler that opens only
  `http`/`https` URLs, in the system browser, after the user taps — any other scheme returns silently (no
  exception, which the library would otherwise print with the URL).
- **HTML**: the renderer silently **drops** inline HTML tags and HTML blocks (no `HTML_TAG` / `HTML_BLOCK`
  handling), which would eat `Vec<T>` and the `<command-name>` markup of slash commands in user entries. The
  app adds both back as text: a `markdownAnnotator` whose `annotate` appends an `HTML_TAG` node's own text, and
  a `markdownComponents(custom = …)` that renders an `HTML_BLOCK` as plain text. How the parser tokenizes these
  is inferred from CommonMark, not run yet; the D9 test pins it.

Alternatives: rendering agent text as plain text (safest, but code blocks and lists become unreadable on a
phone); a WebView with a Markdown library (much larger attack surface, and remote loading would have to be
blocked by configuration rather than by absence).

**No simulated streaming**: an entry appears whole when its update arrives; there is no per-entry animation at
all (the simplest way to keep any transition independent of length). Busy is a static "Working…" row from
`wait = busy`, removed when the state leaves `busy`.

**Scrolling**: when the list is at the bottom, it follows new entries; when the user has scrolled up, it stays
and a "New messages" button jumps down.

**What the screen states, in this precedence** (one primary state; the rest of the screen follows it):

1. Not connected — paused (with Resume), or not connected with the reason from `link`; held entries stay
   visible, marked as possibly out of date.
2. The desktop has no window — the latest list says `window: false`, or `conversation.ended` arrived and no
   `window: true` list has come since — held entries visible, marked.
3. Closed on the desktop — the session is absent from the current connection's latest `window: true` list.
4. Dormant (D7) — held entries hidden.
5. Otherwise the conversation, with banners for catching up, older entries not shown (`truncated`), or
   unavailable (pointing to the desktop).

**Nothing written**: entries, drafts and replies live only in `Desktop`'s memory; the screens use plain
`remember` (never `rememberSaveable`) for anything holding conversation text, so it does not enter the saved
instance state.

### D6. Replying

**Gate** (pure, `ReplyGate`): sending is possible only when connected, the watch state is `watching`, `wait`
is `ready` or `busy`, no send of this screen is in flight, and the text is not blank after the desktop's
cleaning and trimming (trimming as JavaScript's `trim()`, which also removes U+FEFF). Otherwise the composer is
disabled with one reason, in this precedence: not connected (or paused) → not watching yet → `awaiting-choice`
→ `unknown` → in flight. The reasons:

- `awaiting-choice`: "The agent asks for a choice: ⟨tool⟩ ⟨arg⟩. Answer it on the desktop." `tool` and `arg`
  come from `conversation.wait.pending` as plain text; no answer controls exist.
- `unknown`: "spekterm can't tell whether the agent can take a message now. Reply on the desktop."

The composer is multi-line; Enter inserts a newline and only the Send button sends, so a multi-line message
goes as one message. The draft lives in `ConversationState` (kept across leaving the screen, dropped with the
held conversation, D3). **Size**: the encoded request frame must stay under the desktop's 1 MiB message
limit; the phone refuses longer text itself ("too long to send"), because the desktop would answer by closing
the connection. `conversation.send` uses a 30 s call timeout (replies to one session queue on the desktop, and a
timeout ends the connection).

**Send**: the text leaves the composer and appears at once at the end of the conversation as an
**unconfirmed** reply, remembering the wait state it was sent in. `conversation.send` answers:

- `submitted` → stays unconfirmed until confirmed (below).
- `filled-not-submitted` → shown as "Typed into the agent's input but not sent — the agent's state changed (for
  example, a choice appeared). Finish it on the desktop." Not matched further: the desktop never submits it.
- `WAIT_STATE`, `NOT_WATCHING`, `BAD_PARAMS`, `NOT_FOUND`, `UNKNOWN_OPERATION` → refused before anything was
  written: the reply is removed and its text goes back to the composer, with the reason.
- The connection ends before the answer, or any other error (`INTERNAL` can follow a written text) → "May not
  have been sent"; still matched.

**Confirmation** (pure, `UnconfirmedMatcher`): an unconfirmed reply is confirmed by a `user` entry that is not
`meta`, whose uuid the phone did not hold when the reply was sent, and whose text equals the sent text after
the desktop's cleaning, both trimmed. The uuid condition keeps an older identical message ("yes") in a
replacing update from confirming a new one. Each entry confirms at most one reply, oldest first. A confirmed
reply disappears; the matching entry is the one shown.

**Not seen**: a reply sent while `ready` that is not confirmed 30 seconds after `submitted` is labeled "Not in
the conversation yet — check the desktop". A reply sent while `busy` is labeled "Waiting for the agent to take
it", with no time limit: a busy agent takes typed input only when it gets to it, and whether that input is
then recorded as a `user` record is unverified (Risks). When the agent then leaves `busy` without the reply
having matched, the label becomes "Sent while the agent worked — it may not show in the conversation".
Matching continues in every case.

**Placement**: a reply still pending (not yet confirmed, or waiting for the agent) is shown at the end of the
conversation. Once it is labeled not seen, may not show, may not have been sent, or typed but not sent, it
moves to where it was sent — after the entry that was last when it was sent — and is collapsed to one line, so
it never stays pinned below newer entries.

### D7. Dormant sessions and waking

A session is dormant when a `window: true` list says `running: false`, or a watch answered `NOT_FOUND`. The
conversation screen then shows "This session is dormant" and a Wake button — never an empty conversation, and
held entries are hidden while dormant. Wake calls `sessions.wake`; while it runs the button shows progress.
Success needs no further step: the list's next `sessions.changed` shows the session running and
reconciliation (D3) watches it, resuming with `afterUuid`. Errors: `NO_WINDOW` (open spekterm on the desktop),
`CREATE_FAILED` (with the desktop's `message` as a detail), `TIMEOUT` (the desktop did not answer in time; try
again), `NOT_FOUND` (the session no longer exists).

A session absent from the current connection's latest `window: true` list while its conversation is open is
shown as "closed on the desktop". This is a display state recomputed from every list, not an action: the focus
is kept (the screen is still visible, so its notifications stay suppressed), reconciliation simply has nothing
to watch, and a later list that holds the session again shows it again. Lists of an earlier connection are not
used, and a `window: false` list never counts as closing anything (D5).

### D8. Navigation, the session list, and notifications

**Navigation**: `androidx.navigation:navigation-compose` 2.10.2 with type-safe routes (kotlinx.serialization is
already in the build): `Sessions` (start destination when paired), `Conversation(sessionId)`, `Status`. Pairing
stays outside the graph: `MainActivity` shows the pair screen when unpaired or re-pairing, as now, and keeps
reloading on `App.changes`. Chosen for its per-destination lifecycle and entry ids (D3) and saved back stack;
the alternative — a hand-rolled screen stack — would reimplement both.

**Intents**: a notification about a session carries `ACTION_OPEN_SESSION` with the session id as data URI.
`MainActivity` handles it in `onCreate` and `onNewIntent` (the activity is `singleTop` and the notification adds
`CLEAR_TOP`) by navigating to `Conversation(id)` with `Sessions` beneath (popping back to `Sessions` first), then
clearing the intent's action so a relaunch from recents does not navigate again — the same pattern as
`ACTION_RESUME`, which keeps its meaning: the "connection stopped" notification resumes the connection and
shows the session list. An intent arriving while unpaired shows the pair screen.

**Session list**: grouped by folder in the desktop's rail order, the global item's sessions under "Global"
(the desktop's "全域"); within a group, the desktop's order. Each row: label, an agent/shell mark, and a state:
dormant, or for agents the wait state — "Waiting for you" (`ready`), "Working" (`busy`), "Asks for a choice"
(`awaiting-choice`), nothing for `unknown`; a handoff-created session's `lifecycle` as a small second label.
Header states: not connected with the reason from `link` (connecting / waiting to retry, remote access off or
the desktop quit, identity changed, version unsupported, connection stopped) and the list's time, paused
(Resume), the desktop has no window (`window: false`), nothing received yet. Every not-connected header links
to the status screen; a "Connection" action opens it at any time.

Opening a shell session shows a page saying shells need the terminal view, which is not in the app yet — no
watch is made.

**Notifications** (`AttentionNotifier`): an event with a `sessionId` gets a content intent as above, distinct
per session (the data URI makes `PendingIntent`s differ under `filterEquals`, so `FLAG_UPDATE_CURRENT` never
overwrites another session's). Inbox and summary notifications open the session list; status notifications
keep their current targets (status screen; "connection stopped" resumes).

**Not notifying for the conversation on screen**: `Desktop.visibleSession` is the focused session (D3). The
attention policy takes it as a parameter: a live event, or an event of a complete answer, for that session is
handled like a suppressed one (cancel, no notify, position stored), and the log records `visible`. In an
incomplete answer such an event still counts in the summary — the summary counts unsuppressed events, as the
spike specifies. Focusing a conversation also cancels that session's notification.

### D9. Evidence

- **JVM unit tests**: `ConversationReducer` (replace with its `truncated`, append keeping `truncated`, append
  dropping held uuids, status mapping, cap, keys with repeated uuids); `UnconfirmedMatcher` (cleaning, JS-like
  trimming, uuid-held rule, one entry confirms one reply, `meta` ignored, late confirmation, ready vs busy
  labels); `ReplyGate` (every reason and its precedence, blank after cleaning, frame size); session list
  grouping, `window: false` not closing anything, and the stamp rule (D2) including its reset on attach;
  the screen-state precedence (D5); `AttentionPolicy` with a visible session.
- **Instrumented, against `FakeDesktop`** (loopback, fixture certificate): `Desktop` attached to a real
  `RemoteConnection` — a `conversation.wait` and update sent before the watch answer are applied; an old
  update for the same session arriving before a re-watch's update is not shown twice; a `sessions.changed`
  before the watch answer sends no second watch; reconnect re-watches with the last uuid and the first list of
  the new connection is applied; `conversation.send` outcomes and errors reach the reply state as D6 says;
  `conversation.ended` then `sessions.changed { window: true }` re-watches; `running: false` while watched
  unwatches and `running: true` re-watches; a stale list answer does not overwrite a newer event; unfocus by a
  non-owner does nothing.
- **Instrumented, Compose**: Markdown holding an image, an `<img>` tag and a link to a local `MockWebServer`
  renders with zero requests reaching it; `Vec<T>`, `<command-name>/opsx:continue</command-name>` and an HTML
  block show as text; a `javascript:` link does nothing when tapped.
- **On the phone against spekterm from source** (user steps labeled 【手機】/【桌機】): early, before the
  composer's labels are finalized — reply while the agent works, and see whether and when the message appears
  in the conversation (Risks). Then: list and its live states; open, read, catch-up; reply while ready; a
  choice appearing (the composer refuses and shows tool and argument); answering on the desktop re-enables it;
  dormant + Wake; hibernate while reading, then wake; Wi-Fi off and on while watching (nothing repeated or
  lost); screen off and on quickly; pause and resume; the desktop's window reloaded (`conversation.ended`);
  notification taps (from the list, from another conversation, after the process was killed); no
  notification while the conversation is on screen; a multi-line reply arrives as one message;
  `filled-not-submitted` (a prompt within half a second of a reply — may be impractical by hand; then carried
  by unit tests and the fake desktop).

## Risks / Trade-offs

- [A reply sent while the agent is busy may be recorded only when the agent takes it — or as a record type
  the desktop does not project (`queue-operation`, `attachment`), in which case it never confirms] → Such
  replies are labeled "waiting for the agent to take it" while it works, and "may not show in the
  conversation" once it stops, moving to their send position (D6) — so the worst case is a collapsed line, not
  a false alarm or a reply pinned under newer entries. Checked on the phone first (D9); if they never project, the fix
  belongs in spekterm's transcript projection and is reported there.
- [Large first updates (400 entries with long tool results) take time to parse and lay out] → Parsing happens
  off the main thread; results stay collapsed so only their summary lines are laid out. Measured on the phone
  with a long session.
- [Conversation content shows in the recent-apps thumbnail, which the system stores] → Not addressed here (no
  `FLAG_SECURE`), noted for a later change; the app itself writes no content to storage or logs.
- [The 30-second "not seen" threshold is a guess] → It only changes a label; matching continues.
- [The Markdown parser's HTML tokenization is inferred] → Pinned by the Compose test before the screen is built
  on it.

## Migration Plan

No data migration: nothing new is persisted. The status screen stops being the paired home and is reached from
the session list. `connection-spike` is archived first (its specs then exist under `openspec/specs/`); this
change modifies `attention-notifications` and so is archived after it. The change is not committed until the
user has tested it on the phone.

## Open Questions

None blocking. Whether a reply typed while the agent works is confirmed is answered by the first phone test
(Risks).
