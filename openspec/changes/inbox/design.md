## Context

Built on `background-tasks` (archived). `Desktop` holds what the desktop sends over the service's one connection: the session list
(`sessions`, stamped by `ListClock`), the handoff list (`handoffs`, a `HandoffsState` stamped by its own `seq`, read at attach
when `handoffs.list` is in `operations`, kept across a detach, emptied by `clear()`), the focused conversation, OpenSpec reads, and
a session being started from the phone (`creation`, driven by `CreateTracker`: the conversation opens only once the list shows the
new session running, and only while the list is on screen). `EventRoute` sends `sessions.changed`, `handoffs.changed` and the
conversation events to `Desktop`; any other kind is ignored. The attention notifier posts an `intake` event keyed by its item and,
having no `sessionId`, opens the session list on tap. The conversation screen treats a session the current list does not hold as
closed (`SessionsView.kt:208`), and a list with a window that lacks the held session marks the conversation gone for good
(`Desktop.kt:717`) — so a conversation must not be opened before its session is listed. Replies are gated by `Replies.blocked` and
their errors mapped by `SendOutcome.of`. Screens are Compose, navigated by typed routes in `MainActivity.Paired`; the session list's
top bar has only the overflow menu, and `SessionsScreen` reads `Desktop`'s flows directly. Texts live in `values/strings.xml` and
`values-zh-rTW/strings.xml`; `StringsTest` and `HardcodedTextTest` keep them complete and out of code. Third-party text is shown
with plain `Text`, which makes no links.

Facts about the desktop at 445dc24 (`docs/remote-protocol.md`, `src/main/intake-projection.ts`, `remote-protocol.ts`,
`remote-host.ts`, `ipc/intake.ts`, `handoff-opener.ts`, `src/renderer/src/shell/intake/*`, `shell/remote/RemoteRequests.tsx`):

- `inbox.list` → `{ items: [Item] }`; `inbox.changed` carries the same shape, whole, to every connection. An Item is
  `{ id, adapter, state, originKind, originId, originLabel, title, actor, body, bodyLength, receivedAt, occurredAt, sessionId?,
  folderId?, unresolved?, scheduledAt? }`. Listed states are `pending` and `accepted`; dismissed items and settled accepted items
  (their prompt seen to start the agent, or cleared on the desktop) are not listed. Order: newest `occurredAt` first.
- An item whose content expired has `body`, `title`, `actor`, `originLabel` `""` and `bodyLength`, `receivedAt`, `occurredAt` 0,
  and — even when pending — neither `folderId` nor `unresolved`; accepting it fails.
- `folderId` (pending only): the routing's suggestion, or the handoff's target; `unresolved` (`NO_MATCH`, `FOLDER_GONE`) when there
  is none. A scheduled handoff whose target folder was removed has `unresolved: FOLDER_GONE` and no `folderId`.
- `scheduledAt` (epoch ms): present only while a handoff waits for its time (pending, not fired). A past value means due; it opens
  within about 30 s, then an `inbox.changed` without the field follows. A device's `inbox.accept` on it opens it now and marks it
  fired before opening, so even when that accept fails the item loses `scheduledAt` and becomes an ordinary pending item. A
  device's Run now raises no `intake` event; a due opening (or the desktop's own Run now) does.
- `inbox.accept { adapter, id, folderId }` → `{ sessionId }`: reuses the session an earlier accept of the item left in that folder,
  or creates one (quietly), and answers once the session is created and attached — usually before the session list shows it (the
  list is a debounced copy). The prompt is written later, at the agent's first `ready` (at once in a reused ready session):
  submitted for a first-party body in a new session, left unsent for a third-party body and in a reused session. Errors:
  `FOLDER_REQUIRED`; `NOT_FOUND` (no such item, **or the chosen folder is gone**, message "the folder is gone"); `CREATE_FAILED`
  (agent event reporting off, the desktop opening that handoff right now, or another failure; the message says which); `NO_WINDOW`;
  `TIMEOUT` (the desktop's 15 s). The messages are fixed English. A failure leaves the item pending.
- `inbox.ignore { adapter, id }` → `{}`; `NOT_FOUND` for an unknown item. It needs no window, and it removes an accepted item too
  (as dismissed — unlike the desktop's "Clear from inbox", which settles it).
- `inbox.list`, `inbox.accept` and `inbox.ignore` are in the hello's `operations` (generated from the server's allow-list).
- The desktop preselects a pending item's folder: the user's choice in this opening of the inbox (if that folder is gone: none, and
  it says so — it does not fall back), else the folder of the session an earlier accept left, else the routing's suggestion, else
  none. It shows the title apart only when the body does not contain it (a trailing `…` stripped first).
- Body limits: a third-party body is refused over 4 000 characters (`TOO_LONG`); a handoff's over 20 000.
- `intake` attention events carry `intake: { adapter, id }` and a title, no `sessionId`. A handoff raises one on arrival (and a
  scheduled one when it opens) but is accepted and submitted at once, and settled once its agent starts, so by the time the user
  taps the notification it is usually no longer listed. The desktop's own notification focuses the session when exactly one
  accepted item is behind it, else opens the inbox.

Facts about spekterm's change `remote-prefill-send` (session `spekterm-8b98`; its `design.md`, not yet committed or reviewed — this
design follows that draft and is checked again against it when it lands, task 2.4):

