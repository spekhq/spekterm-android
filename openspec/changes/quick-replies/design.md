## Context

The reply composer (`ui/ConversationScreen.kt`, `Composer`) sends through `Desktop.send(sessionId, text)`, which
checks `Replies.blocked(...)` (connection, watch, wait state, pending prompt, a reply in flight, the text, the
attachments, a model switch, a shell command typed but withheld — `CommandWaits`), records the reply at once
(`Replies.sent`, which also clears `draft`, `attachNote`, `prefillRefused`, `commandRefused`), and sends
`conversation.send` with the ready attachments. The inbox hold is **not** checked by `send`: the composer passes it
in (`gate.inboxHold` → `inboxPrompt`), and a reply that slips past it is refused by the desktop with
`PREFILL_PENDING`. A refused reply comes back to the field (`Replies.answered`, `draft = reply.text` before the draft,
`draftReturns + 1`, which the composer's `LaunchedEffect` copies into its own `field`).

`Desktop.runCommand` is the precedent for a second sender that must not touch the draft: `Reply.command`, a
`command` branch in `sent` (draft left alone) and in `answered` (a refused command is held as `commandRefused`, not
written into the field) — `run-shell-commands` D3.

The theme choice is kept in `ThemeStore` (an `AtomicFile` JSON in `noBackupFilesDir`, kept across unpairing) and
offered in the Settings screen (`StatusScreen.kt`, title `status_title`, `AppearanceSection`). The composer is shared
by the conversation screen and the reply bar (`ReplyArea` / `Composer`, `reply-from-other-screens`). A shell session
has no composer; an agent session's composer has a command mode (`c.commandMode`, `shell-commands`).

## Goals / Non-Goals

**Goals:** one tap sends a kept sentence to the watched agent, under the gates of a typed reply, without touching
what the user has in the field; the list is the user's, on the phone.

**Non-Goals:** syncing with the desktop's list (each device keeps its own — the user's decision); a protocol change;
a fill-then-confirm mode (the user chose send-at-once); quick replies on a session not watched.

## Decisions

### D1. `Desktop.sendQuick`, a third sender beside `send` and `runCommand`

`sendQuick(sessionId, sentence)`:

- **Gate**: one pure function, `Replies.quickBlocked(c, connected, paused, inboxPrompt, switch, sentence)`, which
  calls `Replies.blocked` with `c`'s watch, wait, pending, in-flight and withheld state, `text = sentence` and
  `attachments = emptyList()` always. The composer calls it (with its `connected` / `paused` and `gate.inboxHold`) to
  enable the chips; `sendQuick` calls it as `send` calls `blocked` (`attached` checked, `true, false` for connected /
  paused — pausing is held off by the screen), passing the inbox hold the composer gave it. No new flag: `Empty`, `TooLong`, `Attaching`, `AttachmentFailed`
  cannot occur for a stored sentence with no attachment, and `BareCommand` must stay (D2 also refuses storing one).
  Unlike `send`, the inbox hold is passed in, so the chips' enablement and the send agree.
- **Record**: a new `Reply.quick` flag; `Replies.sent(..., quick = true)` appends the reply (with `commandsShown =
  c.usage != null`, as a typed one, so a kept `/simplify` that never shows gets `COMMAND_NOT_SHOWN`), clears
  `quickRefused` and `prefillRefused` (reasons about the desktop's state, stale once a reply went), and leaves
  `draft`, `attachments`, `attachNote` and `commandRefused` as they were. The typed and command `sent` also clear
  `quickRefused`.
- **Model and effort**: tracked as `send` does (`ModelEffort.typed(sentence, 0)` → `pendingSwitch` / `putSwitch`), so
  a kept `/model x` or `/effort high` is held and confirmed exactly like a typed one (`model-effort-switch`,
  `conversation-reply`).
- **Settle**: `Replies.answered` gets a `quick` branch for `Refused`: the reply is dropped (nothing shown as sent),
  nothing is written into `draft`, `draftReturns` is unchanged, and the refusal is held as `quickRefused` (the code),
  shown as one line in the composer — "Quick reply not sent:" and the reason, `PREFILL_PENDING` included (its reason
  text is the existing `switch_refused_prefill`) — not hidden by the inbox note, until the next send or tap. Every other outcome
  (submitted, filled-not-submitted, unknown) is the typed reply's.

*Alternative:* put the sentence in the field and call `send`. Rejected: it sends the user's attachments and replaces
their draft.

### D2. The store and the rules of a sentence

A sentence is valid when, after the reply's own cleaning and trimming (`Replies.clean`, `Replies.jsTrim`, so the rule
and the gate agree), it is 1 to 200 UTF-16 units long (Kotlin `String.length`, as the desktop's
JavaScript counts), holds no line break or other control character, and is not a bare `/model` or `/effort` (`ModelEffort`'s own test; the
command with no argument opens a picker on the desktop the phone cannot close — `BareCommand`, Replies.kt). A list
is valid when every sentence is valid, there are at most 12, and no two are equal after trimming.

