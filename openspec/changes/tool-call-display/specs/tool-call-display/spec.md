## ADDED Requirements

### Requirement: A tool call is shown by what it did

Each tool call SHALL be shown with a one-line summary chosen by its tool from the input fields the desktop sends, and,
when expanded, with the parts that tool's call is made of. At least these tools SHALL have their own summary and
expanded view:

- Bash: the summary is the call's description when it has one, else its command; expanded, the command and the output.
- SendMessage: shown as a message (below), not as a call. A recipient that is not a name (a socket address or an
  agent id) SHALL be shown as "another session" or "a subagent", never as the address.
- Edit: the summary names the file; expanded, the file's path and the replaced text against the new text, the
  removed lines and the added lines marked as such.
- Write: the summary names the file; expanded, its path and the content written.
- Read: the summary names the file and, when given, the lines read.
- Agent (and Task): the summary names the subagent's kind and its description; expanded, the prompt it was given.
  The agent CLI's notice that it started the subagent SHALL NOT be presented as output; any other result SHALL be
  shown.
- Skill: the summary is the skill as it would be typed, `/name` and its arguments.
- SendUserFile: shown as a message holding the files (below, and `sent-files`), not as a call.
- AskUserQuestion, Grep, Glob, ToolSearch, WebSearch, WebFetch, Monitor, TaskStop, ScheduleWakeup, CronCreate,
  Artifact, ExitPlanMode: a summary from their meaningful field (the question, the pattern, the query, the address,
  the description, the task, the reason, the schedule, the action, the plan).

Any other tool, and any call the desktop sends without input fields, SHALL be shown with its name and the
identifying argument the desktop sends, as before this change; nothing shown before SHALL be missing. A tool whose
name has the form `mcp__server__tool` SHALL be named by its server and tool. A field missing from a call's input SHALL
NOT drop the call: its summary falls back to the identifying argument. A result with no text SHALL NOT be shown as an
empty part; a failed call's result SHALL always be shown.

A run of tool calls SHALL show the latest call's summary line.

Every part of a call — its input fields (a message sent, a subagent's prompt, a plan, a command, file content),
its results, field values — SHALL be shown as plain text.

#### Scenario: A recipient by socket

- **WHEN** the agent replied to a session whose address is a socket path
- **THEN** its message says it went to another session, and the path is shown nowhere

#### Scenario: An edit

- **WHEN** the agent edited `memory/mac-update-check-pending.md`, replacing one line with two
- **THEN** the call's line names `mac-update-check-pending.md`; expanded, it shows the old line marked removed and the
  two new lines marked added

#### Scenario: A skill

- **WHEN** the agent invoked the skill `openspec-apply-change` with the arguments `inbox`
- **THEN** the call's line shows `/openspec-apply-change inbox`

#### Scenario: A subagent

- **WHEN** the agent started an `openspec-change-reviewer` subagent described as "Review inbox change artifacts"
- **THEN** the call's line shows the kind and the description; expanded, it shows the prompt, and not the agent's
  notice that the subagent started

#### Scenario: An older desktop

- **WHEN** the desktop sends tool calls without input fields
- **THEN** every call shows its name and identifying argument, as before this change

#### Scenario: A tool search

- **WHEN** the user expands a ToolSearch call whose result has no text
- **THEN** the call shows its query and no empty output

### Requirement: What was asked is told apart from what came back

In an expanded tool call, each part SHALL carry a label saying what it is — such as Command, Output, Error, Message,
Prompt, Change, Content — and the parts the agent supplied SHALL look different from the parts the tool returned, in
a way that does not rely on color alone. A screen reader SHALL announce each part's label before its text, once. A
failed call's returned part SHALL be labeled as an error.

#### Scenario: A command and its output

- **WHEN** the user expands a Bash call whose command printed a listing
- **THEN** the command is shown under a "Command" label, set apart from the listing, which is shown under an
  "Output" label

#### Scenario: A failed command

- **WHEN** the user expands a Bash call that failed
- **THEN** its output is labeled as an error

### Requirement: Text cut short says so

When the desktop cut a field of a tool call short, the expanded call SHALL say that the text was cut short on the
desktop, and an Edit's changes SHALL NOT be presented as complete.

#### Scenario: A long file written

- **WHEN** the agent wrote a file whose content the desktop cut short
- **THEN** the expanded call shows the content it received and says the rest was cut short on the desktop

### Requirement: Show what others sent as messages

Whatever reaches the conversation that the user did not type themselves and that is addressed between parties SHALL
be shown as a chat message, not as a tool call or a collapsed line: a message from another session, a subagent's
report, a message the session's agent sent with SendMessage, and the files it sent the user with SendUserFile. All
four SHALL look the same: an avatar and a line naming who sent it — and, for a message the agent sent, to whom — above
a bubble holding its content, with the time below. They SHALL be shown on the side that is not the user's own
messages. A sender or recipient that is a session in spekterm's list SHALL be named by its name as spekterm shows it,
else by the name given; the session's own agent SHALL be named by the session's name; a sender with no name SHALL be
called another session, a subagent, or another agent. A message's content SHALL be shown without the user expanding
it; one longer than a few lines SHALL show its beginning with a way to show all of it. A message the agent sent SHALL
say whether it was sent, and why not when it was not. The wrapper the agent CLI puts around a message from another
session or a subagent's report SHALL NOT be shown: neither the sender's address nor its internal id SHALL appear;
text the app does not recognize as such a wrapper SHALL be shown as it is. A message SHALL end a run of tool calls,
and SHALL NOT also be shown as a call inside one. A background task's notice SHALL be shown as one short line saying
what finished, not as a message. Any other injected message SHALL be marked as injected and collapsed, as before.
Against a desktop that sends no input fields, the agent's SendMessage and SendUserFile calls SHALL be shown as tool
calls, as before.

#### Scenario: A message from another session

- **WHEN** the spekterm session labeled "自動更新功能" (messaging name `spekterm-e8d8`) sent this session a message
- **THEN** the conversation shows a message from "自動更新功能" with its text, without being expanded, and no socket
  address

#### Scenario: A message from outside spekterm

- **WHEN** a Claude session not in spekterm's list, named `api-server-8393`, sent this session a message
- **THEN** the message is shown as from `api-server-8393`

#### Scenario: The agent sends a message

- **WHEN** the agent of the session labeled "A" sent "測試訊息" to the session labeled "測試收件", and it was delivered
- **THEN** the conversation shows a message from "A" to "測試收件" holding "測試訊息", saying it was sent, in the same
  style as a message received, and no SendMessage tool call

#### Scenario: A message that was not sent

- **WHEN** the agent's SendMessage answered that there is no such session
- **THEN** the message says it was not sent, and why

#### Scenario: A subagent reports back

- **WHEN** an `openspec-change-reviewer` subagent returned a long report
- **THEN** the conversation shows a message from `openspec-change-reviewer` whose beginning reads as formatted text,
  its first heading shown as a heading, with a way to show all of it, and the subagent's internal id is shown nowhere

#### Scenario: A background task finished

- **WHEN** the agent received a notice that a background command completed
- **THEN** the conversation shows one short line saying what completed
