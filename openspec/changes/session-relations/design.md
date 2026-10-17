## Context

Built on `openspec-browse` (archived). `Desktop` holds the last session list (`Desktop.sessions`, replaced whole by
each `sessions.list` answer and `sessions.changed` event, ordered within a connection by `ListClock`) and the
operations from `hello`. `Session` already decodes `lineage: Lineage(parentId, title?)` and `lifecycle`; the list
shows `lifecycle` as "handoff: <state>" and ignores `lineage`. `SessionsView.groups` groups rows by folder: the
global item first, then the desktop's folders in order (folders with no session left out), then one "Other folders"
group for sessions of a folder the list does not name; rows keep the desktop's order. The session menu
(`SessionActions.of`, `SessionMenu`) is shared by the list's long press and the conversation screen's top bar, and
opens only when the desktop accepts at least one action. Events reach `Desktop` through `EventRoute.DESKTOP_KINDS`;
other kinds are ignored. The interface is English.

Facts about the desktop (spekterm `docs/remote-protocol.md` §5–§6, `src/main/remote-host.ts`,
`src/main/handoff-brief.ts`, `src/main/handoff-lifecycle-view.ts`; the rail in
`src/renderer/src/shell/session-forest.ts`, `WorkspaceRail.tsx`, `terminal/lineage.tsx`, `terminal/HandoffBrief.tsx`):

- `lineage` is on every session the desktop created from a handoff: `parentId` is the session that handed it off
  (it may no longer exist), `title` the source's title at the time of the handoff, when the desktop has one. The
  desktop's own snapshot of the source's folder is not sent.
- `lifecycle` (`working` / `waiting` / `done` / `idle`) is on handoff-created sessions; `idle` means nothing to say
  (hibernated before reporting done, ended, wait unknown); a done handoff stays `done` when hibernated.
- `handoffs.list` → `[{ sessionId, state, summary?, reportedAt? }]` (an array) for every handoff-created session;
  `summary` / `reportedAt` are the latest completion report. `handoffs.changed` carries the same array to every
  connection without subscribing.
- `handoffs.brief { sessionId }` → `{ body?, report?: { summary, reportedAt } }`; `NOT_FOUND` when the session has
  no stored note. `body` is the handoff's full text (up to 20 000 characters), written by an agent.
- All three arrived with the protocol itself (spekterm a66f975, 0.2.5); `hello`'s `operations` lists them from
  29a5d0a (0.2.6), the oldest desktop the app supports.
- `handoffs.changed` is pushed (with `sessions.changed`, debounced 150 ms) when a tracked handoff session's report
  or wait state changes (`handoff-completion.ts` `track()`, `index.ts` `onCompletionChange`), but not when one is
  hibernated, newly created or closed (`ipc/terminal.ts` only broadcasts the session list then). So the handoff
  list's `state` can be stale; its `summary` / `reportedAt` are not.
- `handoffs.brief` answers `NOT_FOUND` when the session has no stored note, yet a session can still have a report
  (a handoff from before notes were stored, or one with a third-party body — `session-store.ts`): its summary is in
  `handoffs.list` only.
- An exited session leaves the desktop's list at once (`src/main/ipc/terminal.ts`: a pty that really ends is removed
  from the store); there is no "ended but still listed" state, so `running: false` in the list always means
  hibernated (dormant).
- With no window, the desktop answers a current list with `window: false` and no sessions (renderer reload or a
  closed window).
- The rail's tree: a session's parent is its `lineage.parentId` **only when that session exists and is in the same
  rail item**; a self-reference or a loop makes the node a root; siblings keep the item's single order; DFS
  flattening; indentation capped at depth 3. A session handed off from another item, or from one that is gone, is a
  root and says where it came from ("← source"); a parent shows "→ N" for its existing children in any item. The
  brief dialog shows the note as plain text (`whitespace-pre-wrap`, no Markdown), and the latest report with its
  time; it is fetched anew each time it opens.
- The desktop's "exists" (`src/shared/lineage/existence.ts`): in the list, not exited, its folder still in the
  workspace. Hibernated counts as existing.
- The desktop's words (`src/shared/i18n/en.json`): "From <repo> › <title>", "From <repo> › <title> (closed)", "From a
  session that has closed", "Handed off N sessions"; states "Working", "Waiting for you", "Done".

Facts about `seen` (spekterm change `session-wait-indicator`, **not committed** when this was written; from that
session's message of 2026-10-17 and this side's reply):

- `Session.seen?: boolean`, present only while `wait` is `"ready"`. `true` = the user looked at the session since
  its turn ended: shown on the desktop while its window is focused, or its conversation watched by a paired device.
  The desktop's main process decides it; changes arrive in `sessions.changed`.
