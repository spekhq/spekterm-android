## MODIFIED Requirements

### Requirement: Put a command into the field from the conversation

The expanded view of the agent's `Bash` tool call SHALL offer "Run it myself" below its command, whatever the call's
outcome, while the conversation's composer can be used for that session — unless the desktop cut the command short,
in which case it SHALL NOT be offered. An inline code span in the agent's own text whose content, without white space
at either end, is `!`, white space, and a command SHALL be shown as something to tap, while the composer can be used;
a code block in the agent's own text whose content, so trimmed, is the same SHALL offer "Run it myself" below it, the
same while; in the user's own messages, in messages from other sessions and in the agent's thinking
(`conversation-view`) both SHALL stay as they are. Each SHALL
put the command — the call's command, or the span's or block's text after the `!` without white space at either end
— into the
command draft in place of what it held, turn command mode on, and focus the field. It SHALL NOT send anything. The
reply draft and its attachments SHALL stay as they were.

#### Scenario: A command in thinking

- **WHEN** the agent's thinking holds the code span `! npm run deploy`
- **THEN** it is shown as code, and tapping it leaves the command field as it was

#### Scenario: A blocked command

- **WHEN** the agent's `Bash` call `git push origin master` was refused, the user expands it and taps "Run it myself"
- **THEN** the composer is in command mode holding `git push origin master`, the keyboard is up, and nothing was sent

#### Scenario: A command cut short

- **WHEN** the agent's `Bash` call holds a script longer than the desktop sends whole
- **THEN** its expanded view says it was cut, and offers no "Run it myself"

#### Scenario: The agent asks the user to run a command

- **WHEN** the agent's text reads "please run `! npm run deploy` yourself" and the user taps the code span
- **THEN** the composer is in command mode holding `npm run deploy`, and nothing was sent

#### Scenario: The agent writes the command in a code block

- **WHEN** the agent's text holds a code block reading `! cd ~/repo && git push origin master` and the user taps "Run
  it myself" below it
- **THEN** the composer is in command mode holding `cd ~/repo && git push origin master`, and nothing was sent; a code
  block reading `ls -la` offers no button

#### Scenario: A code span that is not a command

- **WHEN** the agent's text holds the code span `!important`
- **THEN** it is shown as it is, with nothing to tap, as are `a != b` and `!` alone