`QuickReplyStore`: `{ "replies": [..] }` in an atomic JSON file in `noBackupFilesDir`, as `ThemeStore`. The rules
live in a pure `QuickReplies.parse(json: String): List<String>?` (JVM-testable, `AtomicFile` is not):  `null` when the
JSON is not this shape or breaks any rule — **the whole list is then not set**, never repaired in part (the editor
cannot produce such a file; only corruption can). `save(list)` writes exactly the list (`[]` allowed), trimmed;
`reset()` deletes the file. `App` exposes `StateFlow<List<String>?>`: `null` = not set, resolved to the defaults by
the UI with `stringResource`, so a per-app language change (which recreates the activity: the manifest's
`configChanges` has no `locale`) shows the defaults in the new language with no refresh code.

Defaults (string resources): `quick_reply_default_close` "Can this session be closed now?" / 「這個 session 可以關了嗎？」;
`quick_reply_default_risk` "Is there any risk, or anything I need to decide?" / 「會有什麼風險或我要特別決策的嗎？」;
`quick_reply_default_remaining` "Is there anything left to do in this session?" / 「這個 session 還有什麼要做的嗎？」.
The same three as the desktop's defaults.

### D3. The chips

In the composer, directly above the reply field: a horizontally scrolling row of `SuggestionChip`s (one line, never
wrapping), each at most 220 dp wide (as `AttachmentCard`) showing its sentence on one line with an ellipsis; its
semantics carry the whole sentence. `Composer` takes `quickReplies: List<String>`, `roomForQuick: Boolean` and
`onQuick: (String) -> Unit` (defaulting to `Desktop.sendQuick`, as `PromptCard`'s `onAnswer` defaults to
`Desktop.answer`), passed down by `ReplyArea` from the conversation screen's and `WithReplyBar`'s own
`BoxWithConstraints`, so a Compose test drives it without the store or a connection. Shown
exactly while: the list is not empty, the composer is not in command mode (it shows the `$` field then, and hides
attachments and skills the same way), the skills panel (`SlashPanel`) is not showing, and the space for the
conversation and its reply area (`BoxWithConstraints` of the screen) is at least 240 dp tall (first guessed at 360 dp; lowered after measuring, task 4) — below that (landscape
with the keyboard open, typically) the row is hidden so the field stays visible. While the prompt card shows, the
row shows disabled. Enabled exactly while D1's gate is null. A tap calls `sendQuick`.

Why the composer says why a chip is disabled: the gate is the typed reply's, so its reason is already on screen —
the reason line for most states, the inbox note for the inbox hold, the waiting-command notice for `CommandWaits`.

### D4. The editor

A "Quick replies" section on the Settings screen after Appearance: each sentence as a row with edit, move up, move
down and remove buttons, each labelled with the action and the sentence; an Add row; "Restore defaults" (asks to
confirm). Editing uses a dialog with one single-line text field limited to 200 units; Save is disabled while the
trimmed text is not a valid sentence or equals another sentence, with the reason under the field. The first change
saves the whole list as shown. Add is not offered at 12.

## Risks / Trade-offs

- [A mis-tap sends a question] → the sentences are questions, harmless by nature; the user chose send-at-once.
- [The agent's desktop input box may hold the desktop user's typing, which the sentence would join] → true of every
  phone reply today; not changed here (the desktop's change notes it).
- [The 360 dp threshold is a guess] → measured on the real phone in task 4; adjusted there and recorded in
  `docs/conversation-findings.md`.
