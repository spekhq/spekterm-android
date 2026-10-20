## Context

spekterm 0.2.15 (`remote-phone-context`, `6b6ebfa`; authority `~/git/spekterm/docs/remote-protocol.md`,
`conversation.watch` and `conversation.send`) adds two events to a watch:

- `conversation.status`: `{ sessionId, model?, effort?, context?: { percent?, size? }, costUsd?, rateLimits?: {
  fiveHour?: { percent, resetsAt? }, sevenDay?: { percent, resetsAt? } } }`. Sent once when the watch starts (the
  session id alone when nothing was reported) and again whenever a value changes, checked every 2 s. Built by
  `src/main/remote-agent-status.ts` `usageView()`: text cleaned of control characters and cut to 80 characters,
  numbers only when finite, a reset time only when positive (then × 1000, epoch ms). The rate limits are the
  **account's**, as this session's agent last reported them.
- `conversation.skills`: `{ sessionId, skills: [{ name, description }] | null }`, from the agent's own skill listing
  in its transcript (`src/main/transcript-skills.ts`): names non-blank and without white space, deduplicated,
  description at most 300 characters or `null`. `null` means no list is known (no first message yet, or `/clear`
  moved to a new transcript). A removed skill stays listed until the agent lists every skill again.

Either may arrive before the watch's answer. A desktop before 0.2.15 sends neither. Both are events, not in
`operations`.

