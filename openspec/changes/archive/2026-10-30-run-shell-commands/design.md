## Context

A reply goes through `Composer` (`ui/ConversationScreen.kt`) → `Desktop.send(sessionId, text)` → `conversation.send`.
The desktop (`src/main/remote-conversation.ts`) cleans the text, writes it into the agent's terminal, waits
`SUBMIT_KEY_DELAY_MS` (500 ms), writes `\r`, and answers `{ outcome, text }` with exactly what it wrote. It does
nothing special with a leading `!`. `Replies.blocked` is the reply gate; `Replies.matches` confirms a reply against a
user entry, preferring the desktop's `written` text. The reply draft lives in `Conversation.draft` while the session
is held and in `Desktop.drafts` (a map by session) while it is not. `Composer` is shared by the conversation screen
and the reply bar below the OpenSpec and handoff screens (`reply-from-other-screens` D2).

`ConversationItems.group` turns entries into shown items; `CommandOutput` already shows a built-in command's
`<local-command-stdout>` as output, three lines until expanded (`session-model-effort` D8). A `<bash-input>` entry is
shown today as a plain user message: `AgentMarkdown` puts the HTML tags back as text, so the user sees the markup.

Measured on the dev machine on 2026-10-30, Claude Code 2.1.314, with node-pty writing the text, waiting 500 ms, then
writing `\r` (the desktop's sequence; scripts in the session's scratchpad, results to be copied into
`docs/conversation-findings.md` by task 4.1):

