## MODIFIED Requirements

### Requirement: Say when a slash command will not run, or may not have

A reply whose text starts with `/` followed by a character that is not white space is a slash command. When such a
reply is sent to a desktop that shows a command in the conversation as the line typed — one that sent a usage event
for the watch before the reply was sent (spekterm 0.2.15 or later) — and it is then marked as not in the conversation,
after the waits `conversation-reply` sets, the mark SHALL also say that a command the agent's CLI does not know, a skill only
the agent may use, or a command that opened something on the desktop, never appears in the conversation as sent; it SHALL still point to the desktop, and
its other marks, its timing and how it is confirmed SHALL be unchanged, except that a reply that is `/model` or
`/effort` with one argument, on one line and without attachments, is also confirmed by the agent's reports (`conversation-reply`, `model-effort-switch`). A slash-command reply sent to an older desktop
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