In the app, `Desktop.onEvent` dispatches desktop events (routed by `service/EventRoute.kt`'s `DESKTOP_KINDS`) through
`change(sessionId)`, which applies a transform only to the held `Conversation` of that session while its watch is
`Requested` or `Watching`. `ConversationReducer.requested` is applied before the `conversation.watch` request goes
out, so events arriving before its answer find the watch `Requested` and are kept. The wait state, pending tool and
prompt are cleared in `requested`, `unwatched`, `disconnected` and `ended`. The composer (`ConversationScreen.kt`
`Composer`) holds its text as a `String`, mirrored into `Desktop.setDraft`. `Reply.label` (`desktop/Replies.kt`) turns
a reply's state into what its row says.

## Goals / Non-Goals

**Goals:**

- See at a glance which model a watched session runs, how full its context is and what it has cost; see the rate
  limits when they matter, and in full on request.
- Find a skill by name or description and start a reply with it, without remembering or typing its name.
- Not be misled: a value not reported is not shown, a list not known is not shown as empty, a slash command that
  will not run as one, or that may be unknown, says so.

**Non-Goals:**

- Usage on the session list or in notifications (the desktop sends it only to a watch).
- Built-in CLI commands (`/clear`, `/compact`, …) in the list: the desktop does not send them, and a list of the app's
  own would drift from the CLI's. The user types them.
- Validating a typed command against the list before sending: a built-in command is never in it.
- Showing the desktop's "sent from the phone" note: it is outside the message by design.
- Any change on the desktop.

## Decisions

### D1. Decoding, field by field

`AgentUsage.decode(element)` and `SkillList.decode(element)` in `net/Sessions.kt`, by hand like `ConversationWait`,
so one malformed value never drops the event (obligation 7):

- `AgentUsage(sessionId, model, effort, contextPercent, contextSize, costUsd, fiveHour, sevenDay)` — every value
  nullable. Text: a string, trimmed, empty → null. Numbers: a JSON number that is finite; a percent outside 0–100 is
  clamped (the desktop does not clamp); a size or cost below 0 → null. A window (`UsageWindow(percent, resetsAt)`)
  needs its percent; `resetsAt` ≤ 0 or not a number → null. No `sessionId` → the event is dropped.
- `SkillList(sessionId, skills: List<Skill>?)` — `skills` null, missing or not an array → `null` (not known), never an
  empty list. An item needs a `name` that is a non-blank string with no white space, else it is dropped; `description`
  a string (trimmed, empty → null) or null. A repeated name keeps its first item.

`EventRoute.DESKTOP_KINDS` gains both kinds.

### D2. Held with the watch, cleared with it

`Conversation` gains `usage: AgentUsage? = null` and `skills: Skills? = null`, where `Skills` is `Unknown` (the desktop
sent `null`) or `Known(list)`; `null` means no event this watch. `Desktop.onEvent` applies them through `change()`,
replacing what is held (each event is the whole state). `requested`, `unwatched`, `disconnected` and `ended` clear both,
as they clear the wait state: they describe the live session, and a stale model or skill list after a reconnect would
read as current. Because `requested` runs before the request goes out, an event arriving before the answer is kept.

*Alternative:* keep them while disconnected, as the entries are — rejected: entries are history, usage is a reading,
and the line would show a cost frozen at the drop. The screen is back to showing them within one event of the next
watch, which the desktop sends at once.

### D3. The usage line

Shown on the conversation screen while the watch is active and `usage` holds any value, as the first row under the
top bar (above the content notices and background tasks), in `labelMedium`, `onSurfaceVariant`, one line,
ellipsized at the end. `UsageLine.parts(usage, now)` (pure, in `desktop`) returns typed parts, in this order, each
present only when its value is — the words come from string resources in the screen, so `desktop` holds none
(`HardcodedTextTest`):

1. `Model(model, effort)` — the screen shows "Opus 5.5 (high)", or either alone;
2. `Context(percent, size)` — "Context 43%", or "Context 43% of 200K" with the size;
3. `Cost(usd)` — the session's cost;
4. `Limit(window, percent)` for each rate-limit window whose **rounded** percent is at least **80** and whose reset is
   not past (a window without a reset time counts as not past), five-hour before seven-day — shown "5-hour limit 85%"
   in the warning color.

Pure formatting, in `desktop` and JVM-tested: `UsageLine.percent(p)` rounds half up to a whole number (42.5 → 43;
`NumberFormat`'s `HALF_EVEN` would give 42); `UsageLine.size(tokens)` gives `200K`, `128K`, `1M`, `1.5M` — divided
by 1,000 or 1,000,000, at most one decimal, ".0" dropped — the way the agent's own UI names windows;
`UsageLine.cost(usd, locale)` is `NumberFormat.getCurrencyInstance(locale)` with currency `USD` and **exactly two
fraction digits** set explicitly (a locale whose own currency has none, such as ja-JP, would otherwise give "$3"):
"$3.21" in en-US, "US$3.21" in zh-TW.

The line is a button ("Usage details"), opening a dialog with every value present, one row each: Model, Effort,
Context ("43% of 200,000 tokens", the size in full with the locale's grouping), Cost, 5-hour limit and 7-day limit
(the percent and its reset time, the time alone when today, with the weekday and date otherwise; with no reset time,
the percent alone; with a reset time past, "reset at <time> — not reported since" instead of the percent, which no
longer holds). A footnote: the values are the agent's last report to the desktop, and the rate limits are the
account's, not this session's. Times through `DateUtils.formatDateTime` (`FORMAT_SHOW_TIME`, plus
`FORMAT_SHOW_WEEKDAY | FORMAT_SHOW_DATE | FORMAT_ABBREV_ALL` when not today) in the zone of the screen's
`rememberClock()` (`conversation-times`).

"Now" must move on its own: the desktop sends an event only when a value changes, so an idle agent leaves an 85%
warning standing for hours after its reset. `UsageLine.nextChange(usage, now)` is the earliest future `resetsAt`;
the line and the dialog share a `now` state that a `LaunchedEffect(usage, nextChange)` sets again once that time
comes (and on resume, with the clock).

*Alternatives:* in the top bar's subtitle — rejected: it holds the wait state, and both do not fit one line on a
phone; rate limits always on the line — rejected: they are the account's, rarely the reason to look, and the line
would ellipsize the model; a threshold per window — not needed yet.

### D4. The skills sheet

A skills button (a new `SpekIcons.Skills`, a slash in a rounded square) in the composer row, before the attach
button, shown while the watch is active and `skills` is not null; enabled as the attach button is (connected, not
paused, not closed). It opens a `ModalBottomSheet`:

- a title, "Skills", and a search field filtering by `SkillFilter.matches(skill, query)`: case-insensitive substring
  of the name or the description, the query trimmed; empty query → all;
- `Known`: the skills sorted by name, case-insensitive (the desktop's order is the agent's listing order, which mixes
  sources), each row `/name` in `titleSmall` and the description in `bodySmall`, at most three lines, both plain
  `Text` (obligation 15; never linkified); no match → "No skill matches"; an empty list → "No skills in this session";
- `Unknown`: "The agent lists its skills with the session's first message, and again after /clear. Send a message,
  then look again." — never an empty list;
- a footnote: built-in commands such as /compact are not listed and can be typed.

Choosing a skill closes the sheet, applies `SkillInsert.insert(draft, name)` to the reply field, **writes the result
with `Desktop.setDraft`** (a value set by the program never passes `onValueChange`, the only caller today, so without
it the insertion is lost on leaving the screen), and focuses the field. With `d` the draft without leading white
space:

- `d` empty → `/name `;
- `d`'s first word (up to the first white space) is a command name or a bare slash — matches `^/[^/\s]*$`, one slash
  and no other — → that word is replaced and what follows is kept as is: `/old args` → `/name args`,
  `/old\nmore` → `/name\nmore`, `/old` → `/name `, `/` → `/name `;
- otherwise → `/name ` followed by `d`, so what was typed becomes the arguments — including a draft starting with a
  path, `/tmp/x.log shows an error` → `/name /tmp/x.log shows an error`.

"White space" here, in the regular expression and in `Replies.command` is JavaScript's `\s` set of D6 (U+3000, which
Zhuyin input types, among it), so a first word ends where the desktop's does. The cursor goes right after the name and
the one white-space character that follows it (the inserted space, or the
draft's own separator). For that, `Composer` holds a `TextFieldValue` instead of a `String`, still mirroring its text
into `Desktop.setDraft`; a returned draft (`draftReturns`) sets the cursor at its end explicitly.

*Alternative:* insert at the cursor — rejected: a slash command only runs from the start of the reply.

### D5. Marking a slash-command reply

`Replies.command(text)`: the reply text (`written` when the desktop answered, else as typed), without leading white
space, starts with `/` followed by a character that is not white space. `Replies.pastedCommand(text, attachments)`
per D6.

- **Only for a desktop that shows commands as typed.** Before 0.2.15 a slash command's conversation entry was the
  agent's own command markers, so a reply `/x` that ran never matched and was marked not in the conversation; the new
  wording would then claim an unknown command for one that ran. A 0.2.15 desktop sends a `conversation.status` at the
  start of every watch, before its answer (spekterm `remote-agent-status.ts` `watchUsage`, `tick()` at once), and a
  reply is only sent while the watch is `Watching` (`Replies.blocked`), so `c.usage != null` at the send says the
  desktop is 0.2.15 or later. `Replies.sent` records it on the reply as `commandsShown`; it is read at the send, not
  live, because a dropped connection clears `usage` and the mark would flip back.
- `Reply.label` returns a new `Label.COMMAND_NOT_SHOWN` (settled) where it would return `NOT_IN_CONVERSATION_YET` or
  `NOT_SHOWN_AFTER_WORK`, for a reply with `commandsShown` that is a command and not a pasted command. Its text keeps
  what those labels say and adds why a command may never appear: "Not in the conversation — a command the agent's CLI
  does not know, or one that opened something on the desktop, never appears; check the desktop". Every other state,
  the timing and the matching are unchanged (`conversation-reply`).
- The composer, while the draft is a pasted command, shows a note above the field (as the blocked text is shown, not
  blocking): "This long, it will arrive as a plain message, not run as a command". Sending stays allowed: a long plain
  message starting with `/` is legitimate. The note is not limited to 0.2.15: the paste rule is the agent CLI's.

*Alternative:* flag a command not in the skill list before sending — rejected: built-in commands are never listed, and
the list may lag the agent's.

### D6. The paste threshold

Measured by the desktop session with the agent CLI 2.1.304 (and corrected in spekterm's `docs/remote-protocol.md`,
`8d8bfeb`): the limit is the length of the whole written line in characters, not bytes — 800 characters run as a
command, 810 are taken as a paste; 800 CJK characters (2,220 bytes) still run, 850 do not. Newlines neither count
extra nor break the command: multi-line arguments run as one command. So `PASTE_CHARS = 800`, and a command is a
pasted command when the line the desktop will write is **longer than 800** characters (Kotlin `String.length`, UTF-16
units, as the CLI's JavaScript counts; characters outside the BMP were not measured and count as two, erring early).

That line is `Replies.normalized(text)` followed by the attachment sentence, in the desktop's order (spekterm
`remote-conversation.ts` `slashForm`, then the sentence): `Replies.clean` (control characters other than newline
removed, as today), then a JavaScript `trim()` and the white space after the command's name collapsed to one space,
with JavaScript's `\s` set (Kotlin's `\s` lacks U+00A0, U+FEFF and the other Unicode spaces, so the set is spelled
out). The desktop appends the sentence as `\n\n(The user attached 1 file from their phone; read it with the Read tool:
`<path>`)` (spekterm `remote-conversation.ts`): 77 fixed characters, and a path of
`<userData>/remote-attachments/<sessionId>/sent/<id>-<name>` — 124 characters before the name on Linux, 145 on
macOS, and the stored name may gain an extension. The paths are unknown to the phone, so each attachment counts
`ATTACHMENT_CHARS = 250` plus its name's length: above the first one's 201–222 and a further one's separator,
backticks and path, with room for a longer home directory — erring early.

### D7. Logging

`Desktop` logs, under `Kind.CONVERSATION`, `"event" to "status"` with which values are present (names only) and
`"event" to "skills"` with the count or `null` — never the model name, cost, skill names or descriptions. The fields
come from `UsageLine.logFields(usage)` and `SkillList.logCount`, pure and tested.

## Risks / Trade-offs

- [The status line costs a row of a small screen] → one line, only while something is reported, under the top bar
  where the eye already is; the detail is a tap away.
- [Rate limits read as this session's] → the dialog says they are the account's; the line shows them only near full.
- [A removed skill stays listed, and choosing it yields an unknown command] → the same as the agent's own view; the
  reply's mark (D5) says what happened. The list cannot know better.
- [Detecting 0.2.15 by its first status event] → the event is sent at the start of every 0.2.15 watch, even with
  nothing reported; a later desktop that stopped sending it would only lose the more specific mark, never gain a wrong
  one.
- [`TextFieldValue` in the composer touches IME behavior] → the change is mechanical (value + selection); the
  instrumented and emulator checks type, insert, leave and come back, and send, in both languages.
- [The 80% threshold is a judgment call; D6's numbers are the CLI's, measured on one version, plus an estimate for
  attachments] → constants with tests, easy to move when the CLI changes.

## Migration Plan

None: an app update; a desktop before 0.2.15 sends neither event, its slash-command replies keep their marks, and the
screen looks as before. The README's list of what needs which desktop names 6b6ebfa (0.2.15) for these two features,
and its testing section names 0.2.15 as the release with everything.

## Open Questions

None.