- A desktop that never sends `seen` is older: every ready session stays "Waiting for you".
- In that change's spec (confirmed by its session, protocol text not committed): `seen` is always present
  (`true` or `false`) exactly while `wait` is `"ready"`, absent otherwise, so its absence on a ready session means
  an older desktop; `false` again at every turn end nobody saw; a turn that ends while the session is watched (or
  shown in the focused desktop window) is `seen: true` the first time it is ready; a session that becomes ready
  without a turn ending (a fresh start, a resume after a restart, `/clear`) is `seen: true`. Attention events are
  unchanged (`waiting` still raised). Added in its review: a wake from hibernation (the phone's quiet
  `sessions.wake` included) is `seen: true` once the agent reports its resume; and a handoff session's `lifecycle`
  follows the same mark — `waiting` only while ready and unseen (or asking for a choice), `idle` once seen; `working`
  still follows `busy`. Divergence it documents: typing over an open permission prompt on the desktop answers it
  there, but the list keeps `wait: "awaiting-choice"` until the agent's next event, so the phone keeps "Asks for a
  choice" meanwhile (no field says otherwise; not mirrored here).
- The phone watches a conversation only while its screen is visible: `conversation.watch` on `ON_START`,
  `conversation.unwatch` on `ON_STOP` (backgrounding and screen lock included) — so a phone watch is the user
  looking at it.

## Goals / Non-Goals

**Goals:**

- The list shows the same tree the desktop's rail shows, from the same data, so the two never disagree about which
  session sits under which.
- Every relation can be followed from the phone: to the source, to each child, to the note and the latest report.
- Nothing new on the desktop side for relations. `seen` (D7–D8) needs spekterm's `session-wait-indicator`; this
  change cannot be archived before that is committed and the phone run against it is done (the user chose one
  change for both).

**Non-Goals:**

