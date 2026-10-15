## MODIFIED Requirements

### Requirement: A sent reply is unconfirmed until it appears in the conversation

A sent reply SHALL appear at once at the end of the conversation, marked as not yet confirmed, and SHALL stay so
until a user message with the same text — compared after the desktop's cleaning, ignoring leading and trailing
white space — appears in the conversation that was not there when the reply was sent; then the mark SHALL go
and only the conversation's own message SHALL remain, wherever in the conversation that message appears (a
reply the agent takes in while it works appears where it took it in). A reply sent while the agent waited for
input that has not appeared within 30 seconds SHALL be marked as not in the conversation yet, pointing to the
desktop. A reply sent while the agent was working SHALL instead be marked as waiting for the agent to take it,
without a time limit, because the agent takes it only when it gets to it; a permission prompt the agent raises
meanwhile SHALL NOT end that wait, because the agent continues its turn once the prompt is answered. When the
desktop reports the agent ready for input, or reports its state as unknown, such a reply — sent while the agent
was working, accepted by the desktop, and not yet appeared — SHALL be marked as not yet confirmed; if it has still
not appeared 30 seconds after the first such report, it SHALL be marked as not in the conversation, pointing to
the desktop. If the desktop reports the agent working or asking for a choice again within those 30 seconds, the
reply SHALL again be marked as waiting for the agent to take it; a later report of the agent working SHALL NOT
change the not-in-the-conversation mark. In every case the mark SHALL clear if the reply appears
later. A reply still waiting SHALL be shown at the end of the conversation; once it is marked as not appearing,
not in the conversation, possibly not sent, or not sent, it SHALL be shown where it was sent and SHALL NOT stay
below newer entries.
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

#### Scenario: The agent takes a reply while it works

- **WHEN** the user sends a reply while the agent runs tools, and the agent takes it in before its turn ends
- **THEN** the reply is confirmed by the user message shown where the agent took it in, and the mark is gone

#### Scenario: A permission prompt follows a reply sent while the agent works

- **WHEN** the user sends a reply while the agent works, and the agent then asks for a choice before taking it
- **THEN** the reply stays marked as waiting for the agent to take it while the choice is pending

#### Scenario: A reply sent during a text-only answer

- **WHEN** the user sends a reply while the agent writes an answer without tools, the desktop reports the agent
  ready when that answer ends, and the agent then takes the reply in a new turn
- **THEN** the reply shows as not yet confirmed, not as missing, until the conversation shows the user's message

#### Scenario: A reply sent while the agent works never appears

- **WHEN** the desktop reports the agent ready for input and a reply sent while it worked has not appeared in the
  conversation 30 seconds later
- **THEN** the reply is marked as not in the conversation with a pointer to the desktop, and newer entries appear
  below it

#### Scenario: The desktop refuses the reply

- **WHEN** the agent raised a permission prompt just as the user sent a reply, and the desktop refuses it
- **THEN** nothing is shown as sent, the text is back in the reply field, and the screen says why

#### Scenario: The connection ends while sending

- **WHEN** the connection ends after the user sent a reply and before the desktop answered
- **THEN** the screen says it is unknown whether the reply was sent, and if it later appears in the
  conversation the mark clears