| Sent | Recorded (user entries, neither `isMeta`) |
|---|---|
| `!echo busy-probe` while a 15 s Bash call ran | after the turn ended: `<bash-input>echo busy-probe</bash-input>`, then `<bash-stdout>busy-probe</bash-stdout><bash-stderr></bash-stderr>` |
| `!echo long-probe x…x \| wc -c` (425 chars) | `<bash-input>` with the whole command; output `412` (an 876-char command in the review's run: whole, output `863`) |
| `!echo first-line` `\n` `echo second-line` | `<bash-input>` holding both lines; both lines of output |
| `!   echo spaced-probe   ` | `<bash-input>   echo spaced-probe</bash-input>` — leading spaces kept, trailing dropped |
| `!echo "<a>&b" 1>&2; echo '</bash-input>'` | `<bash-input>` holding the command **unescaped**, `</bash-input>` inside included; stdout `&lt;a&gt;&amp;b\n&lt;/bash-input&gt;`, stderr empty — the output is HTML-escaped and stderr was folded into stdout |
| `!exit 3` | both empty; no exit status recorded |
| `!sleep 35; echo slow-done` while the agent waited (review, `rv.js`) | nothing in the transcript until the command ended (46 s after Enter); then both records at once, the input's `timestamp` the send time. Hooks: no `UserPromptSubmit`, no `PreToolUse` — the desktop reports the agent **ready** the whole time |
| `!seq 1 8000` (review, `big.js`) | stdout is `<persisted-output>\nOutput too large (38KB). Full output saved to: …\n\nPreview (first 2KB):\n1\n2…\n</persisted-output>` — raw `<`, the preview **not** escaped. The CLI does this past `bashOutputMaxChars` (30000) |
| a command exiting non-zero (review, `tty.js`) | `<bash-stdout></bash-stdout><bash-stderr>…</bash-stderr>`, what the command printed to stdout included — stderr folds into stdout only on success. stdin is not a TTY: `read` gets nothing, and text sent while it runs becomes a plain message after it ends |

Read in the CLI 2.1.314 binary (review): the bash handler awaits the command before returning both records; two more
output shapes exist with no stdout tag — `<bash-stderr>Command failed: …</bash-stderr>` and `<bash-stderr>Not run:
this command was sent for a different folder…</bash-stderr>`.

After every command the agent took a turn on its own and commented on the output.

## Goals / Non-Goals

**Goals:**

- Run a command from the phone with nothing on the desktop changed, on any desktop that sends replies.
- Make it hard to run something by mistake: the mode is visible, and it does not outlive one command.
- Show shell-mode records readably, whoever ran them.

**Non-Goals:**

- A shell outside the agent (a separate terminal on the phone). It would bypass the agent's context and open a
  remote shell the pairing was never meant to give.
- Telling which `Bash` calls were refused by reading the result's text: the wording is English and changes between
  CLI versions (`conversation-archive` refuses the same for interrupts). "Run it myself" is offered on every `Bash`
  call instead.
- Showing an exit status: the CLI does not record one. Non-empty error output is the only sign of failure, and is
  shown as one.
- Interactive commands. Shell mode gives the command no TTY and no input, so a login that asks for a password or
  opens a browser (the CLI's own example for `!`, `gcloud auth login`) cannot be finished from the phone — a browser
  would open on the desktop. The command line says it takes no input.
- Allowing a permission prompt for tools the desktop does not allow once (`prompt-answer`): unchanged.

## Decisions

### D1. A command is a reply with `!` in front

`Desktop.runCommand(sessionId, command)` sends `"!" + command` through `conversation.send` with no attachment ids.
The alternative — a new desktop request that writes into the terminal — would need a desktop release and buys
nothing: the CLI's own shell mode is what the user would use on the desktop, and its records are what the agent sees.
The `!` is added at the send, never typed by the user or shown in the field.

The gate is `Replies.blocked` with a `command = true` flag: the same checks in the same order, except that
attachments are not passed (a command never carries them), `ModelEffort.bare` is skipped (a command is not a
`/model`), and the emptiness and length checks run on `"!" + command`'s command part and on the whole frame
respectively. `Desktop.send`'s own re-check uses the same flag. `ModelEffort.typed` is not called for a command, so
`!/model x` never starts a switch.

### D2. A command reply is matched against `<bash-input>`

`Reply` gets `command: Boolean`. `Replies.matches` for a command reply ignores `written` (the desktop wrote `!cmd`,
the CLI records `<bash-input>cmd</bash-input>`) and compares: entry not `meta`, uuid not in `held`, `jsTrim(text)`
starts with `<bash-input>` and ends with `</bash-input>`, and `jsTrim(inner) == jsTrim(clean(command))`. Trimming both
ends covers the measured leading-space case without depending on it. `Reply.text` keeps the command without `!`, so a
refusal returns it to the command draft as typed. The pending row shows `$ command` in monospace instead of the text bubble.

The CLI writes `<bash-input>` only when the command has finished, and fires no hook meanwhile, so the desktop reports
`ready` while it runs (measured, Context). The reply's 30-second limits would call every command longer than 30 s
missing. So for a command reply, `Reply.label` uses `Replies.COMMAND_NOT_SEEN_MS` (10 minutes) for both the
`NOT_IN_CONVERSATION_YET` limit and `inGrace`, and a new unsettled `Label.RUNNING` ("Running on the desktop — shown
when it finishes") in place of `NOT_YET_CONFIRMED`; `WAITING_FOR_AGENT` stays for a command sent while the agent
works, until it stops. `Replies.waited` and the reply bar read the same predicate, so they agree. Ten minutes:
longer than nearly every command run by hand, short enough that a command that never ran is called out the same
session; a command that runs longer is confirmed late, and the settled mark then clears as for a reply.

`commandsShown` (the slash-command line rule) is false for a command reply.

### D3. Command mode and its draft live beside the reply draft

`Conversation` gets `commandMode: Boolean` and `commandDraft: String`; `Desktop.drafts` becomes a map of a small
`Draft(reply, command, commandMode)` so all three survive leaving the session the way the reply draft does today
(kept until the session leaves the list or the phone is unpaired; `clear()` empties it). `keepDraft` drops the entry
only when the reply draft and the command draft are empty **and** the mode is off, so "mode on, empty" survives too.
`Desktop.setCommandDraft`, `Desktop.setCommandMode` mirror `setDraft`.

`Composer` shows `commandDraft` when `commandMode` is on and `draft` otherwise, each with its own `TextFieldValue`,
so switching never moves text between them. Today the reply field re-reads `c.draft` only when the conversation
arrives (`(sessionId, c != null)`) and on `draftReturns`; the command field does the same with `commandDraft` on
arrival and on a new counter `commandReturns`, bumped by `putCommand` (D6) and by "back to the command" (below), so a
command put in while the composer is on screen shows at once. Attachments stay in `Conversation.attachments` untouched and are simply
not shown in command mode.

Alternative considered: one draft whose meaning flips with the mode. Rejected: flipping the mode with a half-written
reply would turn that reply into a command.

The mode ends at the send, not at the desktop's answer, so the next character typed after tapping Run goes into a
reply: `Replies.sent` for a command sets `commandMode = false, commandDraft = ""` and leaves `draft`, `attachments`
and `attachNote` as they are (a reply's `sent` empties them). The refusal branch of `Replies.answered` puts the
command, with the reason, into `Conversation.commandRefused` — **not** into `commandDraft`: the command field
re-reads its draft only on `commandReturns`, so writing the draft under a field the user is typing in would be
overwritten by their next keystroke. The reply draft stays as it is (today's branch prepends a refused reply to
`draft`). Nor does it turn the mode on: the refusal can arrive while the user is already typing a reply, and switching
under their fingers would put that reply into a field whose button runs it. The composer shows a notice "The command
did not run: <reason>" with "Back to the command" (`Desktop.backToCommand`), which puts the refused command before
the current `commandDraft` (joined by a line break), turns the mode on, bumps `commandReturns` and clears
`commandRefused`. Any later send clears it too, as `prefillRefused` is cleared.

`Unknown` changes nothing in the composer: the `$` row carries the label, as a reply's row does. `NotSubmitted` is
different for a command. The desktop withheld the Enter (the state left `ready`/`busy` within the 500 ms), so `!cmd`
sits in the CLI's input; the next `conversation.send` would append to it and press Enter, running `cmd` followed by
whatever was sent — a spliced shell command with no prompt. For a reply this only glues two texts; for a command it is
unsafe. So while a command reply in state `NOT_SUBMITTED` exists for the session, `Replies.blocked` returns a new
`Blocked.CommandWaits(command)` for every send, reply or command, ahead of the other checks after
`paused`/`connected`. The composer says "`$ cmd` waits in the agent's input on the desktop. Anything sent now would
run with it — clear it there first." with "I cleared it" (`Desktop.clearedWithheld`), which drops that reply's
blocking (`Reply.cleared = true`; the row keeps its "not submitted" label).

### D4. The command field's look

- One button left of the field, in place of the attach button (kewang, 2026-10-30, after using a first build with two
  buttons on the Pixel: the field had become too narrow and the Chinese hint wrapped to two lines). In reply mode it is
  `+`, "Attach or run a command", and opens a menu: Photo and File when attaching is offered, then Command mode. In
  command mode it is the terminal icon (`>_`) in the tertiary color, "Back to a reply", and one tap turns the mode off.
  Its state (on/off) is said to a screen reader. Alternatives shown to kewang: two smaller buttons (little width
  gained), the switch inside the field (in between); he chose the menu.
- Hint: one line, ellipsized; the skills hint shortened ("Reply · / for skills", 「傳訊息 · 打 / 選 skill」) so it
  fits beside one button.
- Field: monospace text, a leading `$`, container `tertiaryContainer` instead of `surfaceContainerHigh`, hint
  "Command to run". Up to 6 lines as a reply.
- Line above the field (always while in command mode): "Runs in the agent's terminal on the desktop, without asking
  permission, and takes no input. The agent sees the output." While the agent works, it reads instead: "Runs when the
  agent finishes its turn, without asking permission, and takes no input. The agent sees the output."
- Button: `FilledIconButton` with a play icon, content description "Run".
- Slash panel: off (`slash` false in command mode); the long-slash-command note (`Replies.pastedCommand`) off. Blocked
  reasons: the same texts as replies.

### D5. Showing shell-mode records

Two shown items in `ConversationItems`:

- `ShellCommand(key, command, entry)`: entry is `Entry.User`, not `meta`, `origin == null`, `jsTrim(text)` starts with
  `<bash-input>` and ends with `</bash-input>`; `command` is what is between, untouched. No "no other marker" rule
  (unlike D8's `wrapped`): the command is not escaped and may hold the markers (measured).
- `ShellOutput(key, stdout, stderr, persisted, entry)`: same entry conditions, and `t = jsTrim(text)` is one of
  - starts with `<bash-stdout>`, and the last `</bash-stdout>` is followed by exactly `<bash-stderr>` … `</bash-stderr>`
    to the end: stdout is between `<bash-stdout>` and that last `</bash-stdout>` (the CLI's own renderer cuts so;
    stdout may hold a raw `<` in the persisted form), stderr between the last two markers;
  - `<bash-stderr>` … `</bash-stderr>` alone: stdout empty.

  A stdout that, trimmed, starts with `<persisted-output>` and ends with `</persisted-output>` is unwrapped, **not**
  unescaped (the CLI keeps it raw), and `persisted = true`. Otherwise each part is unescaped (`&lt; &gt; &quot; &#39;
  &amp;`, `&amp;` last). Both are stripped of escape sequences with `CommandOutput`'s regex, then `jsTrim`.

Both end a run (`close()`), take anchors, count for their day and show no time, as `CommandOutput` does — added to
the same `when` branches (`withAnchor`, `viewerSources`, `ConversationTimes`). `ShellCommand` is drawn as a
right-aligned user row holding `$ command` in monospace under the label "Command you ran"; `ShellOutput` reuses
`CommandOutputRow` with the label "Output" — "Failed" when stderr is not empty, the stderr lines in the error color —
and, when `persisted`, a line "Full output saved on the desktop"; both empty → "No output".

### D6. "Run it myself" from a `Bash` call

`ToolParts` takes an optional `onRunCommand: ((String) -> Unit)?`, passed by the caller only when `tool.name ==
"Bash"` (`Monitor` also makes a `COMMAND` part, and `ToolParts` does not know the tool). Under that `COMMAND` part a
`TextButton` "Run it myself" sits — only when the part's `cut == null`: the desktop cuts a tool input past 4000
characters per string (`transcript-tool-input.ts` `STRING_LIMIT`), and running a cut script would run another command
with no prompt. A cut command shows its cut line, as today, and no button. The conversation screen passes
`{ Desktop.putCommand(sessionId, it); focus the field }` while the composer's gate is not "session closed / not an
agent"; elsewhere (the viewer, a read-only state) it passes `null` and nothing is shown. `Desktop.putCommand` sets
`commandDraft = command, commandMode = true`, clears `commandRefused` and bumps `commandReturns`; the composer reloads
the field and requests focus on that bump (D3), so no separate focus counter is needed.

### D7. A tappable `! command` code span in the agent's text

`AgentMarkdown` gets an optional `onCommand: ((String) -> Unit)?`; the conversation passes it only for the agent's
own text entries (`Entry.Text` from the assistant), not for user, peer or task-notice entries. In `safeAnnotator` a
`CODE_SPAN` whose content — without its backticks (`getTextInNode` includes them) — `jsTrim`med, matches
`^!\s+(\S[\s\S]*)$` is appended as the renderer does a code span (`pushStyle(getCodeSpanStyle(typography))`, a
space each side, `pop`) plus a
`LinkAnnotation.Clickable("command")` whose listener calls `onCommand(group1.trim())`, and an underline so it reads
as tappable. Code blocks (`CODE_FENCE`, `CODE_BLOCK`) are left alone: they are often long scripts the agent is
showing, not asking the user to run. Without `onCommand` the annotator behaves exactly as today. The annotator's
`remember` is keyed on the typography (light and dark differ) and `onCommand`; today it has no key.

`!gcloud auth login` without a space is not taken: the CLI's own prompt tells agents to write `! <command>`, and
requiring the space keeps `!important` and the like inert. A trade-off, not a gap.

Risk to check in 2.4: the renderer's own code-span style must be reproduced when the annotator takes over the node;
if that proves brittle, fall back to a long-press on the agent text entry offering "Run `cmd` myself" for each
`! command` span found — same spec behavior.

## Risks / Trade-offs

- [A command runs with no permission prompt] → that is the point, and the user typed or chose it; the field is set
  apart by `$`, font, color, hint, the line and the button, and the mode ends with each command, so a later reply is
  never run.
- [The agent takes a turn after every command (measured)] → said in the line ("the agent sees the output"); it costs
  one short turn and keeps the agent informed, which is what the user wants after running a blocked command.
- [A later CLI changes the `<bash-input>` / `<bash-stdout>` format] → the records then show as plain user messages
  (today's behavior) and a command reply is marked "not in the conversation yet" after 10 minutes, pointing to the
  desktop; nothing is lost. The format is pinned by unit tests built from the measured records.
- [A command that never ran is called out only after 10 minutes] → the desktop's own answer still marks one it
  withheld or refused at once; only "accepted but never recorded" waits.
- [The desktop arms its phone-origin note before the Enter (`remote-conversation.ts` `armOrigin`), and a `!` command
  fires no `UserPromptSubmit` to take it] → the note stays armed until its 10-second TTL, so a prompt typed on the
  desktop within 10 s would be marked as sent from the phone. Accepted: rare and harmless; a desktop follow-up (do not
  arm for a leading `!`) can be raised separately.
- [A command sent while the agent works runs only at the turn's end] → said on the line before sending; the pending
  row carries the "waiting for the agent to take it" label as a reply does.
- [Shared composer in the reply bar] → the button and command mode work there too (same `Composer`); "Run it myself"
  is only on the conversation screen, where the tool calls are.
