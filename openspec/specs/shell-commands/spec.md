# shell-commands Specification

## Purpose
Lets the user run a shell command in the agent's terminal from the phone — typed in a command mode of the composer, or
taken from the agent's tool calls and text — and shows honestly whether it reached the agent.

## Requirements

### Requirement: A button beside the field turns the composer into a command field

Wherever the composer is offered for an agent session, one button beside the reply field SHALL offer attaching (when
attaching is offered) and command mode in one menu; in command mode the same button SHALL show that the mode is on, in
its own color, and SHALL turn the field back into a reply field in one tap. The field's hint, in either mode, SHALL
stay on one line. In command mode the field SHALL be set apart from a reply in more than color: it SHALL show a `$`
before the text, the text in a monospace face, a hint that names it a command, and a line above it saying that the
command runs in the agent's terminal on the desktop, without a permission prompt and without a way to type into it,
and that the agent sees its output; the send button SHALL read "Run" and show a different icon. In command mode
attachments SHALL NOT be offered or shown, typing a slash SHALL NOT offer the session's skills, and no note about long
slash commands SHALL be shown. The button SHALL be usable whenever the field is, and SHALL say whether command mode is
on to a screen reader.

The command being written SHALL be kept apart from the reply being written: turning the mode on shows the command
draft, turning it off shows the reply draft and the attachments, and neither text is lost. The mode and the command
draft SHALL be kept per session as the reply draft is (`conversation-reply`): when the user leaves the conversation
and comes back, also after other sessions' conversations were opened, until the session is gone or the phone is
unpaired — also when the mode is on and the command draft is empty.

#### Scenario: Turning command mode on

- **WHEN** the user has typed half a reply with a photo attached and chooses command mode from the button's menu
- **THEN** the field is empty and set apart as a command field, the photo is not shown, and the button shows command
  mode instead of opening the menu

#### Scenario: Turning it off again

- **WHEN** the user then types `git status`, and taps the button
- **THEN** the half reply and the photo are back, and turning the mode on again shows `git status`

#### Scenario: Leaving and coming back

- **WHEN** the user leaves a conversation with command mode on and `npm test` typed, opens another session, and comes
  back
- **THEN** command mode is on and `npm test` is in the field

### Requirement: A command is sent as a reply that begins with `!`

Sending a command SHALL write `!` followed by the command, as typed, through the same request a reply uses, with no
attachments, so that the agent CLI runs it in its shell mode. A command SHALL be sendable under the same conditions as
a reply without attachments (`conversation-reply`): connected and not paused, watched, the agent waiting for input or
working, no choice open, no first prompt from the inbox waiting, no model switch holding, no reply being sent; the
screen SHALL say why otherwise, as for a reply. A command that is empty once the desktop's cleaning removes control
characters and white space at both ends SHALL NOT be sendable. A command too long for the desktop to accept SHALL be
refused on the phone, saying so. A command may hold several lines; it SHALL be sent as one. A command SHALL NOT be
checked as a slash command or as a `/model` or `/effort` switch. While the agent works, the screen SHALL say that a
command sent now runs once the agent's turn ends.

Once a command is sent, the composer SHALL turn command mode off and empty the command draft, so that what is typed
next is a reply; the reply draft and its attachments SHALL stay as they were. A command the desktop refuses SHALL NOT
be lost and SHALL NOT turn command mode on by itself: the screen SHALL say that the command did not run, with the
reason as for a reply, and offer a way to go back to it, which puts it into the command draft before any command typed
there since and turns command mode on. The notice SHALL go when the user goes back to it or sends anything else.

#### Scenario: Running a command while the agent waits

- **WHEN** the agent waits for input, the user has half a reply typed, and runs `ls -la` in command mode
- **THEN** the agent's terminal runs `ls -la` in its shell mode, and the composer is back in reply mode holding the
  half reply

#### Scenario: Running a command while the agent works

- **WHEN** the agent is working and the user types a command
- **THEN** the screen says the command runs once the agent's turn ends, and running it is possible

#### Scenario: While a choice is open

- **WHEN** the agent asks permission for a tool and the user is in command mode
- **THEN** the command cannot be run, and the screen says why as it does for a reply

#### Scenario: A command that looks like a slash command

- **WHEN** the user runs `/model` in command mode
- **THEN** it is sent, as `!/model`, and not refused as a bare `/model`

