## Why

When a command is blocked by the permission rules, or the agent asks the user to run one themselves (`! gcloud auth
login` — though that one is interactive, see below), or the user simply wants to run something in the session's directory, the phone has no way to do it: the
user has to walk back to the desktop and type `!` in the agent's terminal. kewang asked (2026-10-30, from the phone)
for a way to type commands from the phone, and chose a command-mode switch next to the reply field plus one-tap
"run it myself" from the conversation, over a typed `!` prefix (hard to type on a phone keyboard, and nothing shows
the feature exists).

Measured on the dev machine (Claude Code 2.1.314, written the way the desktop writes a reply — the text, 500 ms, then
Enter): a reply that begins with `!` enters the agent CLI's shell mode and runs, also when long (425 characters) or
several lines; sent while the agent works, it waits and runs when the turn ends. So the desktop needs no change. The
CLI records a command only when it ends, and gives it no TTY or input: a command that asks for a password or opens a
browser cannot be finished from the phone.

## What Changes

- **Command mode** in the composer: a switch beside the reply field turns it into a command field — set apart in
  look (monospace, a `$`, its own color), saying the command runs in the agent's terminal on the desktop without a
  permission prompt and takes no input, and that the agent sees its output, with a "Run" button instead of "Send". Attachments and the
  slash skills are not offered in it. The command has its own draft, kept per session like the reply's, so turning
  the mode on and off never loses either text. After a command is sent the field goes back to reply mode, so the next
  message is never run by mistake.
- Sending a command writes `!` and the command through the existing `conversation.send`, under the same conditions a
  reply is sent (connected, watched, the agent waiting or working, no choice open, no inbox prompt, no model switch
  holding). A command sent while the agent works says it runs once the agent's turn ends.
- A sent command shows at the end of the conversation as `$ command`, unconfirmed until the agent CLI records it,
  labeled as running on the desktop until it finishes (the CLI records it only then), and called missing only after
  10 minutes instead of a reply's 30 seconds.
- **Run it myself**: the expanded view of a `Bash` tool call offers a button that puts its command into the command
  field (mode on, keyboard up), not sent — not for a command the desktop cut short; and an inline code span in the agent's text that reads `! command` can be
  tapped to do the same.
- The conversation shows the CLI's shell-mode records — whoever ran the command, phone or desktop — as what they
  are: the command the user ran (`$ command`), and its output under a label (failed when it has error output; a note
  when the CLI saved a too-large output on the desktop), first three lines until expanded,
  instead of the raw `<bash-input>` / `<bash-stdout>` markup shown today.

## Capabilities

### New Capabilities

- `shell-commands`: command mode in the composer, sending a command, its confirmation and labels, and putting a
  command into the field from a `Bash` call or from the agent's text.

### Modified Capabilities

- `conversation-view`: the agent CLI's shell-mode records are shown as the command the user ran and its output.

## Impact

- `desktop/Conversation.kt` / `desktop/Desktop.kt`: per-session command mode and command draft; a send path for a
  command (no attachments, `!` prefixed); a reply marked as a command.
- `desktop/Replies.kt`: the gate for a command; matching a command reply against a `<bash-input>` entry.
- `desktop/ConversationItems.kt`, `desktop/ConversationTimes.kt`: two new shown items for a shell command and its
  output, counted for their day.
- `ui/ConversationScreen.kt`: the mode switch, the command field and its line, the "Run" button; the pending command
  row; the two new items.
- `ui/ToolViews.kt`: "Run it myself" under a `Bash` call's command.
- `ui/AgentMarkdown.kt` / `ui/RepositoryMarkdown.kt`: a tappable `! command` code span in the agent's text.
- Strings in `values` and `values-zh-rTW`; `README.md`; `docs/conversation-findings.md` (the measurements and the
  checks on the emulator and the Pixel).
- Protocol and desktop: nothing new.
