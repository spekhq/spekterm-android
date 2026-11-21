## ADDED Requirements

### Requirement: The user keeps a list of quick replies on the phone

The app SHALL keep a list of quick replies on the phone: sentences the user sends to agents often. A sentence SHALL be
one line, without control characters, 1 to 200 characters after trimming, and not `/model` or `/effort` without an
argument; the list SHALL hold at most 12, no two equal. A stored list that breaks any of these SHALL be treated as
not set, never repaired in part. Until the user sets the list, it SHALL be three defaults in the app's language:
"Can this session be closed now?", "Is there any risk, or anything I need to decide?" and "Is there anything left to
do in this session?" (in Chinese (Taiwan): 「這個 session 可以關了嗎？」「會有什麼風險或我要特別決策的嗎？」「這個 session 還有什麼要做的嗎？」);
a change of the app's language SHALL change them while the list is not set. Once set — even to no sentence — the list
SHALL be exactly what the user set, in the user's order. Unpairing or pairing another desktop SHALL keep it.

#### Scenario: Defaults in the app's language

- **WHEN** the user never edited the quick replies and sets the app's language to 中文（台灣）
- **THEN** the quick replies are the three defaults in Traditional Chinese

#### Scenario: Kept across unpairing

- **WHEN** the user edited the list, then unpairs and pairs again
- **THEN** the list is as edited

#### Scenario: A damaged stored list

- **WHEN** the stored list holds the same sentence twice
- **THEN** the quick replies are the defaults

### Requirement: Edit the quick replies in the app's settings

The Settings screen SHALL offer a quick replies section listing the sentences (the defaults while not set), where
the user SHALL be able to change a sentence, remove one, move one up or down, add one, and restore the defaults after
a confirmation. A sentence SHALL be trimmed when saved, and SHALL NOT be saved when it breaks a rule of a sentence or
equals another sentence of the list, the editor saying why; adding SHALL NOT be offered when the list holds 12. The
first change SHALL save the whole list as shown. Each row's buttons SHALL be labelled with their action and the
sentence.

#### Scenario: Adding a sentence

- **WHEN** the list is the defaults and the user adds "Go ahead if there is no risk"
- **THEN** the list is the three defaults followed by that sentence, and it is kept after the app is restarted

#### Scenario: A duplicate is refused

- **WHEN** the user adds a sentence equal to one in the list
- **THEN** it cannot be saved, and the editor says it is already in the list

#### Scenario: Restoring the defaults

- **WHEN** the user removed every quick reply and then restores the defaults and confirms
- **THEN** the list is the three defaults in the app's language

### Requirement: Quick replies show above the reply field

While the composer of an agent session shows — on the conversation screen and in the reply bar of the screens opened
from it — a row of quick replies SHALL show directly above the reply field, one chip per sentence in the list's order,
each showing its sentence on one line and naming the whole sentence to a screen reader. The row SHALL keep to one
line, scrolling sideways when it does not fit. It SHALL NOT show while the list is empty, while the composer is in
command mode, while the skills list shows, or while the height left for the conversation and its reply area is below
the threshold the design sets, so that the reply field stays visible above the keyboard. While the agent asks for a
choice the row SHALL show, disabled.

#### Scenario: On the conversation screen

- **WHEN** an agent session's conversation is on screen and the list is the defaults
- **THEN** three chips show above the reply field

#### Scenario: In the reply bar

- **WHEN** the user opened a change from the conversation and the reply bar shows
- **THEN** the same chips show above the bar's reply field

#### Scenario: Command mode

- **WHEN** the user switches the composer to command mode
- **THEN** no chip shows

#### Scenario: Landscape with the keyboard open

- **WHEN** the phone is in landscape with the keyboard open and the height left is below the threshold
- **THEN** no chip shows and the reply field is visible

### Requirement: A tap sends the sentence at once, when a typed reply could be sent

Tapping a quick reply SHALL send its sentence to the session as a reply, with no further step, and SHALL show it as
a reply is shown — at once, unconfirmed until it appears in the conversation — with the outcomes of a typed reply
except that a refused quick reply SHALL NOT come back to the reply field (`conversation-reply`). It SHALL carry no
attachment, and SHALL leave the reply field's text, its attachments and its notes as they were. A quick reply that
is `/model` or `/effort` with one argument SHALL be held and confirmed as a typed one is (`model-effort-switch`).
Chips SHALL be enabled exactly while that sentence could be sent as a typed reply with no attachment, under the
conditions of `conversation-reply` "Free text only while the agent is ready or working" other than those on the
text and the attachments: among them connected and not paused, watching, the agent ready or working, no filled-in
first prompt listed or held, no shell command waiting unsent, no model switch holding, and no reply of this session
still being sent. While a chip
is disabled, the composer SHALL say why — its reason line, or the inbox note or the waiting-command notice above it.

#### Scenario: Sent while the agent waits

- **WHEN** the agent waits for input and the user taps "Can this session be closed now?"
- **THEN** the agent receives that sentence, and the reply field still holds what the user had typed there, also
  after leaving the screen and coming back

#### Scenario: Attachments stay

- **WHEN** the user attached a photo to the reply field and taps a quick reply
- **THEN** the sentence is sent without the photo, and the photo stays attached in the field

#### Scenario: Asking for a choice

- **WHEN** the agent asks for a choice
- **THEN** the chips are disabled, and the screen shows the prompt card as before

#### Scenario: Unknown wait state

- **WHEN** the desktop reports the agent's wait state as unknown
- **THEN** the chips are disabled and the reason line says spekterm cannot tell whether the agent can take a message
