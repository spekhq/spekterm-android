## Context

The usage line (`agent-usage`, `ui/UsageAndSkills.kt` `UsageRow`, opened into `UsageDialog`) shows the model and effort
from the desktop's `conversation.status` event, which spekterm builds from the agent's statusline payload
(`src/main/agent-status.ts`: `model.display_name`, `effort.level`; no model id) and pushes within 2 s of a change
(`USAGE_POLL_MS`). Replies go out through `Desktop.send` → `conversation.send`; a reply starting with `/` is a slash
command that the desktop writes trimmed (`slashForm`) and, with a desktop of 0.2.15 or later, the conversation shows
as the line typed when the CLI records it (`skill-commands`).

Measured on 2026-10-25 with the agent CLI 2.1.310, in a throw-away session with a statusline that saved each payload
(`~/.claude/settings.json` was saved before and put back after every command; it ended identical):

- `/effort <level>`: `low`, `medium`, `high`, `xhigh`, `max`, `auto` are accepted ("Valid options are: low, medium,
  high, xhigh, max, auto, ultracode [on|off]"); anything else is answered "Invalid argument". It runs **at once** — the
  terminal showed "Set effort level to medium" and "thinking with medium effort" while a `sleep 30` tool call ran —
  with no confirmation on Opus 5.5, Sonnet 5.5 and Haiku 5.5 (the docs add Fable 5.1). It is **saved as Claude Code's
  default for new sessions** of that model (`modelSettings.<model>.effortLevel` in `~/.claude/settings.json`, so every
  new Claude Code session on that computer, spekterm's or not), except `max` ("this session only"); `auto` clears the
  saved level (the payload then reads the model's default, `medium`). The payload, and so the usage line, changes at
  once.
- `/model <name>`: aliases (`opus`, `sonnet`, `haiku`, `default`, `opus[1m]`) and full ids (`claude-opus-5-5`) were
  accepted; `fable` was not tried (the CLI's help names it as an alias). An unknown name is answered "Model 'x' not
  found". Also **saved as the default** (`model` in the same file; `default` removes it). On a conversation with no
  warm prompt cache it switches at once. **With a warm cache** — on a subscription, within an hour of the last
  request; the docs: only when the new model is not the one that answered last — it opens "Switch model? … This
  conversation is cached for the current model … 1. Yes / 2. No", at once even while the agent works. The desktop
  sees no hook event for it: the wait state stays `ready`/`busy`. Typed text is ignored while it is open, and **the
  next reply's submit key confirms "Yes"** while its text is lost. A switch confirmed mid-turn applies to that turn's
  next API call.
- Bare `/model` and `/effort` open interactive pickers in the terminal, which stay open the same way.
- **What the transcript records.** A command run while the agent waits is `user` records — `<local-command-caveat>…`
  (`isMeta: true`, so the desktop sends `meta: true` without an origin), the command's markers (shown as
  `/effort low`), and `<local-command-stdout>Set effort level to low (saved as your default for new sessions): …
  </local-command-stdout>` (not meta). **A command run while the agent works is not recorded at all** — no caveat, no
  line, no output (the reviewer checked both probe transcripts: the mid-turn `/effort medium` and `/model sonnet` took
  effect, the payload and the next assistant message show it, and the transcript holds nothing of either; every
  `/effort` and `/model` record in the local transcripts was written with no tool call open). A cancelled or unknown
  `/model` is `type: "system", subtype: "local_command"`, which the desktop does not forward. So the conversation can
  never be the confirmation of a switch; the usage report can. The reviewer scanned every local transcript: 63 stdout
  records, each one wrapper around the whole text, 23 holding ANSI SGR sequences (`/compact`:
  `\x1b[2mCompacted …\x1b[22m`), none `stderr`. spekterm's protocol document says such output "is shown as recorded":
  the phone shows it today as a message the user typed, markers and escape codes included, and the caveat as an
  injected message.
- The CLI's `PreModelSwitch` hook can answer `permissionDecision: "allow"`, which skips the warm-cache confirmation
  (code.claude.com/docs/en/hooks.md#premodelswitch). kewang chose (2026-10-25) to have the desktop use it; spekterm's
  change `remote-model-switch` (session `spekterm-35af`, artifacts reviewed, awaiting kewang) drafts the contract in
  D2. Its first apply step measures the hook; if `allow` does not skip the box, that change is withdrawn.
- `conversation.answer` answers a permission prompt by writing keys — `1` for allow (spekterm's protocol document) —
  which, with a "Switch model?" box open, could select its "1. Yes". Not measured.

## Goals / Non-Goals

**Goals:**

- Change the watched session's effort or model in two taps from the conversation screen.
- Never let the phone confirm a model switch the user did not see — by a reply, an answer, or a picker left open.
- Say what the CLI does beyond this session: the saved default, and the cost of a mid-conversation model switch.
- Confirm a switch by what the agent reports, whether it ran while the agent waited or worked.

**Non-Goals:**

- A session-only switch: the CLI's argument form has none (only its interactive pickers' `s` key).
- The account's own model list: the phone cannot read the CLI's picker, which differs per account. It offers the
  CLI's aliases and a free name.
- A switch line for a typed `/model` or `/effort` reply: it stays a slash-command reply (`skill-commands`), which the
  hold, the report's confirmation (D6) and the bare-command block (D7) also apply to.
- Ultracode (`/effort ultracode on|off`) and fast mode.
- Any change on the desktop in this repository; the desktop half is spekterm's `remote-model-switch`.

## Decisions

### D1. Where: the usage line opens the sheet; the menu offers it too

Tapping the usage line opens a `ModalBottomSheet`, "Model and effort", replacing `UsageDialog`: switching is what a
look at the model leads to, and one tap fewer than a button inside the dialog. The sheet holds, in order: what the
agent last reported ("Now: Opus 5.5 · high", plain text), the effort row, the model row (D3), the notes (D4), the
reason a switch cannot be sent now (D5), then the details the dialog showed — same rows, same reset-time handling,
same footnote, driven by the same `rememberUsageNow` — so the `agent-usage` behavior is kept, only moved. The line's
click label becomes the sheet's name.

The usage line is absent until the agent reports something (a new session before its first message). So the
conversation's overflow menu gets "Model and effort", **shown** while the sheet is available (D5) and never disabled:
a switch that cannot be sent now opens the sheet with its reason. It is a conversation-only item:
`SessionMenu` gains an `extra` list of screen items (label and action) shown after the session actions, and opens when
either list is non-empty (it opens only for a non-empty action list today, so on a desktop that accepts no session
operation the conversation's menu would not open at all). The session list passes none.

*Alternatives:* a button in the details dialog — three taps; a chip in the top bar — the bar already holds the title,
the wait state and two actions on a phone's width.

### D2. How: effort over `conversation.send`, model over `conversation.setModel`

- **Effort** is sent as the text `/effort <level>` through `conversation.send`, with no attachments. Nothing new is
  needed from a 0.2.15 desktop.
- **Model** is sent with `conversation.setModel` — spekterm's `remote-model-switch` draft (2026-10-25, may still be
  adjusted before it is settled): `p: { sessionId, model }` → `r: { outcome, text }`, as `conversation.send`, with
  `text` the line `/model <model>`; the same watch requirement, gates, errors (`NOT_WATCHING`, `WAIT_STATE`,
  `PREFILL_PENDING`, `NOT_FOUND`, `NO_CONVERSATION`), queue and re-checks, plus `BAD_PARAMS` for a name outside its
  charset (D3). Before the submit key the desktop arms a 15-second one-shot mark that its injected `PreModelSwitch`
  hook consumes (`allow` for `source: "command"` and the same requested name). It also arms for a `conversation.send`
  that is exactly `/model <one name>` on one line with no attachments, so a typed command is covered too. Listed in
  `operations`; the phone offers models only when `Operations.SET_MODEL` (`"conversation.setModel"`) is listed
  (obligation 8), never by version. The answer is decoded as `SendResult` and read through `SendOutcome`.
- **`submitted` does not mean switched** (the desktop's D6): the hook may not run (a session started before the
  desktop had it, agent events off), and the box then opens as before. The phone confirms by the usage report (D6)
  and holds what could confirm the box until then.

If spekterm settles on a different name or parameters, only `Frames.conversationSetModel`, `Operations.SET_MODEL` and
`ModelEffort.validName` change. If `remote-model-switch` is withdrawn, the model half of this change is dropped (the
artifacts are amended then) and the effort half and D8 ship on their own.

*Alternative:* send `/model` over `conversation.send` and only warn — rejected with kewang (2026-10-25): away from the
desk, mid-conversation the cache is nearly always warm, so the switch would nearly always stall.

### D3. What can be chosen, and what is marked

`desktop/ModelEffort.kt`, pure:

- `EFFORTS = listOf("low", "medium", "high", "xhigh", "max", "auto")`, shown as chips with the CLI's words (they are
  the CLI's terms, from a string resource each so `HardcodedTextTest` passes; "auto" has a short meaning, "the model's
  default").
- `MODELS = listOf("default", "opus", "fable", "sonnet", "haiku")`, shown as chips "Default" (translated: 「預設」),
  "Opus", "Fable", "Sonnet", "Haiku" (names, kept, from strings), then "Other…", which opens a one-line field for any
  name the desktop accepts — `opus[1m]`, `claude-opus-4-8`, a Vertex `…@…` or Bedrock id.
- `ModelEffort.validName(s)`: the desktop's charset (its D4) on the trimmed text — 1–100 characters, only
  `A-Z a-z 0-9 . _ : / @ [ ] -`, not starting with `-`. `Switch.Model` holds the **trimmed** name, which is what is
  sent (the desktop does not trim). The field says "Letters, digits and . _ : / @ [ ] -" when it does not match; send
  stays disabled.
- **Marks are hints; every chip sends.** The report has the model's display name only, so a mark can be wrong
  (`opus[1m]` and `opus` both read "Opus 5.5…", `default` reads as its model, `auto` reads as `medium`); refusing to
  send a marked chip would make such switches impossible. `currentEffort(usage)`: the reported effort when it equals a
  level ignoring case. `currentModel(usage)`: the alias whose name is the first word of a reported model of the form
  "<Family> <version>" with nothing after (`^(\S+) [0-9][0-9.]*$`, ignoring case: "Opus 5.5" → `opus`, "Opus 5.5 (1M
  context)" → none). "Default" is never marked. The reported values are named at the top (D1) whatever is marked.
- `ModelEffort.command(Switch)`: `/effort <level>` or `/model <name>`.

### D4. What the sheet says

Under the rows, always: "Each choice also becomes Claude Code's default for new sessions on that computer (max is for
this session only; auto clears it)." Under the model row: "Switching mid-conversation makes the next response re-read
the whole conversation: slower, and more tokens." — the desktop's hook skips the CLI's own warning, so the phone shows
it. Without `conversation.setModel` the model row is replaced by "Changing the model from the phone needs a newer
spekterm on the desktop." All plain `Text`.

### D5. When a switch can be sent

The sheet is available while the watch is active and the conversation holds a usage event for it (`c.usage != null`:
a 0.2.15 desktop). A switch can be sent when `Replies.blocked(connected, paused, watch, wait, pending, inFlight, text =
command, attachments = emptyList(), inboxPrompt, hold)` is null and no switch of the session is being sent — the gates
of a reply without the composer's attachments, so a photo still uploading does not hold a switch back; checked again
in `Desktop.switch`. Otherwise the chips are disabled and the sheet says why: `blockedText` (made `internal`, given
the same `answerable`) for every reason it covers, and for `Blocked.InboxPrompt`, which the composer leaves to its
inbox note, the sheet's own line "A first prompt from the inbox waits in this session; send or edit it first."

### D6. A switch is not a reply: sent, confirmed by the report, held

A switch never becomes a `Reply`: replies are confirmed by their line in the conversation, which a command run while
the agent works never gets (Context). `Desktop` keeps one `PendingSwitch` per session, in a map like `drafts`, written
directly by `Desktop` (not through the held conversation, so it is kept while another session is on screen), kept
across leaving the screen, a re-watch and a reconnect, mirrored for the focused session into `Conversation.switch`,
and cleared by `resetForTest()` / `clear()`. It lives in memory, as drafts do: a killed app process forgets it.

`PendingSwitch(id, switch, replyId, before, sentAt, held, state, anyway)` — `id` a number new for each switch, which
the answer must match to be stored (a switch sent meanwhile — a typed `/model` while an effort switch is still being
sent — replaces the pending one, and the earlier answer is then dropped, never written onto the new switch); `replyId` set for a typed command (below);
`before` the reported model (for a model) or effort (for an effort) **at the send**, or null; `sentAt` the send's
time; `held` the entry uuids held at the send (as `Reply.held`); `state` one of `Sending`, `Submitted`, `Lost` (no
answer, or an error `SendOutcome.of` does not read as a refusal: the desktop may have written it), `Confirmed`,
`Refused(code)`, `NotSubmitted`; `anyway` set by "Continue anyway" (below).

- **Sending.** `Desktop.switch(sessionId, s)` checks D5, stores `Sending` with `before`, `sentAt` and `held`, closes
  the sheet, requests `conversation.send` (effort) or `conversation.setModel` (model) with no attachments, and stores
  the answer: `submitted` → `Submitted`, `filled-not-submitted` → `NotSubmitted`, a refusal → `Refused(code)`,
  anything else → `Lost`. Nothing of the composer — draft, attachments, notes — is read or changed.
- **Confirmed** (from `Sending`, `Submitted` or `Lost`) by `ModelEffort.confirmed(p, usage, entries)`, evaluated on
  every usage event and every new entry of that session:
  - an effort when the reported effort equals the level ignoring case — for `auto`, when it differs from a known
    `before`; and, for an effort only, at once on `submitted` when the level is the marked current one (no
    confirmation exists for an effort, so nothing can be left open);
  - a model when a reported model is known, differs from a known `before`, and — for the aliases opus, fable, sonnet,
    haiku — its first word is the alias, ignoring case ("Opus 4.8" → "Opus 5.5" confirms `opus`; "Sonnet 5.5" does not
    confirm `haiku`); for `default` and a typed name, differing is enough. A model switch is **never** confirmed at
    once: a mark can be wrong ("Opus 4.8" is marked `opus`, which resolves to Opus 5.5), and the box opens whenever
    the new model is not the one that answered last;
  - either when a user entry whose uuid is not in `held` reads the command line (a switch run while the agent waited).
  A missing usage (it is dropped on every re-watch and disconnect), a missing value or an unknown `before` is **no
  information**, never a change.
- **What the screen shows** above the composer, one line, plain text: `Sending` / unconfirmed "Changing the effort to
  low…" / "Switching the model to sonnet…"; `Confirmed` nothing (the usage line shows the value), and the pending
  switch is removed; `Refused` "Not changed: " and the reason — `WAIT_STATE` "the agent couldn't take it now",
  `PREFILL_PENDING` "a first prompt waits in the session", `NOT_FOUND` "the session has no running agent",
  `BAD_PARAMS` "the desktop didn't accept that model name", `NOT_WATCHING` "the conversation wasn't being watched",
  `UNKNOWN_OPERATION` "the desktop doesn't support this" (the codes `SendOutcome.of` reads as a refusal) — with
  Dismiss; `NotSubmitted` "Typed into the agent's input but not sent — clear it on the desktop before replying", with
  Dismiss; an effort not confirmed 10 s after `sentAt` "The effort hasn't changed yet — look at the desktop", with
  Dismiss; a model after "Continue anyway" "The model hasn't changed — if the desktop still asks to confirm, a reply
  or another switch sent now could confirm it", with Dismiss. A new switch replaces a settled one; dismissing removes
  it.
- **The hold** — for a model switch whose state is `Sending`, `Submitted` or `Lost`, without `anyway`, in that
  session: the
  composer is blocked with `Blocked.ModelSwitch`, the prompt card's answers are disabled (`Offer.Held`), and no other
  switch can be sent. **It starts at `Sending`**: a reply sent then would be queued by the desktop behind `/model`
  (`runQueued`) and its submit key would confirm the box. Until 10 s after `sentAt` it says "Switching the model…";
  from then on "The model hasn't changed yet. The desktop may be asking to confirm the switch — a reply or an answer
  sent now could confirm it, and a reply would be lost. Look at the desktop." with "Continue anyway", which sets
  `anyway` and lifts the hold. The time is read from `sentAt`, so a screen opened later shows the second text once
  the conversation is shown again. The hold ends only by confirmation, "Continue anyway", or the session being
  closed, gone or no longer running (the box goes with the agent's process) — **not** by a re-watch, a reconnect or
  leaving the screen, which do not close a box on the desktop. `Desktop.send`, `Desktop.answer` and `Desktop.switch`
  each check it again before sending.
- **A typed `/model <name>` or `/effort <level>`.** When a reply's normalized text is one of these with one argument,
  on one line, with no attachments, `Desktop.send` stores a `PendingSwitch` for it **at the send**, with its
  `replyId`, `before`, `sentAt` and `held` taken then — so a model holds from `Sending` as above, and both are
  confirmed by the report. When it is confirmed, its reply counts as confirmed and leaves the screen as a confirmed
  reply does (`conversation-reply`, MODIFIED) — run while the agent worked, its line never appears, and the reply
  would otherwise end as "a command the CLI doesn't know". When the reply is refused, not submitted or confirmed by
  its line, the pending switch follows it. The switch line is not shown for it (the reply's row is). On a desktop
  before 0.2.15 nothing is ever reported, so only its line can confirm it; a `/model` sent while the agent works then
  holds until "Continue anyway" — accepted, said by the hold's text. While its switch holds, the reply's row reads
  "Waiting for the model to switch" and does not settle (`Reply.Label.SWITCHING`): measured on the emulator, the
  30-second "a command the agent's CLI doesn't know" was wrong for a switch waiting in the box. The conversation list
  follows the newest row when the switch's notes above the composer change, so the hold's text never hides it.
- **Order in `Replies.blocked`**: `ModelSwitch` right after `InFlight` and before `AttachmentFailed` / `Attaching` /
  `Empty`, so the warning shows with an empty field; the bare command (D7) after `Empty`, before `TooLong`. While the
  watch is being re-established the earlier reasons (not watching, unknown) show first.
- **Prompt answers.** `Answers.offer` returns a new `Offer.Held` while the hold is on: the card shows the prompt and
  the hold's text instead of answer buttons, with "Continue anyway". Not measured whether a `1` would reach the box
  rather than the prompt; holding is the safe side. The composer then says "Answer the choice above", as for an
  answerable prompt, not "answer it on the desktop".

### D7. Bare commands

The composer does not send a reply whose command, after `Replies.normalized`, is exactly `/model` or `/effort` with no
argument (`Blocked.BareCommand`): the field's note says "Add a name, or choose in Model and effort". Both open a
picker in the terminal that the phone cannot close (measured), and spekterm's draft makes "never send a bare
`/model` or `/effort`" a client obligation. The name is compared as typed, case-sensitive, as the CLI's command names
are written; "/models" or "/Model" are not this check's business.

### D8. A built-in command's output

`ConversationItems` gains `CommandOutput(key, text, entry)`: a user entry, not `meta`, whose whole text (trimmed) is
one `<local-command-stdout>…</local-command-stdout>` or `<local-command-stderr>…</local-command-stderr>` (no further
marker inside), shown as the inner text with ANSI escape sequences removed (CSI `ESC [ … final byte` and any other
`ESC x`), trimmed, in a muted monospace block under a "Command output" label, plain text, never linkified. Past three
lines it shows the first three and "Show all"; this is a new small composable (tool results collapse whole). It takes
the place the user entry had: it closes a run as a non-meta user entry does today, and it carries the anchors after
it. It shows no time of its own (`ConversationTimes.timeOf` null) and counts for the day by its `at`, as a tool call
does.

A `meta` user entry whose whole text is one `<local-command-caveat>…</local-command-caveat>` is not shown: in
`ConversationItems.group` it still `close()`s the current run, as the injected entry it was did, and its anchors are
handed to the item before it (or, with none before it, to the next item), so a settled reply anchored on it keeps its
place. Anything else holding these markers stays as it is. It is for every built-in (`/compact`, `/cost`…).

### D9. Logging

`Desktop` logs `Kind.SEND` with `"switch" to "effort" | "model"`, the outcome and the error code, and how a pending
switch ended (`confirmed`, `refused`, `not-submitted`, `anyway`, `dismissed`, `gone`) with the seconds it took — never
the model name or the level.

## Risks / Trade-offs

- [The model half depends on spekterm's change, whose hook is not measured yet] → the effort half and D8 work with
  today's desktop; the model row says what is missing; tasks 3.2 and 4.1 test models against the desktop that ships
  the operation; a withdrawal drops the model half (D2).
- [The hook may not run for some session, and the box opens] → the hold (D6) keeps replies and answers from
  confirming it until the switch shows or the user says to continue.
- [A switch to the model already in use, or one the report cannot tell apart] → a model switch is never confirmed
  without a changed report or its line; sent while the agent works, it holds, and after 10 s says to look at the
  desktop, which shows no box; "Continue anyway" ends it. A nuisance, chosen over confirming a switch whose box may be
  open.
- [`auto` when the saved level already was the model's default] → nothing changes in the report; after 10 s the
  effort text says to look at the desktop. Accepted.
- [The CLI saves the choice as the default for new sessions on that computer] → said in the sheet; no argument form
  avoids it.
- [Aliases drift as models change; `fable` unmeasured] → they are the CLI's, resolved by the CLI; "Other…" covers the
  rest; an unknown name changes nothing and ends in the 10-s text; the emulator run tries `fable` (task 3.2).
- [The hook skips the CLI's cost warning] → the sheet shows it (D4).
- [Whether the CLI takes `/Model` as `/model`] → measured on the emulator (2026-10-25, CLI 2.1.310): it does not run
  it, so the case-sensitive checks of D6 and D7 stand. A `/model` declined in the box ("Kept model as …") leaves no
  entry either, so a declined switch is never confirmed by a line.
- [The CLI's wording of command output and of the caveat may change] → only exact whole-entry wrappers are
  recognized; anything else is shown as today.
- [The checkout is shared with `tool-result-content`, applied in another session (on master since 6d8d298, not yet
  archived), which also changed `ShownRow`, `Frames.kt`, `Desktop.kt`, `ConversationItems.kt` and the strings] → an
  exception to "commit straight to master, no branches": this change works in a local worktree and branch
  (`model-effort-switch`), never pushed, rebased onto master and fast-forwarded into it when done.
- [Both changes modify "Show the conversation of an agent session" (`conversation-view`) and "Show what others sent as
  messages" (`tool-call-display`), and an archive replaces a modified requirement whole] → this change's deltas carry
  `tool-result-content`'s text of both with the caveat added, so this change is archived after it; archived first, it
  would drop the picture note's exception.

## Migration Plan

None: an app update. A desktop before 0.2.15 sends no usage event, so neither the line nor the menu item appears; a
desktop without `conversation.setModel` gets effort only.

## Open Questions

- Settled by spekterm's measurement (CLI 2.1.310): `allow` skips the box, idle and while the agent works;
  `requested_model` is the typed name lower-cased, and the desktop compares so. Seen from the emulator too (task 3.2). Its obligations, as revised on
  2026-10-25: never send a bare `/model` or `/effort`; a name outside its charset is refused with `BAD_PARAMS` (the
  free-name field is fine within it).
