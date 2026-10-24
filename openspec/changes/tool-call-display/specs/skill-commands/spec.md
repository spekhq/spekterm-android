## MODIFIED Requirements

### Requirement: Take the session's skills from the watched session's reports

The app SHALL take the skills of a session only from the desktop's skill events of the conversation it watches, each
replacing what was held. It SHALL distinguish a list the desktop does not know — sent as such, or malformed — from a
known list, and SHALL never show a list it does not know as empty. A skill SHALL have a name with no white space and a
description or none; a malformed skill SHALL be left out without affecting the others, and a repeated name SHALL keep
its first skill. The skills SHALL be dropped when the watch is requested again, left, ended or its connection lost,
and an event arriving before the desktop answers the watch SHALL be kept. A desktop that never sends the event — older
than spekterm 0.2.15 — SHALL leave the screen as it was before the feature.

#### Scenario: Not known yet

- **WHEN** the desktop says it knows no skill list for the session, because it has not received a message yet
- **THEN** the skills list says the agent lists its skills with the first message, and shows no empty list

#### Scenario: A malformed skill

- **WHEN** the desktop sends three skills, one of them without a name
- **THEN** two skills are listed

#### Scenario: An older desktop

- **WHEN** the desktop sends no skill event
- **THEN** typing "/" in the reply field shows no list, and the field's hint does not mention skills

### Requirement: Choose a skill to start a reply

While the app watches an agent session and holds a skill event for it, typing `/` at the start of the reply field
SHALL offer the session's skills in a list shown above the field, with no button of its own in the composer. The list
SHALL show while the field has focus, starts with `/`, the cursor is in that first word, and that word is a command
name being typed — a slash and a name with no other slash and no white space; it SHALL go away once the word is ended
(white space typed), the cursor leaves it, or the word becomes a path, and Back SHALL close it before it leaves the
screen. The list SHALL be filtered by what was typed between the slash and the cursor, so that a slash typed before
existing text lists every skill. The list SHALL show each skill's name as a slash command and
its description, both as plain text, never as a link or markup; the filter text SHALL be found in the name or the
description, ignoring case, names that start with it first, each group sorted by name;
it SHALL say when nothing matches, when the session has no skills, when the agent has not listed its skills yet,
that built-in commands are not listed, and that a few listed skills only the agent may use, to be asked for in words.
It SHALL leave the field visible above the keyboard on any screen, in either orientation, taking at most a part of
the height, and SHALL be announced to a screen reader when it appears. The reply field's hint SHALL say that `/`
offers the skills while they are offered. Choosing a skill
SHALL replace the command name being typed with `/name` and a separator, and put the cursor after the separator: a
bare slash becomes `/name `, text after the word — its separator first — is kept as the arguments, and when the slash
was typed before existing text, that text is kept as the arguments. The field SHALL keep the result
when the user leaves the screen and comes back, as it keeps a typed draft. Choosing SHALL NOT send anything; the reply
is sent as any reply is (`conversation-reply`).

#### Scenario: An empty field

- **WHEN** the reply field is empty and the user types "/opsx"
- **THEN** a list above the field shows the skills whose name or description contains "opsx", those whose name starts
  with it first; choosing "opsx:apply" leaves the field reading "/opsx:apply " with the cursor at its end, the list
  gone, and nothing sent

#### Scenario: The list goes away

- **WHEN** the user types "/review" and then a space
- **THEN** the list is no longer shown, and the field reads "/review "

#### Scenario: Text already typed

- **WHEN** the field reads "fix the login bug", the user moves the cursor to its start and types "/"
- **THEN** the list shows every skill; choosing "simplify" leaves the field reading "/simplify fix the login bug"

#### Scenario: A path typed first

- **WHEN** the user types "/tmp/x.log shows an error"
- **THEN** no list is shown once the second slash is typed, and the text stays as typed

#### Scenario: Back closes the list

- **WHEN** the list is shown and the user presses Back
- **THEN** the list closes and the conversation screen stays open

#### Scenario: The hint

- **WHEN** the session's skills are known or not yet known, and the reply field is empty
- **THEN** the field's hint says that `/` offers the skills

#### Scenario: A small screen

- **WHEN** the phone is held sideways with the keyboard up and the user types "/"
- **THEN** the list takes only part of the remaining height, and the reply field stays visible

#### Scenario: The skills are not known yet

- **WHEN** the user types "/" in a session that has not had a message yet
- **THEN** the list says the agent lists its skills with the first message, and shows no skills

#### Scenario: Another command already typed

- **WHEN** the field reads "/review 123", the user moves the cursor back into "/review", and chooses "code-review"
- **THEN** the field reads "/code-review 123"

#### Scenario: Leaving after choosing

- **WHEN** the user chooses a skill, leaves the conversation screen and opens it again
- **THEN** the field still reads what choosing left in it

#### Scenario: Searching

- **WHEN** the user types "/Android" in the reply field
- **THEN** only skills whose name or description contains "android", in any case, are listed, those whose name starts
  with it first

#### Scenario: A description with a link

- **WHEN** a skill's description contains a URL and a Markdown link
- **THEN** it is shown as the text the desktop sent, and tapping it does nothing
