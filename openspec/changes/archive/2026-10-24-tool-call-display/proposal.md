## Why

On the phone, most tool calls say nothing useful. The desktop sends a call as its name and one identifying argument
(`{ kind: "tool", name, arg }`), and the app shows that argument and the raw result text, in two grey boxes that look
the same. kewang reported, with screenshots of a real session (2026-10-24):

- A Bash call's command and its output sit in two boxes of the same style, one under the other: which is the
  command and which the output is not clear at a glance.
- `SendMessage` shows only its name, and its result is a JSON string. spekterm's handoffs and its sessions' messages
  to each other run through it, and the phone cannot say to whom a message went or what it said. A message another
  session sent in is shown as "injected", without saying who sent it.
- `Edit` and `Write` show the path only: not what changed.
- `Agent` shows a description only, `Skill` nothing at all.
- `SendUserFile` — the agent sending the user a file — shows its name, and the user has no way to see the file on the
  phone.

Two weeks of kewang's transcripts (2026-10-10 to 10-24, as spekterm's `remote-tool-detail` design measured them):
Bash 28.8k calls, Write 1.5k, Edit 1.4k, Read 1.0k, SendMessage 0.9k, Agent 0.5k, ToolSearch 0.5k, Skill 260,
Artifact 180, AskUserQuestion 140, TaskStop 130, WebFetch, WebSearch and Monitor about 50 each, and SendUserFile 17
(19 files: Markdown 7, JPEG 5, video 5, an APK, a zip); this change's reviewer counted about 2,000 MCP calls. Every Agent call's result is the
agent CLI's "launched" notice; the subagent's report arrives later as a message of its own.

kewang also asked, the same day, that skills come from typing `/` in the reply field instead of from a button: the
skills button narrows an already small field.

The phone cannot fix most of this alone: it does not receive the fields. spekterm's change `remote-tool-detail`
(handed off 2026-10-24) adds them: a tool call's input fields white-listed per tool, where a message came from, the
messages and notices that arrive while the agent works (dropped today), and `files.read`, which reads a file the agent
sent the user in chunks. This change is the phone's side.

## What Changes

- **Bash**: the command and the output are told apart: the command under a "Command" label, set apart in its own
  style; the output under an "Output" label (or "Error" when the call failed). The line in the list keeps the
  command; with a `description`, that description leads instead.
- **SendMessage**: the line says "→ recipient", a spekterm session named by its label as spekterm shows it (e.g.
  "自動更新功能", not `spekterm-e8d8`), and the message's summary or first line; expanded, the whole message, and the result as "Sent" / "Not sent" rather than its JSON. A recipient given as a socket address
  or an agent id reads "another session" / "a subagent".
- **A message from another session** is labeled with who sent it (the session's label, as above), and **a subagent's report** with the subagent;
  expanded, both read as formatted text. A background task's notice is labeled as such. All stay context, not the
  user's words. A peer message ends a run of tool calls so it is never hidden in one; a notice arriving mid-run joins
  the run.
- **Edit**: the line names the file; expanded, the replaced text and the new text, marked as removed and added lines.
  **Write**: the file and its content. **Read**: the file and the lines read.
- **Agent / Task**: the line shows the subagent's kind and description; expanded, its prompt. The CLI's "launched"
  notice is not shown as a report.
- **Skill**: the skill's name and arguments, as the line typed (`/name args`).
- **SendUserFile**: the caption and one row per file, with its name. Tapping a file opens it over the conversation: images,
  Markdown (rendered as the repository's Markdown is, loading nothing remote) and plain text, read from the desktop
  in chunks. Other kinds (video, archives, PDF) are handed to another app on the phone ("Open with…"),
  through a copy in the app's private cache deleted when the conversation is left; that copy is the only thing
  written to the phone's storage. Nothing is written to the phone's
  storage; closing the file returns to the conversation as it was.
- **Others with a known shape** (AskUserQuestion, Grep, Glob, ToolSearch, WebSearch, WebFetch, Monitor, TaskStop,
  ScheduleWakeup, CronCreate, Artifact, ExitPlanMode): a line made of their meaningful fields; an empty result is
  not shown as an empty box.
- **Any other tool** (MCP tools included — the desktop sends them no fields): as today, name and argument; an
  `mcp__server__tool` name reads `server · tool`.
- **Skills from `/`**: typing `/` at the start of the reply field shows the session's skills in a list above the
  field, filtered as the name is typed; choosing one fills in `/name `. The skills button and its sheet are removed,
  giving the field its room back; the field's hint says `/` offers the skills.
- Text the desktop cut short says so. Against a desktop that sends no input fields, every call is shown as today.

## Capabilities

### New Capabilities

- `tool-call-display`: how a tool call and its result are shown, per tool, from the fields the desktop sends; how a
  message's sender is shown.
- `sent-files`: the files the agent sent the user, listed on its call and opened on the phone.

### Modified Capabilities

- `skill-commands`: "Choose a skill to start a reply" — the list opens from a typed `/` above the reply field, not
  from a button and a sheet.

- `conversation-view`: "Show the conversation of an agent session" — a tool call is shown by its kind
  (`tool-call-display`), not only by name and one argument; an injected message says who sent it; background-task
  notices join runs and messages from other sessions end them. "Agent text loads no remote content" — the texts
  the body of a message received from another session or a subagent's report is formatted under agent text's rules
  instead of plain.

## Impact

- `net/Sessions.kt` (`Entry.Tool`, `Entry.User`, `Session.peerName` decoding), `desktop/ConversationItems.kt` (a call's summary line),
  `ui/ConversationScreen.kt` (`CallLine`, `RunItem`, `ResultLine`, `PlainRow`), new `desktop/ToolView.kt`,
  `desktop/EditDiff.kt`, `desktop/PeerText.kt` and `ui/ToolViews.kt`, a sent-file
  viewer dialog, new `desktop/SentFiles.kt`, `desktop/Desktop.kt` (the chunked read), `net/Frames.kt` (`Operations`),
  `log/ConnectionLog.kt` (a `FILES` kind), `res/xml/file_paths.xml` (a `sent/` cache path), `ui/UsageAndSkills.kt` (the sheet removed), new `desktop/SlashQuery.kt`, `ui/Icons.kt`, strings in `values` and
  `values-zh-rTW`, `README.md`.
- Depends on spekterm's `remote-tool-detail` (release to be named there). Apply waits for its final protocol shape;
  design D1 is reconciled with it first.
