## MODIFIED Requirements

### Requirement: Watch only while on screen, and resume after a reconnect

The app SHALL watch a session's conversation only while its conversation screen, or a screen carrying its reply
bar (`conversation-reply`), is visible, and SHALL stop watching it when none is visible any more — the user leaves
them, switches app, or the screen turns off — because a watched session counts as in use on the desktop and is not
put to sleep. Moving between the conversation screen and a screen carrying its reply bar SHALL keep the watch,
without stopping and starting it again. When one of them becomes
visible again, or the connection returns while one is visible, the app SHALL watch again from the last entry it
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

#### Scenario: Opening a change from the conversation keeps the watch

- **WHEN** the user opens the OpenSpec from a conversation, then a change, then goes back to the conversation
- **THEN** the session is watched throughout, and the phone neither stops nor restarts the watch on the way

#### Scenario: A back gesture cancelled on a change

- **WHEN** a change carrying a session's reply bar is on screen and the user starts the back gesture and cancels it
- **THEN** the session is still watched, and the bar can still send

#### Scenario: Leaving the bar's screen stops watching

- **WHEN** the user goes from a change carrying a session's reply bar back through the conversation to the session
  list
- **THEN** the phone no longer watches that conversation