- Reordering sessions (the rail's drag) or collapsing a subtree; a folder still collapses as a whole.
- Changing what a "Handoff finished" notification opens: it still opens the session's conversation, whose menu
  now leads to the handoff screen.
- The inbox (a handoff's arrival) and accepting or ignoring items: a later change.
- Showing the relations file an agent reads; the phone shows the desktop's sessions, not agents' views.
- `seen` on the conversation screen: its subtitle keeps "Waiting for you" from the conversation's own wait state.
  There it says whose turn it is, and the user is by definition looking at it (the watch is what makes it seen).
- Telling the desktop anything new: the phone's existing watch is the only signal it sends.

## Decisions

### D1. The tree: the rail's rule, per group, with "exists" as the phone can see it

A pure function builds each group's tree from the group's rows in the desktop's order: a row's parent is its
`lineage.parentId` when a row of **the same group** has that id and it is not the row itself; then any row whose
chain of parents loops is made a root; children are listed under their parent in the desktop's order; rows are
flattened depth first; each row carries its depth, shown at most 3. This is `buildForest` in Kotlin, test cases
ported.

"Exists" on the phone is "in the session list the phone holds (the last one received, current or not) and in a
group that list names" — the global item or a folder in
`folders`. The "Other folders" group holds sessions of folders no longer in the workspace, which the desktop counts
as gone, so it builds no tree (all roots) and its sessions are never anyone's existing source or child — nor do they
get a children line or "Handoff" for having children. An exited session is not in the desktop's list at all, so
this rule gives the rail's answer in every case.

A malformed `lineage` (no string `parentId`, or not an object) is read as no lineage, never as a reason to drop the
session: `Lineage` is decoded leniently like `closeOffered`.

*Alternatives:* nesting across folders (a child under a parent in another folder) — rejected: the group heading
would no longer describe its rows, and the rail does not do it. A flat list with marks only — rejected: the user
asked to see the relations, and the rail's shape is what they already read.

### D2. The relation line on a row

Two pure functions, so the handoff screen can reuse the first without the second's tree rule:

- **The source description**, for any session with `lineage`, wherever it sits: "From <group> › <label>" when the
  source exists (D1) — `<group>` is the folder's name or "Global", `<label>` its current label; "From <title>
  (closed)" when it does not and `lineage.title` is non-blank; "From a session that has closed" otherwise. The
  desktop's words.
- **The row's relation line**: for a row with `lineage`, the source description when the row is a root, or
  "Handoff" when it is nested under its source (the indentation names the source; the word still says it is a
  handoff); then the state, joined with " · ": `working` → "Working", `waiting` → "Not done yet", `done` → "Done";
  `idle` and unknown values add nothing (a nested row with none reads "Handoff"). Under it, for an existing row
  (D1) with existing children: "Handed off N session(s)" counting existing sessions whose `lineage.parentId` is
  this row, in any group; "· 1 of 2 done" when some are `done`, "· all done" when all are.

`waiting` is not worded "Waiting for you" as on the desktop: the phone's right-hand state already says "Waiting for
you" or "Asks for a choice" in words, and an older desktop without `seen` sends `waiting` for a seen agent too, which
the left would then contradict with nothing to reconcile it (the desktop tells the states apart with icons). "Not
done yet" is what the lifecycle adds: the handoff has not reported done. With `session-wait-indicator`, a seen
stopped handoff is `idle` and its line says only "Handoff" or its source. The labels are the desktop's labels, which follow renames.

*Alternative:* icons and counts only, as the rail's tabs do — rejected: the phone has room for words, and icons
without labels are what made the rows unreadable before.

### D3. `Desktop.handoffs`: the desktop's handoff list, followed

After each authentication, when `operations` holds `handoffs.list`, `Desktop` asks it next to `sessions.list`, and
applies `handoffs.changed` events; answers and events are ordered by their own `ListClock` as the session list is.
The list is decoded element by element (one malformed entry skipped; unknown fields ignored) into a map by session
id. Like the session list it is kept, marked not current, when the connection ends, and dropped by `clear()` and
`resetForTest()` (with its clock). Only its `summary` and `reportedAt` are used: every state shown, on rows and on
the handoff screen, comes from the session list's `lifecycle`, because `handoffs.changed` is not sent when a child
is hibernated, created or closed.

*Alternative:* asking `handoffs.list` only while the handoff screen is open — rejected: the event arrives anyway,
and a screen that opens empty and fills a moment later is worse than a map already held.

### D4. The handoff screen

A route `HandoffRoute(sessionId)`, reached from "Handoff" in the session's menu. It reads everything except the note
from `Desktop.sessions` and `Desktop.handoffs`, so it follows the desktop live.

What it shows first is one pure function, in this order (as `Screen.of` for the conversation screen): **paused**
and **not connected** — the relations from the last list with a window, and the reason, as the list's notices say
it; **no window** — the relations from the last list that had one, and "spekterm has no open window on the
desktop"; **closed** — "This session is no longer on the desktop." when a current list with a window lacks it;
otherwise the content. In every case the relations come from the last list that had a window, which is kept by the screen while it is open, so a renderer reload
on the desktop never reads as closed and never empties the relations.

Sections, each only when it has something:

1. **From** — for a session with `lineage`, nested or not: the source description of D2; tapping it opens the
   source (its conversation, or the shell notice) when the source exists.
2. **Handoff note** — for a session with `lineage`, when `operations` holds `handoffs.brief`. Asked on entering the
   screen (keyed by the session id, not on every recomposition), again when the session's `reportedAt` in
   `Desktop.handoffs` changes, and again when the connection comes back while the note is not loaded or last failed
   for lack of a connection. While asking: "Loading…". `NOT_FOUND`: "The desktop kept no note for this handoff." An
   answer without `body`: the same sentence. Not connected: "Reading the note needs the connection to the desktop."
   Another error: what failed, with "Try again". The body is selectable plain text in the body style, line breaks
   kept, no Markdown, no link detection.
3. **Latest report** — for a session with `lineage`: of the brief's `report` and the summary in
   `Desktop.handoffs`, the one with the later `reportedAt` (so a brief still on its way never takes the screen back
   to an older report, and a `NOT_FOUND` brief still shows a report the list has); "No report yet." when neither has
   one.
4. **Handed off from here** — each existing child (D1), in the desktop's list order, with its label, its group
   name, its state from `lifecycle` (D2's words) and its latest summary from `Desktop.handoffs` (one line,
   ellipsized); tapping one opens it.

The brief is held by the screen (not cached in `Desktop`), fetched again on every visit, as the desktop does.
(Whether `popUpTo` on a typed route matches its arguments in the Navigation version used is checked when
building; if not, the screen pops by back-stack entry id.) Opening a session from the screen pushes its conversation (back returns here); opening "Handoff" for a session
whose handoff screen is already on the back stack pops back to it instead of stacking another
(`launchSingleTop` with `popUpTo` on that route), so From → child → From cannot grow the stack without bound.

*Alternative:* a bottom sheet over the list — rejected: the note can be 20 000 characters, and a sheet that scrolls
inside a list that scrolls is hard to read; a screen also gives back navigation from a child to its source.

### D5. "Handoff" in the session menu

`SessionActions.of` gains `Action.HANDOFF`, first in the menu, when the session has `lineage`, or exists (D1) and
has at least one existing child, whatever `operations` holds. The menu therefore opens for such a session even on a desktop that
accepts no other action. Unlike the other items, it does not need the connection: it is enabled whenever shown. The
conversation screen's menu gets the same item from the same function.

### D6. Logging

`Kind.HANDOFFS` (the list answer: `error`, `count`) and `Kind.BRIEF` (`error`, `hasBody`, `hasReport`); events are
not logged one by one. Never a label, a note or a summary.

### D7. `seen`: a ready agent the user looked at is idle

`Session` gains `seen: Boolean?`, decoded leniently: the JSON booleans give `true` / `false`; absent or any other
value gives `null` (obligation 7: a strange value never drops the session). `SessionsView.state` maps a running
agent with `wait: "ready"` to `RowState.READY` when `seen` is `false` or `null`, and to a new `RowState.IDLE` when
`seen` is `true`; every other mapping is unchanged. `StateLabel(IDLE)` says "Idle" with a neutral dot
drawn hollow in the working color (`StatusDot(spek.working, hollow = true)`), normal weight, `onSurfaceVariant`
text — no more emphasis than "Working", and not the grey hollow dot that already means dormant (as the desktop
draws idle: a hollow green ring).

The close offer keeps its precedence: an offered session shows the offer whether `READY` or `IDLE` (the offer is
its own attention state), and the collapsed summary counts it as an offer either way. `Summary.ready` counts only
`READY` rows not offered; `IDLE` counts nowhere. A reply's gate (`conversation-reply`) reads the conversation's
wait state, not the row's, so it is unaffected.

*Alternative:* hiding the state of a seen ready session entirely (the `NONE` dot) — rejected: "Idle" says the
agent is at its prompt, which the user still wants to know; `NONE` means "running, state unknown".

### D8. Removing a waiting notification once the desktop no longer counts the session as waiting

A pure tracker compares each applied session list with the previous one (per connection; the first list compares
with nothing): every running agent session that is ready with `seen: true` now and was not ready-and-seen before
is a transition. For each transition the service cancels the session's notification
(`AttentionPolicy.sessionKey(sessionId)`, `AttentionNotifier.ID`) **only when the notification shown under that key
was raised for a `waiting` or `choice` event**: `AttentionNotifier` remembers, in memory, the kind it last showed per
key (forgotten when it is cancelled; empty after the process restarts, which then removes nothing — the safe side).
A "Handoff finished" or inbox notification is never removed this way: `seen: true` does not prove the user saw a
report that may have been adopted after the turn ended (spekterm `lifecycle.ts`), and the first list of every
connection (a reconnect, a desktop window reload) counts as a transition.

`seen: true` also comes without anyone looking: a session that became ready without a turn ending (a restored or
woken agent, `/clear`) is seen. A `waiting` notification from before is then removed too; that is intended — the
desktop no longer counts the session as waiting for the user, and the row says "Idle". The first list of a
connection must count, because the attention replay is applied before the list is asked, so a session looked at on
the desktop while the phone was away would otherwise keep its notification. The tracker is fed from `Desktop.applyList`, once per list accepted
by the `ListClock`, never from the `StateFlow`, which may skip an intermediate list (ready+seen, busy, ready+seen
would lose its second transition). Only transitions count, so a `waiting` notification raised for a new turn end, which arrives
before the list that resets `seen` to `false`, is not removed by the stale `true` of the previous list.

*Alternative:* removing it on every list that shows it seen — rejected for that race. Removing it from the
attention stream — rejected: the desktop raises no event for being looked at.

## Risks / Trade-offs

- [`seen`'s shape changes before spekterm commits it] → the `seen` tasks start only after the protocol commit is
  recorded here; the relations tasks do not depend on it.
- [A desktop that omits `seen: false` while ready] → read as an older desktop: "Waiting for you", the safe side.
- [A `waiting` notification removed after a desktop restart or `/clear`, with nobody having looked (D8)] →
  accepted: the desktop no longer counts the session as waiting; the row says "Idle".

- [A deep tree on a narrow screen] → indentation capped at depth 3 (16 dp a level), as on the rail. A row deeper
  than 3 is drawn at depth 3, level with its parent, and says nothing about its source (it is nested), so it reads
  like a sibling. → Accepted, as on the desktop; the handoff screen shows the exact relation.
- [A long note] → its own screen, scrollable, selectable; nothing is truncated.
- [The note is agent-written text] → plain text only; no Markdown, no links, nothing loaded (as the desktop's
  dialog, and in the spirit of obligation 2 for inbox bodies).
- [A desktop without `handoffs.list` / `handoffs.brief`] → they arrived with the protocol, so every desktop the
  app supports has them; the screen still works from the session list alone.

## Migration Plan

None: an app update; nothing stored changes.