#### Scenario: A refused command

- **WHEN** the user runs `npm test`, starts typing a reply, and the desktop then refuses the command
- **THEN** the field stays in reply mode with what the user typed, the screen says `npm test` did not run and why, and
  going back to it shows command mode holding `npm test`

### Requirement: A sent command is unconfirmed until the agent CLI records it

A sent command SHALL appear at once at the end of the conversation as `$` and the command, marked as a command the
user ran, not yet confirmed, and SHALL follow the rules of a sent reply in `conversation-reply` — its labels, its place
in the conversation — except in four ways:

- **Matching**: it SHALL be confirmed by a user entry not marked as injected that was not in the conversation when it
  was sent and whose whole text, without white space at either end, begins with `<bash-input>` and ends with
  `</bash-input>`, with what is between them, without white space at either end, equal to the command after the
  desktop's cleaning, without white space at either end.
- **Time**: the agent CLI records a command only once it has finished, and the desktop reports the agent ready while
  it runs. So where a reply would be labeled not yet confirmed, a command SHALL be labeled as running on the desktop,
  shown once it finishes; each 30-second limit a reply has SHALL be 10 minutes for a command.
- **Withheld**: when the desktop typed the command into the agent's input but withheld the Enter, anything sent next
  would be appended to it and run as part of the command. So while such a command exists for the session, sending
  anything — reply or command — SHALL NOT be possible; the screen SHALL say that `$` and the command wait in the
  agent's input on the desktop, that anything sent would run with it, and that it must be cleared there first, and
  SHALL offer a way to say it was cleared, which makes sending possible again.
- **Refusal**: a refused command goes back to the command draft as the requirement above says, not to the reply field.

#### Scenario: Confirmed

- **WHEN** the user runs `git status` and the agent CLI records `<bash-input>git status</bash-input>`
- **THEN** the pending `$ git status` goes, and the conversation's own record shows in its place

#### Scenario: A long command

- **WHEN** the agent waited for input and the user ran `sleep 40; echo done`
- **THEN** for those 40 seconds the `$` row says the command is running on the desktop, not that it is missing, and
  the row is confirmed when the record appears

#### Scenario: A withheld command

- **WHEN** the user ran `npm test`, and a choice opened on the desktop before the Enter, so the desktop withheld it
- **THEN** neither a reply nor a command can be sent, the screen says `npm test` waits in the agent's input and must be
  cleared on the desktop, and after the user says it was cleared, sending is possible again

#### Scenario: Not recorded

- **WHEN** the agent waited for input, the user ran a command, and no matching record appeared within 10 minutes
- **THEN** the command is marked as not in the conversation yet, pointing to the desktop

### Requirement: Put a command into the field from the conversation

The expanded view of the agent's `Bash` tool call SHALL offer "Run it myself" below its command, whatever the call's
outcome, while the conversation's composer can be used for that session — unless the desktop cut the command short,
in which case it SHALL NOT be offered. An inline code span in the agent's own text whose content, without white space
at either end, is `!`, white space, and a command SHALL be shown as something to tap, while the composer can be used;
in the user's own messages, in messages from other sessions, and in code blocks it SHALL stay as it is. Either SHALL
put the command — the call's command, or the span's text after the `!` without white space at either end — into the
command draft in place of what it held, turn command mode on, and focus the field. It SHALL NOT send anything. The
reply draft and its attachments SHALL stay as they were.

#### Scenario: A blocked command

- **WHEN** the agent's `Bash` call `git push origin master` was refused, the user expands it and taps "Run it myself"
- **THEN** the composer is in command mode holding `git push origin master`, the keyboard is up, and nothing was sent

#### Scenario: A command cut short

- **WHEN** the agent's `Bash` call holds a script longer than the desktop sends whole
- **THEN** its expanded view says it was cut, and offers no "Run it myself"

#### Scenario: The agent asks the user to run a command

- **WHEN** the agent's text reads "please run `! npm run deploy` yourself" and the user taps the code span
- **THEN** the composer is in command mode holding `npm run deploy`, and nothing was sent

#### Scenario: A code span that is not a command

- **WHEN** the agent's text holds the code span `!important`
- **THEN** it is shown as it is, with nothing to tap, as are `a != b` and `!` alone
