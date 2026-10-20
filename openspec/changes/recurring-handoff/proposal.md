## Why

spekterm 0.2.16 (d88a3d1) lets an agent propose a **repeating handoff**: work that comes back on a cron schedule once
the user approves it on the desktop. The phone's inbox does not know the new `recurrence` field, so today it shows a
proposal as an ordinary item waiting for the user, with an Accept the desktop refuses (`APPROVE_ON_DESKTOP`) and no
word of its schedule; and it shows one time of an approved recurrence as a one-off scheduled handoff whose Cancel
reads as if the whole thing stopped, when it skips that one time only. Obligation 17 of the protocol
(`docs/remote-protocol.md` §9) asks for the schedule, the next times, and the right meaning of each button.

## What Changes

- Read `recurrence: { role, repeat, until?, next? }` on `inbox.list` / `inbox.changed` items; a malformed one is
  ignored as an unknown field, and the role — never the presence of `scheduledAt` — says what an item is.
- A new **Waiting for approval** section at the top of the Inbox for proposals: the schedule as written, the next
  times it would open, its end or "until stopped", where it would open, its origin and full body. No Accept: it says
  it is approved on the desktop, and offers **Decline** (`inbox.ignore`, confirmed). Proposals stay counted in the
  Inbox entry's number (pending without `scheduledAt`, as today and as on the desktop's badge).
- A waiting time of an approved recurrence stays under **Scheduled** with its time, and also shows its schedule and
  end; its Cancel becomes **Skip this time**, whose confirmation says only this time is skipped and skipping does
  not stop the repeating handoff. Run now is unchanged.
- A time whose opening failed stays under **Waiting** as today, marked as part of a repeating handoff with its
  schedule; its Dismiss also becomes **Skip this time**.
- An accept answered `APPROVE_ON_DESKTOP` says to approve it on the desktop.
- `tools/drop-handoff.mjs` gains `--repeat` and `--until`, to produce proposals on the dev desktop.
- Not here: approving or stopping a recurrence, and the desktop's list of approved recurrences — the protocol has no
  operation for them (desktop-only in this version).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `inbox`: the Inbox shows repeating handoffs — proposals apart, with their schedule and next times and only Decline;
  a recurrence's times with their schedule and Skip this time. (`session-list`'s count rule — pending without
  `scheduledAt` — already counts proposals and does not change.)

## Impact

- Code: `net/Inbox.kt` (decode), `desktop/Inbox.kt` (`InboxView` sections and count, `InboxFailure`),
  `ui/InboxScreen.kt` (the new section and cards, button labels, confirmations), `ui/SessionsScreen.kt` (the count
  moves to `InboxView.count`, same rule), `net/Frames.kt` (`RemoteError.APPROVE_ON_DESKTOP`), strings in `values` and
  `values-zh-rTW`.
- Tests: `InboxItemTest`, `InboxTest`, `InboxUiTest`.
- Tooling and docs: `tools/drop-handoff.mjs`, `tools/README.md`, `README.md` ("Inbox", the versions line, the
  desktop version to test against).
- Desktop: needs spekterm d88a3d1 (0.2.16) or later for the field; an older desktop never sends it and nothing changes.
