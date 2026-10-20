## Context

The desktop's projection (spekterm `src/main/intake-projection.ts` `recurrenceOf`) adds to an inbox item
`recurrence: { role, repeat, until?, next? }`: `role` is `"proposal"` (no approved recurrence yet) or `"occurrence"`;
`repeat` is the five-field cron expression as the agent wrote it, read in the desktop's time zone; `until` the end
in epoch ms when there is one; `next`, on a proposal only, up to three times (epoch ms) from `max(now, start)` up to
`until` — computed when the list was built, so they can be in the past by the time they are read; empty when the
expression no longer parses, and when no time is left before `until` (a proposal left past its end, which the
desktop can no longer approve: `recurrence-keeper.ts` answers `ended`). An expired item carries no `recurrence` (the
projection returns before computing it), so it stays an ordinary expired item. The desktop counts proposals in its
badge (`isWaitingForUser` in `src/shared/intake/scheduled.ts`: pending without `scheduledAt`), lists them in its Schedules tab under "Waiting for
approval", announces them on arrival (so the phone already gets an attention event for them, keyed by the item), and
refuses `inbox.accept` on one with `APPROVE_ON_DESKTOP`. On an occurrence, accept is run now (waiting) or the ordinary
accept (failed); ignore skips that one time (`dismissIntake` → the keeper schedules the next). **While a failed
occurrence waits for the user, its recurrence makes no next time** (`recurrence-keeper.ts`: nothing is created while
an occurrence waits); after accept or ignore the next one is computed from now. There is also no next one after the
last time before `until`, after the recurrence was stopped on the desktop, or while the inbox is full. The cron subset
allows at most one time an hour (the minute field is a single number).

The phone today: `InboxItem` (`net/Inbox.kt`) decodes the item without `recurrence`; `InboxView.scheduled` /
`waiting` (`desktop/Inbox.kt`) split pending items on `scheduledAt`; `InboxScreen` shows Scheduled, Waiting, Already
opened, with `ScheduledCard` (Run now, Cancel) and `WaitingCard` (folder picker, Accept, Dismiss) and one confirmation
dialog keyed on `scheduledAt`; `SessionsScreen` counts `InboxView.waiting`, which the `session-list` spec defines as pending without `scheduledAt`.

## Goals / Non-Goals

**Goals:**

- A proposal is recognizable as one at a glance, says when it would run and until when, and cannot look approvable.
- Every button on a recurrence says what it does to the recurrence: Decline ends the proposal; Skip this time
  leaves the recurrence going.
- Nothing changes for one-off scheduled handoffs and ordinary items, nor against a desktop older than 0.2.16.

**Non-Goals:**

