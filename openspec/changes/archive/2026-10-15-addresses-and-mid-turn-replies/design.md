## Context

spekterm 0.2.5 (commit 4c37609) is the desktop side of this change; the maintainer runs it. Authority is its
`docs/remote-protocol.md`, version 1, sections 1, 4, 6 and 9:

- `hello` → `r`: `{ protocol, server, addresses: [string] }`; `pair` → `r` also gains `addresses`. Only after
  authentication. The list is built like the pairing link's `a=`: IPv4, then IPv6 other than link-local, then
  the user's additional address.
- `addresses.changed` → `d`: `{ addresses: [string] }`, sent to authenticated connections when the list changes
  (checked every 5 s on the desktop).
- Obligation 7: ignore unknown fields and event kinds. Obligation 8: keep the link's list and the latest list,
  try the latest first, never replace the stored list outright.
- A reply typed while the agent works (`queued_command`) is now a `user` entry, `meta: false`, where the agent
  absorbed it. Task notices and compaction summaries are `meta: true`. A `UserPromptSubmit` hook makes text-only
  answers `busy`. Still unreported: a denied permission prompt and an interrupted turn (the state stays as last
  reported until the next prompt). The second report of a pending choice keeps its tool.

Today the phone stores `Pairing.addresses` (the link's) and `lastAddress`, and tries `lastAddress` then the link's
order (`Pairing.addressOrder()`, `store/Stores.kt`). `hello`'s answer is checked for `ok` only. Non-`attention`
events go to `Desktop.onEvent`, whose `when` has no `else`. `Frames.json` ignores unknown keys.

## Goals / Non-Goals

**Goals:** follow the desktop's address list (obligation 8); confirm and pin obligation 7; make the busy-reply
fallback label fit 0.2.5; re-run the findings' scenarios against 0.2.5 and record them.

**Non-Goals:** a new protocol version; desktop changes; answering permission prompts from the phone; telling the
user which desktop version is running; using `server.version` to change behavior.

## Decisions

### D1. Storage: one more optional field on the pairing

`Pairing` gains `latestAddresses: List<String> = emptyList()`, stored in the same encrypted file as `lastAddress`.
An existing pairing decodes with the default (empty = "the desktop never sent one"), and an older app build reads
the new file because `Frames.json` ignores unknown keys. `addresses` keeps the link's list and is never written
after pairing.

Updates go through `CredentialStore.updateIf(deviceId)`, so a late write of a stopping service never touches a
newer pairing. `updateIf` changes to write only when the changed pairing differs from the stored one, and to
return whether it wrote: a reconnect then does not rewrite the file or bump `App.changes`, and the log can say
whether the list changed. The comparison happens inside `updateIf`, against the pairing just loaded — the
service's own copy is the one it connected with and may be older. A save bumps `App.changes`, which already
reloads the status screen.

The changes themselves are pure functions on `Pairing`: `withLatest(list)` (`null` → unchanged) and
`authenticated(address, list)` (`lastAddress` and the list in one step, for the `hello` answer).

Alternatives: replacing `addresses` (forbidden by obligation 8: the link's LAN address returns when the laptop
goes home); a separate unencrypted file (the addresses sit with the pairing already, and a second file could
outlive an unpair).

### D2. The order of attempts

```
[last]   if last != null and (latest is empty or last ∈ latest)
+ latest
+ link
+ [last]                       (when not already placed)
→ distinct, keeping the first occurrence
```

With no latest list this matches today's order whenever `lastAddress` is one of the link's addresses (always
so before this change, since it came from that list); a `lastAddress` outside the link, possible from now on
because it may come from a latest list, is no longer dropped. `CredentialStoreTest` (instrumented) asserts the
old dropping and is updated.

`lastAddress` first keeps today's fast path (the address that worked is usually still right). When the desktop's
current list no longer holds it, the desktop says it is gone, so it moves behind the lists instead of costing a
connection timeout (up to 10 s, `RemoteClient.OPEN_TIMEOUT_MS`) at the start of every round. It is never
dropped: an address learned from an intermediate list and since gone from it may come back. The link's list stays
in every order (obligation 8).

The handoff suggested `lastAddress, latest, link`; this differs only when the latest list omits `lastAddress`,
which is exactly the changed-address case it was written for.

### D3. Reading the list: lenient, validated

One reader, `Frames.addresses(element)`, takes `element.addresses`:

- absent, or not an array → `null` (an older desktop): the stored list stays (spec "An older desktop sends no
  list");
- an array → its string elements, each normalized as the link parser does it — trimmed, surrounding `[` `]`
  removed — and left out when blank or when OkHttp cannot use it as a host; duplicates removed. The text is
  kept as given, not canonicalized (`FD7A::1` and `fd7a::1` stay two entries, costing at most one extra attempt),
  so the link parser's results and `PairingLinkTest` stay as they are. The link parser
  and this reader share one function, `PairingLink.normalize(address): String?`, so `[fd7a::1]` from the
  desktop's additional address and `fd7a::1` from the link are one address (the desktop accepts brackets in the
  additional address, `remote-store.ts`). Interface addresses come without a zone and without link-local ones. An empty array is a list: it is
  stored as empty, which leaves the order to `lastAddress` and the link.

Reading the array by hand (not through `HelloResult`/`PairResult` with a `List<String>` field) keeps one odd
element from discarding the whole answer. Used for the `hello` answer (`ConnectionService.session`), the `pair`
answer (`Pairer`, stored with the new pairing), and the event.

### D4. The event and the routing

`ConnectionService` routes each event by kind through a pure `EventRoute.of(kind)`:

| kind | route |
|---|---|
| `attention` | the notifier (as today) |
| `addresses.changed` | store the list (D1, D3), log it |
| `sessions.changed`, `conversation.update`, `conversation.wait`, `conversation.ended` | `Desktop.onEvent` |
| anything else | ignored (obligation 7) |

Today an unknown kind reaches `Desktop.onEvent` and falls through its `when`; routing it nowhere makes the rule
explicit and testable on the JVM. The event is handled on the service's connection loop, not in `Desktop`: the
pairing and the store belong to the service. The service holds the pairing it connected with; `updateIf` reloads
the current one, so a list stored by the `hello` answer and then an event apply in order.

A failed write (`StoreUnavailableException` from the load, a Keystore error from the save) is caught at both
places, logged (`STORE_UNAVAILABLE` / `ERROR`), and the connection goes on: a list not stored arrives again with
the next `hello`. Separately, any exception escaping `session()` — today's `lastAddress` write and announcement
update after `hello` and the notifier can throw too — now cancels the connection before it propagates
(`catch (e: Exception) { connection.cancel(); throw e }`, cancellation excepted): today the supervisor starts
another round while the old socket stays open with nobody reading its events.

A new log kind `ADDRESSES` records `source` (`hello` or `event`; `Pairer` writes no log today and this change
does not add one), the count, and whether the stored list changed. Addresses are not secret and the log already holds
them in `ATTEMPT`; the count suffices here.

### D5. Obligation 7 elsewhere

Already true, pinned by tests rather than changed: `Frames.json` has `ignoreUnknownKeys`; `Frames.decode` returns
`null` for an unknown `t`, which `RemoteConnection` drops; unknown entry kinds are skipped by
`ConversationUpdate.decode`; unknown wait states read as `unknown`. Tests: an unknown event kind decodes as an
event and routes to `IGNORED`; `hello`/`pair` answers with unknown fields (and `addresses`) decode; an
`addresses.changed` with an extra field reads its list.

### D6. Replies sent while the agent works

With 0.2.5 the mid-turn `user` entry confirms such a reply through the existing matcher (a non-meta user entry,
not held at send time, same cleaned text); its position in the list does not matter. Three adjustments, all in
`Replies.waited` (which now takes `now`) and `Reply.label`:

- **A permission prompt no longer ends the wait.** Today `waited` marks a busy reply `agentStopped` on any state
  other than `busy`; `awaiting-choice` now counts as working. A prompt is a pause inside the turn: once answered,
  the agent goes on and absorbs the queued reply. Under 0.2.5 a prompt answered "No" or Esc'd leaves the state at
  `awaiting-choice` with no further report, so such a reply keeps "Waiting for the agent to take it" until the
  next state report. Where Claude Code puts a queued message when a prompt is refused (kept queued, or put back in
  the input) is **not measured** — spekterm's `docs/lessons/transcript.md` records only "prompt denied → nothing
  in 70 s"; checked on the phone (tasks). Measured since: Claude Code interrupts the turn and then submits the
  queued message as the next prompt, so the reply confirms (`docs/conversation-findings.md`).
- **Grace after the agent stops.** `agentStopped: Boolean` becomes `stoppedAt: Long?`, set by a `ready` or
  `unknown` report when not set. While `now - stoppedAt < NOT_SEEN_MS` (30 s) the label is "Not yet confirmed"
  (pending, at the end); after it, the fallback label (settled, at the send position). A `busy` or
  `awaiting-choice` report within the grace clears `stoppedAt` (back to "Waiting for the agent"); after the grace
  it does not, so a settled reply never jumps back below newer entries. Why: during a text-only answer a reply is
  taken in a *second* turn — `UserPromptSubmit` at once, `Stop`, then the next turn writes the `user` record
  (transcript.md, "message typed during a text-only answer") — and the desktop's wait tick can send `ready` before
  that record is read (`conversation-follow.ts` `onWait`). "Within the grace" is one predicate,
  `Reply.inGrace(now)` (`now - stoppedAt < NOT_SEEN_MS`), used by both `waited` and `label`, so the two never
  disagree at the 30 s boundary; repeated `ready` reports do not restart it. Without the grace the reply would flash the error-tinted
  fallback label and jump to its send position before confirming. The same grace covers a passing `unknown` report
  inside a turn. Only wait reports from the desktop reach `waited`; the app's own `unknown` on disconnect, unwatch
  or `conversation.ended` does not.
- **The fallback label** is kept for desktops older than 0.2.5 and for turns whose end the desktop does not
  report, reworded from "Sent while the agent worked — it may not show in the conversation" to "Sent while the
  agent worked — not in the conversation; check the desktop" (and renamed `MAY_NOT_SHOW` → `NOT_SHOWN_AFTER_WORK`):
  with 0.2.5 it is no longer the expected outcome, so it points to where the text can be found. Placement and late
  confirmation are unchanged.

Not measured either: whether a multi-line reply queued mid-turn keeps its text verbatim in `queued_command`'s
`prompt` (a difference would leave it unconfirmed, showing the fallback label). Checked on the phone (tasks):
verbatim.

The `session-conversation` design's first Risk ("a reply sent while busy … never confirms") is resolved by 0.2.5;
that design is archived, so this note stands in for its update.

### D7. Kept as is

- `ConversationReducer.waited` keeping the held tool on a toolless `awaiting-choice` report: harmless with 0.2.5
  (the desktop now repeats the tool) and still needed for older desktops.
- `meta` entries render as "Injected by the agent", collapsed (`ui/ConversationScreen.kt`); task notices and
  compaction summaries now arrive `meta: true` and need no phone change — checked on the phone (tasks).

## Risks / Trade-offs

- [The desktop lists an address the phone cannot reach (another subnet, a Docker bridge)] → The link already does;
  each costs one bounded attempt per round. The latest list is tried first, so a long list delays the link's
  addresses in a round where none of the latest works — bounded by the 10 s open timeout per address, as today.
- [A stale or wrong list from a desktop] → It never removes the link's addresses or the last good one (D2), so the
  phone can always fall back to what worked before.
- [Mid-turn confirmation depends on the desktop's projection] → Verified on the phone against 0.2.5 (tasks); older
  desktops keep the fallback label.
- [`awaiting-choice` no longer ends the busy wait] → On a desktop older than 0.2.5 a refused prompt also ends the
  turn silently; the reply then waits until the next state report instead of being marked at once. The label is
  no less true ("waiting for the agent to take it" — it was not taken).
- [The 30 s grace delays the fallback label on older desktops] → It only changes a label, as the ready-reply
  threshold does; matching continues.
- [A stray `busy` after the turn ended clears the grace] → The desktop reports a subagent's `PreToolUse` as
  `busy` and occasionally one arrives after `Stop` with nothing after it (spekterm `docs/lessons/transcript.md`,
  "once in five runs"). A reply that never appears then shows "Waiting for the agent to take it" until the next
  state report — while the desktop and the phone both show the agent working, so the label agrees with what the
  user sees, and the next prompt ends it. Accepted over the alternative (only `awaiting-choice` clears the grace),
  which would make a text-only answer's second turn that uses tools flash "not yet confirmed" first.

## Migration Plan

No migration step: the new field is optional and absent in existing pairings; the first `hello` answer of a 0.2.5
desktop fills it. Rolling back the app leaves the field ignored.

## Open Questions

None.
