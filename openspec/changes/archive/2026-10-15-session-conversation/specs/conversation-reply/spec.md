## Purpose

Lets the user reply to the agent of the conversation on screen with free text, only when the agent can take it,
and shows honestly whether the reply reached the agent.

## ADDED Requirements

### Requirement: Free text only while the agent is ready or working

The app SHALL let the user send free text to the session whose conversation is on screen only while the phone
is connected, the conversation is being watched, and the desktop reports the agent as waiting for input or
working. In every other case sending SHALL NOT be possible and the screen SHALL say why: not connected or
paused; not watching yet; the agent asks for a choice (next requirement); or the wait state is unknown — then
saying that spekterm cannot tell whether the agent can take a message now and that the user can reply on the
desktop. The app SHALL NOT try to send while the wait state is unknown. Text that is empty once the desktop's
cleaning would remove control characters SHALL NOT be sendable. Until the desktop has reported a wait state for
the conversation, it SHALL be treated as unknown.

#### Scenario: Replying while the agent waits

- **WHEN** the agent waits for input and the user sends a message
- **THEN** the message reaches the agent

#### Scenario: Replying while the agent works

- **WHEN** the agent is working and the user sends a message
- **THEN** the message is accepted by the desktop

#### Scenario: Unknown wait state

- **WHEN** the desktop reports the agent's wait state as unknown
- **THEN** sending is not possible, and the screen says spekterm cannot tell whether the agent can take a message
  and that the user can reply on the desktop

### Requirement: While the agent asks for a choice, show what it asks and refuse free text

While the desktop reports that the agent asks for a choice, the app SHALL refuse free text, SHALL show what the
agent asks — the tool and its argument as the desktop reports them, as plain text; a later report of the same
choice that names no tool keeps the one shown — and SHALL say that the choice must be answered on the desktop. The app SHALL NOT offer any way to answer the choice: the desktop does
not report the options shown to the user, so answering would mean guessing keys, and a wrong guess makes a
decision the user never agreed to. When the desktop reports that the agent no longer asks for a choice,
sending SHALL become possible again according to the wait state.

#### Scenario: A permission prompt appears

- **WHEN** the agent raises a permission prompt to run a command while its conversation is on screen
- **THEN** sending is not possible, the screen names the tool and the command, says to answer on the desktop,
  and offers no way to answer

#### Scenario: Answered on the desktop

- **WHEN** the user answers that prompt on the desktop and the agent goes on working
- **THEN** sending becomes possible again on the phone

### Requirement: A reply is one message, sent as typed

The reply field SHALL accept several lines, and a multi-line reply SHALL reach the agent as exactly one message
with its line breaks kept. The phone SHALL send the text as typed; the desktop removes control characters other
than line breaks. Text too long for the desktop to accept SHALL be refused on the phone with a message saying
so, without sending it. A reply being written SHALL be kept when the user leaves the conversation and comes
back to it, unless another session's conversation was opened in between.

#### Scenario: A multi-line reply

- **WHEN** the user sends a reply of three lines
- **THEN** the agent's conversation shows exactly one user message holding the three lines

### Requirement: A sent reply is unconfirmed until it appears in the conversation

A sent reply SHALL appear at once at the end of the conversation, marked as not yet confirmed, and SHALL stay so
until a user message with the same text — compared after the desktop's cleaning, ignoring leading and trailing
white space — appears in the conversation that was not there when the reply was sent; then the mark SHALL go
and only the conversation's own message SHALL remain. A reply sent while the agent waited for input that has not
appeared within 30 seconds SHALL be marked as not in the conversation yet, pointing to the desktop. A reply sent
while the agent was working SHALL instead be marked as waiting for the agent to take it, without a time limit,
because the agent takes it only when it is ready; if the agent stops working and the reply has still not
appeared, it SHALL be marked as possibly not shown in the conversation. In every case the mark SHALL clear if
the reply appears later. A reply still waiting SHALL be shown at the end of the conversation; once it is marked
as not appearing, not shown, possibly not sent, or not sent, it SHALL be shown where it was sent and SHALL NOT
stay below newer entries.
The app SHALL tell the user, and not show the reply as pending:

- when the desktop typed the reply into the agent's input but withheld sending it because the agent's state
  changed meanwhile (for example, a choice appeared) — the text stays unsent in the agent's input and must be
  finished on the desktop;
- when the desktop refused the reply before typing anything — the agent could no longer take it, the session has
  no running process, or the desktop does not support replies — the text SHALL be returned to the reply field,
  with the reason;
- when the connection ended before the desktop answered, or the desktop answered with any other error — it is
  then unknown whether the reply was typed, which the screen SHALL say, while still clearing the mark if the
  reply appears in the conversation.

#### Scenario: A reply is confirmed

- **WHEN** the user sends a reply while the agent waits for input, and the agent takes it
- **THEN** the reply first shows as not yet confirmed, and once the conversation shows the user's message the
  mark is gone and the message is shown once

#### Scenario: An identical earlier message does not confirm

- **WHEN** the conversation already holds a user message "yes", the user sends "yes" again, and the desktop then
  re-delivers the whole conversation without the new message
- **THEN** the new reply stays not yet confirmed

#### Scenario: The agent's state changes while sending

- **WHEN** the desktop answers that it typed the reply but withheld sending it because the agent's state changed
- **THEN** the screen says the text sits unsent in the agent's input and must be finished on the desktop

#### Scenario: The reply does not appear

- **WHEN** a reply sent while the agent waited for input does not appear in the conversation within 30 seconds
- **THEN** the screen says it is not in the conversation yet and to check the desktop

#### Scenario: A reply sent while the agent works

- **WHEN** the user sends a reply while the agent is working, and the agent keeps working for minutes
- **THEN** the reply is marked as waiting for the agent to take it, not as missing

#### Scenario: A reply sent while the agent works never appears

- **WHEN** the agent stops working and a reply sent while it worked has not appeared in the conversation
- **THEN** the reply is marked as possibly not shown in the conversation, and newer entries appear below it

#### Scenario: The desktop refuses the reply

- **WHEN** the agent raised a permission prompt just as the user sent a reply, and the desktop refuses it
- **THEN** nothing is shown as sent, the text is back in the reply field, and the screen says why

#### Scenario: The connection ends while sending

- **WHEN** the connection ends after the user sent a reply and before the desktop answered
- **THEN** the screen says it is unknown whether the reply was sent, and if it later appears in the
  conversation the mark clears
