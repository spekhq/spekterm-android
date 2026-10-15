## Why

spekterm 0.2.5 (commit 4c37609, change `companion-test-fixes`) fixed the four desktop defects recorded in
`docs/conversation-findings.md` ("Findings") and, still within protocol version 1, started telling a paired phone
where the desktop can be reached now: an `addresses` list in the answers to `hello` and `pair`, and an
`addresses.changed` event while connected. Its `docs/remote-protocol.md` section 9 adds two client obligations
this app does not meet yet: (7) ignore unknown fields and event kinds, and (8) keep the pairing link's addresses
and the latest list received, trying the latest first.

The Pixel run met exactly the case obligation 8 addresses: the desktop's LAN address changed under a pairing,
and the phone kept trying only the addresses from the QR code. And a reply typed while the agent works is now
shown by the desktop where the agent took it in, so the phone's fallback label for such replies ("it may not show
in the conversation") no longer describes the normal case.

## What Changes

- **Follow the desktop's addresses.** The pairing keeps, next to the link's address list, the latest list the
  desktop sent — in the `pair` answer, every `hello` answer, and each `addresses.changed` event — persisted like
  the address that last connected. The order of attempts becomes: the address that last connected (while the
  desktop still lists it), then the desktop's latest list, then the link's list, then anything else that once
  connected; no address twice. An answer without the field (a desktop older than 0.2.5) leaves the stored list as
  it was. The status screen shows the order as it now stands.
- **Ignore what the app does not know** (obligation 7): an unknown event kind or an unknown field in any frame is
  skipped, never an error — confirmed in code and pinned by tests.
- **Replies sent while the agent works.** With 0.2.5 they are confirmed by the user entry the desktop now
  projects. A reply still not in the conversation once the agent stops gets 30 seconds' grace ("not yet
  confirmed"): during a text-only answer the desktop reports the agent ready just before the turn that takes the
  reply starts. After that it gets the fallback label, kept for older desktops and for turns the desktop does not
  report the end of, reworded to point to the desktop. A permission prompt raised after the reply no longer counts
  as the agent having stopped; a refused prompt or an interrupted turn, which the desktop does not report, leaves
  the reply waiting until the next state report.
- **Findings re-run.** `docs/conversation-findings.md` marks the four desktop defects fixed in spekterm 0.2.5 and
  records the affected scenarios re-run against it.

Not changed: the protocol version (1), the desktop (no spekterm edits), the phone's handling of a pending
choice's tool on a report without one (kept for older desktops), and answering permission prompts (still on the
desktop only).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `desktop-connection`: "Try the pairing's addresses" also keeps and tries first the desktop's latest address
  list; a new requirement to ignore unknown event kinds and fields.
- `conversation-reply`: "A sent reply is unconfirmed until it appears in the conversation" — a reply sent while
  the agent works is confirmed when the agent takes it mid-turn; a permission prompt does not count as the agent
  stopping; once the agent stops, 30 seconds' grace before the not-shown label, which points to the desktop.

## Impact

- Code: `store/Stores.kt` (`Pairing`: the latest list and the order; `CredentialStore.updateIf` writes only a
  change), `net/Frames.kt` (reading `addresses`),
  `pairing/Pairer.kt` and `pairing/PairingLink.kt` (the `pair` answer's list, address validation shared),
  `service/ConnectionService.kt` (the `hello` answer, the `addresses.changed` event, event routing),
  `log/ConnectionLog.kt` (a log kind), `desktop/Replies.kt`, `desktop/Conversation.kt`, `desktop/Desktop.kt` and
  `ui/ConversationScreen.kt` (the busy-reply rule, its clock and label), `ui/StatusScreen.kt` (unchanged code; it shows the new order).
- Stored data: the encrypted pairing gains one optional field; an existing pairing reads as having no latest list.
- Docs: `docs/conversation-findings.md`, a pointer in `docs/spike-findings.md`.
- Tests: new JVM tests; `CredentialStoreTest` (instrumented) updated for the new order and `updateIf`.
- Desktop: needs spekterm 0.2.5 or later for the address list and the mid-turn user entries; works unchanged
  with older desktops.
