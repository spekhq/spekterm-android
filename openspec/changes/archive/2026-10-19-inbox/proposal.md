## Why

The phone tells the user that an inbox item arrived (an `intake` notification), but it cannot show the item or act on it: tapping
the notification opens the session list, and the user has to walk to the desktop to read a Slack mention, choose where it opens
and accept it. spekterm now also holds **scheduled handoffs** in its inbox (spekterm `scheduled-handoff`, 445dc24, 0.2.12): an
agent handed work off to a later time, and the protocol asks a client to show these apart, with their time, offering Run now and
Cancel (`docs/remote-protocol.md` §5 `inbox.list` `scheduledAt`, §9 obligation 14). The README lists the inbox under "Not yet".

One gap stands between reading an item on the phone and getting it worked on: when a third-party item (a Slack mention) is
accepted, the desktop fills in the agent's first prompt but deliberately does not send it — the user presses Enter on the desktop.
The protocol has no way for a device to see that prompt or send it, and a reply typed from the phone would be appended to it. A
spekterm change adds this (`remote-prefill-send`, handed off to session `spekterm-8b98` on 2026-10-19: a `prefill` field on the
session, a `sessions.submitPrefill` operation, `conversation.send` refused with `PREFILL_PENDING`, client obligation 16); kewang
chose to build the phone's inbox together with it rather than ship it half-way.

## What Changes

- **The inbox is held from the desktop.** At every authentication the app reads `inbox.list` (when the desktop lists it) and
  keeps the latest whole list from `inbox.changed`, stamped like the handoff list; it stays on screen, marked as of when, while
  the connection is down or paused, and is forgotten on unpairing. Items are decoded leniently. Sessions' `prefill` is decoded
  leniently too.
- **A new Inbox screen**, reached from the session list's top bar, whose entry shows how many items wait for the user (pending and
  not scheduled — the desktop's own count). Three sections:
  - **Scheduled**: each handoff waiting for its time, earliest first, with its time, origin, folder and full body as plain text —
    **Run now** (`inbox.accept`) and **Cancel** (`inbox.ignore`, asked first). Not counted, never notified while waiting.
  - **Waiting**: each pending item with its author, origin, time, full body as plain text and the folder it will open in
    (preselected as the desktop does) — a folder picker, **Accept** and **Dismiss** (asked first).
  - **Already opened**: accepted items, with the body and — when the desktop lists it — the filled-in first prompt as plain text
    and **Send the first prompt** (`sessions.submitPrefill`, obligation 16); "finish at the desktop" when something else was typed
    there; "send it on the desktop" with a desktop that cannot.
  - After Accept or Run now, the session's conversation opens once the desktop lists the session (never before: the conversation
    screen would read it as closed). Each failure is shown on its item in the user's words; nothing is retried by itself.
- **An `intake` notification opens what it is about**: the Inbox at a pending item, the conversation of an accepted one once its
  session is listed, or the session list for a handoff already opened and started.
- **The conversation screen of a session whose first prompt from the inbox waits** says so and links to its card. While the
  desktop lists the prompt, free text is not sent (the desktop would refuse it with `PREFILL_PENDING`); with an older desktop, a
  reply is held back until the user chooses to reply anyway, since it would be submitted together with the prompt.
- **No body, title or prompt is logged**; actions log their operation, outcome and code only.
- **New texts in both languages** (`app-language`), in the desktop's words where it has them.

## Capabilities

### New Capabilities

- `inbox`: holding the desktop's inbox and showing it on the phone — scheduled handoffs, items waiting for the user, opened items
  whose first prompt waits — and acting on them: run now, cancel, accept into a chosen folder, dismiss, send the first prompt.

### Modified Capabilities

- `attention-notifications`: tapping an `intake` notification opens the Inbox, the accepted item's conversation, or the session
  list, by what the app holds of the item (it always opened the session list).
- `session-list`: the top bar has an Inbox entry with the count of items waiting for the user.
- `conversation-reply`: free text is not sent while a first prompt from the inbox waits in the session; the screen says so and
  links to the item.

## Impact

- `net`: `InboxItem` with a lenient decoder; `Session.prefill` (a lenient serializer); `Frames.inboxAccept`, `inboxIgnore`,
  `sessionsSubmitPrefill`; `Operations.INBOX_*`, `SUBMIT_PREFILL`; `PREFILL_*` codes.
- `desktop`: `InboxState` and `Desktop.inbox`; `inboxAccept` / `inboxIgnore` / `submitPrefill`; the opening wait (`OpeningTracker`);
  pure `InboxView`, `InboxOutcome`, `InboxLog`, `IntakeTap`; `Replies.blocked` and `SendOutcome` for the inbox prompt.
- `service`: `EventRoute` routes `inbox.changed`; the `intake` notification carries its item (`ACTION_OPEN_INTAKE`).
- `ui`: `InboxScreen`, `InboxButton` in `SessionsScreen`, the note in `ConversationScreen`, routes in `MainActivity`, strings in
  `values` and `values-zh-rTW`.
- `tools`: `drop-intake.mjs` takes a title and a body file; `drop-handoff.mjs` takes `--at`.
- Tests: `FramesTest`, new `InboxStateTest`, `InboxViewTest`, `OpeningTrackerTest`, `IntakeTapTest`, `RepliesTest`,
  `EventRouteTest`; instrumented Compose tests on the emulator; screenshots; a phone run recorded in `docs/conversation-findings.md`.
- **Depends on spekterm's `remote-prefill-send`** (session `spekterm-8b98`, in review there) for the send and the `prefill` field —
  followed as drafted and checked against its committed protocol document before the code that uses it; on spekterm 445dc24
  (0.2.12) for scheduled handoffs. Works with an older desktop's inbox without either. No new dependency.
