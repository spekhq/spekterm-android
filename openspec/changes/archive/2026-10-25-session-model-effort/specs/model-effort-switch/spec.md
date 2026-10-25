## ADDED Requirements

### Requirement: Offer the switch on the conversation screen

While the app watches an agent session and holds a usage event of that watch from the desktop (spekterm 0.2.15 or
later), the conversation screen SHALL offer a "Model and effort" sheet, opened by tapping the usage line
(`agent-usage`) and from an item of the conversation's overflow menu. The item SHALL be shown, not disabled, whenever
the sheet is offered, and SHALL NOT appear on the session list. Without a usage event for the watch — an older
desktop, or a watch not yet answered — neither SHALL be offered. The sheet SHALL name the model and effort the agent
last reported, as plain text, when reported.

#### Scenario: From the usage line

- **WHEN** the usage line reads "Opus 5.5 (high) · Context 43% of 200K · $3.21" and the user taps it
- **THEN** the sheet opens, naming "Opus 5.5" and "high" as what the agent last reported

#### Scenario: Before the first message

- **WHEN** the desktop's usage event for the watch carries only the session id, so there is no usage line
- **THEN** the conversation's overflow menu offers "Model and effort", and the sheet opens without reported values

#### Scenario: A desktop with no session operations

- **WHEN** the desktop accepts none of the session operations the menu offers, and the sheet is offered
- **THEN** the conversation's overflow menu opens and holds "Model and effort"

#### Scenario: An older desktop

- **WHEN** the desktop sends no usage event for the watch
- **THEN** the menu has no "Model and effort" item

### Requirement: Choose an effort level or a model

The sheet SHALL offer the effort levels low, medium, high, xhigh, max and auto, and — only when the desktop lists
`conversation.setModel` in its operations — the models Default, Opus, Fable, Sonnet and Haiku and a field for any
other model name. A typed name SHALL be sendable only when, trimmed, it is 1 to 100 characters of letters, digits and
`.`, `_`, `:`, `/`, `@`, `[`, `]`, `-`, and does not start with `-`; otherwise the field SHALL say which characters
it takes. The reported effort SHALL be marked when it is one of the levels, ignoring case; a model SHALL be marked when
the reported model is its name followed by a version number and nothing else, ignoring case; Default SHALL never be
marked. A mark SHALL only be a hint: choosing a marked choice SHALL send it like any other. Without
`conversation.setModel` the sheet SHALL say that changing the model from the phone needs a newer spekterm on the
desktop, and offer effort only.

#### Scenario: The current ones marked

- **WHEN** the agent reported model "Sonnet 5.5" and effort "xhigh"
- **THEN** Sonnet and xhigh are marked, and choosing Sonnet still sends it

#### Scenario: A model with a suffix

- **WHEN** the agent reported "Opus 5.5 (1M context)"
- **THEN** no model is marked, and the sheet names "Opus 5.5 (1M context)" as what the agent last reported

#### Scenario: Another model by name

- **WHEN** the user types "opus[1m]" or "claude-opus-4-8" into the other-model field
- **THEN** it can be sent; "opus 1m", "-x" and "opus+1" cannot, and the field says which characters it takes

#### Scenario: A desktop without the operation

- **WHEN** the desktop does not list `conversation.setModel`
- **THEN** the sheet offers the effort levels and says changing the model needs a newer spekterm on the desktop

### Requirement: Say what a switch changes beyond the session

The sheet SHALL say that a choice also becomes Claude Code's default for new sessions on the desktop's computer,
except max, which applies to this session only, and auto, which clears the saved level. While models are offered, it
SHALL say that switching the model mid-conversation makes the next response re-read the whole conversation, slower and
with more tokens.

#### Scenario: Reading the notes

- **WHEN** the sheet is open on a desktop that lists `conversation.setModel`
- **THEN** it says the choice becomes the default for new sessions, and that a model switch makes the next response
  re-read the conversation

### Requirement: Send a switch as a command the agent's CLI runs

