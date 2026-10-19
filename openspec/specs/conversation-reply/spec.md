# conversation-reply Specification

## Purpose
Lets the user reply to the agent of the conversation on screen with free text, only when the agent can take it,
and shows honestly whether the reply reached the agent.

## Requirements

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
uploaded, or has failed, sending SHALL NOT be possible and the screen SHALL say so. Until the desktop has
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

### Requirement: While the agent asks for a choice, show what it asks and refuse free text

While the desktop reports that the agent asks for a choice, the app SHALL refuse free text. When the desktop
states the open prompt, the app SHALL show it as `prompt-answer` describes, and the composer SHALL point to it:
to answer it above when the phone can answer it, otherwise to answer it on the desktop. When the desktop states
no prompt (a desktop that does not report prompts, or a prompt it cannot describe), the app SHALL show what the
agent asks — the tool and its argument as the desktop reports them, as plain text; a later report of the same
choice that names no tool keeps the one shown — SHALL say that the choice must be answered on the desktop, and
SHALL NOT offer any way to answer it. The app SHALL NOT answer a choice by typing into the agent's terminal.
When the desktop reports that the agent no longer asks for a choice, sending SHALL become possible again
according to the wait state.

#### Scenario: A permission prompt appears

- **WHEN** the agent raises a permission prompt to run a command while its conversation is on screen, and the
  desktop states the prompt and offers answering
- **THEN** free text cannot be sent, the screen names the tool and the command, and the composer points to the
  answers shown above it

#### Scenario: A desktop that does not state prompts

- **WHEN** the agent raises a permission prompt and the desktop reports the tool and command but no prompt
- **THEN** sending is not possible, the screen names the tool and the command, says to answer on the desktop,
  and offers no way to answer

#### Scenario: Answered on the desktop

- **WHEN** the user answers that prompt on the desktop and the agent goes on working
- **THEN** sending becomes possible again on the phone

#### Scenario: Denied from the phone

- **WHEN** the user denies the agent's own permission prompt from the phone
- **THEN** the desktop reports the agent waiting for input, and the user can send a reply saying what to do
  instead

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
until a user message matching it — compared ignoring leading and trailing white space — appears in the
conversation that was not there when the reply was sent; then the mark SHALL go and only the conversation's own
message SHALL remain, wherever in the conversation that message appears (a reply the agent takes in while it
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

### Requirement: Say when an inbox item's first prompt waits unsent

While the session on screen is a running agent session that carries a `prefill`, or for which the app holds an accepted inbox item,
the conversation screen SHALL show a note above the reply field saying:

- with a `prefill` not edited whose item the Inbox shows under Already opened: the first prompt from the inbox is filled in on the
  desktop and not sent yet, offering to review and send it, which opens the Inbox at that item's card where the item's body and
  the prompt are shown next to the send control (`inbox`);
- with a `prefill` edited, or whose item the Inbox does not show: the prompt is not sent yet and is to be finished at the desktop,
  offering to open the item in the Inbox when it is shown there;
- with no `prefill`, when the desktop lists `sessions.submitPrefill`: the desktop is filling in the first prompt — a hint that does
  not stop replies;
- with no `prefill`, when the desktop does not list `sessions.submitPrefill`: the first prompt is filled in on the desktop and not
  sent, to be sent there, and that a reply from here would be sent with it — offering to open the item in the Inbox and to reply
  anyway, which lets the user send free text on this screen until the note changes.

The note SHALL go when none of these holds. Opening the Inbox from it SHALL return to the Inbox when it is already behind the
conversation, rather than stacking another, and SHALL show the item's card. A reply the desktop refuses with `PREFILL_PENDING` SHALL
come back to the reply field with a reason saying the desktop holds an unsent first prompt from the inbox in this session, to be sent
or cleared at the desktop — also when no note is shown.

#### Scenario: Opening a session just accepted from the phone

- **WHEN** the user accepts a Slack mention on the phone and its session's conversation opens before the desktop has filled in the
  prompt
- **THEN** the screen says the desktop is filling in the first prompt

#### Scenario: The prompt is filled in

- **WHEN** the desktop then lists the filled-in prompt on the session
- **THEN** the note says the first prompt is filled in and not sent, sending free text is not possible, and Review and send opens the
  Inbox at that item's card

#### Scenario: Sent on the desktop

- **WHEN** the note is shown and the user presses Enter on the desktop
- **THEN** the note goes once the desktop settles the item, and the agent's turn appears in the conversation

#### Scenario: An older desktop after a hibernation

- **WHEN** the desktop does not list `sessions.submitPrefill`, the app holds an accepted item for the session, and the user taps
  Reply anyway
- **THEN** free text can be sent on this screen

#### Scenario: A record the desktop keeps

- **WHEN** no note is shown and the desktop refuses a reply with `PREFILL_PENDING`
- **THEN** the reply returns to the reply field, and the screen says the desktop holds an unsent first prompt from the inbox