- Approving or stopping a recurrence from the phone, and listing approved recurrences: no protocol operation.
- Reading or explaining the cron expression (the next times answer "when"; the desktop also shows it as written).
- Recomputing `next` on the phone (it would need the desktop's time zone and cron rules).

## Decisions

### D1. Decoding

`InboxItem` gains `recurrence: Recurrence?`, `Recurrence(role: Role, repeat: String, until: Long?, next: List<Long>)`
with `Role { PROPOSAL, OCCURRENCE, OTHER }`. `recurrence` is read when it is an object whose `role` is a string;
otherwise it is `null` (an unknown or malformed field is ignored, as the spec already requires). A `role` other than
`"proposal"` and `"occurrence"` reads `OTHER`. `repeat` is read when it is a string, else empty — a proposal stays a
proposal without it (the safe direction: it is never shown with Accept), its card just has no expression line. `until`
is read when it is a number; `next` keeps the array's numbers and drops other entries. No item is dropped for its
`recurrence`.

### D2. Which section, and what is counted

Classification reads the role first (obligation: "never by the presence of `scheduledAt`"):

- `InboxView.proposals`: pending with role `PROPOSAL`, in the desktop's order — whether or not `scheduledAt` came
  with it.
- `InboxView.scheduled`: pending with `scheduledAt`, not a proposal (unchanged otherwise, earliest first).
- `InboxView.waiting`: pending without `scheduledAt`, not a proposal.
- An `OTHER` role or no `recurrence` classifies as today.

The Inbox shows **Waiting for approval** first, then Scheduled, Waiting, Already opened: a proposal needs the user, but
not here, so it leads the list without mixing with what can be acted on; this is also the desktop's Schedules tab's
order (approval, then scheduled). The entry's count keeps `session-list`'s rule — pending without `scheduledAt` —
which is the desktop's badge rule and already includes proposals; it moves to its own `InboxView.count`, since
`waiting` no longer is that set. The section order and the keys in it move out of the composable into a pure
`InboxView.rows(items, sessions)` (headings and item keys, in screen order), which both the `LazyColumn`'s focus
lookup and the set of shown keys use, so a proposal that arrives after the focus is set still triggers the scroll,
and the empty state includes the new section.

### D3. The proposal card

Top to bottom: the label "Repeating handoff"; a line `Repeats <repeat>` in monospace with "(desktop time)"; the next
times as one line of date-and-time strings (the screen's existing `whenText`, in the app's language and the phone's
zone, which is what epoch ms mean), keeping only times later than now (`InboxView.upcoming`); "Until <date time>" or
"Until stopped"; origin and the folder it would open in when the desktop names one of its folders (`folderId`, as on
a scheduled card); then the body (`Body`, whole, plain, selectable — obligations 2 and 17: the body before any
control); then one line, by `InboxView.proposalState(recurrence, now)`:

- `ENDED` — `until` is not later than now, or the desktop sent an empty `next` with an `until` (no time is left
  before the end, so the desktop answers approval with `ended`): the desktop's `proposalEnded` (D4a; no times line);
- `NO_TIMES` — no time left later than now otherwise (no `until`, or the sent times went stale): "The next times are
  shown on the desktop. Approve it there.";
- `APPROVABLE` — "Approve it on the desktop."

The only control is **Decline**, gated like Dismiss, confirmed by a dialog "Decline this repeating handoff? It will
not run.", with the card's `Outcome` line for a failed Decline as on any card.

### D4. Occurrences

`ScheduledCard`, for an item with role `OCCURRENCE`: under the time line, `Repeats <repeat>` (and "until <time>" when
present); the Cancel button reads **Skip this time**, its dialog "Skip this time? Only this time is skipped;
skipping does not stop the repeating handoff." — true whether or not a next time follows (Context: none after the
last time before `until`, a stopped recurrence or a full inbox), so the phone promises no next time. Run now
unchanged. `WaitingCard` for an occurrence (its opening failed): the meta line is followed by "Part of a repeating
handoff · Repeats <repeat>" — nothing about whether it repeats next, which the phone cannot tell (the recurrence may
be paused on this time, stopped, ended, or already have its next time scheduled); Dismiss reads **Skip this time**
with the same dialog; Accept unchanged (the ordinary accept). On either card, an empty `repeat` (D1) leaves out the
"Repeats" part. The dialog's choice moves from `scheduledAt != null` to a small
`InboxView.ignoreKind(item)` → `DECLINE | SKIP | CANCEL | DISMISS`, which also picks the button label, so a card and its
dialog cannot disagree.

### D4a. Words

The desktop's own strings (spekterm `src/shared/i18n/en.json` / `zh-TW.json`, the `intake` block), as `app-language`
asks: `proposalsLabel` Waiting for approval / 待核准; `recurrenceItemLabel` Repeating handoff / 週期交接; `repeats`
Repeats {repeat} / 重複 {repeat}; `repeatNext` Next: {times} / 接下來：{times}; `repeatUntil` until {time} / 直到 {time};
`repeatUntilStopped` until stopped / 直到停止; `decline` Decline / 拒絕; `approve` Approve / 核准; `skipOccurrence`
Skip this time / 跳過這次; `proposalEnded` "Its end time has passed, so it can no longer be approved." / 結束時間已過，
無法再核准。 The phone-only lines (approve on the desktop, the confirmations, "desktop time") use these words.

### D5. `APPROVE_ON_DESKTOP`

`RemoteError.APPROVE_ON_DESKTOP`; `InboxFailure.of` maps it (for any op) to a new reason `APPROVE_ON_DESKTOP`, shown as
"Approve this on the desktop." The phone never offers accept on a proposal, so it is reached only when an item the
phone does not see as a proposal (no or malformed `recurrence`) is one on the desktop; the reason stays with the item
as any other (`inbox` spec).

### D6. Producing proposals on the dev desktop

`tools/drop-handoff.mjs` gains `--repeat <cron>` and `--until <ISO 8601 with an offset>`, written as `repeat` / `until`
in the handoff JSON (spekterm `handoff-delivery.ts` reads them beside `at`; with `repeat`, `at` is the recurrence's
start). The tool's header and `tools/README.md` say so. Approval is then done in the dev desktop's Schedules tab.

## Risks / Trade-offs

- [Stale `next`] The times are as of the last inbox change; a proposal left for a week shows times now past. →
  Past times are dropped on the phone (D3); the desktop's own copy is current.
- [Desktop time zone ≠ phone's] `repeat` is read in the desktop's zone, `next` shown in the phone's. → The
  expression is labelled "desktop time"; the next times are absolute instants and right in either zone.
- [Count includes something the phone cannot finish] A proposal raises the badge but can only be approved at the
  desktop. → Matches the desktop's badge; its card says where to approve; Decline clears it from the phone.
