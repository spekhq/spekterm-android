# conversation-view Specification

## Purpose
Shows one agent session's conversation on the phone — what the user and the agent said, the agent's tool calls
and their results — kept current while it is on screen, and honest about whatever it cannot show.

## Requirements

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
text SHALL NOT be rendered as HTML, and SHALL NOT be dropped either: tags and HTML blocks SHALL be shown as the
text they are. A link SHALL open only when the user taps it, only for `http` and `https`
addresses, and only in a separate browser; links of any other scheme SHALL do nothing. Tool arguments, thinking
and tool results SHALL be shown as plain text.

#### Scenario: An image in agent text

- **WHEN** an agent message contains a Markdown image pointing to a web address
- **THEN** the phone makes no request to that address

#### Scenario: Angle brackets in agent text

- **WHEN** an agent message mentions `Vec<T>`, or a user entry holds a slash command recorded as
  `<command-name>/opsx:continue</command-name>`
- **THEN** the text shows those characters as written, and nothing of it is missing

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
it holds. Entries delivered again after a resume SHALL NOT be shown twice. Whether older entries are not shown
SHALL be judged from the desktop's replacing deliveries and from what the phone itself dropped, not from
appended ones. The app SHALL NOT write conversation content — entries, replies or drafts — to the phone's
storage, the connection log, or a notification; it holds them in memory only.

#### Scenario: Wi-Fi drops while reading

- **WHEN** the phone loses its connection while a conversation is on screen, the agent writes two messages,
  and the connection returns
- **THEN** both messages appear once, in order, and no earlier entry is repeated

#### Scenario: Leaving and returning quickly

- **WHEN** the user turns the screen off and on again while a conversation is open, and the agent writes a
  message meanwhile
- **THEN** every entry is shown once, and the new message appears

#### Scenario: A long conversation grows

- **WHEN** the desktop delivered a conversation whose older entries were not loaded, and the agent then writes
  a message
- **THEN** the screen still says older entries are not shown

#### Scenario: Leaving the screen stops watching

- **WHEN** the user goes back from the conversation to the session list
- **THEN** the phone no longer watches that conversation

#### Scenario: The desktop's window reloads

- **WHEN** the desktop's window reloads while a conversation is on screen, which stops the session
- **THEN** the screen says the desktop's window went away, then shows the session as dormant with a way to wake
  it, and after waking the conversation resumes from where it was

### Requirement: A dormant session shows as dormant and can be woken

A session that is not running SHALL be shown as dormant, with a way to wake it, and SHALL NOT be shown as an
empty conversation. Waking SHALL start the session on the desktop without taking over the desktop's display;
once the desktop reports it running, its conversation SHALL be shown. When waking fails, the screen SHALL say
why: the desktop has no open window, the desktop could not start the session (with the desktop's reason), the
desktop did not answer in time, or the session no longer exists. A watched session that stops running — put
to sleep, or its process ended — SHALL be shown as dormant, and its conversation SHALL be watched again once it
runs. A session that is closed on the desktop while its conversation is open SHALL be shown as closed, judged only from the desktop's current list; while
the desktop has no open window it lists no sessions, and that SHALL NOT be taken for the session being closed.
Once the desktop has a window again, the screen SHALL show the session as its list does.
When several of these hold, the screen SHALL say first that the phone is not connected, then that the desktop
has no open window, then that the session was closed, then that it is dormant.

#### Scenario: Waking a dormant session

- **WHEN** the user opens a dormant session and chooses to wake it
- **THEN** the screen shows it is waking, and then shows the session's conversation once it is running

#### Scenario: A dormant session is not an empty conversation

- **WHEN** the user opens a dormant session
- **THEN** the screen says the session is dormant, and does not show an empty conversation

#### Scenario: No window

- **WHEN** the user wakes a session while the desktop has no open window
- **THEN** the screen says spekterm has no open window on the desktop

#### Scenario: The session is hibernated while being read

- **WHEN** the user hibernates a session on the desktop while its conversation is on the phone's screen, and
  then wakes it
- **THEN** the phone shows the session as dormant, and then shows its conversation again, new entries included

#### Scenario: Closed on the desktop

- **WHEN** the user closes a session on the desktop while its conversation is on the phone's screen
- **THEN** the phone says the session was closed on the desktop

#### Scenario: The desktop's window closes

- **WHEN** the desktop's window closes while a conversation is on the phone's screen
- **THEN** the phone says spekterm has no open window on the desktop, not that the session was closed
