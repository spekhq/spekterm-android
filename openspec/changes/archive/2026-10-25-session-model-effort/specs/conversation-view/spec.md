## MODIFIED Requirements

### Requirement: Show the conversation of an agent session

The conversation screen SHALL show the session's entries in order, newest last: the user's messages, the agent's
text, the agent's thinking, its tool calls, each shown by its kind as `tool-call-display` says, and their results
(whether each succeeded or failed). A tool call and the result the desktop gives for it (the same tool call id)
SHALL be shown as one item, marked as succeeded or failed once the result is there; a result whose call is not
shown SHALL be shown on its own. Two or more tool calls in a row — with only their results, thinking, background-task notices and the agent CLI's
note about a picture it read (`tool-call-display`) between them — SHALL be summarized in one collapsed item that says how many calls it holds, how many failed, and what the
latest call was; expanding it SHALL show each call. A reply of the user's shown between tool calls
(`conversation-reply`) SHALL end the run there, so that the reply stays where it was sent; a message from another
session, a subagent's report, and a message or files the agent sent (`tool-call-display`) SHALL end the run too, so
that none is ever hidden inside one. Thinking and tool results SHALL be collapsed until the user
expands them. Messages the agent itself injected rather than the user typing them SHALL be marked as such, except the agent CLI's
note about a picture, which is not shown (`tool-call-display`), and its fixed caveat before a built-in command,
which SHALL NOT be shown (requirement "Show a built-in command's output as the command's output"); those exchanged with
other sessions, subagents and the user SHALL be shown as messages from their sender (`tool-call-display`). New
entries SHALL appear as the desktop delivers them, without the user refreshing; a new tool call that extends a
summarized run SHALL update the summary without collapsing it if the user expanded it. When the user has scrolled
up, new entries SHALL NOT move the view, and the screen SHALL offer to jump to the newest.

#### Scenario: Reading a conversation

- **WHEN** the user opens a running agent session that has a conversation
- **THEN** the screen shows its entries, newest last, with thinking and tool results collapsed

#### Scenario: The agent answers while the screen is open

- **WHEN** the agent writes a new message while the conversation is on screen and scrolled to the newest entry
- **THEN** the message appears without the user doing anything, and the view follows it

#### Scenario: A run of tool calls

- **WHEN** the agent made seven tool calls in a row, one of which failed, and then wrote its answer
- **THEN** the screen shows one item saying "7 tool calls", that one failed, and the latest call, followed by the
  answer; expanding the item shows the seven calls, each marked succeeded or failed

#### Scenario: A single tool call

- **WHEN** the agent made one tool call between two messages
- **THEN** the call is shown as one item with its name, its one-line summary (`tool-call-display`) and whether it
  succeeded, with its result collapsed

#### Scenario: A reply sent while the agent made tool calls

- **WHEN** the user sent a reply after the agent's third tool call in a row, the agent made two more calls, and the
  reply is then marked as not in the conversation
- **THEN** the screen shows the first three calls, then the reply, then the other two calls; no item holds calls
  from both sides of the reply

#### Scenario: A message arrives during a run

- **WHEN** the agent made two tool calls, a background task's notice and then another session's message arrived, and
  the agent made two more calls
- **THEN** the screen shows a run of the first two calls, the notice's line, the message, then a run of the other two
  calls

#### Scenario: A notice between two calls

- **WHEN** a background task's notice arrived between two of the agent's tool calls
- **THEN** the screen shows one run of the two calls, holding the notice

#### Scenario: A run that grows while expanded

- **WHEN** the user expanded a run of tool calls and the agent makes another call
- **THEN** the run stays expanded and shows the new call, and its count goes up by one

#### Scenario: A picture read inside a run

- **WHEN** the agent ran a command, read a screenshot (followed by the CLI's note about its sizes), and ran another
  command
- **THEN** the screen shows one item saying "3 tool calls", and no injected message

#### Scenario: A command's caveat

- **WHEN** the user ran "/effort low" and the conversation holds the CLI's caveat entry before it
- **THEN** the screen shows "/effort low" and its output, and nothing for the caveat

## ADDED Requirements

### Requirement: Show a built-in command's output as the command's output

A user entry not marked as injected whose whole text, without white space at either end, is one
`<local-command-stdout>…</local-command-stdout>` or `<local-command-stderr>…</local-command-stderr>` with no other
marker inside SHALL be shown as the output of the agent CLI's command: the text between the markers without terminal
escape sequences, as plain text, under a label saying it is command output, not as a message the user typed, with
only its first three lines shown until the user expands it. It SHALL end a run of tool calls as the user's message it
replaces would, keep the place of a reply sent after it, and show no time of its own while counting for its day as
a tool call does. An entry marked as injected
whose whole text is one `<local-command-caveat>…</local-command-caveat>` SHALL NOT be shown; it SHALL still end a run
of tool calls as an injected message does, and a reply sent after it SHALL stay where it was sent. Any other entry
holding these markers SHALL be shown as it is today.

#### Scenario: An effort change

- **WHEN** the conversation holds, in order, an injected entry "<local-command-caveat>The command below was run
  directly in Claude Code…</local-command-caveat>", the user entry "/effort low", and the entry
  "<local-command-stdout>Set effort level to low (saved as your default for new sessions): Quick…
  </local-command-stdout>"
- **THEN** the screen shows "/effort low" as the user's message, followed by "Set effort level to low (saved as your
  default for new sessions): Quick…" as command output, and no caveat

#### Scenario: Output with terminal styling

- **WHEN** the output entry of "/compact" reads "<local-command-stdout>\u001b[2mCompacted (ctrl+o to see full
  summary)\u001b[22m</local-command-stdout>"
- **THEN** the screen shows "Compacted (ctrl+o to see full summary)" as command output, with no escape codes

#### Scenario: A caveat between two tool calls

- **WHEN** the CLI's caveat entry arrived between two of the agent's tool calls
- **THEN** the screen shows the two calls as it did before this change: in two items, not one run

#### Scenario: Markers among other text

- **WHEN** a user entry reads "see <local-command-stdout>x</local-command-stdout> above"
- **THEN** it is shown as the user's message, as recorded
