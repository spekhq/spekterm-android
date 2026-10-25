# tool-call-display Specification

## Purpose
How the phone shows an agent's tool calls by what each did, told apart from what came back, and what is
exchanged between parties — messages from other sessions, subagents' reports, the agent's own messages and the files
it sends — as chat messages from their sender.

## Requirements

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
- Read: the summary names the file and, when given, the lines read; expanded, the file's path and, when the result
  is the agent CLI's numbered lines, the content under a "Content" label with each line's number beside it and not
  part of the text when copied. A note the agent CLI put before the lines for the model (such as a memory file's age)
  or after them (such as which artifact the file is the source of) SHALL be shown apart from the content, muted. A
  result of any other shape SHALL be shown as it came, as "Output"; a blank result beside pictures SHALL show no part.
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

#### Scenario: A text file read

- **WHEN** the user expands a Read of `proposal.md` whose result is the CLI's numbered lines `1\t## Why`, `2\t`,
  `3\tFrom the phone…`
- **THEN** the call shows the path and, under "Content", the three lines with the numbers 1–3 beside them, and copying
  the text gives `## Why` and the other lines without a number

#### Scenario: A memory file read

- **WHEN** the user expands a Read whose result starts with the CLI's note that the memory is 3 days old, followed by
  numbered lines
- **THEN** the note is shown muted above the content, and the content is shown with its numbers apart

#### Scenario: A read result of another shape

- **WHEN** the user expands a Read whose result is not numbered lines, such as the text the CLI gives for a PDF
- **THEN** it is shown as it came, under "Output"

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
what finished, not as a message. The note the agent CLI adds to the conversation after a picture it read, telling the model the picture's original
and shown sizes (`[Image: original 1080x2400, displayed at 900x2000. Multiply coordinates by 1.20 to map to original
image.]`), SHALL NOT be shown, and SHALL NOT end a run of tool calls. Any other injected message SHALL be marked as
injected and collapsed, as before.
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

#### Scenario: The CLI's note after a picture

- **WHEN** the agent read a screenshot, the CLI added its note about the picture's sizes, and the agent then ran two
  more commands
- **THEN** the conversation shows one run of three tool calls, and the note is shown nowhere

#### Scenario: A reply sent right after the note

- **WHEN** the user sent a reply while the CLI's picture note was the last entry, and the agent then made two more
  calls
- **THEN** the reply is shown after the call that read the picture and before the two calls, as any reply is

#### Scenario: Another injected message

- **WHEN** a meta message reads "[Image: something else]"
- **THEN** it is shown marked as injected and collapsed, as before

### Requirement: Pictures a tool brought back are seen without expanding

When the desktop says which pictures a tool call's result held and offers reading them, the conversation SHALL show
them without the user expanding anything: under a lone call's line, or a lone result's; for a collapsed run of calls,
one row under the run's line of as many of its pictures as fit, at most three, the latest calls' first, with how many
more there are, which expands the run when tapped; for an expanded run, under the line of the call that brought them
back. This SHALL hold for every tool, not only Read. A picture SHALL be read from the desktop by the call and its
position, never by a path, and only when shown, when next to the item shown full screen, or when the run holding it
is expanded. Pictures up to 2 MB SHALL be read by themselves; a larger one SHALL show
its size and be read on a tap, or once it is the picture shown full screen; one that could not be read SHALL say why, in words about a picture, not a sent file.
Tapping a picture SHALL open it full screen, zoomable by pinching or by a double tap (a double tap again going back), titled by its file name for a Read and otherwise by its tool
call and position. Full screen, swiping left or right SHALL show the conversation's other tool-result pictures and
the files the agent sent, in conversation order, with the current one's place among them, except while a picture is
zoomed in, when a drag SHALL move the picture. A sent file the app does not show itself SHALL appear there by its name
and kind with a way to open it in another app, read only when that is chosen. A picture SHALL be decoded
only as PNG, JPEG, GIF or WebP, and SHALL NOT be handed to another app. Pictures SHALL be kept in memory only, for as long as the conversation screen is open; of those not on screen, at most a
bounded number SHALL be kept, and one dropped SHALL be read again when shown again. A failed call SHALL show no pictures. Each
picture SHALL be announced to a screen reader as a picture, by the same title.

Against a desktop that does not offer reading a result's pictures, no picture SHALL be shown, and a Read of a file named
as a picture whose result is empty and not an error SHALL say, when expanded, that seeing it on the phone needs a newer
spekterm; everything shown before SHALL still be shown.

#### Scenario: A screenshot read

- **WHEN** the agent read `rn.png`, one picture of 400 KB, as a lone call
- **THEN** the picture is shown under the call's line without a tap, and tapping it opens it full screen

#### Scenario: A browser screenshot

- **WHEN** an MCP browser tool's result held a line of text and one picture
- **THEN** the picture is shown under the call's line, and opening it shows it full screen titled by the tool and
  "picture 1", without handing it to another app

#### Scenario: A run with pictures

- **WHEN** a collapsed run of six calls holds five Reads of screenshots, on a phone 412 dp wide
- **THEN** under the run's line three thumbnails of the latest ones are shown with "+2", only those three are read, and
  tapping "+2" expands the run, showing each picture under its own call

#### Scenario: Swiping between pictures

- **WHEN** the conversation shows three tool-result pictures and the second is opened full screen
- **THEN** it shows the second with "2 / 3", swiping left shows the third with "3 / 3", and swiping right from the
  second shows the first

#### Scenario: Only the pictures near the one open are read

- **WHEN** the conversation shows five tool-result pictures, none read yet, and the first is opened full screen
- **THEN** the first and second are read and the others are not, until a swipe brings one next to the one shown

#### Scenario: A file between two pictures

- **WHEN** the agent sent `report.pdf` between two screenshots it read, and the first screenshot is opened full screen
- **THEN** swiping left shows a page for `report.pdf` with its kind and "Open in another app", nothing of it is read,
  and swiping left again shows the second screenshot; tapping "Open in another app" reads it and hands it off

#### Scenario: A double tap

- **WHEN** a picture is open full screen at its fitted size and the user double-taps it
- **THEN** it zooms in around that point, and a second double tap shows it fitted again

#### Scenario: Swiping while zoomed in

- **WHEN** an opened picture is zoomed in and the user drags sideways
- **THEN** the picture moves and stays the one shown

#### Scenario: A large picture

- **WHEN** a result's picture is 6.1 MB
- **THEN** it shows "6.1 MB" without reading it, and is read only when tapped

#### Scenario: A picture no longer there

- **WHEN** a picture is read and the desktop answers that the conversation no longer holds it
- **THEN** it says the picture is no longer in the conversation

#### Scenario: A failed read

- **WHEN** a Read failed
- **THEN** no picture is shown, and its error is

#### Scenario: An older desktop

- **WHEN** the desktop does not offer reading a result's pictures, and the agent read `sp.png`
- **THEN** no picture is shown, and the expanded call shows its path and says a newer spekterm is needed to see the
  picture on the phone
