## Context

Built on `prompts-attachments-rename` (archived). `Desktop` holds the session list (`Desktop.sessions`, replaced
whole by every `sessions.list` answer and `sessions.changed` event) and the operations the desktop accepts
(`Desktop.operations`, from `hello`). `Session` is decoded with kotlinx serialization (`ignoreUnknownKeys`), element
by element, so one malformed session is skipped, never the list — but a known field of the wrong type drops that
session. The session menu (`SessionActions.of`, `SessionMenu`) and its dialogs (`SessionDialogs`, the pure
`SessionAction` state machine: `Confirm` → `Working` → `Busy` / `Failed` / `Done`) are shared by the session list and
the conversation screen; `SessionDialogs` is held by the screen, not the row. Closing from the conversation screen
returns to the list on `Done`. The app's interface is English, with text buttons and no icon library. There is no
terminal view: a shell's screen says so, and an offer is only ever made for an agent session.

Facts about the desktop (spekterm working tree, change `remote-close-offer`, not yet committed —
`docs/remote-protocol.md` §5 `Session` / `sessions.close` / `sessions.keep`, §7, §9 obligations 12–13;
`scripts/probe-remote.mjs` `runCloseOffer`; the change's `design.md`):

- `closeOffered: true` appears only on a running agent session whose agent asked to close itself and the user has
  not answered; it is absent otherwise, never `false`. It goes when the user keeps the session (desktop or
  `sessions.keep`), types into it (a phone reply or `terminal.input` counts), or the session's process ends; a woken
  session never brings an old offer back. Every change is announced at once in `sessions.changed` (debounced 150 ms
  on the desktop), with a 5 s recompute as backstop.
- `sessions.keep { sessionId } → { sessionId }`, listed in `operations`. The desktop withdraws its record first, then
  asks its window to drop the card; the mark leaves the next list. A session with no offer answers with no effect,
  so a retry is safe. Errors: `NOT_FOUND`, `NO_WINDOW`, `TIMEOUT`, `CREATE_FAILED`, `BAD_PARAMS`. Never
  `SESSION_BUSY`.
- Close is the existing `sessions.close`, idle check included: an offered session has just ended its turn and is
  normally `ready`, but can still answer `SESSION_BUSY`.
- No attention event of its own: the turn's end raises the usual `waiting` (none after an API error).

## Goals / Non-Goals

**Goals:**

- The offer is visible wherever the phone shows the session (its row, its conversation screen, the count in a
  collapsed heading), and is answerable from the row and the screen.
- The phone holds no state about offers: what it shows follows the latest list.

**Non-Goals:**

- A notification of its own for an offer. A notification shows an attention event, not the session list, and the
  offer is decided on the desktop after the `waiting` that ends the turn has been raised (desktop D7); the
  `waiting` notification still arrives and opens the conversation, which shows the offer.
- Hiding the offer on the phone before the desktop's list drops it.
- Any change to the session menu's close, hibernate, wake or rename.

## Decisions

### D1. `Session.closeOffered`, decoded leniently

`Session` gains `closeOffered: Boolean = false`, decoded with a small serializer that reads any JSON element and
yields `true` only for the boolean `true` — a `JsonPrimitive` that is not a string and whose `booleanOrNull` is
`true` (absent, `false`, `null`, a number, an array, an object and a string, `"true"` included, → `false`; a bare
`booleanOrNull` would read the string `"true"` as `true`, and the plain decoder accepts it too). Obligation 7
says an unknown value of a known field is not an error; with the plain `Boolean` decoder, a desktop sending
`"closeOffered": "yes"` would drop the whole session from the list. Encoding (tests only) writes the boolean.

Whether to show the offer is one pure function, `SessionActions.offered(session) = session.closeOffered &&
session.running && session.isAgent`: the desktop promises the mark only there, and the phone does not show an
offer it could not answer meaningfully (a dormant session closes without an idle check; a shell has nothing to
keep).

Alternative: decode `Session` by hand. Rejected — one lenient field does not justify rewriting the decoder of the
other eight.

### D2. Which buttons, and when they work

`SessionActions.offerActions(session, operations)`: empty unless `offered(session)`; otherwise `CLOSE` when
`sessions.close` is in `operations` and `KEEP` when `sessions.keep` is (obligation 8). The sentence is shown
whenever the session is offered, even with no button (a desktop that marks offers lists `sessions.keep`, so this
is only defensive). The buttons are enabled by the existing `SessionActions.enabled(connected, paused, sessions)`
— connected, not paused, the shown list current, the desktop has a window — the same conditions as the menu.
A list that is not current still shows the sentence (it is the last list received, and the screen already says the
list may be out of date) with the buttons disabled.

### D3. Answering goes through the existing session-action dialog

`SessionAction.Kind` gains `KEEP`. Two new entry points in the pure state machine:

- `SessionAction.fromOffer(CLOSE, …)` → `Working(CLOSE, force = false)` directly, **skipping `Confirm`**: choosing
  Close on the offer is obligation 12's confirmation (obligation 13). From `Working`, the existing transitions
  apply unchanged: `SESSION_BUSY` → `Busy` (reason shown, "Close anyway" → `agreed` → forced), other errors →
  `Failed` with the close texts, success → `Done` (the conversation screen goes back to the list).
- `SessionAction.fromOffer(KEEP, …)` → `Working(KEEP, force = false)`. `answered` never turns a `KEEP` answer into
  `Busy` (the desktop does not answer it so; were it to, it is a `Failed` with its code). `Done(KEEP)` closes the
  dialog and nothing else: the offer goes when the list drops the mark.

`SessionDialogs.answerOffer(action, session)` starts these and runs them as `run` does today, calling
`Desktop.close(id, force)` or the new `Desktop.keep(id)`. The `Working` dialog reads "Keeping “name”…" for Keep;
`Failed` gets Keep's texts (`keepText`): no longer exists; no open window; did not answer in time, "its card may
still show there" (see Risks); the desktop could not do it (its message); not connected, "the session list shows whether it happened";
otherwise "Could not keep it (code)".

Alternatives: a separate small state for Keep inside the row or card (rejected: the row can be dropped by the list
while the request is out — the reason `SessionDialogs` lives in the screen); keeping the confirmation for an
offer's Close (rejected: obligation 13 says not to ask twice, and the user already chose Close on a card that says
what it does).

