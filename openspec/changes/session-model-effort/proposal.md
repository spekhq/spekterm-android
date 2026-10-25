## Why

From the phone the user can see which model and effort a session runs (`agent-usage`), but cannot change either
without typing `/model <name>` or `/effort <level>` exactly — and a typed `/model` mid-conversation is a trap: measured
with the agent CLI 2.1.310, while the prompt cache is warm (within an hour of the last request on a subscription) it
opens a "Switch model?" confirmation in the desktop's terminal that the desktop does not report, the phone cannot see
or answer, and that the next reply's submit key silently confirms while its text is lost. kewang asked for a quick
place in the session to change both.

## What Changes

- Tapping the usage line opens a "Model and effort" sheet instead of the details dialog: what the agent last
  reported, the effort levels and the models to choose from, notes on what a choice changes, and below them every
  reported value as the dialog showed it. The conversation's overflow menu offers the same sheet, so a session that
  has reported nothing yet can be switched too.
- Choosing an effort level sends `/effort <level>` (`conversation.send`), which a desktop of 0.2.15 or later already
  runs: no confirmation on the current models, applied at once even while the agent works.
- Choosing a model sends it with a new desktop operation, `conversation.setModel` — drafted by spekterm's change
  `remote-model-switch` (2026-10-25) — which writes `/model <name>` and lets the desktop's own hook approve that one
  switch, so no confirmation is left open in the terminal. The models are offered only when the desktop lists the
  operation; otherwise the sheet says a newer desktop is needed. Besides the CLI's aliases (default, opus, fable,
  sonnet, haiku), any model name in the desktop's charset can be typed. The current ones are marked as a hint only;
  every choice sends.
- A switch is not a reply: the CLI does not record a command it runs while the agent works, so a switch is confirmed
  by the usage the agent reports, shown as one line above the composer while it is changing, and as a note when it
  was refused, left unsent in the agent's input, or not seen within 10 s. It never takes or returns the composer's
  draft or attachments, and it survives leaving the screen.
- Because the desktop cannot see the confirmation and its hook may not run for every session, a model switch — from
  the sheet or a typed `/model <name>` — holds replies, prompt answers and further switches until the reported model
  changes, from the moment it is sent; after 10 s it says the desktop may be asking to confirm and offers to continue anyway. A bare `/model` or
  `/effort`, which opens a picker the phone cannot close, cannot be sent from the reply field.
- The sheet says what the CLI does beyond this session: the choice also becomes Claude Code's default for new
  sessions on that computer (except `max`, which is this session's only, and `auto`, which clears it), and a model
  switch makes the next response re-read the whole conversation — the cost warning the skipped confirmation would
  have shown.
- The CLI's answer to a built-in command, which the desktop forwards as a user entry wrapped in
  `<local-command-stdout>`, is shown as that command's output — without its markers and terminal escape codes —
  instead of a message from the user; the CLI's fixed caveat before each command is not shown.

## Capabilities

### New Capabilities

- `model-effort-switch`: changing the watched session's model and effort from the phone — where it is offered, what
  can be chosen, how it is sent and confirmed, what it holds, and what the user is told it changes.

### Modified Capabilities

- `agent-usage`: tapping the usage line opens the model-and-effort sheet, which holds the details the dialog showed.
- `conversation-reply`: a model switch holds replies; a bare `/model` or `/effort` cannot be sent; a typed `/model`
  or `/effort` with an argument is also confirmed by the agent's reports.
- `skill-commands`: that exception to how a slash-command reply is confirmed.
- `prompt-answer`: a model switch holds answers.
- `conversation-view`: a built-in command's output is shown as the command's output; the CLI's command caveat is not
  shown.
- `tool-call-display`: the command caveat is the one injected message not shown.

## Impact

- `net/Frames.kt`: `Operations.SET_MODEL` and the `conversation.setModel` frame (next to `tool-result-content`'s
  `IMAGES_READ`, on master since 6d8d298).
- `desktop`: a new pure `ModelEffort` (choices, marks, the command line, the name check, confirmation); a per-session
  `PendingSwitch` in `Desktop` with `Desktop.switch`; `Replies.blocked` learns the hold and the bare command;
  `Answers.offer` learns the hold; `ConversationItems` recognizes command output and the caveat.
- `ui`: the sheet replaces `UsageDialog` in `UsageAndSkills.kt`; `SessionMenu.kt` takes screen-only items;
  `ConversationScreen.kt` opens the sheet from the usage line and the menu and shows the switch line, the hold and the
  command output; `PromptCard.kt` shows the hold; strings in `values` and `values-zh-rTW`.
- Protocol: version 1. Effort needs nothing new (0.2.15's usage event); model needs `conversation.setModel` in
  `operations` (never gated by version), from the spekterm release that ships it.
- `README.md`: what the conversation offers and which desktop it needs.
- Depends on spekterm's `remote-model-switch` for the model half, whose hook is measured first; if it is withdrawn,
  the model half is dropped and the effort half and the command output ship on their own.
