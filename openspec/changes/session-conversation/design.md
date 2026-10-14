## Context

Built on `connection-spike` (commit `2747a55`, not archived yet): `ConnectionService` is a `specialUse` foreground
service that owns the phone's only authenticated connection (spike D5). Its supervisor runs one `session()` per
connection: `hello`, then `attention.watch`, then a loop over `connection.events` that handles `attention` events
and ignores every other kind. `RemoteConnection.call` is safe to use from any coroutine (a concurrent map of
pending requests; OkHttp's `WebSocket.send` is thread-safe). The UI has two screens chosen in `MainActivity`:
pairing when unpaired, status otherwise. `StatusBoard` is a process-wide `StateFlow` the service writes and the
status screen reads. Attention notifications open `MainActivity` with no further target.

Facts about the desktop that shape the design (spekterm `docs/remote-protocol.md` sections 5–6, and source):

- **The watch's first events can precede its answer.** `conversation.watch` starts the follow synchronously,
  and the follow emits the current `conversation.wait` during that call (`conversation-follow.ts`
  `startFollow`), before the operation returns; the `res` frame is written after. So a `conversation.wait`
  — and possibly a `conversation.update` — for the requested session can arrive before the watch is answered.
- **Resume.** The first update after a watch is the last 400 entries with `reset: true`. With `afterUuid`, it is
  only the entries after the **last** entry carrying that uuid, with `reset: false`; if that uuid is not among
  the 400, the update stays `reset: true` (`remote-conversation.ts` `resumeFrom`). One transcript record yields
  several entries **sharing one uuid** (text and tool calls of one assistant message), so a uuid does not
  identify an entry. Later updates pass through: `reset: true` again whenever the transcript is rewritten or
  replaced (for example `/clear` in the agent starts a new one).
- **Watching counts as displayed** for the desktop's hibernation (`hibernation.ts` `setRemoteWatched`): a
  watched session is active and is not put to sleep for idleness. It does **not** suppress attention events:
  `suppressed` means only "displayed in the desktop's focused window" (`index.ts` `displayedInFocus`). So the
  phone gets unsuppressed `waiting` events for the very conversation it shows.
- **`sessions.changed`** is sent to every authenticated connection whenever the list's JSON changes — including
  every wait-state change of any agent, pty start and exit, and the window going away (`remote-service.ts`).
  The spike already receives these (and drops them); this change adds no traffic of its own while no
  conversation is watched.
- **Reply.** The desktop cleans the text (removes C0 controls except newline, and DEL), refuses an empty result
  (`BAD_PARAMS`), writes it, waits 500 ms, re-reads the wait state and withholds Enter if it left
  `ready`/`busy` (`filled-not-submitted`); replies to one session are serialized across connections.
- **Frame size.** The desktop's WebSocket server refuses messages over 1 MiB (`maxPayload`) by closing the
  connection.
- **Window.** `windowGone` ends every watch with `conversation.ended { reason: "window" }` and sends a session
  list with `window: false` (empty sessions); when the window is back, `sessions.changed` carries `window: true`.
- **Desktop UI precedents** (`ConversationView.tsx`): user messages and agent text as Markdown, thinking and
  tool results collapsed, a tool as its name plus one identifying argument; an unconfirmed message is cleared
  by a user entry whose trimmed text equals the sent text; the wait state starts as unknown.

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
- Tablet or landscape-specific layouts.

## Decisions

### D1. A process-wide `Desktop` layer between the service and the screens

A new package `desktop` holds an `object Desktop` (process-wide, like `StatusBoard`) with:

- **State as `StateFlow`s**: `sessions` (`SessionsState`: the last list, whether it is from the current
  connection, its time), `conversation` (`ConversationState` of the session of interest, below), and `link`
  (connected or not — the service's `Status.State` plus the persisted `paused` flag).
- **Hooks for the service**: `attach(connection)` once a connection has authenticated and its attention
  watch is answered; `onEvent(event)` for every non-attention event, in arrival order; `detach()` when the
  connection ends.
- **Calls for the screens**: `focus(sessionId)` / `unfocus(sessionId)` (D3), `wake(sessionId)`,
  `send(sessionId, text)`. Each is a suspend function on the attached connection that answers a typed result,
  including "not connected" — the screens never touch `RemoteConnection`.

**Why a singleton fed by the service rather than a bound service or a ViewModel holding a connection**: the
service already owns the only connection (spike D5), and the UI only needs to observe state and issue a few
calls. A bound service adds an IPC-shaped API with no second process to justify it; a ViewModel cannot own a
connection without breaking the one-connection rule. Pure state transitions (applying updates, matching
unconfirmed messages, gating) live in plain functions under `desktop/`, tested on the JVM; `Desktop` only wires
them to the connection and the flows.

The service's loop becomes: `attention` → notifier (unchanged), anything else → `Desktop.onEvent`. After
`attention.watch` is answered, the service calls `Desktop.attach`, which (on its own coroutine) calls
`sessions.list` and, if a conversation is in focus, watches it (D4).

### D2. Ordering: an arrival sequence on every frame

A list answer and list events race: on the wire `[sessions.changed A][res L][sessions.changed B]` must end at B,
but the answer completes a deferred on the reader thread while events go through the service's loop, so A can
be applied after L. `RemoteConnection` stamps every decoded frame (answers and events) with a per-connection
arrival number taken on OkHttp's reader thread. `Desktop` applies a session list (answer or event) only if its
stamp is greater than the last applied one. The same stamp orders a watch's answer against its early events
(D4). This is a small, additive change to `RemoteConnection`/`ServerFrame` (a `seq` field).

### D3. Focus: watch only what is on screen

The conversation screen calls `Desktop.focus(id)` when its lifecycle reaches STARTED and `unfocus(id)` when it
drops below (navigation-compose gives each destination its own lifecycle; screen off, another app, or leaving
the screen all stop it). `Desktop` keeps at most one focus. Reconciliation, run on every focus change, attach,
`sessions.changed` and `conversation.ended`:

- focused, connected, session running and an agent, not watched → `conversation.watch` (D4);
- unfocused while watched → `conversation.unwatch`;
- focus on another session → watch it (the desktop ends the previous watch itself).

Unwatching promptly matters beyond traffic: a watched session counts as displayed on the desktop and is never
hibernated for idleness. A rotation briefly unwatches and re-watches; with `afterUuid` (D4) that costs only an
empty update.

The held conversation is kept in memory when unfocused, for one session only (the last one focused), so
returning to it resumes rather than reloads. It is dropped when another session is focused, on unpair, and
when the process ends.

### D4. Watch, resume and apply

`ConversationState` for the session of interest: `entries` (list of `Entry`), `status` (`attaching`, `ok`,
`unavailable` from the desktop, plus local `notWatching`), `truncated`, `wait` (`ready|busy|awaiting-choice|
unknown`, initially `unknown`), `pending` (`tool`, `arg` or null), `watchError` (the last refusal code), and the
reply state (D6).

- **Request**: `conversation.watch { sessionId, afterUuid }` where `afterUuid` is the uuid of the last held
  entry, omitted when nothing is held. Before sending, `Desktop` marks the session as *requested* and resets
  `wait` to `unknown`; events whose `sessionId` is the requested one are applied even before the answer arrives
  (Context: the first `conversation.wait` precedes the answer). Events for any other session are dropped.
- **Answer**: ok → status stays as the events set it. `NOT_FOUND` → the session is not running: treated as
  dormant (D7). `NO_CONVERSATION` → shown as a shell (should not happen; shells are never watched). Any other
  error → `watchError`, retried at the next reconciliation trigger, not in a loop.
- **Apply** (pure, `ConversationReducer`): `reset: true` replaces `entries` with the update's; `reset: false`
  appends. `status` and `truncated` are taken from each update. Entries are keyed for the list by
  `uuid` plus their index among entries sharing that uuid, since uuids repeat (Context).
- **Cap**: at most 2,000 entries held; on overflow the oldest are dropped and `truncated` is set locally, so the
  screen says older entries are not shown.
- **Disconnect**: `detach()` sets `status = notWatching` and `wait = unknown`, keeps `entries`; after the next
  `attach`, reconciliation re-watches with `afterUuid`, so the phone receives exactly the missed entries, or a
  replacing update when the desktop no longer has that uuid in its window.
- **`conversation.ended`**: status `notWatching` with reason "window"; reconciliation re-watches when a
  `sessions.changed` with `window: true` shows the session running.

### D5. Rendering a conversation

One `LazyColumn`, newest at the bottom, keyed as in D4. Per kind, following the desktop:

- **user**: a bubble, Markdown; a `meta` entry (agent-injected, not typed) is labeled and collapsed.
- **text**: Markdown.
- **thinking**: collapsed under "Thinking", plain text when expanded.
- **tool**: the tool name and its identifying argument, one line, plain text; expandable to the full argument.
- **result**: collapsed "Result" / "Failed"; expanded, plain monospace text in a bounded, scrollable box.

**Markdown**: `com.mikepenz:multiplatform-markdown-renderer-m3` 0.46.0 (Compose, Material 3, built for the same
Kotlin 2.4.21; its POM brings no image or network library). Remote content: the library's default
`imageTransformer` is `NoOpImageTransformerImpl`, which loads nothing (checked in the 0.46.0 sources); the app
never configures another, and never adds a Coil module. Links: the library opens them through
`LocalUriHandler`; the conversation provides its own handler that opens only `http`/`https` URLs, in the
system browser, after the user taps — any other scheme does nothing. Raw HTML in agent text must not be rendered as
HTML; no HTML handling was found in the 0.46.0 sources, and a test confirms it shows as text (D9) — if it does
not, HTML nodes are stripped to their text before rendering. Agent text is never interpreted beyond
Markdown — no autolinked phone numbers, no intents.

Alternatives: rendering agent text as plain text (safest, but code blocks and lists become unreadable on a
phone); a WebView with a Markdown library (much larger attack surface, and remote loading would have to be
blocked by configuration rather than by absence).

**No simulated streaming**: an entry appears whole when its update arrives; there is no per-entry animation at
all (the simplest way to keep any transition independent of length). Busy is a static "Working…" row from
`wait = busy`, removed when the state leaves `busy`.

**Scrolling**: when the list is at the bottom, it follows new entries; when the user has scrolled up, it stays
and a "New messages" button jumps down.

**Banners at the top**: catching up (`attaching`); older entries not loaded (`truncated`); content unavailable
(`unavailable`, saying to check the desktop); not connected / paused (with Resume) / desktop window gone, each
saying the content shown may be out of date.

### D6. Replying

**Gate** (pure, `ReplyGate`): sending is possible only when connected, the session is watched (the watch
answered ok), `wait` is `ready` or `busy`, no send of this screen is in flight, and the text is not blank after
the desktop's cleaning. Otherwise the composer is disabled with one reason, in this precedence: not connected
(or paused) → not watching yet → `awaiting-choice` → `unknown` → in flight. The reasons:

- `awaiting-choice`: "The agent asks for a choice: ⟨tool⟩ ⟨arg⟩. Answer it on the desktop." `tool` and `arg`
  come from `conversation.wait.pending` as plain text; no answer controls exist.
- `unknown`: "spekterm can't tell whether the agent can take a message now. Reply on the desktop."

The composer is multi-line; Enter inserts a newline and only the Send button sends, so a multi-line message
goes as one message. The draft survives leaving and returning to the screen (held in `ConversationState`).
**Size**: the encoded request frame must stay under the desktop's 1 MiB message limit; the phone refuses
longer text itself ("too long to send"), because the desktop would answer by closing the connection.

**Send**: the text leaves the composer and appears at once at the end of the conversation as an
**unconfirmed** message. `conversation.send` answers:

- `submitted` → stays unconfirmed until confirmed (below).
- `filled-not-submitted` → shown as "Typed into the agent's input but not sent — a choice appeared. Finish it on
  the desktop." It is not matched further: the desktop never submits it.
- `WAIT_STATE` / `NOT_WATCHING` / `BAD_PARAMS` → nothing was written; the message is removed and its text goes
  back to the composer, with the reason.
- The connection ends before the answer → it is unknown whether the text was written: the message stays,
  marked "May not have been sent", and is still matched after the re-watch.

**Confirmation** (pure, `UnconfirmedMatcher`): an unconfirmed message is confirmed by a `user` entry that is not
`meta`, whose uuid the phone did not hold when the message was sent, and whose text equals the sent text after
the desktop's cleaning, both trimmed. The uuid condition keeps an older identical message ("yes") in a
replacing update from confirming a new one. Each entry confirms at most one message, oldest first. A confirmed
message disappears; the matching entry is the one shown.

**Not seen**: 30 seconds after `submitted` without confirmation the label becomes "Not in the conversation
yet — check the desktop". Matching continues; a late confirmation still clears it. Unconfirmed messages are
dropped when another session is focused.

### D7. Dormant sessions and waking

A session is dormant when the list says `running: false` (or a watch answered `NOT_FOUND`). The conversation
screen then shows "This session is dormant" and a Wake button — never an empty conversation, even when
entries are held from earlier (they are hidden while dormant). Wake calls `sessions.wake`; while it runs the
button shows progress. Success needs no further step: the list's next `sessions.changed` shows the session
running and reconciliation (D3) watches it. Errors: `NO_WINDOW` (open spekterm on the desktop), `CREATE_FAILED`
(with the desktop's `message` as a detail), `TIMEOUT` (the desktop did not answer in time; try again),
`NOT_FOUND` (the session no longer exists).

A session that disappears from the list while its conversation is open shows "This session was closed on the
desktop", and is unfocused.

### D8. Navigation, the session list, and notifications

**Navigation**: `androidx.navigation:navigation-compose` 2.10.2 with type-safe routes (kotlinx.serialization is
already in the build): `Sessions` (start destination when paired), `Conversation(sessionId)`, `Status`. Pairing
stays outside the graph: `MainActivity` shows the pair screen when unpaired or re-pairing, as now. Chosen for
its per-destination lifecycle (D3) and saved back stack; the alternative — a hand-rolled screen stack — would
reimplement both.

**Session list**: grouped by folder in the desktop's rail order, the global item's sessions under "Global"
(the desktop's "全域"); within a group, the desktop's order. Each row: label, an agent/shell mark, and a state:
dormant, or for agents the wait state — "Waiting for you" (`ready`), "Working" (`busy`), "Asks for a choice"
(`awaiting-choice`), nothing for `unknown`; a handoff-created session's `lifecycle` as a small second label.
Header states: not connected (the list is the last one received, with its time), paused (Resume), desktop has
no window (`window: false`), never received (nothing yet). A "Connection" action opens the status screen.

Opening a shell session shows a page saying shells need the terminal view, which is not in the app yet — no
watch is made.

**Notifications** (`AttentionNotifier`): an event with a `sessionId` gets a content intent opening
`Conversation(sessionId)` with `Sessions` beneath it; each such `PendingIntent` is distinct per session (the
session id as the intent's data URI, so `FLAG_UPDATE_CURRENT` never overwrites another session's). Inbox and
summary notifications open the session list; status notifications (pair again, identity, stopped) open the
status screen. An intent arriving while unpaired shows the pair screen.

**Not notifying for the conversation on screen**: `Desktop.visibleSession` is the focused session (D3). The
attention policy treats an event for that session like a suppressed one: no notification, and any shown for it
is cancelled; the position is stored as usual and the connection log records the outcome as `visible`.
Focusing a conversation also cancels that session's notification.

### D9. Evidence

- **JVM unit tests**: `ConversationReducer` (replace, append, status and truncated, cap, keys with repeated
  uuids); `UnconfirmedMatcher` (cleaning, trimming, uuid-held rule, one entry confirms one message, `meta`
  ignored, late confirmation); `ReplyGate` (every reason and its precedence, blank after cleaning, frame size);
  session list grouping and the arrival-sequence rule (D2); `AttentionPolicy` with a visible session.
- **Instrumented, against `FakeDesktop`** (loopback, fixture certificate): `Desktop` attached to a real
  `RemoteConnection` — a `conversation.wait` sent before the watch answer is applied; reconnect re-watches with
  the last uuid; `conversation.send` outcomes reach the reply state; `conversation.ended` then
  `sessions.changed { window: true }` re-watches; a stale list answer does not overwrite a newer event.
- **Instrumented, Compose**: a Markdown entry with an image and a link to a local mock server renders with no
  request reaching the server; raw HTML shows as text; the link opens only on tap and only for `https`/`http`.
- **On the phone against spekterm from source** (user steps labeled 【手機】/【桌機】): list and its live states;
  open, read, catch-up; reply while ready and while busy (whether a message typed while the agent works
  appears in the transcript, and so confirms, is **unverified** — see Risks); a choice appearing (the composer
  refuses and shows tool and argument); answering on the desktop re-enables it; `filled-not-submitted`
  (reply, then have a prompt appear within half a second — may be impractical by hand; then carried by unit
  tests and the fake desktop); dormant + Wake; Wi-Fi off and on while watching (resumed, nothing repeated or
  lost); pause and resume; the desktop's window reloaded (`conversation.ended`); notification tap opens the
  conversation; no notification while it is on screen; a multi-line reply arrives as one message.

## Risks / Trade-offs

- [A message sent while the agent is busy may not appear in the transcript as a `user` record until the agent
  takes it — or may be recorded as a record type the desktop does not project] → It then stays unconfirmed and
  turns "Not in the conversation yet" after 30 s, which is honest but alarming. Measured on the phone; if it is
  the second case, the fix belongs in spekterm's transcript projection and is reported there.
- [Large first updates (400 entries with long tool results) take time to parse and lay out] → Parsing happens
  on the service's IO loop, not the main thread; results stay collapsed so only their summary lines are laid
  out. Measured on the phone with a long session.
- [Conversation content is sensitive and shows in the recent-apps thumbnail] → Not addressed here (no
  `FLAG_SECURE`), noted for a later change; content is never written to storage or logs.
- [The 30-second "not seen" threshold is a guess] → It only changes a label; matching continues.
- [Spike files are under review by another session] → This change edits `ConnectionService`, `RemoteClient`
  (`RemoteConnection`), `Frames`, `AttentionNotifier`, `AttentionPolicy`, `Notifications` and `MainActivity`;
  implementation starts only after that review's fixes are committed.

## Migration Plan

No data migration: nothing new is persisted. The status screen stops being the paired home and is reached from
the session list. The change is not committed until the user has tested it on the phone.

## Open Questions

None blocking. Whether a reply typed while the agent works is confirmed is answered by the phone test (Risks).