### D4. `Desktop.keep`

`Desktop.keep(sessionId): RemoteError?` is shaped like `rename`: not attached → `NOT_CONNECTED`; otherwise the
request in the attached connection's scope; logs `KEEP` with `error`. It changes nothing held by the phone: the list
that drops the mark does. `Frames.sessionsKeep(sessionId)` = `{ "sessionId" }`; `Operations.KEEP =
"sessions.keep"`.

### D5. Where the offer is shown

- **Session list row.** For an offered session, under the label and kind lines, the row shows "The agent asked to
  close this session." (primary colour, `bodyMedium`) and, end-aligned, "Close" and "Keep" as text buttons, in that
  order — the desktop's card (`CloseOffer.tsx`) puts Close first, as the primary answer, and Keep second, fainter.
  The sentence **replaces only "Waiting for you"**: an offered session is not guaranteed to be `ready` (the desktop's
  `remote-close-offer` D6 and Risks: an open background subagent's prompt keeps `awaiting-choice`; a turn started by
  Claude Code's local messaging is no keystroke, so the offer stays while the agent is `busy`), and "Asks for a
  choice" or "Working" stays at the row's end with the offer under it. A tap elsewhere on the row still opens the
  session and a long press still opens the menu; the buttons consume their own taps.
- **Collapsed heading.** `Summary` gains `offers` (default `0`, so existing constructions stay valid). Each row is
  counted once, by the most urgent question: a `CHOICE` row counts as a choice, offered or not; any other offered
  row counts as an offer; a `READY` row not offered counts as ready. The heading reads "1 asks to close" / "N ask to
  close" between the choice and ready counts, in the primary colour.
- **Conversation screen.** A card above the composer (below the entries and the prompt card, the same place and
  shape as the prompt card): the sentence and the two buttons. It is shown in the same branch as the prompt card
  and the composer — `Screen.Content`, `Paused`, `NotConnected` and `NoWindow`, where the screen keeps showing the
  conversation under an "out of date" notice — read from the last list held, with the buttons enabled by the screen's
  `actionsEnabled`. In practice `NoWindow` shows none: a list without a window holds no sessions. In the short gap
  where the conversation says the window is gone but the list still has it, the buttons stay usable, as the menu's
  do; the desktop then answers `NO_WINDOW`; it is not shown on `Closed`, `Shell` and `Dormant`, which replace the conversation. The top
  bar's subtitle keeps showing the conversation's wait state ("Waiting for you"): it is the conversation's own
  state, and the card right above the composer says what the user is asked. The composer stays usable: a reply from
  the phone withdraws the offer on the desktop, which is the desktop's rule, not the phone's.

Wording follows the desktop's `sessions.closeOffer` strings in English: "The agent asked to close this session.",
"Close", "Keep".

## Risks / Trade-offs

- [The mark lingers for up to ~150 ms (one debounce) after Keep's answer] → the dialog's `Working` covers the
  request itself; a second Keep in that window is harmless (no effect on the desktop).
- [An offer withdrawn by typing on the desktop while the phone is disconnected stays on the phone's last list] →
  the buttons are disabled while the list is not current, and the next list replaces it.
- [A desktop at the feature's first cut might change wording or detail before it is committed] → the phone depends
  only on the field, the operation and the errors, which the handoff calls final; texts are the phone's own.
- [Keep times out after the desktop withdrew its record] → the desktop removes the offer from its list before asking
  its window to drop the card, so on `TIMEOUT` the phone's next list has no mark and no Keep to retry, while the
  desktop may still show its card (desktop Risks). The text says so ("its card may still show there"); the user
  answers it there.
- [An offer withdrawn on the desktop (typing) just before the phone's Close, inside the ~150 ms debounce and the
  network delay] → the Close goes out unconfirmed for a session no longer offered. The desktop's idle check catches
  the usual case (typing makes the agent `busy` → `SESSION_BUSY`, the busy dialog asks); otherwise the user had
  chosen Close on a card that said what it does.
- [Close from an offer removes the attachments the user had uploaded but not sent, without the menu's confirmation
  saying so] → deliberate: the offer is answered in one step, as on the desktop's card, whose Close removes them too.
- [The offer after a turn that ended in an API error raises no `waiting` notification] → seen in the list only;
  a desktop gap, recorded, not worked around.

## Migration Plan

None: additive. A desktop without the feature sends no mark and lists no `sessions.keep`; nothing new is shown.
