## MODIFIED Requirements

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
injected and collapsed, as before, except the agent CLI's fixed caveat before a built-in command, which SHALL NOT be
shown (`conversation-view`).
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
