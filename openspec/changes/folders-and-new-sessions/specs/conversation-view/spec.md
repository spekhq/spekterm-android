## MODIFIED Requirements

### Requirement: Say when the content is incomplete

The conversation screen SHALL say, rather than present the content as complete:

- that it is catching up, while the desktop has not yet delivered the conversation;
- that older entries are not shown, when the desktop did not load them or the phone no longer holds them;
- that the conversation cannot be read, when the desktop reports it unavailable, pointing to the desktop;
- that the phone is not connected, that the connection is paused (with a way to resume), or that the desktop's
  window went away — saying in each case that what is shown may be out of date.

When the desktop has delivered the conversation and it holds no entries and the user has sent no reply, the
screen SHALL say that there are no messages yet, rather than showing an empty screen.

#### Scenario: Opening a conversation

- **WHEN** the user opens a conversation and the desktop has not yet delivered it
- **THEN** the screen says it is catching up, not that the conversation is empty

#### Scenario: A long conversation

- **WHEN** the desktop delivers a conversation whose older entries were not loaded
- **THEN** the screen says older entries are not shown

#### Scenario: Disconnected while reading

- **WHEN** the phone loses its connection while a conversation is on screen
- **THEN** the screen keeps the entries it has and says the phone is not connected and they may be out of date

#### Scenario: A new session

- **WHEN** the user opens the conversation of an agent session in which nothing has been said yet
- **THEN** the screen says there are no messages yet
