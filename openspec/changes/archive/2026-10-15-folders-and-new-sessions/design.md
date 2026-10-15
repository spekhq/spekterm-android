## Context

Built on `session-conversation` and `addresses-and-mid-turn-replies` (archived). The session list
(`ui/SessionsScreen.kt`) draws `SessionsView.groups(list)` in a `LazyColumn`: per group a small `labelLarge`
text in the primary color, then the rows; it returns early, before the `LazyColumn`, when there is no list or
the desktop has a window and no sessions. `Desktop` (process-wide, one single-threaded dispatcher) holds
`sessions: StateFlow<SessionsState>` (`list`, `current`, `receivedAt` — no connection identity) and makes
requests over the attached connection. `wake` is a `suspend` call made from the screen's
`rememberCoroutineScope`, running its request in `a.scope.async { … }.await()`; that shape is **not** reused
here (D6). Per-pairing data lives in small `AtomicFile` JSON stores (`PositionStore`: `{deviceId, …}`), deleted
on pairing and unpairing in `MainActivity` and on the desktop's revocation in `ConnectionService.unpair`, next
to `Desktop.clear()`. `Notice` (`ui/Common.kt`) has one optional action. The connection log records codes and
outcomes, never text. The app has no icon library; top-bar actions are text buttons.

Facts about the desktop that shape the design (spekterm 0.2.5 — `docs/remote-protocol.md` section 5,
`src/main/remote-protocol.ts`, `remote-host.ts`, `remote-requests.ts`, `remote-service.ts`, `session-create.ts`,
`agent-events.ts`, `src/renderer/src/shell/remote/RemoteRequests.tsx`, `shell/terminal/sessions.tsx`,
`transcript-follow-service.ts`):

- **`sessions.create` needs `folderId` present.** `p.folderId === null ? null : str(p, 'folderId')`: the global
  item is an explicit JSON `null`; an absent key is `BAD_PARAMS`. `target` must be `"claude"` or `"shell"`.
  The app's `Frames` encoding keeps a `JsonNull` inside a `JsonObject` (`explicitNulls = false` does not drop
  it) — fixed by a test (2.1).
- **It is carried out by the desktop's interface, quietly** (`api.create(…, { quiet: true })`): persisted; it
  sets no item's focused session and does not take keyboard focus, so it replaces nothing on screen — **but a
  folder with no session that is selected on the desktop shows the new one** (the `focusedIdFor` fallback).
  Outcomes: `{ sessionId }`; `NO_WINDOW`; `CREATE_FAILED` with the interface's message — **an unknown or
  unavailable folder is also `CREATE_FAILED`**, and the message may hold an absolute path; `TIMEOUT` after 15 s
  (`RENDERER_REQUEST_TIMEOUT_MS`), below the phone's 20 s call timeout. A `TIMEOUT` does not cancel the
  interface's work: the session may still be created afterwards.
- **The list and the answer race.** The session is added provisionally (`addProvisional`, not running) before
  its process is spawned, which starts the 150 ms list debounce (`LIST_DEBOUNCE_MS`); the spawn awaits the user
  environment and any previous exit. So the answer usually precedes the list holding the session, but a list
  can also arrive first holding it with `running: false`. Opening the conversation on the answer alone would
  show "closed on the desktop"; opening it on a not-yet-running listing would flash "dormant".
- **A new agent session's conversation is `ok` with no entries** while its transcript file does not exist yet,
  so the phone's conversation moves from catching up to `Phase.OK` with nothing to show — today a blank area.
- **A new agent's wait state is `unknown` until the agent's `SessionStart` hook reports** (`agent-events.ts`:
  `SessionStart` → `ready`). With the desktop's agent events off, it stays unknown, and the reply gate
  (`conversation-reply`) keeps sending impossible from the phone (it already says to reply on the desktop).
- **Only macOS has a desktop without a window**: on Linux and Windows closing the last window quits spekterm
  (`index.ts` `window-all-closed`), which the phone sees as remote access off.
- `Folder` already decodes `branch` (`net/Sessions.kt`).

## Goals / Non-Goals

**Goals:**
- Headings that make the folder obvious at any scroll position; collapsing that never hides attention.
- Starting an agent session in any folder of the desktop, ending in its conversation on the phone.

**Non-Goals:**
- Creating shell sessions, closing, renaming or hibernating sessions from the phone (not protocol operations,
  or useless without a terminal view).
- Choosing the agent's first prompt at creation (the user types it in the conversation once it opens).
- Making a new session usable from the phone when the desktop's agent events are off (recorded, not solved).
- Reordering or filtering folders on the phone; the desktop's order is kept.

## Decisions

### D1. Group identity

