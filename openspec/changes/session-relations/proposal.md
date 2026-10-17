## Why

On the desktop, a session started by a handoff sits under the session that handed it off, marked with where it came
from and what it was asked to do. On the phone every session is a flat row: the only trace of a handoff is a
"handoff: working" line, so the user cannot tell which agent started which, which ones a parent is waiting on, or
what a session was handed off to do. The desktop already sends all of this to paired devices — every session's
`lineage { parentId, title? }` and `lifecycle` in `sessions.list`, and `handoffs.list` / `handoffs.brief` /
`handoffs.changed` for the handoff note and the latest report — since spekterm a66f975 (protocol version 1). The app
decodes `lineage` and shows none of it. No desktop change is needed for this part.

The same rows also over-report attention: every agent at its prompt (`wait: "ready"`) shows "Waiting for you" for
as long as it sits there, so the state stops meaning "needs you". spekterm is adding, in its change
`session-wait-indicator`, a `seen` mark on a ready session — `true` once the user has looked at it since its turn
ended, on the desktop or by watching its conversation from a paired device — and its rail will show a seen ready
session as idle. The phone must apply the same rule so both ends agree on what waits for the user.

## What Changes

- **The session list nests handoffs under their source.** Within one folder's group, a session handed off from
  another session of the same group is shown under it, indented, as the desktop's rail does: siblings in the
  desktop's order, indentation at most three levels deep, a broken relation (a source that is gone, in another
  group, or a loop) shown at the top level of its group. No session ever disappears from the list because of its
  relation. Folders, their order, collapsing and the heading counts are unchanged.
- **A row says where a handoff came from**, in the desktop's words, when it is not shown under its source: "From
  <folder> › <label>" for a source still on the desktop (in another folder, or the global item), "From <title>
  (closed)" when the source is gone and the desktop kept its title, "From a session that has closed" otherwise; a
  row shown under its source says "Handoff". The handoff's state follows on that line: "Working", "Not done yet"
  (not "Waiting for you", which the row's own state already says, or contradicts with "Idle"), "Done".
- **A row says what was handed off from it**: "Handed off N sessions", with how many are done ("1 of 2 done", "all
  done"), counting every session handed off from it that is still on the desktop, in any folder.
- **A handoff screen** for a session that came from a handoff or handed off others, opened from the session's menu
  (long press in the list, or the menu on its conversation screen) as "Handoff": where it came from (tappable when
  that session is still on the desktop), the handoff note read with `handoffs.brief` each time the screen opens,
  the latest report with its time, and the sessions handed off from it with their state and latest report summary,
  each tappable. A desktop without a window (macOS after closing it) never reads as the session being closed. The note and the report are plain text; nothing in them loads or opens anything.
- **The session menu opens for relations too**: a session with a relation gets "Handoff" in its menu even when the
  desktop accepts no session action, and the item works while the phone is not connected (from the last list; the
  note says it needs the connection).
- **The app keeps the desktop's handoff list**: it asks `handoffs.list` after each authentication when the desktop
  offers it and follows `handoffs.changed`, for the children's latest reports.
- **A ready agent the user has looked at is idle, not waiting.** A running agent session whose `wait` is
  `"ready"` and that the desktop marks `seen: true` shows "Idle", calm, not in the attention colour; with
  `seen: false` it shows "Waiting for you" as today. A desktop that sends no `seen` (an older one) keeps every ready
  session "Waiting for you". "Asks for a choice", "Working", unknown and dormant are unchanged, and so is an agent's
  offer to close its session. A collapsed folder's heading counts only unseen ready sessions as waiting.
- **A waiting notification goes once the desktop no longer counts the session as waiting.** When a session turns
  ready and seen in the list, the phone removes that session's "waiting" or "asks for a choice" notification (not a
  "handoff finished" or inbox one).
- **The connection log records** the handoff requests (`HANDOFFS`, `BRIEF`) with their error codes; never a label,
  a note or a report.

## Capabilities

### New Capabilities

- `session-relations`: showing how the desktop's sessions relate by handoff — nesting in the list, the source and
  children marks on a row, and the handoff screen with the note, the latest report and the related sessions.

### Modified Capabilities

- `session-list`: sessions of a group are shown as a tree by handoff instead of a flat list in the desktop's order,
  and a handoff's row says its source and state per `session-relations`; a session's menu also opens for a session
  with a relation, holding "Handoff"; a ready session marked seen shows idle instead of waiting for input, and a
  collapsed heading counts only unseen ready sessions as waiting.
- `app-appearance`: idle joins the states told in words, with no more emphasis than working.
- `attention-notifications`: a session's waiting or choice notification is removed once the desktop's list shows
  it ready and seen.

## Impact

- `net`: `Operations.HANDOFFS_LIST` / `HANDOFFS_BRIEF`, `Frames.handoffsBrief`, decoding of the handoff list
  (element by element) and the brief; `handoffs.changed` routed to `Desktop` in `EventRoute`.
- `desktop`: the tree and the relation marks as pure functions next to `SessionsView`; `Desktop.handoffs` (the
  last handoff list) and `Desktop.brief(sessionId)`; log kinds `HANDOFFS`, `BRIEF`.
- `ui`: indentation and the relation line in `SessionsScreen`'s rows; the "Handoff" item in `SessionMenu`; a new
  handoff screen and its route in `MainActivity`.
- `tools/drop-handoff.mjs`: makes handoffs and reports on a dev desktop, for screenshots and the phone run.
- Tests: new pure tests for the tree, the marks and the handoff screen's state, `FramesTest`, `EventRouteTest`, `DesktopTest`; a phone run
  recorded in `docs/conversation-findings.md` against a dev spekterm desktop.
- `seen`: `Session.seen` (lenient, like `closeOffered`), `RowState.IDLE` in `SessionsView` and `StateLabel`, the
  collapsed summary, and removing a session's notification on the transition to ready-and-seen (service side,
  `AttentionPolicy.sessionKey`). Tests: `FramesTest`, `SessionsViewTest`, a pure transition test.
- **Depends on spekterm's `session-wait-indicator`** for `seen` (the user chose to keep it in this change, so this
  change is archived only after that is committed and the phone run against it is done): not committed when this was written. The shape
  used here is the one in that change's spec (field `seen`, always `true` or `false` exactly while `wait` is
  `"ready"`; reset at each unseen turn end; a watch already open at the turn's end counts; a session ready without a
  turn ending is seen), confirmed by its session but not yet in a committed protocol text; its protocol commit is recorded in design.md before the `seen` tasks are
  built, and the phone run needs a desktop with it.
- No new dependency. A desktop that sends no `lineage` shows the list as before; one without the handoff
  operations shows the tree and the marks but no note and no children's reports.
