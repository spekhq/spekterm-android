## MODIFIED Requirements

### Requirement: Free text only while the agent is ready or working

The app SHALL let the user send free text to the session whose conversation is on screen only while the phone
is connected, the conversation is being watched, and the desktop reports the agent as waiting for input or
working. In every other case sending SHALL NOT be possible and the screen SHALL say why: not connected or
paused; not watching yet; the agent asks for a choice (next requirement); the desktop lists a filled-in first prompt from the inbox on
the session, or — with a desktop that does not list `sessions.submitPrefill` — the app holds an accepted inbox item for the session
and the user has not chosen to reply anyway (requirement "Say when an inbox item's first prompt waits unsent"); or the wait state is
unknown — then
saying that spekterm cannot tell whether the agent can take a message now and that the user can reply on the
desktop. The app SHALL NOT try to send while the wait state is unknown. Text that is empty once the desktop's
cleaning would remove control characters SHALL NOT be sendable, unless the reply carries at least one
attachment that is ready (`reply-attachments`). While an attachment of the reply is still being prepared or
uploaded, or has failed, sending SHALL NOT be possible and the screen SHALL say so. While a model switch holds
(`model-effort-switch`), sending SHALL NOT be possible and the screen SHALL say why, even with the field empty. A
reply that is `/model` or `/effort` with no argument SHALL NOT be sendable, and the screen SHALL say to add one. Until the desktop has
reported a wait state for the conversation, it SHALL be treated as unknown.

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

#### Scenario: Only an attachment

- **WHEN** the agent waits for input, the user attached a photo that finished uploading, and the reply field is
  empty
- **THEN** the reply can be sent, and the agent receives the desktop's sentence naming the photo

#### Scenario: An attachment still uploading

- **WHEN** the user typed a reply and an attached file is still uploading
- **THEN** sending is not possible until the upload completes, and the screen says the attachment is uploading

#### Scenario: A first prompt from the inbox waits

- **WHEN** the desktop lists a filled-in first prompt from the inbox on the session on screen
- **THEN** sending free text is not possible, and the screen says the prompt waits

#### Scenario: A model switch holds

- **WHEN** a model switch from the sheet was written by the desktop and the reported model has not changed yet
- **THEN** sending is not possible, and the screen says the model is being switched

### Requirement: A sent reply is unconfirmed until it appears in the conversation

A sent reply SHALL appear at once at the end of the conversation, marked as not yet confirmed, and SHALL stay so
until a user message matching it — compared ignoring leading and trailing white space — appears in the
conversation that was not there when the reply was sent — or, for a reply that is `/model` or `/effort` with one
argument on one line and without attachments, until the agent's reports confirm it (`model-effort-switch`), since one run while the agent works never
appears; then the mark SHALL go and only the conversation's own message, if any, SHALL remain, wherever in the conversation that message appears (a reply the agent takes in while it
works appears where it took it in). A reply matches the text the desktop answered that it wrote, which includes
the sentence naming its attachments. Until such an answer exists — while it is being sent, when the connection
ended before the answer, or with a desktop whose answer carries no text — a reply without attachments matches
the text as typed after the desktop's cleaning, and a reply with attachments matches a message that begins with
that text and names every one of its attachments; a reply of attachments alone SHALL NOT be confirmed by a
message that only has the same empty text. A matching message that arrived before the desktop's answer SHALL
confirm a reply with attachments once the answer arrives. A reply SHALL show the names of the attachments it carries. A reply sent
while the agent waited for input that has not appeared within 30 seconds SHALL be marked as not in the
conversation yet, pointing to the desktop. A reply sent while the agent was working SHALL instead be marked as
waiting for the agent to take it, without a time limit, because the agent takes it only when it gets to it; a
permission prompt the agent raises meanwhile SHALL NOT end that wait, because the agent continues its turn once
the prompt is answered. When the desktop reports the agent ready for input, or reports its state as unknown,
such a reply — sent while the agent was working, accepted by the desktop, and not yet appeared — SHALL be marked
as not yet confirmed; if it has still not appeared 30 seconds after the first such report, it SHALL be marked as
not in the conversation, pointing to the desktop. If the desktop reports the agent working or asking for a
choice again within those 30 seconds, the reply SHALL again be marked as waiting for the agent to take it; a
later report of the agent working SHALL NOT change the not-in-the-conversation mark. In every case the mark SHALL
clear if the reply appears later. A reply still waiting SHALL be shown at the end of the conversation; once it
is marked as not appearing, not in the conversation, possibly not sent, or not sent, it SHALL be shown where it
was sent and SHALL NOT stay below newer entries.
The app SHALL tell the user, and not show the reply as pending:

- when the desktop typed the reply into the agent's input but withheld sending it because the agent's state
  changed meanwhile (for example, a choice appeared) — the text stays unsent in the agent's input and must be
  finished on the desktop;
- when the desktop refused the reply before typing anything — the agent could no longer take it, the session has
  no running process, or the desktop does not support replies — the text SHALL be returned to the reply field,
  with the reason, and its attachments SHALL be returned to the reply as `reply-attachments` describes;
- when the connection ended before the desktop answered, or the desktop answered with any other error — it is
  then unknown whether the reply was typed, which the screen SHALL say, while still clearing the mark if the
  reply appears in the conversation.

#### Scenario: A reply is confirmed

- **WHEN** the user sends a reply while the agent waits for input, and the agent takes it
- **THEN** the reply first shows as not yet confirmed, and once the conversation shows the user's message the
  mark is gone and the message is shown once

#### Scenario: A reply with an attachment is confirmed

- **WHEN** the user sends "look at this" with a photo, and the agent takes it
- **THEN** the reply shows the photo's name while not yet confirmed, and the conversation's message — the text
  followed by the desktop's sentence naming the stored photo — confirms it

#### Scenario: A reply with an attachment whose answer was lost

- **WHEN** the connection ends after the user sent a reply with a photo and before the desktop answered, and the
  agent then takes the reply
- **THEN** the reply is marked as possibly not sent until the conversation's message holding its text and naming
  the photo appears, and then the mark clears

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

#### Scenario: A typed effort change while the agent works

- **WHEN** the user sends "/effort high" while the agent works, and the desktop then reports effort "high"
- **THEN** the reply's mark goes, although the conversation never shows "/effort high"
