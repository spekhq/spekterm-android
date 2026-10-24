## ADDED Requirements

### Requirement: A tool call is shown by what it did

Each tool call SHALL be shown with a one-line summary chosen by its tool from the input fields the desktop sends, and,
when expanded, with the parts that tool's call is made of. At least these tools SHALL have their own summary and
expanded view:

- Bash: the summary is the call's description when it has one, else its command; expanded, the command and the output.
- SendMessage: the summary names the recipient — by the session's name as spekterm shows it when the recipient is a
  session in spekterm's list — and the message's summary or first line; expanded, the whole message,
  and whether it was sent, stated in words rather than as the desktop's raw result. A recipient that is not a name
  (a socket address or an agent id) SHALL be shown as "another session" or "a subagent", never as the address.
- Edit: the summary names the file; expanded, the file's path and the replaced text against the new text, the
  removed lines and the added lines marked as such.
- Write: the summary names the file; expanded, its path and the content written.
- Read: the summary names the file and, when given, the lines read.
- Agent (and Task): the summary names the subagent's kind and its description; expanded, the prompt it was given.
  The agent CLI's notice that it started the subagent SHALL NOT be presented as output; any other result SHALL be
  shown.
- Skill: the summary is the skill as it would be typed, `/name` and its arguments.
- SendUserFile: as `sent-files` says.
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

#### Scenario: A message to another session

- **WHEN** the agent sent the spekterm session labeled "自動更新功能", whose messaging name is `spekterm-e8d8`, the
  message "Tell e8d8 auto-update is archived"
- **THEN** the call's line shows `→ 自動更新功能` and that text, not `spekterm-e8d8`; expanded, it shows the message and that it was
  sent, and no JSON

#### Scenario: A message addressed by socket

- **WHEN** the agent replied to a session whose address is a socket path
- **THEN** the call's line says the message went to another session, and the path is shown nowhere

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

### Requirement: Say who sent an injected message

A message the agent received that the user did not type SHALL say where it came from when the desktop says so: a
message from another session SHALL name that session — by its name as spekterm shows it when the session is in
spekterm's list, else by the name given — else say it came from another session;
a subagent's report SHALL say it is a subagent's report, naming the subagent when its name is given; a background
task's notice SHALL say it is one. Any other injected message SHALL be marked as injected, as before. These messages
SHALL stay collapsed until expanded and SHALL be shown as context, not as the user's words. The wrapper the agent CLI
puts around a message from another session or a subagent's report SHALL NOT be shown: neither the sender's address
nor its internal id SHALL appear; text the app does not recognize as such a wrapper SHALL be shown as it is.

#### Scenario: A message from another session

- **WHEN** the spekterm session labeled "自動更新功能" (messaging name `spekterm-e8d8`) sent this session a message
- **THEN** the conversation shows a collapsed item saying it is from "自動更新功能"; expanded, it shows the message,
  and no socket address

#### Scenario: A message from outside spekterm

- **WHEN** a Claude session not in spekterm's list, named `api-server-8393`, sent this session a message
- **THEN** the collapsed item says it is from `api-server-8393`

#### Scenario: A subagent reports back

- **WHEN** an `openspec-change-reviewer` subagent returned its report
- **THEN** the conversation shows a collapsed item saying it is a report from `openspec-change-reviewer`; expanded,
  the report reads as formatted text, its first heading shown as a heading, and the subagent's internal id is shown
  nowhere

#### Scenario: A background task finished

- **WHEN** the agent received a notice that a background command completed
- **THEN** the conversation shows a collapsed item saying it is a background task notice