`SessionsView.Group` gains `key: String`: `"global"`, `"folder:<id>"`, or `"other"` (sessions of folders the
list does not name). The key is the `LazyColumn` item key and the collapse key. `Group` also gains `branch`
(from the folder) and `summary: Summary(choice: Int, ready: Int)` counted from the rows' `RowState`
(`CHOICE`, `READY`). Pure, tested on the JVM.

### D2. Headings

Each group's heading is a `stickyHeader` (`LazyListScope.stickyHeader`, foundation; opt in at the call site if
still experimental): a full-width band in `surfaceContainerHigh`, the name in `titleSmall` (`"Global"`, the
folder name, or `"Other folders"`), the branch after it in `onSurfaceVariant` when non-null, the session count
at the end, and a chevron (`▾` / `▸`) for the collapse state; the whole band is the tap target (≥ 48 dp). When
collapsed and the summary is non-zero, a second line says e.g. "1 asks for a choice · 2 waiting for you" in the
colors the rows use for those states. Alternative considered: cards per folder — rejected, a sticky band
carries the same separation with less height and stays visible while scrolling.

### D3. Remembering collapsed groups

A `CollapsedStore` beside `PositionStore`: one `AtomicFile` JSON `{ deviceId, keys: [String] }`; `load(deviceId)`
returns the empty set when the stored device differs or there is no file; `save(deviceId, keys)`; `delete()`.
It is deleted wherever positions are: `MainActivity.onPaired`, `MainActivity.unpair`, `ConnectionService.unpair`.
`SessionsScreen` (given the pairing's `deviceId`) loads it once into a `mutableStateOf<Set<String>>`; writes go
through a single-threaded writer (`Dispatchers.IO.limitedParallelism(1)`), each writing the whole latest set,
so quick toggles cannot land out of order. Keys of folders that disappear are kept (that is what makes a
returning folder stay collapsed); the set is bounded by the folders the user ever collapsed and cleared with
the pairing. Alternative considered: pruning keys not in the current list — rejected, a folder briefly gone
(the desktop's window closed: the list then holds no folders) would lose its state.

### D4. Top bar, the menu, and settings

The top bar holds "New" (D5) and a "⋮" menu. The menu holds "Collapse all" when any shown group is expanded,
else "Expand all" (left out when there are no groups), and "Settings". The "Connection" text button goes away:
"Settings" opens the existing status screen (`StatusRoute`), which already holds everything the app lets the
user set — pause / resume, notification and battery settings, exporting the log, pairing again, unpairing —
so it is retitled "Settings" with the desktop's name and connection state as its first section ("Connection"),
and nothing else on it moves. The not-connected notice's "Details" keeps going straight to it. Alternative
considered: a separate settings screen with the status screen under it — rejected, it would be a screen with
no content of its own. Collapse all adds
every shown group's key; expand all clears the set (including keys of groups not shown, which is what "expand
all" means to the user).

### D5. Where "New session" lives, and when it is usable

"New" opens a `ModalBottomSheet` listing `SessionsView.choices(list)`: the global item, then every folder of
`list.folders` in order (with or without sessions), each with its branch. Tapping a row closes the sheet and
calls `Desktop.create`. A pure `SessionsView.canCreate(connected, paused, sessions, creation)` decides
whether "New" is enabled: connected, not paused, `sessions.current`, `list.window`, and no create `Starting`.
While the sheet is open, `canCreate` turning false closes it (the list's existing notice says why).
Alternative considered: a "+" on each heading — rejected for now: it cannot reach folders without sessions
(they have no heading), and a stray tap beside the collapse target would create a session on the desktop.

### D6. `Desktop.create`: owned by `Desktop`, decided on the dispatcher

`create(folderId: String?, name: String)` is **not** `suspend` and does not run in the caller's scope: leaving
the screen or closing the sheet must not cancel it. It launches in `Desktop`'s own `scope`, and every step
runs on the dispatcher:

1. Returns `Unit`; does nothing unless attached and no create is `Starting` (the UI is gated by `canCreate`).
   Sets `creation = Starting(name)`, remembers `a = attached` and the current `generation`.
2. `request(a, "sessions.create", …)` directly (no `a.scope.async`; the coroutine is a child of `Desktop`'s
   scope, not of `a.scope`, so a detach fails the call with `ConnectionClosedException` → `null`, never a
   cancellation); `null` counts as "no answer". A `try/finally` writes `Failed(UNKNOWN)` only when `creation`
   is still `Starting`, this create set no `pending`, and `generation` is unchanged — so a successful answer
   that is waiting for the list is never overwritten, and nothing is written after a `clear()`.
3. The answer goes through `CreateOutcome.of(ok, sessionId, code, message)`: `NO_WINDOW`; `CREATE_FAILED`
   with the desktop's message; `TIMEOUT` and no answer → `UNKNOWN`; another code, or ok without a
   `sessionId` → `CREATE_FAILED` without a message.
4. On `{ sessionId }`: if `attached !== a` already (the detach was handled first) → `Unlisted` at once.
   Otherwise a `pending = Pending(token = (sessionId, generation), a)` is held on the dispatcher, and the
   tracker decides: the held list is checked at once; then `applyList` (when `attached === a` and the list
   holds the id **with `running: true`**) → `Opened`; `detach(a)` → `Unlisted`; a 10 s timer in `Desktop`'s
   scope → `timedOut(token)` → `Unlisted`. The first of these wins and clears `pending`; the timer's `Job` is
   cancelled when `pending` resolves or is cleared, and a `timedOut` whose token is not the current
   `pending`'s is ignored (so an earlier create's timer can never end a later create's wait).
5. `clear()` and `resetForTest()` increment `generation` and drop `pending` and `creation`; a create of an
   older generation writes nothing.

The transitions (answered, listed, detached, timed out, cleared) are a pure `CreateTracker` reducer over
`(pending, event) → (pending, creation?)`, tested on the JVM; `Desktop` only feeds it. No automatic retry, at
any layer: a retried create can make two sessions. The log gets kind `CREATE` with `global` (bool), `outcome`
and `error` code — never the folder name or the desktop's message (it can hold a path).

Alternative considered: waiting for the list with `withTimeoutOrNull` on the `sessions` flow outside the
dispatcher — rejected: `SessionsState` has no connection identity, reading `attached` off the dispatcher is a
race, and a conflated `StateFlow` can hide a detach followed by a quick reconnect.

### D7. Opening the new session

`Desktop.listOnScreen` (`@Volatile`, written from the main thread) is set by `SessionsScreen` on `ON_RESUME`
and cleared on `ON_PAUSE` and on dispose (re-pairing removes the whole graph), as `focus` / `unfocus` are by
the conversation screen. The tracker records it at the moment it produces `Opened(sessionId, name,
autoOpen)`, and clearing it turns a not-yet-consumed `Opened` into `autoOpen = false` — so an `Opened` produced
just before the user left the list cannot navigate when they come back. `SessionsScreen` navigates (`onOpen(sessionId)`, now taking an id; `MainActivity` adapts) and
calls `consumeCreation()` only when `autoOpen` is true; otherwise — the user was reading another conversation,
or the screen was off — it shows "Started a session in X" with "Open" and "Dismiss", so returning to the list
never navigates on its own. `Starting` ("Starting a session in X…"), `Unlisted` ("Started a session in X; it
will appear in the list."), and `Failed` show as a notice with "Dismiss": NO_WINDOW "spekterm has no open
window on the desktop."; CREATE_FAILED "The desktop could not start the session: <message>"; UNKNOWN "Can't
tell whether the session was started. If it was, it will appear in the list."; NOT_CONNECTED is not reachable
from the UI (`canCreate`) and maps to UNKNOWN's text if it happens. These notices are drawn **before** the
screen's early return for an empty list (an empty desktop is where "New" is most used). `Notice` gains an
optional second action. Because `Opened` is produced only once the list holds the session running,
`Screen.of` needs no new state.

### D8. No messages yet

`ConversationScreen.Entries`: when `phase == Phase.OK`, no entries and no replies, it shows "No messages yet."
centered, instead of the blank box. Catching up and the other phases keep their notices.

## Risks / Trade-offs

- [The desktop finishes a create after answering `TIMEOUT`] → the outcome says the phone cannot tell, and the
  list shows the session when it arrives; nothing retries.
- [The user creates again after an `UNKNOWN` outcome] → two sessions on the desktop; the message points to
  the list first. Accepted: closing a session on the desktop is easy.
- [The spawn takes longer than 10 s] → `Unlisted`; the session appears in the list and the user opens it from
  there.
- [Agent events off on the desktop] → the wait state stays unknown and the phone cannot send the first
  message; the conversation's existing notice says to reply on the desktop. Recorded in 4.1 (how long
  "unknown" lasts with events on).
- [A selected empty folder on the desktop shows the new session] → desktop behavior; the spec promises only
  no change of selection or focus and no replaced session. Recorded in 4.1.
- [`stickyHeader` experimental in this foundation version] → opt in at the call site; behavior checked on the
  phone.
- [The collapsed store is read on the main thread at first composition] → a file of a few hundred bytes.

## Migration Plan

None: new store file, absent on upgrade (= nothing collapsed). Rollback is reinstalling the previous build;
the extra file is ignored.
