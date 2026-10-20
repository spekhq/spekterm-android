## ADDED Requirements

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
- **THEN** the composer has no skills button

### Requirement: Choose a skill to start a reply

While the app watches an agent session and holds a skill event for it, the composer SHALL offer the session's skills.
The list SHALL show each skill's name as a slash command and its description, both as plain text, never as a link or
markup, sorted by name; it SHALL filter by text found in the name or the description, ignoring case; it SHALL say
when nothing matches, when the session has no skills, that built-in commands are not listed, and that a few listed
skills only the agent may use, to be asked for in words. Choosing a skill
SHALL put `/name` and a separator at the start of the reply field and the cursor after the separator: an empty field,
or one holding a bare slash, becomes `/name `, a field whose first word is a command name — a slash and a name with no
other slash — has that name replaced and the rest, its separator first, kept, and any other text, a path among it, is kept after `/name ` as its arguments. The field SHALL keep the result
when the user leaves the screen and comes back, as it keeps a typed draft. Choosing SHALL NOT send anything; the reply
is sent as any reply is (`conversation-reply`).

#### Scenario: An empty field

- **WHEN** the reply field is empty and the user chooses "opsx:apply"
- **THEN** the field reads "/opsx:apply " with the cursor at its end, and nothing is sent

#### Scenario: Text already typed

- **WHEN** the field reads "fix the login bug" and the user chooses "simplify"
- **THEN** the field reads "/simplify fix the login bug"

#### Scenario: Another command already typed

- **WHEN** the field reads "/review 123" and the user chooses "code-review"
- **THEN** the field reads "/code-review 123"

#### Scenario: A path typed first

- **WHEN** the field reads "/tmp/x.log shows an error" and the user chooses "simplify"
- **THEN** the field reads "/simplify /tmp/x.log shows an error"

#### Scenario: Leaving after choosing

- **WHEN** the user chooses a skill, leaves the conversation screen and opens it again
- **THEN** the field still reads what choosing left in it

#### Scenario: Searching

- **WHEN** the user types "android" into the list's search
- **THEN** only skills whose name or description contains "android", in any case, are listed

#### Scenario: A description with a link

- **WHEN** a skill's description contains a URL and a Markdown link
- **THEN** it is shown as the text the desktop sent, and tapping it does nothing

### Requirement: Say when a slash command will not run, or may not have

A reply whose text starts with `/` followed by a character that is not white space is a slash command. When such a
reply is sent to a desktop that shows a command in the conversation as the line typed — one that sent a usage event
for the watch before the reply was sent (spekterm 0.2.15 or later) — and it is then marked as not in the conversation,
after the waits `conversation-reply` sets, the mark SHALL also say that a command the agent's CLI does not know, a skill only
the agent may use, or a command that opened something on the desktop, never appears in the conversation as sent; it SHALL still point to the desktop, and
its other marks, its timing and how it is confirmed SHALL be unchanged. A slash-command reply sent to an older desktop
SHALL keep the marks of any reply. While the reply field holds a slash command whose line, as the desktop will write
it with its attachments named, is longer than the agent's CLI runs as a command (800 characters, measured with its
version 2.1.304), the composer SHALL say that it will arrive as a plain message and not run as a command, and SHALL
still let it be sent.

#### Scenario: An unknown command

- **WHEN** the user sends "/no-such-skill" while the agent waits for input, and it has not appeared 30 seconds later
- **THEN** the reply is marked as not in the conversation, saying that a command the agent's CLI does not know never
  appears, and pointing to the desktop

#### Scenario: An older desktop

- **WHEN** the desktop sent no usage event for the watch, and the user's reply "/simplify" has not appeared 30 seconds
  after it was sent
- **THEN** the reply is marked as not in the conversation yet, as any reply

#### Scenario: A command that runs

- **WHEN** the user sends "/simplify" and the conversation shows the user message "/simplify"
- **THEN** the reply's mark goes, as for any reply

#### Scenario: A plain message is unchanged

- **WHEN** the user sends "hello" while the agent waits for input, and it has not appeared 30 seconds later
- **THEN** the reply is marked as not in the conversation yet, as before

#### Scenario: A command too long

- **WHEN** the reply field holds "/simplify " followed by 800 more characters
- **THEN** the composer says it will arrive as a plain message, and the send button stays enabled

#### Scenario: A command that fits

- **WHEN** the reply field holds "/simplify " followed by 700 characters and no attachment
- **THEN** the composer shows no such note
