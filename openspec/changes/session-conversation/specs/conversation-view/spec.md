## Purpose

Shows one agent session's conversation on the phone — what the user and the agent said, the agent's tool calls
and their results — kept current while it is on screen, and honest about whatever it cannot show.

## ADDED Requirements

### Requirement: Show the conversation of an agent session

The conversation screen SHALL show the session's entries in order, newest last: the user's messages, the agent's
text, the agent's thinking, its tool calls (the tool's name and identifying argument) and their results
(whether each succeeded or failed). Thinking and tool results SHALL be collapsed until the user expands them.
Messages the agent itself injected rather than the user typing them SHALL be marked as such. New entries SHALL
appear as the desktop delivers them, without the user refreshing. When the user has scrolled up, new entries
SHALL NOT move the view, and the screen SHALL offer to jump to the newest.

#### Scenario: Reading a conversation

- **WHEN** the user opens a running agent session that has a conversation
- **THEN** the screen shows its entries, newest last, with thinking and tool results collapsed

#### Scenario: The agent answers while the screen is open

- **WHEN** the agent writes a new message while the conversation is on screen and scrolled to the newest entry
- **THEN** the message appears without the user doing anything, and the view follows it

### Requirement: Messages appear whole, and working is shown from the wait state

Each entry SHALL appear whole when it arrives. The app SHALL NOT simulate content being produced — no
character-by-character reveal, no delay or transition whose length depends on the entry's length, nothing that
claims progress within a message. Whether the agent is working SHALL be shown from the wait state the desktop
reports, and SHALL disappear when the agent stops working.

#### Scenario: A long and a short message

- **WHEN** a long message and a short message arrive
- **THEN** each appears completely at once, in the same way

#### Scenario: Working

- **WHEN** the desktop reports the agent as working
- **THEN** the screen shows that the agent is working, and stops showing it once the desktop reports otherwise

### Requirement: Agent text loads no remote content

Agent and user text MAY be rendered as Markdown, but rendering SHALL NOT fetch anything over the network:
images SHALL NOT be loaded, and nothing in the text SHALL cause a request without the user acting. HTML in the
text SHALL NOT be rendered as HTML. A link SHALL open only when the user taps it, only for `http` and `https`
addresses, and only in a separate browser; links of any other scheme SHALL do nothing. Tool arguments, thinking
and tool results SHALL be shown as plain text.

#### Scenario: An image in agent text

- **WHEN** an agent message contains a Markdown image pointing to a web address
- **THEN** the phone makes no request to that address

#### Scenario: A link of another scheme

- **WHEN** the user taps a link in agent text whose scheme is not `http` or `https`
- **THEN** nothing is opened

### Requirement: Say when the content is incomplete

The conversation screen SHALL say, rather than present the content as complete:

- that it is catching up, while the desktop has not yet delivered the conversation;
- that older entries are not shown, when the desktop did not load them or the phone no longer holds them;
- that the conversation cannot be read, when the desktop reports it unavailable, pointing to the desktop;
- that the phone is not connected, that the connection is paused (with a way to resume), or that the desktop's
  window went away — saying in each case that what is shown may be out of date.

#### Scenario: Opening a conversation

- **WHEN** the user opens a conversation and the desktop has not yet delivered it
- **THEN** the screen says it is catching up, not that the conversation is empty

#### Scenario: A long conversation

- **WHEN** the desktop delivers a conversation whose older entries were not loaded
- **THEN** the screen says older entries are not shown

#### Scenario: Disconnected while reading

- **WHEN** the phone loses its connection while a conversation is on screen
- **THEN** the screen keeps the entries it has and says the phone is not connected and they may be out of date

### Requirement: Watch only while on screen, and resume after a reconnect

The app SHALL watch a session's conversation only while its conversation screen is visible, and SHALL stop
watching it when the screen is no longer visible — the user leaves it, switches app, or the screen turns off —
because a watched session counts as in use on the desktop and is not put to sleep. When the screen becomes
visible again, or the connection returns while it is visible, the app SHALL watch again from the last entry it
holds, so that it receives only the entries it missed, none repeated and none lost; when the desktop can no
longer resume from that entry, the app SHALL replace what it holds with what the desktop sends. When the
desktop replaces the conversation (for example, the user cleared it in the agent), the app SHALL replace what
it holds. Conversation content SHALL be held in memory only: never written to the phone's storage, the
connection log, or a notification.

#### Scenario: Wi-Fi drops while reading

- **WHEN** the phone loses its connection while a conversation is on screen, the agent writes two messages,
  and the connection returns
- **THEN** both messages appear once, in order, and no earlier entry is repeated

#### Scenario: Leaving the screen stops watching

- **WHEN** the user goes back from the conversation to the session list
- **THEN** the phone no longer watches that conversation

#### Scenario: The desktop's window reloads

- **WHEN** the desktop's window reloads while a conversation is on screen
- **THEN** the screen says the desktop's window went away, and when the desktop lists the session as running
  again the conversation resumes without the user doing anything

### Requirement: A dormant session shows as dormant and can be woken

A session that is not running SHALL be shown as dormant, with a way to wake it, and SHALL NOT be shown as an
empty conversation. Waking SHALL start the session on the desktop without taking over the desktop's display;
once the desktop reports it running, its conversation SHALL be shown. When waking fails, the screen SHALL say
why: the desktop has no open window, the desktop could not start the session (with the desktop's reason), the
desktop did not answer in time, or the session no longer exists. A session that is closed on the desktop while
its conversation is open SHALL be shown as closed.

#### Scenario: Waking a dormant session

- **WHEN** the user opens a dormant session and chooses to wake it
- **THEN** the screen shows it is waking, and then shows the session's conversation once it is running

#### Scenario: A dormant session is not an empty conversation

- **WHEN** the user opens a dormant session
- **THEN** the screen says the session is dormant, and does not show an empty conversation

#### Scenario: No window

- **WHEN** the user wakes a session while the desktop has no open window
- **THEN** the screen says spekterm has no open window on the desktop

### Requirement: A session's notification opens its conversation

Tapping an attention notification about a session SHALL open that session's conversation, with the session list
behind it. Notifications about different sessions SHALL each open their own session. Notifications not about a
session (a new inbox item, a summary of missed events) SHALL open the session list. This refines where the app
opens; tapping a notification still opens the app (`attention-notifications`).

#### Scenario: Tapping a waiting notification

- **WHEN** the user taps the notification saying an agent is waiting for input
- **THEN** that session's conversation opens, and going back shows the session list

#### Scenario: Two sessions' notifications

- **WHEN** two sessions have notifications and the user taps the older one
- **THEN** the conversation of that older notification's session opens

### Requirement: No notification for the conversation on screen

While a session's conversation is visible on the phone, the app SHALL raise no attention notification for that
session, and SHALL remove any it shows for that session when the conversation becomes visible; it SHALL still
record the event as handled, as for a suppressed event (`attention-notifications`). Once the conversation is no
longer visible, notifications for that session SHALL resume.

#### Scenario: The agent finishes while the user reads

- **WHEN** an agent finishes its turn while its conversation is visible on the phone
- **THEN** the phone raises no notification for it

#### Scenario: Opening a conversation clears its notification

- **WHEN** the user opens a session's conversation from the session list while a notification for that session
  is shown
- **THEN** that notification is removed