Choosing an effort level SHALL send `/effort <level>` with `conversation.send` and no attachments. Choosing a model
SHALL send its trimmed name with `conversation.setModel`. A switch SHALL be possible exactly when a reply with that
text and no attachments could be sent (`conversation-reply`), no model switch holds (requirement "Hold what could
confirm a model switch"), and no other switch of the session is being sent, including while the agent works;
otherwise the sheet SHALL disable its choices and say why — also when a first prompt from the inbox waits in the
session. Sending SHALL close the sheet. A switch SHALL NOT be shown as a reply, and SHALL NOT read or change the reply
field's text, the composer's attachments or its notes, whether it is sent, refused or fails. The reply field SHALL NOT
send `/model` or `/effort` without an argument, and SHALL say to add one or to choose in the sheet.

#### Scenario: Lowering the effort while the agent works

- **WHEN** the agent is working and the user chooses "low"
- **THEN** "/effort low" is sent, no reply row appears, and the reply field keeps what was typed in it

#### Scenario: Switching the model

- **WHEN** the desktop lists `conversation.setModel`, the conversation is mid-way, and the user chooses Sonnet
- **THEN** the phone sends `conversation.setModel` with "sonnet"

#### Scenario: A photo still uploading

- **WHEN** an attachment in the composer is still uploading and the user chooses "high"
- **THEN** "/effort high" is sent, and the attachment stays in the composer

#### Scenario: A choice pending

- **WHEN** the agent asks for a permission choice and the user opens the sheet
- **THEN** its choices are disabled, and it says to answer the choice first, as the composer does

#### Scenario: An inbox prompt waits

- **WHEN** the desktop lists a filled-in first prompt from the inbox on the session and the user opens the sheet
- **THEN** its choices are disabled, and it says the prompt waits and must be sent or edited first

#### Scenario: A bare command typed

- **WHEN** the reply field holds "/model" and nothing else
- **THEN** it cannot be sent, and the composer says to add a name or choose in Model and effort

### Requirement: Confirm a switch by what the agent reports

A switch SHALL be confirmed by the desktop's usage reports, because a command the agent's CLI runs while the agent
works is never recorded in the conversation: an effort when the reported effort is the level chosen — for auto, when
it differs from a known effort reported when it was sent — or at once when the desktop wrote it and the level is the
one marked current; a model when a reported model differs from a known model reported when it was sent and, for the
aliases Opus, Fable, Sonnet and Haiku, starts with that name; either when its command line appears in the
conversation after it was sent. A model switch SHALL NOT be confirmed by the mark alone. A usage report missing, a
value missing, or no value known at the send SHALL count as no information. A typed reply that is `/model` or
`/effort` with one argument on one line and no attachments SHALL be confirmed the same way, and its reply SHALL then
count as confirmed (`conversation-reply`). While a switch chosen in the sheet is being sent, and until it is confirmed,
the screen SHALL say it is changing; once confirmed, that SHALL go and the usage line SHALL show the new value. When the
desktop refuses it, the screen SHALL say that the model or effort was not changed and why; when the desktop typed it
but withheld its submit key, that it sits unsent in the agent's input and must be cleared on the desktop before
replying; and when an effort switch is not confirmed 10 seconds after it was sent, that it has not changed yet and to
look at the desktop. Each of these SHALL stay until the user dismisses it or sends another switch, across leaving the
screen and reconnecting, for as long as the app runs.

#### Scenario: Confirmed while the agent works

- **WHEN** the agent is working, the user chooses "medium", and the desktop reports effort "medium" two seconds later
- **THEN** the screen says the effort is changing for those two seconds, then the note goes and the usage line shows
  "(medium)", although the conversation never shows "/effort medium"

#### Scenario: A marked model is not confirmed by its mark

- **WHEN** the agent reports "Opus 4.8", Opus is marked, the user chooses Opus, and the desktop answers that it wrote
  it
- **THEN** the switch is not confirmed until the desktop reports a model starting with "Opus" other than "Opus 4.8",
  or the conversation shows "/model opus"

#### Scenario: Another model change is not this switch

- **WHEN** the user chose Haiku, and the desktop then reports "Sonnet 5.5" instead of the model before
- **THEN** the Haiku switch is not confirmed

#### Scenario: A typed command while the agent works

- **WHEN** the agent is working, the user sends the reply "/effort low", and the desktop reports effort "low"
- **THEN** the reply counts as confirmed, and is not later marked as a command the CLI does not know

#### Scenario: Refused

- **WHEN** the desktop refuses the switch because a filled-in prompt waits in the session
- **THEN** the screen says the model or effort was not changed because a first prompt waits, and the reply field
  still holds what the user had typed

#### Scenario: Nothing changes

- **WHEN** the user chose "auto" while the reported effort already was the model's default, and nothing changes in
  10 seconds
- **THEN** the screen says the effort has not changed yet and to look at the desktop, with a way to dismiss it

### Requirement: Hold what could confirm a model switch

From the moment a model switch is sent — chosen in the sheet, or a reply that is `/model` and one name on one line
without attachments — until it is confirmed, unless the desktop refused it or answered that it withheld its submit key,
the phone SHALL NOT send a reply, answer a prompt, or send another switch in that session, and SHALL say the model is
being switched. From 10 seconds after it was sent it SHALL instead say that the model has not changed yet, that the
desktop may be asking to confirm the switch and that a reply or an answer sent now could confirm it, a reply being
lost, and to look at the desktop; and SHALL offer to continue anyway, which ends the hold for that switch and leaves a
note that a reply or switch sent now could still confirm it. The hold SHALL end only when the switch is confirmed, the desktop refuses it or answers
that it withheld its submit key, the user continues anyway, or the session is closed, gone or no longer running — not when the screen is left, the
conversation watched again, or the connection lost — for as long as the app runs. An effort switch SHALL hold nothing.

#### Scenario: Sending at once after choosing

- **WHEN** the user chooses Sonnet with a reply typed, and taps send before the desktop has answered the switch
- **THEN** the reply is not sent, and the screen says the model is being switched

#### Scenario: A switch that goes through

- **WHEN** the user switches to Sonnet and the desktop reports "Sonnet 5.5" two seconds later
- **THEN** the reply field and the prompt card are held for those two seconds, then work again

#### Scenario: A confirmation left open

- **WHEN** the user switches to Sonnet, and 10 seconds later the reported model is still the one before
- **THEN** the reply field says the desktop may be asking to confirm and to look there, and offers to continue anyway

#### Scenario: Away and back

- **WHEN** the user switches to Sonnet, turns the screen off at once, and opens the conversation again a minute later
  with the model still unchanged
- **THEN** once the conversation is shown again, the hold is still there and says to look at the desktop

#### Scenario: A prompt during the hold

- **WHEN** the hold is on and the agent raises a permission prompt
- **THEN** the card shows the prompt and the hold's text instead of its answers, until the switch is confirmed or the
  user continues anyway

#### Scenario: Continuing anyway

- **WHEN** the hold says to look at the desktop and the user chooses to continue anyway
- **THEN** replies and answers can be sent again, the hold does not return for that switch, and a note says a reply
  or switch sent now could still confirm a confirmation left open
