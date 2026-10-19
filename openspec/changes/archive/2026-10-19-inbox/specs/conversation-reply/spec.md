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

## ADDED Requirements

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
