## Why

An agent on the desktop can ask to close its own session, but spekterm never closes it on its own: once the agent's
turn ends, the desktop shows "The agent asked to close this session" with Close and Keep, and waits for the user.
The phone could not see that offer — its list just showed the session waiting — so a user away from the desk could
neither let the session go nor keep it. spekterm now exposes the offer to paired devices (change
`remote-close-offer` in spekterm, protocol still version 1, additive): a `closeOffered: true` mark on the session in
`sessions.list` / `sessions.changed`, a new `sessions.keep` operation, and client obligation 13 — wherever a client
shows a session marked `closeOffered`, it offers Close (`sessions.close`) and Keep (`sessions.keep`).

## What Changes

- **The offer is read from the session list.** A running agent session marked `closeOffered: true` is shown as
  offered; the mark's absence, any other value, a dormant session or a shell are not offered. Nothing about the offer
  is kept on the phone: it is shown exactly while the desktop's list marks it, so an offer withdrawn on the desktop
  (Keep there, typing into the session, its process ending) disappears from the phone with the next list.
- **The session list shows it.** An offered session's row says "The agent asked to close this session." with
  "Close" and "Keep", in place of "Waiting for you" (a session that also asks for a choice or is working keeps
  saying so). A collapsed folder's heading counts offered sessions ("1 asks to close") apart from those waiting for
  the user.
- **The conversation screen shows it.** Above the composer, a card with the same sentence and the same two buttons,
  also while the connection is paused or down (from the last list, buttons not usable).
- **Close answers the offer.** It sends `sessions.close` without `force` and **without asking again**: choosing
  Close on the offer is the confirmation obligation 12 asks for. A `SESSION_BUSY` answer goes through the existing
  busy dialog (the reason, "Close anyway" forces only if the user agrees). Closing from the session's own screen
  returns to the list, as the menu's close does. The session menu's "Close session" keeps its confirmation.
- **Keep answers the offer.** It sends `sessions.keep`; the session keeps running and the offer goes from the phone
  when the desktop's list drops the mark (the desktop's card goes too). Errors are explained in the same dialog as
  the other session actions. Keep is shown only when the desktop lists `sessions.keep` in its operations, Close only
  when it lists `sessions.close`; a desktop without the operation does not send the mark either.
- **Both buttons are usable** only while the phone is connected, the connection is not paused, the shown list is
  current and the desktop has a window — the session menu's conditions.
- **The connection log records Keep** (`KEEP`, its error code), like the other session actions; never labels.

## Capabilities

### New Capabilities

- `close-offer`: showing an agent's offer to close its own session on the phone, and answering it with Close or
  Keep.

### Modified Capabilities

- `session-list`: an offered session's row shows the offer in place of waiting for input; a collapsed group's heading also says how many of its sessions ask to close, and does not count
  them as waiting for the user.
- `session-hibernate-close`: closing asks the user first — except from an agent's close offer, where choosing Close
  is the confirmation.

## Impact

- `net`: `Session.closeOffered` (tolerant decoding), `Operations.KEEP`, `Frames.sessionsKeep`.
- `desktop`: `Desktop.keep`, `SessionAction` (`Kind.KEEP`, starting a close from the offer without confirming),
  `SessionsView` (offered rows and the collapsed summary), log kind `KEEP`.
- `ui`: the offer in `SessionsScreen`'s row and `ConversationScreen`, the dialog texts in `SessionMenu.kt`.
- Tests: `FramesTest`, `SessionActionsTest`, `SessionsViewTest`, `DesktopTest`; a phone run recorded in
  `docs/conversation-findings.md` against a dev spekterm desktop with `remote-close-offer`.
- No new dependency; nothing changes for a desktop without the feature.