- `Session.prefill?: { id, text, intake: { adapter, id }, edited }` in `sessions.list` / `sessions.changed`, present exactly while
  the session is a running agent session holding a filled-in prompt the desktop shows as pending (a third-party prompt from when it
  is written; a first-party one only after the desktop's own key did not take within 10 s) and its item is still listed for it.
  `text` is exactly what was written; `edited` says the input box may hold more than `text`.
- `sessions.submitPrefill { sessionId, prefillId }` → `{ outcome: "confirmed" | "sent" }` writes only the submit key. Errors:
  `BAD_PARAMS`, `NO_WINDOW`, `NOT_FOUND`, `PREFILL_GONE` (no such fill now), `PREFILL_EDITED`, `WAIT_STATE` (`detail.state`).
  `confirmed`: the agent started within 3 s (the item is then settled by the desktop); `sent`: the key was written and nothing more
  is known — the prefill stays listed and may be sent again.
- `conversation.send` is refused with `PREFILL_PENDING` (`detail.prefillId`, `detail.edited`), nothing written, while the session
  holds any prefill record; a record that appears during the reply's 500 ms delay withholds the key (`filled-not-submitted`).
- New obligation **16**: submit only on the user's explicit action for that session, after showing — on that screen or the one just
  before — the full body of the item `prefill.intake` names and `prefill.text`, both as plain text; never as part of accepting,
  automatically or from a notification action; show `edited: true` as "finish at the desktop", not as a send control; on `sent`,
  say the desktop has not seen the agent start yet.

## Goals / Non-Goals

**Goals:**

- From an `intake` notification, the user reads the item, decides where it opens, accepts it, sends its first prompt and follows the
  agent — all on the phone.
- A scheduled handoff can be seen, run now or cancelled from the phone.
- The phone shows what the desktop's inbox shows, in the desktop's words, and never sends third-party text the user has not seen —
  neither through the send control nor appended to a reply.

**Non-Goals:**

- Rejected deliveries, routing rules, Slack settings: not in the protocol.
- Removing an opened item without sending its prompt. `inbox.ignore` would do it, but as a dismissal, not the desktop's "Clear
  from inbox" (a settle); the phone does not offer it, so the two never disagree about what happened.
- Editing the first prompt, or seeing the context file it points to.
- A count on the launcher icon, or notifying for a scheduled handoff before it opens.

## Decisions

### D1. `InboxItem`, decoded leniently

`InboxItem(id, adapter, state: State, originLabel, title, actor, body, bodyLength, receivedAt, occurredAt, sessionId?, folderId?,
unresolved: Unresolved?, scheduledAt?)` with `State { PENDING, ACCEPTED, OTHER }` and `Unresolved { NO_MATCH, FOLDER_GONE, OTHER }`.
Decoded element by element like `HandoffEntry.decodeList`: the answer not an object with an `items` array → `null` (the list is
not replaced); an element that is not an object with non-empty string `id` and `adapter` is skipped; a string field missing or of
another type reads `""`, a number `0`, an optional one `null`; an unknown `state` or `unresolved` value maps to `OTHER`
(obligation 7). `originKind` and `originId` are not kept: nothing shows them. An `OTHER`-state item is shown nowhere. The item's
key is `adapter` + `id`.

### D2. `InboxState`, held like the handoff list

`InboxState(items, current, seq, receivedAt)` with `applied(seq, items)`, `attached()`, `detached()` exactly as `HandoffsState`
(an equal or older stamp is dropped; a new connection keeps the items until its first list). `Desktop.attach` reads `inbox.list`
when it is in `operations`; `inbox.changed` is routed to `Desktop` (`EventRoute`) and applied with its stamp. `clear()` empties it.
In memory only: bodies are third-party text and are not written to storage.

*Alternative:* re-read the list when the Inbox screen opens. Rejected — the entry's count and the conversation screen's note
(D7) need it at all times, and the desktop pushes every change anyway.

### D3. `Session.prefill`, decoded leniently

`Session` gains `prefill: Prefill? = null`, `Prefill(id, text, intake: ItemKey, edited: Boolean)`, through a lenient serializer
(as `LenientLineage`): not an object, or no non-empty string `id`, or `text` not a string, or `intake` without string `adapter` and
`id` → `null`; `edited` not a boolean → `true` (the safe reading: no send control). It is read only for a running agent session.

### D4. Sections and order — `InboxView`

A pure object, unit-tested:

- `scheduled(items)`: `state == PENDING && scheduledAt != null`, by `scheduledAt` ascending (ties keep list order).
- `waiting(items)`: `state == PENDING && scheduledAt == null`, in list order. Its size is the entry's count.
- `opened(items, list)`: `state == ACCEPTED && sessionId != null`, kept only when the current session list has a window and holds
  that session in a listed folder (the desktop's rule); with no current list or no window, kept as last known.
- `expired(item)`: `receivedAt == 0 && bodyLength == 0`.
- `showTitle(title, body)`: the desktop's rule.
- `preselect(override, item, list)`: the desktop's rule — the override when it is a listed folder, none with `overrideGone` when it
  is not (no fall-back), else the existing session's folder when listed, else `folderId` when listed, else none. The override is
  the user's pick on this screen, held in the screen's saved state by item key, forgotten when the item leaves.
- `noFolderReason(item, selection)`: `overrideGone` → the chosen folder is gone; else `unresolved` `FOLDER_GONE` → the rule's
  folder is gone; `NO_MATCH` or `OTHER` → cannot tell which folder; an expired item → none (it shows its own line, D5).
- `runnable(item, list)`: a scheduled item runs now only with a `folderId` that is a listed folder; otherwise the card says why
  (`noFolderReason`) and Run now is disabled.
- `linked(sessionId, items, list, operations)`: the note of D7, for a running agent session only — `Prefill` when the session
  carries `prefill` (`text`, `edited`, and whether the item it names is shown under Already opened); otherwise `Waiting` when a held
  item is `ACCEPTED` with this `sessionId`; else none.
- `gates(operations, connected, paused, sessions)`: whether Accept / Run now (`inbox.accept` listed, connected, not paused, a current
  list with a window), Dismiss / Cancel (`inbox.ignore` listed, connected, not paused) and Send (`sessions.submitPrefill` listed, the
  same as Accept) are enabled; `runnable` and `edited` narrow them per item.

### D5. The Inbox screen

A route `InboxRoute`, pushed over the session list. Top bar: back, "Inbox", the overflow with Status. Under the connection notices
the session list uses (paused / not connected with "as of" / no window), a `LazyColumn`:

1. **Scheduled** heading, then a card per item: "Opens {time}" (date and time in the app's language), "from {originLabel}",
   "in {folder}" when runnable; the title when shown; the body; "{n} characters"; **Cancel** and **Run now**. A past `scheduledAt`
   reads "Due — opening".
2. **Waiting** heading (the desktop's 待處理), then a card per item: actor, origin label, occurred time (omitted when 0); the title
   when shown; the body; the length; "Opens in" with the folder picker (an exposed dropdown of the list's folders, "Choose a
   folder…" when none is selected) and the reason when none is; **Dismiss** and **Accept**. An expired item shows "Its contents are
   no longer available" and only Dismiss.
3. **Already opened**: a card per item: "Opened in {folder}", "from {origin}", the title, the body, the length. When its session
   carries a `prefill` naming this item: "First prompt, not sent yet:" and `prefill.text` as plain monospace text, then **Send the
   first prompt** (D8) or, when `edited`, "Something else was typed there too — finish it at the desktop". When it carries none and
   its session is running: "The desktop is filling in the first prompt" (with `sessions.submitPrefill` listed) or "Its first prompt
   waits on the desktop — send it there" (without); when its session is not running, no prompt line (a dormant session's input box
   is gone with its process). Tapping the card's header opens the session's conversation.

Bodies and `prefill.text` are plain `Text`, whole, selectable (`SelectionContainer`), never truncated (obligations 2 and 16). Empty
inbox: "Nothing waiting". An action's error is held by item key (not by section: a failed Run now moves the item to Waiting) and
shown on its card until the next action on it.

### D6. Gates, actions and outcomes

- Gates (obligation 8, and what the desktop needs), computed by `InboxView.gates`: Accept and Run now need `inbox.accept` listed,
  connected, not paused, and a current list with a window; Dismiss and Cancel need `inbox.ignore` listed, connected, not paused.
  Disabled controls stay visible.
- `Desktop.inboxAccept`, `inboxIgnore` and `submitPrefill` run on the connection's scope (accept 20 s, above the desktop's 15 s;
  the others the default 20 s). One action per item at a time; its card shows progress and disables its buttons until the answer.
  Each is logged as `ConnectionLog.Kind.INBOX` with fields built by a pure `InboxLog.fields(op, outcome, code)` — operation,
  outcome, error code; never a title, body, prompt or folder name.
- Dismiss and Cancel ask first ("Dismiss this item? It will not be opened." / "Cancel this scheduled handoff? It will never
  open."), then send; on success the item leaves with the next `inbox.changed`.
- **Accept and Run now open the conversation only once the session is listed** — an `InboxOpening` state in `Desktop`, stepped by a
  pure `OpeningTracker` like `CreateTracker`: after a success the card says "Opening…"; when a current list with a window holds the
  answered session (running or dormant — an accept may reuse a dormant session, and the conversation screen handles a dormant one),
  and the Inbox screen is at the front, the conversation opens over the Inbox; if the screen was left,
  the card says "Opened in {folder}" with a link instead; if 10 s pass without it, the card says "Opened on the desktop; it is not
  listed yet" with the link, and nothing opens by itself. A new action on the item ends the wait; a detach ends it with "The
  connection ended before the session was listed" and the link.
- Errors (`InboxOutcome.of(op, code, answered, itemStillHeld, message)`, by operation): `FOLDER_REQUIRED` → "Choose a folder
  first"; `NOT_FOUND` on an accept → "Could not open it" with the desktop's message when the item is still held, else "This item is
  no longer in the inbox"; `NOT_FOUND` on an ignore → "This item is no longer in the inbox"; `NOT_FOUND` on a send → "The session
  is not running";
  `NO_WINDOW` → the session list's no-window text; `TIMEOUT` → "The desktop did not answer in time"; no answer → the not-connected
  text; `CREATE_FAILED` → "Could not open it" with the desktop's message; any other → the generic failure with its code. The
  desktop's message is shown as sent (`app-language`: it is fixed English). Nothing is retried.

### D7. The conversation screen's note, and replies

`InboxView.linked` for the session on screen (D4) drives a card above the composer:

- `Prefill`, not edited, its item shown under Already opened: "The first prompt from the inbox is filled in on the desktop and not
  sent yet." with **Review and send**, which opens the Inbox scrolled to that item's card (D10), where the card shows the body and
  the prompt next to the send control (obligation 16: the screen just before the send).
- `Prefill`, edited, or its item not shown under Already opened (not held, or its folder not listed): "…not sent yet — finish it at
  the desktop." with **Open in inbox** when the item is shown there.
- `Waiting` with `sessions.submitPrefill` listed: "The desktop is filling in the first prompt from the inbox." — a hint only.
- `Waiting` without it (an older desktop): "The first prompt from the inbox is filled in on the desktop and not sent yet — send it
  there. A reply from here would be sent with it." with **Open in inbox** and **Reply anyway**.

Replies (`Replies.blocked` gains `INBOX_PROMPT`, ordered after `AwaitingChoice` so a choice's own reason still shows):

- With a `prefill` on the session: blocked. The desktop refuses it anyway, and the user's way forward is the send or the desktop.
- `Waiting` on a newer desktop: not blocked. The desktop guards it (`PREFILL_PENDING` before writing, and the key withheld if a fill
  lands during the reply's delay); the phone adds no stricter rule of its own, which would outlast the prompt — an accepted item is
  not settled when its session's process ends, only when a start is seen, the desktop clears it, or the desktop restarts.
- `Waiting` on an older desktop: blocked until the user taps **Reply anyway**, which lifts the block for this screen until the note's
  form changes. The cost: after a hibernation the prompt is gone but the item stays accepted, so the user taps once more.

`PREFILL_PENDING` joins the codes refused before writing (`SendOutcome.Refused`), and the reply returns to the composer with its own
reason: "The desktop holds an unsent first prompt from the inbox in this session — send it or clear it at the desktop." It also
comes when no note is shown: the desktop keeps its record after the item was cleared there.

### D8. Sending the first prompt

**Send the first prompt** sends `sessions.submitPrefill { sessionId, prefillId: prefill.id }` — shown only with
`sessions.submitPrefill` listed and `edited` false, enabled by `InboxView.gates`. No confirmation: the button
is on the card that shows the body and the prompt, and it names what it does. Outcomes: `confirmed` → "Sent — the agent started"
until the item leaves; `sent` → "Sent, but the desktop has not seen the agent start yet" and the button stays (the prefill stays
listed; sending again is the desktop's documented retry); `PREFILL_GONE` → "This prompt is no longer waiting"; `PREFILL_EDITED` →
the edited text; `WAIT_STATE` → "The agent cannot take it now ({state})"; the rest as D6. Never from a notification, never
automatically after Accept.

### D9. The entry and the notification

- `SessionsScreen`'s top bar gains an `InboxButton(count, shown, onClick)` before the overflow (a composable taking plain
  parameters, so an instrumented test drives it), with a badge of `waiting.size` when non-zero; content description "Inbox" /
  "Inbox, {n} waiting". Shown when `inbox.list` is in `operations` or items are held; it stays while not connected.
- An `intake` notification's tap carries the item key (`spekterm-intake:` data URI, as `Notifications.openSession` does for
  sessions) with `MainActivity.ACTION_OPEN_INTAKE`. On tap the item is looked up in the held inbox — if the held list is not
  current, after waiting up to 5 s for one — and a pure `IntakeTap.route(item, inboxCurrent, list)` decides: held and pending → the Inbox
  scrolled to it; held and accepted, its session held by the current session list → that session's conversation (over the session
  list); held and accepted, its session not listed yet → the session list (the conversation must not open before its session is
  listed, as in D6); not held while the inbox list is current → the session list (a handoff already opened and started; the session
  list shows it); not held while no current inbox list arrived within the 5 s (a cold start, paused, unreachable) → the Inbox,
  which says it is not current. The attention policy's keys and replacement
  are unchanged.

### D10. Focusing an item

`InboxRoute(focus: String?)` carries the item key when pushed. When the Inbox is already on the back stack (the conversation was
opened from it), the app pops back to it and hands the key over through that entry's `savedStateHandle` (`"focus"`), which the
screen reads and clears; a typed route's `launchSingleTop` would not deliver a new argument to the existing entry.

### D11. Texts

The desktop's words where it has them (`app-language`): Inbox / 收件匣, Accept / 接受, Dismiss / 忽略, Opens in / 開在, Choose a
folder… / 選擇資料夾…, Scheduled / 已排程, Opens {time} / {time} 開啟, Run now / 立即執行, Cancel / 取消, Already opened / 已開啟,
Opened in {name} / 已開在 {name}, from {origin} / 來自 {origin}, {n} characters / {n} 個字元, Nothing waiting / 沒有待處理的項目,
the waiting heading / 待處理, the `unresolved` and chosen-folder-gone reasons. New ones (confirmations, outcomes, the notes) are
written for both languages; counts use plurals.

## Risks / Trade-offs

- [spekterm's `remote-prefill-send` may change in its review] → D3, D7, D8 follow the draft. Before task 2.4 is started, this
  change's artifacts are checked against spekterm's committed `docs/remote-protocol.md` and amended where it differs.
- [Older desktop: a first-party handoff's prompt is being submitted] → for the seconds until the desktop settles it, the note and
  card say "send it there" though the desktop already sent it. Accepted.
- [A long body makes a long card] → whole by obligations 2 and 16; the list scrolls, and the actions sit under the body.
- [The session is not listed when the accept answers] → D6's wait; the conversation never opens on a session the list lacks.
- [A handoff's notification usually finds no item] → it opens the session list, as today.
- [Blocking replies on an older desktop while an accepted item names the session] → lifted by Reply anyway (D7); on a newer desktop
  only a listed `prefill` blocks.
- [Third-party text on screen] → plain `Text` only, never `AgentMarkdown`; nothing is logged.
- [`CREATE_FAILED` and `NOT_FOUND` carry several causes with English messages] → the message is shown as detail, never parsed.

## Migration Plan

None: new screen and state. A desktop without `inbox.list` hides the entry; one before 445dc24 sends no `scheduledAt`; one without
`remote-prefill-send` sends no `prefill`, and the note's `Waiting` form tells the user to send on the desktop.

## Open Questions

None blocking. Whether `SelectionContainer` offers to open a URL in selected text is checked on the emulator (task 3.2).
